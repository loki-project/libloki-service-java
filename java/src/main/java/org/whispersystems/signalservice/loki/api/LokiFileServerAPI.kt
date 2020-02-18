package org.whispersystems.signalservice.loki.api

import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.functional.bind
import nl.komponents.kovenant.functional.map
import org.whispersystems.libsignal.logging.Log
import org.whispersystems.signalservice.internal.util.Base64
import org.whispersystems.signalservice.loki.utilities.PublicKeyValidation
import org.whispersystems.signalservice.loki.utilities.recover
import org.whispersystems.signalservice.loki.utilities.retryIfNeeded
import org.whispersystems.signalservice.loki.utilities.successBackground
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.set

class LokiFileServerAPI(public val server: String, private val userHexEncodedPublicKey: String, userPrivateKey: ByteArray, private val database: LokiAPIDatabaseProtocol) : LokiDotNetAPI(userHexEncodedPublicKey, userPrivateKey, database) {

    companion object {
        // region Settings
        private val lastDeviceLinkUpdate = ConcurrentHashMap<String, Long>()
        private val deviceLinkRequestCache = ConcurrentHashMap<String, Promise<Set<DeviceLink>, Exception>>()
        private val deviceLinkUpdateInterval = 20 * 1000
        private val deviceLinkType = "network.loki.messenger.devicemapping"
        internal val maxRetryCount = 8
        public val maxFileSize = 10_000_000 // 10 MB
        // endregion

        // region Initialization
        lateinit var shared: LokiFileServerAPI

        /**
         * Must be called before `LokiAPI` is used.
         */
        fun configure(isDebugMode: Boolean, userHexEncodedPublicKey: String, userPrivateKey: ByteArray, database: LokiAPIDatabaseProtocol) {
            if (::shared.isInitialized) { return }
            val server = if (isDebugMode) "https://file-dev.lokinet.org" else "https://file.getsession.org"
            shared = LokiFileServerAPI(server, userHexEncodedPublicKey, userPrivateKey, database)
        }
        // endregion
    }

    // region Device Link Update Result
    sealed class DeviceLinkUpdateResult {
        class Success(val hexEncodedPublicKey: String, val deviceLinks: Set<DeviceLink>) : DeviceLinkUpdateResult()
        class Failure(val hexEncodedPublicKey: String, val error: Exception) : DeviceLinkUpdateResult()
    }
    // endregion

    // region API
    public fun hasDeviceLinkCacheExpired(referenceTime: Long = System.currentTimeMillis(), hexEncodedPublicKey: String): Boolean {
        return !lastDeviceLinkUpdate.containsKey(hexEncodedPublicKey) || (referenceTime - lastDeviceLinkUpdate[hexEncodedPublicKey]!! > deviceLinkUpdateInterval)
    }

    fun getDeviceLinks(hexEncodedPublicKey: String, isForcedUpdate: Boolean = false): Promise<Set<DeviceLink>, Exception> {
        val hasCachedRequest = deviceLinkRequestCache.containsKey(hexEncodedPublicKey) && !isForcedUpdate
        return if (hasCachedRequest) {
            // Return the request which is still pending
            deviceLinkRequestCache[hexEncodedPublicKey]!!
        } else {
            // Start a request and add it to the cache
            val promise = getDeviceLinks(setOf(hexEncodedPublicKey), isForcedUpdate)
            deviceLinkRequestCache[hexEncodedPublicKey] = promise
            promise.always {
                deviceLinkRequestCache.remove(hexEncodedPublicKey)
            }
            promise
        }
    }

    fun getDeviceLinks(hexEncodedPublicKeys: Set<String>, isForcedUpdate: Boolean = false): Promise<Set<DeviceLink>, Exception> {
        // We should only be able to get links for valid session ids
        val validHexEncodedPublicKeys = hexEncodedPublicKeys.filter { PublicKeyValidation.isValid(it) }
        if (validHexEncodedPublicKeys.isEmpty()) {
            return Promise.of(setOf())
        }

        // Filter out the all the public keys which we need to fetch
        val now = System.currentTimeMillis()
        val updatees = validHexEncodedPublicKeys
            // We shouldn't fetch our own devices. We always assume our local database is correct.
            .filter { it != userHexEncodedPublicKey }
            .filter { (hasDeviceLinkCacheExpired(now, it) || isForcedUpdate) }
            .toSet()
        val cachedDeviceLinks = validHexEncodedPublicKeys.minus(updatees).flatMap { database.getDeviceLinks(it) }.toSet()

        // If we don't need to fetch any devices then return the device links from the database
        if (updatees.isEmpty()) {
            return Promise.of(cachedDeviceLinks)
        }

        return fetchDeviceLinks(updatees, server).successBackground { updateResults ->
            // For each success, add the device links to the database
            for (updateResult in updateResults) {
                if (updateResult is DeviceLinkUpdateResult.Success) {
                    database.clearDeviceLinks(updateResult.hexEncodedPublicKey)
                    updateResult.deviceLinks.forEach { database.addDeviceLink(it) }
                }
            }
        }.map(LokiAPI.sharedWorkContext) { updateResults ->
            // Updatees that didn't show up in the response provided by the file server are assumed to not have any device links
            // We don't want to fetch device links every time for these users
            val excludedUsers = updatees.filter { user ->
                val userDeviceLinks = updateResults.find { updateResult ->
                    when (updateResult) {
                        is DeviceLinkUpdateResult.Success -> updateResult.hexEncodedPublicKey == user
                        is DeviceLinkUpdateResult.Failure -> updateResult.hexEncodedPublicKey == user
                    }
                }
                userDeviceLinks == null
            }
            excludedUsers.forEach { lastDeviceLinkUpdate[it] = now }
            updateResults
        }.toDeviceLinks().recover {
            // Fallback to cached list in the case of failures
            validHexEncodedPublicKeys.flatMap { database.getDeviceLinks(it) }.toSet()
        }
    }

    private fun fetchDeviceLinks(hexEncodedPublicKeys: Set<String>, server: String): Promise<List<DeviceLinkUpdateResult>, Exception> {
        return getUserProfiles(hexEncodedPublicKeys, server, true).map(LokiAPI.sharedWorkContext) { data ->
            data.map dataMap@ { node ->
                val hexEncodedPublicKey = node.get("username").asText()
                val annotations = node.get("annotations")
                val deviceLinksAnnotation = annotations.find { annotation -> annotation.get("type").asText() == deviceLinkType } ?: return@dataMap DeviceLinkUpdateResult.Success(hexEncodedPublicKey, setOf())
                val value = deviceLinksAnnotation.get("value")
                val deviceLinksAsJSON = value.get("authorisations")
                val deviceLinks = deviceLinksAsJSON.mapNotNull { deviceLinkAsJSON ->
                    try {
                        val masterHexEncodedPublicKey = deviceLinkAsJSON.get("primaryDevicePubKey").asText()
                        val slaveHexEncodedPublicKey = deviceLinkAsJSON.get("secondaryDevicePubKey").asText()
                        var requestSignature: ByteArray? = null
                        var authorizationSignature: ByteArray? = null
                        if (deviceLinkAsJSON.hasNonNull("requestSignature")) {
                            val base64EncodedSignature = deviceLinkAsJSON.get("requestSignature").asText()
                            requestSignature = Base64.decode(base64EncodedSignature)
                        }
                        if (deviceLinkAsJSON.hasNonNull("grantSignature")) {
                            val base64EncodedSignature = deviceLinkAsJSON.get("grantSignature").asText()
                            authorizationSignature = Base64.decode(base64EncodedSignature)
                        }
                        val deviceLink = DeviceLink(masterHexEncodedPublicKey, slaveHexEncodedPublicKey, requestSignature, authorizationSignature)
                        val isValid = deviceLink.verify()
                        if (!isValid) {
                            Log.d("Loki", "Ignoring invalid device link: $deviceLinkAsJSON.")
                            return@mapNotNull null
                        }
                        deviceLink
                    } catch (e: Exception) {
                        Log.d("Loki", "Failed to parse device links for $hexEncodedPublicKey from $deviceLinkAsJSON due to error: $e.")
                        null
                    }
                }.toSet()
                DeviceLinkUpdateResult.Success(hexEncodedPublicKey, deviceLinks)
            }
        }.recover { e ->
            hexEncodedPublicKeys.map { DeviceLinkUpdateResult.Failure(it, e) }
        }
    }

    fun setDeviceLinks(deviceLinks: Set<DeviceLink>): Promise<Unit, Exception> {
        val isMaster = deviceLinks.find { it.masterHexEncodedPublicKey == userHexEncodedPublicKey } != null
        val deviceLinksAsJSON = deviceLinks.map { it.toJSON() }
        val value = if (deviceLinks.isNotEmpty()) mapOf( "isPrimary" to isMaster, "authorisations" to deviceLinksAsJSON ) else null
        val annotation = mapOf( "type" to deviceLinkType, "value" to value )
        val parameters = mapOf( "annotations" to listOf( annotation ) )
        return retryIfNeeded(maxRetryCount) {
            execute(HTTPVerb.PATCH, server, "/users/me", parameters = parameters)
        }.map { Unit }
    }

    fun addDeviceLink(deviceLink: DeviceLink): Promise<Unit, Exception> {
        Log.d("Loki", "Updating device links.")
        return getDeviceLinks(userHexEncodedPublicKey, true).bind { deviceLinks ->
            val mutableDeviceLinks = deviceLinks.toMutableSet()
            mutableDeviceLinks.add(deviceLink)
            setDeviceLinks(mutableDeviceLinks)
        }.success {
            database.addDeviceLink(deviceLink)
        }.map { Unit }
    }

    fun removeDeviceLink(deviceLink: DeviceLink): Promise<Unit, Exception> {
        Log.d("Loki", "Updating device links.")
        return getDeviceLinks(userHexEncodedPublicKey, true).bind { deviceLinks ->
            val mutableDeviceLinks = deviceLinks.toMutableSet()
            mutableDeviceLinks.remove(deviceLink)
            setDeviceLinks(mutableDeviceLinks)
        }.success {
            database.removeDeviceLink(deviceLink)
        }.map { Unit }
    }
    // endregion

    private fun Promise<Iterable<DeviceLinkUpdateResult>, Exception>.toDeviceLinks(): Promise<Set<DeviceLink>, Exception> {
        val now = System.currentTimeMillis()
        return this.map(LokiAPI.sharedWorkContext) { updateResults ->
            val deviceLinks = mutableListOf<DeviceLink>()
            for (updateResult in updateResults) {
                when (updateResult) {
                    is DeviceLinkUpdateResult.Success -> {
                        lastDeviceLinkUpdate[updateResult.hexEncodedPublicKey] = now
                        deviceLinks.addAll(updateResult.deviceLinks)
                    }
                    is DeviceLinkUpdateResult.Failure -> {
                        if (updateResult.error is Error.ParsingFailed) {
                            lastDeviceLinkUpdate[updateResult.hexEncodedPublicKey] = now // Don't infinitely update in case of a parsing failure
                        }
                        // Fall back on cached device links in case of a failure
                        val cached = database.getDeviceLinks(updateResult.hexEncodedPublicKey)
                        deviceLinks.addAll(cached)
                    }
                }
            }
            deviceLinks.toSet()
        }
    }
}

