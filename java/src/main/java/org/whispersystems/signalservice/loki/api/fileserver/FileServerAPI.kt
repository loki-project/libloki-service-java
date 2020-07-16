package org.whispersystems.signalservice.loki.api.fileserver

import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.functional.bind
import nl.komponents.kovenant.functional.map
import org.whispersystems.libsignal.logging.Log
import org.whispersystems.signalservice.internal.util.Base64
import org.whispersystems.signalservice.loki.api.SnodeAPI
import org.whispersystems.signalservice.loki.api.LokiDotNetAPI
import org.whispersystems.signalservice.loki.database.LokiAPIDatabaseProtocol
import org.whispersystems.signalservice.loki.protocol.multidevice.DeviceLink
import org.whispersystems.signalservice.loki.utilities.PublicKeyValidation
import org.whispersystems.signalservice.loki.utilities.recover
import org.whispersystems.signalservice.loki.utilities.retryIfNeeded
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.set

class FileServerAPI(public val server: String, userPublicKey: String, userPrivateKey: ByteArray, private val database: LokiAPIDatabaseProtocol) : LokiDotNetAPI(userPublicKey, userPrivateKey, database) {

    companion object {
        // region Settings
        private val lastDeviceLinkUpdate = ConcurrentHashMap<String, Long>()
        private val deviceLinkRequestCache = ConcurrentHashMap<String, Promise<Set<DeviceLink>, Exception>>()
        private val deviceLinkUpdateInterval = 60 * 1000
        private val deviceLinkType = "network.loki.messenger.devicemapping"

        internal val maxRetryCount = 4

        public val maxFileSize = 10_000_000 // 10 MB
        // endregion

        // region Initialization
        lateinit var shared: FileServerAPI

        /**
         * Must be called before `LokiAPI` is used.
         */
        fun configure(userPublicKey: String, userPrivateKey: ByteArray, database: LokiAPIDatabaseProtocol) {
            if (Companion::shared.isInitialized) { return }
            val server = "https://file.getsession.org"
            shared = FileServerAPI(server, userPublicKey, userPrivateKey, database)
        }
        // endregion
    }

    // region Device Link Update Result
    sealed class DeviceLinkUpdateResult {
        class Success(val publicKey: String, val deviceLinks: Set<DeviceLink>) : DeviceLinkUpdateResult()
        class Failure(val publicKey: String, val error: Exception) : DeviceLinkUpdateResult()
    }
    // endregion

    // region API
    public fun hasDeviceLinkCacheExpired(referenceTime: Long = System.currentTimeMillis(), publicKey: String): Boolean {
        return !lastDeviceLinkUpdate.containsKey(publicKey) || (referenceTime - lastDeviceLinkUpdate[publicKey]!! > deviceLinkUpdateInterval)
    }

    fun getDeviceLinks(publicKey: String, isForcedUpdate: Boolean = false): Promise<Set<DeviceLink>, Exception> {
        if (deviceLinkRequestCache.containsKey(publicKey) && !isForcedUpdate) {
            val result = deviceLinkRequestCache[publicKey]
            if (result != null) { return result } // A request was already pending
        }
        val promise = getDeviceLinks(setOf(publicKey), isForcedUpdate)
        deviceLinkRequestCache[publicKey] = promise
        promise.always {
            deviceLinkRequestCache.remove(publicKey)
        }
        return promise
    }

    fun getDeviceLinks(publicKeys: Set<String>, isForcedUpdate: Boolean = false): Promise<Set<DeviceLink>, Exception> {
        val validPublicKeys = publicKeys.filter { PublicKeyValidation.isValid(it) }
        val now = System.currentTimeMillis()
        // IMPORTANT: Don't fetch device links for the current user (i.e. don't remove the it != userHexEncodedPublicKey) check below
        val updatees = validPublicKeys.filter { it != userPublicKey && (hasDeviceLinkCacheExpired(now, it) || isForcedUpdate) }.toSet()
        val cachedDeviceLinks = validPublicKeys.minus(updatees).flatMap { database.getDeviceLinks(it) }.toSet()
        if (updatees.isEmpty()) {
            return Promise.of(cachedDeviceLinks)
        } else {
            return getUserProfiles(updatees, server, true).map(SnodeAPI.sharedContext) { data ->
                data.map dataMap@ { node ->
                    val publicKey = node.get("username").asText()
                    val annotations = node.get("annotations")
                    val deviceLinksAnnotation = annotations.find { annotation -> annotation.get("type").asText() == deviceLinkType } ?: return@dataMap DeviceLinkUpdateResult.Success(publicKey, setOf())
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
                            Log.d("Loki", "Failed to parse device links for $publicKey from $deviceLinkAsJSON due to error: $e.")
                            null
                        }
                    }.toSet()
                    DeviceLinkUpdateResult.Success(publicKey, deviceLinks)
                }
            }.recover { e ->
                publicKeys.map { DeviceLinkUpdateResult.Failure(it, e) }
            }.success { updateResults ->
                for (updateResult in updateResults) {
                    if (updateResult is DeviceLinkUpdateResult.Success) {
                        database.clearDeviceLinks(updateResult.publicKey)
                        updateResult.deviceLinks.forEach { database.addDeviceLink(it) }
                    } else {
                        // Do nothing
                    }
                }
            }.map(SnodeAPI.sharedContext) { updateResults ->
                val deviceLinks = mutableListOf<DeviceLink>()
                for (updateResult in updateResults) {
                    when (updateResult) {
                        is DeviceLinkUpdateResult.Success -> {
                            lastDeviceLinkUpdate[updateResult.publicKey] = now
                            deviceLinks.addAll(updateResult.deviceLinks)
                        }
                        is DeviceLinkUpdateResult.Failure -> {
                            if (updateResult.error is SnodeAPI.Error.ParsingFailed) {
                                lastDeviceLinkUpdate[updateResult.publicKey] = now // Don't infinitely update in case of a parsing failure
                            }
                            deviceLinks.addAll(database.getDeviceLinks(updateResult.publicKey)) // Fall back on cached device links in case of a failure
                        }
                    }
                }
                // Updatees that didn't show up in the response provided by the file server are assumed to not have any device links
                val excludedUpdatees = updatees.filter { updatee ->
                    updateResults.find { updateResult ->
                        when (updateResult) {
                            is DeviceLinkUpdateResult.Success -> updateResult.publicKey == updatee
                            is DeviceLinkUpdateResult.Failure -> updateResult.publicKey == updatee
                        }
                    } == null
                }
                excludedUpdatees.forEach {
                    lastDeviceLinkUpdate[it] = now
                }
                deviceLinks.union(cachedDeviceLinks)
            }.recover {
                publicKeys.flatMap { database.getDeviceLinks(it) }.toSet()
            }
        }
    }

    fun setDeviceLinks(deviceLinks: Set<DeviceLink>): Promise<Unit, Exception> {
        val isMaster = deviceLinks.find { it.masterPublicKey == userPublicKey } != null
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
        return getDeviceLinks(userPublicKey, true).bind { deviceLinks ->
            val mutableDeviceLinks = deviceLinks.toMutableSet()
            mutableDeviceLinks.add(deviceLink)
            setDeviceLinks(mutableDeviceLinks)
        }.success {
            database.addDeviceLink(deviceLink)
        }.map { Unit }
    }

    fun removeDeviceLink(deviceLink: DeviceLink): Promise<Unit, Exception> {
        Log.d("Loki", "Updating device links.")
        return getDeviceLinks(userPublicKey, true).bind { deviceLinks ->
            val mutableDeviceLinks = deviceLinks.toMutableSet()
            mutableDeviceLinks.remove(deviceLink)
            setDeviceLinks(mutableDeviceLinks)
        }.success {
            database.removeDeviceLink(deviceLink)
        }.map { Unit }
    }
    // endregion
}