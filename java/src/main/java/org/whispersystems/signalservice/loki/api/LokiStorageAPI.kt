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

private data class DeviceMappingFetchResult private constructor(val pubKey: String, val error: Exception?, val authorisations: List<PairingAuthorisation>) {
  constructor(pubKey: String, authorisations: List<PairingAuthorisation>): this(pubKey, null, authorisations)
  constructor(pubKey: String, error: Exception): this(pubKey, error, listOf())
  val isSuccess = error == null
}

class LokiStorageAPI(public val server: String, private val userHexEncodedPublicKey: String, userPrivateKey: ByteArray, private val database: LokiAPIDatabaseProtocol) : LokiDotNetAPI(userHexEncodedPublicKey, userPrivateKey, database) {

  companion object {
    // region Settings
    private val maxRetryCount = 8
    private val lastDeviceLinkUpdate = ConcurrentHashMap<String, Long>()
    private val deviceMappingRequestCache = ConcurrentHashMap<String, Promise<List<PairingAuthorisation>, Exception>>()
    private val deviceMappingUpdateInterval = 60 * 1000
    private val deviceMappingType = "network.loki.messenger.devicemapping"
    // endregion

    // region Initialization
    lateinit var shared: LokiStorageAPI

    /**
     * Must be called before `LokiAPI` is used.
     */
    fun configure(isDebugMode: Boolean, userHexEncodedPublicKey: String,  userPrivateKey: ByteArray, database: LokiAPIDatabaseProtocol) {
      if (::shared.isInitialized) { return }
      // TODO: Re-enable when we have dev file server
      val server = if (false && isDebugMode) "https://file-dev.lokinet.org" else "https://file.lokinet.org"
      shared = LokiStorageAPI(server, userHexEncodedPublicKey, userPrivateKey, database)
    }
    // endregion
  }

  // region Private API
  private fun internalFetchDeviceMappings(hexEncodedPublicKeys: List<String>): Promise<List<DeviceMappingFetchResult>, Exception> {
    return getUserProfiles(hexEncodedPublicKeys.toSet(), server, true).map(workContext) { data ->
      data.map dataMap@ { node ->
        val device = node.get("username").asText()
        val annotations = node.get("annotations")
        val deviceMappingAnnotation = annotations.find { annotation ->
          annotation.get("type").asText() == deviceMappingType
        } ?: return@dataMap DeviceMappingFetchResult(device, listOf())
        val value = deviceMappingAnnotation.get("value")
        val authorisationsAsJSON = value.get("authorisations")
        val authorisations = authorisationsAsJSON.mapNotNull { authorisationAsJSON ->
          try {
            val primaryDevicePublicKey = authorisationAsJSON.get("primaryDevicePubKey").asText()
            val secondaryDevicePublicKey = authorisationAsJSON.get("secondaryDevicePubKey").asText()
            var requestSignature: ByteArray? = null
            var grantSignature: ByteArray? = null
            if (authorisationAsJSON.hasNonNull("requestSignature")) {
              val base64EncodedSignature = authorisationAsJSON.get("requestSignature").asText()
              requestSignature = Base64.decode(base64EncodedSignature)
            }
            if (authorisationAsJSON.hasNonNull("grantSignature")) {
              val base64EncodedSignature = authorisationAsJSON.get("grantSignature").asText()
              grantSignature = Base64.decode(base64EncodedSignature)
            }
            val authorisation = PairingAuthorisation(primaryDevicePublicKey, secondaryDevicePublicKey, requestSignature, grantSignature)
            val isValid = authorisation.verify()
            if (!isValid) {
              Log.d("Loki", "Invalid authorisation received: $authorisationAsJSON.")
              return@mapNotNull null
            }
            authorisation
          } catch (e: Exception) {
            Log.d("Loki", "Failed to parse device mapping for $device from $authorisationAsJSON due to error: $e.")
            null
          }
        }
        DeviceMappingFetchResult(device, authorisations)
      }
    }.recover { e -> hexEncodedPublicKeys.map { DeviceMappingFetchResult(it, e) } }
  }

  private fun fetchAndSaveDeviceMappings(hexEncodedPublicKeys: List<String>): Promise<List<DeviceMappingFetchResult>, Exception> {
    return internalFetchDeviceMappings(hexEncodedPublicKeys).successBackground { mappings ->
      for (result in mappings) {
        if (result.isSuccess) {
          database.removePairingAuthorisations(result.pubKey)
          result.authorisations.forEach { database.insertOrUpdatePairingAuthorisation(it) }
        }
      }
    }
  }

  private fun hasCacheExpired(time: Long, pubKey: String): Boolean {
    return !lastDeviceLinkUpdate.containsKey(pubKey) || (time - lastDeviceLinkUpdate[pubKey]!! > deviceMappingUpdateInterval)
  }
  // endregion

  // region Public API
  public fun hasCacheExpired(hexEncodedPublicKey: String): Boolean {
    return hasCacheExpired(System.currentTimeMillis(), hexEncodedPublicKey)
  }

  fun updateUserDeviceMappings(): Promise<Unit, Exception> {
    return getDeviceMappings(userHexEncodedPublicKey).bind(workContext) { authorisations ->
      // We are a primary device if an authorisation has us listed as one
      val isPrimary = authorisations.find { it.primaryDevicePublicKey == userHexEncodedPublicKey } != null
      retryIfNeeded(maxRetryCount) {
        val authorisationsAsJSON = authorisations.map { it.toJSON() }
        val value = if (authorisations.count() > 0) mapOf( "isPrimary" to isPrimary, "authorisations" to authorisationsAsJSON ) else null
        setSelfAnnotation(server, deviceMappingType, value)
      }
    }.map { Unit }.success {
      Log.d("Loki", "Updated user device mappings")
    }
  }

  fun fetchDeviceMappings(hexEncodedPublicKey: String): Promise<List<PairingAuthorisation>, Exception> {
    return internalFetchDeviceMappings(listOf(hexEncodedPublicKey)).map(workContext) { results ->
      if (results.isEmpty()) { throw Error.ParsingFailed }
      val result = results[0]
      if (!result.isSuccess) { throw result.error!! }
      result.authorisations
    }
  }

  fun getDeviceMappings(hexEncodedPublicKey: String): Promise<List<PairingAuthorisation>, Exception> {
    // Cache single device requests so all promises resolve at once
    return if (deviceMappingRequestCache.containsKey(hexEncodedPublicKey)) {
      deviceMappingRequestCache[hexEncodedPublicKey]!!
    } else {
      val promise = getDeviceMappings(setOf(hexEncodedPublicKey))
      deviceMappingRequestCache[hexEncodedPublicKey] = promise
      promise.success {
        deviceMappingRequestCache.remove(hexEncodedPublicKey)
      }
    }
  }

  fun getDeviceMappings(hexEncodedPublicKeys: Set<String>): Promise<List<PairingAuthorisation>, Exception> {
    val now = System.currentTimeMillis()
    val validDevices = hexEncodedPublicKeys.filter { PublicKeyValidation.isValid(it) }
    val devicesToFetch = validDevices.filter { it != userHexEncodedPublicKey && hasCacheExpired(now, it) }
    val databaseAuthorisations = validDevices.minus(devicesToFetch).flatMap { database.getPairingAuthorisations(it) }
    // If we don't need to fetch then bail early
    if (devicesToFetch.isEmpty()) {
      return Promise.of(databaseAuthorisations)
    }
    return fetchAndSaveDeviceMappings(devicesToFetch).map(workContext) { results ->
      val authorisations = mutableListOf<PairingAuthorisation>()
      for (result in results) {
        // Update the last fetch time
        if (result.isSuccess || result.error is Error.ParsingFailed) {
          lastDeviceLinkUpdate[result.pubKey] = now
        }
        // Fall back to using database authorisation if we failed
        val list = if (result.isSuccess) result.authorisations else database.getPairingAuthorisations(result.pubKey)
        authorisations.addAll(list)
      }

      // Any devices we didn't get in result probably didn't have a mapping, set the cache for them
      val noResultDevices = devicesToFetch.filter { device -> results.find { it.pubKey == device } == null }
      noResultDevices.forEach {
        lastDeviceLinkUpdate[it] = now
      }

      // Return the union of the db auth and our fetched auth
      authorisations.union(databaseAuthorisations).toList()
    }.recover { hexEncodedPublicKeys.flatMap { database.getPairingAuthorisations(it) } }
  }

  fun getPrimaryDevicePublicKey(hexEncodedPublicKey: String): Promise<String?, Exception> {
    return getDeviceMappings(hexEncodedPublicKey).map(workContext) { authorisations ->
      val pairing = authorisations.find { it.secondaryDevicePublicKey == hexEncodedPublicKey }
      pairing?.primaryDevicePublicKey
    }
  }

  fun getSecondaryDevicePublicKeys(hexEncodedPublicKey: String): Promise<List<String>, Exception> {
    return getDeviceMappings(hexEncodedPublicKey).map(workContext) { authorisations ->
      authorisations.filter { it.primaryDevicePublicKey == hexEncodedPublicKey }.map { it.secondaryDevicePublicKey }
    }
  }

  fun getAllDevicePublicKeys(hexEncodedPublicKey: String): Promise<Set<String>, Exception> {
    // Our primary device should have all the mappings
    return getPrimaryDevicePublicKey(hexEncodedPublicKey).bind(workContext) { primaryDevicePublicKey ->
      val primaryDevice = primaryDevicePublicKey ?: hexEncodedPublicKey
      getDeviceMappings(primaryDevice)
    }.map(workContext) { authorisations ->
      val publicKeys = authorisations.flatMap { listOf(it.primaryDevicePublicKey, it.secondaryDevicePublicKey) }.toSet()
      publicKeys.plus(hexEncodedPublicKey)
    }
  }
  // endregion
}