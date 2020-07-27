package org.whispersystems.signalservice.loki.api.onionrequests

import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.all
import nl.komponents.kovenant.deferred
import nl.komponents.kovenant.functional.bind
import nl.komponents.kovenant.functional.map
import okhttp3.Request
import org.whispersystems.libsignal.logging.Log
import org.whispersystems.signalservice.internal.util.Base64
import org.whispersystems.signalservice.internal.util.JsonUtil
import org.whispersystems.signalservice.loki.api.*
import org.whispersystems.signalservice.loki.api.utilities.HTTP
import org.whispersystems.signalservice.loki.utilities.*
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private typealias Path = List<Snode>

/**
 * See the "Onion Requests" section of [The Session Whitepaper](https://arxiv.org/pdf/2002.04609.pdf) for more information.
 */
public object OnionRequestAPI {
    public var guardSnodes = setOf<Snode>()
    public var paths: List<Path> // Not a set to ensure we consistently show the same path to the user
        get() = SnodeAPI.shared.database.getOnionRequestPaths()
        set(newValue) { SnodeAPI.shared.database.setOnionRequestPaths(newValue) }

    private val reliableSnodePool: Set<Snode>
        get() {
            val unreliableSnodes = SwarmAPI.shared.snodeFailureCount.keys
            return SwarmAPI.shared.snodePool.minus(unreliableSnodes)
        }

    // region Settings
    /**
     * The number of snodes (including the guard snode) in a path.
     */
    private val pathSize = 3
    public val pathCount = 2 // A main path and a backup path for the case where the target snode is in the main path

    private val guardSnodeCount
        get() = pathCount // One per path
    // endregion

    class HTTPRequestFailedAtTargetSnodeException(val statusCode: Int, val json: Map<*, *>)
        : Exception("HTTP request failed at target snode with status code $statusCode.")
    class InsufficientSnodesException : Exception("Couldn't find enough snodes to build a path.")

    private data class OnionBuildingResult(
        internal val guardSnode: Snode,
        internal val finalEncryptionResult: OnionRequestEncryption.EncryptionResult,
        internal val destinationSymmetricKey: ByteArray
    )

    internal sealed class Destination {
        class Snode(val snode: org.whispersystems.signalservice.loki.api.Snode) : Destination()
        class Server(val host: String, val x25519PublicKey: String) : Destination()
    }

    // region Private API
    /**
     * Tests the given snode. The returned promise errors out if the snode is faulty; the promise is fulfilled otherwise.
     */
    private fun testSnode(snode: Snode): Promise<Unit, Exception> {
        val deferred = deferred<Unit, Exception>()
        Thread { // No need to block the shared context for this
            val url = "${snode.address}:${snode.port}/get_stats/v1"
            try {
                val json = HTTP.execute(HTTP.Verb.GET, url)
                val version = json["version"] as? String
                if (version == null) { deferred.reject(Exception("Missing snode version.")); return@Thread }
                if (version >= "2.0.0") {
                    deferred.resolve(Unit)
                } else {
                    val message = "Unsupported snode version: $version."
                    Log.d("Loki", message)
                    deferred.reject(Exception(message))
                }
            } catch (exception: Exception) {
                deferred.reject(exception)
            }
        }.start()
        return deferred.promise
    }

    /**
     * Finds `guardSnodeCount` guard snodes to use for path building. The returned promise errors out if not
     * enough (reliable) snodes are available.
     */
    private fun getGuardSnodes(): Promise<Set<Snode>, Exception> {
        if (guardSnodes.count() >= guardSnodeCount) {
            return Promise.of(guardSnodes)
        } else {
            Log.d("Loki", "Populating guard snode cache.")
            return SwarmAPI.shared.getRandomSnode().bind(SnodeAPI.sharedContext) { // Just used to populate the snode pool
                var unusedSnodes = reliableSnodePool
                if (unusedSnodes.count() < guardSnodeCount) { throw InsufficientSnodesException() }
                fun getGuardSnode(): Promise<Snode, Exception> {
                    val candidate = unusedSnodes.getRandomElementOrNull()
                        ?: return Promise.ofFail(InsufficientSnodesException())
                    unusedSnodes = unusedSnodes.minus(candidate)
                    Log.d("Loki", "Testing guard snode: $candidate.")
                    // Loop until a reliable guard snode is found
                    val deferred = deferred<Snode, Exception>()
                    testSnode(candidate).success {
                        deferred.resolve(candidate)
                    }.fail {
                        getGuardSnode().success {
                            deferred.resolve(candidate)
                        }.fail { exception ->
                            if (exception is InsufficientSnodesException) {
                                deferred.reject(exception)
                            }
                        }
                    }
                    return deferred.promise
                }
                val promises = (0 until guardSnodeCount).map { getGuardSnode() }
                all(promises).map(SnodeAPI.sharedContext) { guardSnodes ->
                    val guardSnodesAsSet = guardSnodes.toSet()
                    OnionRequestAPI.guardSnodes = guardSnodesAsSet
                    guardSnodesAsSet
                }
            }
        }
    }

    /**
     * Builds and returns `pathCount` paths. The returned promise errors out if not
     * enough (reliable) snodes are available.
     */
    private fun buildPaths(): Promise<List<Path>, Exception> {
        Log.d("Loki", "Building onion request paths.")
        SnodeAPI.shared.broadcaster.broadcast("buildingPaths")
        return SwarmAPI.shared.getRandomSnode().bind(SnodeAPI.sharedContext) { // Just used to populate the snode pool
            getGuardSnodes().map(SnodeAPI.sharedContext) { guardSnodes ->
                var unusedSnodes = reliableSnodePool.minus(guardSnodes)
                val pathSnodeCount = guardSnodeCount * pathSize - guardSnodeCount
                if (unusedSnodes.count() < pathSnodeCount) { throw InsufficientSnodesException() }
                // Don't test path snodes as this would reveal the user's IP to them
                guardSnodes.map { guardSnode ->
                    val result = listOf( guardSnode ) + (0 until (pathSize - 1)).map {
                        val pathSnode = unusedSnodes.getRandomElement()
                        unusedSnodes = unusedSnodes.minus(pathSnode)
                        pathSnode
                    }
                    Log.d("Loki", "Built new onion request path: $result.")
                    result
                }
            }.map { paths ->
                OnionRequestAPI.paths = paths
                SnodeAPI.shared.broadcaster.broadcast("pathsBuilt")
                paths
            }
        }
    }

    /**
     * Returns a `Path` to be used for building an onion request. Builds new paths as needed.
     */
    private fun getPath(snodeToExclude: Snode?): Promise<Path, Exception> {
        if (pathSize < 1) { throw Exception("Can't build path of size zero.") }
        if (guardSnodes.isEmpty() && paths.count() >= pathCount) {
            guardSnodes = setOf( paths[0][0], paths[1][0] )
        }
        fun getPath(): Path {
            if (snodeToExclude != null) {
                return paths.filter { !it.contains(snodeToExclude) }.getRandomElement()
            } else {
                return paths.getRandomElement()
            }
        }
        if (paths.count() >= pathCount) {
            return Promise.of(getPath())
        } else {
            return buildPaths().map(SnodeAPI.sharedContext) { _ ->
                getPath()
            }
        }
    }

    private fun dropPathContaining(snode: Snode) {
        paths = paths.filter { !it.contains(snode) }
    }

    private fun dropGuardSnode(snode: Snode) {
        guardSnodes = guardSnodes.filter { it != snode }.toSet()
    }

    /**
     * Builds an onion around `payload` and returns the result.
     */
    private fun buildOnionForDestination(payload: Map<*, *>, destination: Destination): Promise<OnionBuildingResult, Exception> {
        lateinit var guardSnode: Snode
        lateinit var destinationSymmetricKey: ByteArray // Needed by LokiAPI to decrypt the response sent back by the destination
        lateinit var encryptionResult: OnionRequestEncryption.EncryptionResult
        val snodeToExclude = when (destination) {
            is Destination.Snode -> destination.snode
            is Destination.Server -> null
        }
        return getPath(snodeToExclude).bind(SnodeAPI.sharedContext) { path ->
            guardSnode = path.first()
            // Encrypt in reverse order, i.e. the destination first
            OnionRequestEncryption.encryptPayloadForDestination(payload, destination).bind(SnodeAPI.sharedContext) { r ->
                destinationSymmetricKey = r.symmetricKey
                // Recursively encrypt the layers of the onion (again in reverse order)
                encryptionResult = r
                @Suppress("NAME_SHADOWING") var path = path
                var rhs = destination
                fun addLayer(): Promise<OnionRequestEncryption.EncryptionResult, Exception> {
                    if (path.isEmpty()) {
                        return Promise.of(encryptionResult)
                    } else {
                        val lhs = Destination.Snode(path.last())
                        path = path.dropLast(1)
                        return OnionRequestEncryption.encryptHop(lhs, rhs, encryptionResult).bind(SnodeAPI.sharedContext) { r ->
                            encryptionResult = r
                            rhs = lhs
                            addLayer()
                        }
                    }
                }
                addLayer()
            }
        }.map(SnodeAPI.sharedContext) { OnionBuildingResult(guardSnode, encryptionResult, destinationSymmetricKey) }
    }
    // endregion

    // region Internal API
    /**
     * Sends an onion request to `snode`. Builds new paths as needed.
     */
    internal fun sendOnionRequest(method: Snode.Method, parameters: Map<*, *>, snode: Snode, publicKey: String): Promise<Map<*, *>, Exception> {
        val payload = mapOf( "method" to method.rawValue, "params" to parameters )
        return sendOnionRequest(Destination.Snode(snode), payload).recover { exception ->
            @Suppress("NAME_SHADOWING") val exception = exception as? HTTPRequestFailedAtTargetSnodeException ?: throw exception
            throw SnodeAPI.shared.handleSnodeError(exception.statusCode, exception.json, snode, publicKey)
        }
    }

    /**
     * Sends an onion request to `server`. Builds new paths as needed.
     *
     * `publicKey` is the hex encoded public key of the user the call is associated with. This is needed for swarm cache maintenance.
     */
    internal fun sendOnionRequest(request: Request, server: String, x25519PublicKey: String, isJSONRequired: Boolean = true): Promise<Map<*, *>, Exception> {
        val headers = request.getCanonicalHeaders()
        val url = request.url()
        val urlAsString = url.toString()
        val host = url.host()
        val endpoint = when {
            server.count() < urlAsString.count() -> urlAsString.substringAfter(server)
            else -> ""
        }
        val bodyAsString = request.getBodyAsString()
        val payload = mapOf(
            "body" to bodyAsString,
            "endpoint" to endpoint,
            "method" to request.method(),
            "headers" to headers
        )
        val destination = Destination.Server(host, x25519PublicKey)
        return sendOnionRequest(destination, payload, isJSONRequired).recover { exception ->
            Log.d("Loki", "Couldn't reach server: $server due to error: $exception.")
            throw exception
        }
    }

    /**
     * Sends an onion request to `destination`. Builds new paths as needed.
     */
    internal fun sendOnionRequest(destination: Destination, payload: Map<*, *>, isJSONRequired: Boolean = true): Promise<Map<*, *>, Exception> {
        val deferred = deferred<Map<*, *>, Exception>()
        lateinit var guardSnode: Snode
        buildOnionForDestination(payload, destination).success { result ->
            guardSnode = result.guardSnode
            val url = "${guardSnode.address}:${guardSnode.port}/onion_req"
            val finalEncryptionResult = result.finalEncryptionResult
            val onion = finalEncryptionResult.ciphertext
            @Suppress("NAME_SHADOWING") val parameters = mapOf(
                "ciphertext" to Base64.encodeBytes(onion),
                "ephemeral_key" to finalEncryptionResult.ephemeralPublicKey.toHexString()
            )
            val destinationSymmetricKey = result.destinationSymmetricKey
            Thread {
                try {
                    val json = HTTP.execute(HTTP.Verb.POST, url, parameters)
                    val base64EncodedIVAndCiphertext = json["result"] as? String ?: return@Thread deferred.reject(Exception("Invalid JSON"))
                    val ivAndCiphertext = Base64.decode(base64EncodedIVAndCiphertext)
                    val iv = ivAndCiphertext.sliceArray(0 until OnionRequestEncryption.ivSize)
                    val ciphertext = ivAndCiphertext.sliceArray(OnionRequestEncryption.ivSize until ivAndCiphertext.count())
                    try {
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(destinationSymmetricKey, "AES"), GCMParameterSpec(OnionRequestEncryption.gcmTagSize, iv))
                        val plaintext = cipher.doFinal(ciphertext)
                        try {
                            @Suppress("NAME_SHADOWING") val json = JsonUtil.fromJson(plaintext.toString(Charsets.UTF_8), Map::class.java)
                            val statusCode = json["status"] as Int
                            if (statusCode == 406) {
                                val body = mapOf( "result" to "Your clock is out of sync with the service node network." )
                                val exception = HTTPRequestFailedAtTargetSnodeException(statusCode, body)
                                return@Thread deferred.reject(exception)
                            } else if (json["body"] != null) {
                                val bodyAsString = json["body"] as String
                                val body: Map<*, *>
                                if (!isJSONRequired) {
                                    body = mapOf( "result" to bodyAsString )
                                } else {
                                    body = JsonUtil.fromJson(bodyAsString, Map::class.java)
                                }
                                if (statusCode != 200) {
                                    val exception = HTTPRequestFailedAtTargetSnodeException(statusCode, body)
                                    return@Thread deferred.reject(exception)
                                }
                                deferred.resolve(body)
                            } else {
                                if (statusCode != 200) {
                                    val exception = HTTPRequestFailedAtTargetSnodeException(statusCode, json)
                                    return@Thread deferred.reject(exception)
                                }
                                deferred.resolve(json)
                            }
                        } catch (exception: Exception) {
                            deferred.reject(Exception("Invalid JSON."))
                        }
                    } catch (exception: Exception) {
                        deferred.reject(exception)
                    }
                } catch (exception: Exception) {
                    deferred.reject(exception)
                }
            }.start()
        }.fail { exception ->
            deferred.reject(exception)
        }
        val promise = deferred.promise
        promise.fail { exception ->
            if (exception is HTTP.HTTPRequestFailedException) {
                dropPathContaining(guardSnode)
                dropGuardSnode(guardSnode)
            }
        }
        return promise
    }
    // endregion
}
