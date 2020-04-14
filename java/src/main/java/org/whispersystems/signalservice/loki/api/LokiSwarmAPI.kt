package org.whispersystems.signalservice.loki.api

import nl.komponents.kovenant.Deferred
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.deferred
import nl.komponents.kovenant.functional.bind
import nl.komponents.kovenant.functional.map
import nl.komponents.kovenant.task
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.whispersystems.libsignal.logging.Log
import org.whispersystems.signalservice.internal.util.JsonUtil
import org.whispersystems.signalservice.loki.api.http.HTTP
import org.whispersystems.signalservice.loki.utilities.getRandomElement
import org.whispersystems.signalservice.loki.utilities.prettifiedDescription
import java.io.IOException
import java.security.SecureRandom

class LokiSwarmAPI private constructor(private val database: LokiAPIDatabaseProtocol) {

    companion object {
        internal var failureCount: MutableMap<LokiAPITarget, Int> = mutableMapOf()
        internal var snodeVersions: MutableMap<LokiAPITarget, String> = mutableMapOf()

        // region Settings
        private val minimumSnodeCount = 2
        private val targetSnodeCount = 3

        internal val failureThreshold = 2
        // endregion

        // region Initialization
        lateinit var shared: LokiSwarmAPI

        fun configureIfNeeded(database: LokiAPIDatabaseProtocol) {
            if (::shared.isInitialized) { return; }
            shared = LokiSwarmAPI(database)
        }
        // endregion

        // region Clearnet Setup
        private val seedNodePool: Set<String> = setOf( "http://storage.seed1.loki.network:22023", "http://storage.seed2.loki.network:38157", "http://149.56.148.124:38157" )

        internal var randomSnodePool: MutableSet<LokiAPITarget> = mutableSetOf()
        // endregion

        // region Swarm API
        internal fun getRandomSnode(): Promise<LokiAPITarget, Exception> {
            if (randomSnodePool.isEmpty()) {
                val target = seedNodePool.random()
                val url = "$target/json_rpc"
                Log.d("Loki", "Populating snode pool using: $target.")
                val parameters = mapOf(
                    "method" to "get_n_service_nodes",
                    "params" to mapOf(
                        "active_only" to true,
                        "fields" to mapOf( "public_ip" to true, "storage_port" to true, "pubkey_x25519" to true, "pubkey_ed25519" to true )
                    )
                )
                val deferred = deferred<LokiAPITarget, Exception>()
                deferred<LokiAPITarget, Exception>(LokiAPI.sharedContext)
                Thread {
                    try {
                        val json = HTTP.execute(HTTP.Verb.POST, url, parameters)
                        val intermediate = json["result"] as? Map<*, *>
                        val rawTargets = intermediate?.get("service_node_states") as? List<*>
                        if (rawTargets != null) {
                            randomSnodePool = rawTargets.mapNotNull { rawTarget ->
                                val rawTargetAsJSON = rawTarget as? Map<*, *>
                                val address = rawTargetAsJSON?.get("public_ip") as? String
                                val port = rawTargetAsJSON?.get("storage_port") as? Int
                                val ed25519Key = rawTargetAsJSON?.get("pubkey_ed25519") as? String
                                val x25519Key = rawTargetAsJSON?.get("pubkey_x25519") as? String
                                if (address != null && port != null && ed25519Key != null && x25519Key != null && address != "0.0.0.0") {
                                    LokiAPITarget("https://$address", port, LokiAPITarget.KeySet(ed25519Key, x25519Key))
                                } else {
                                    Log.d("Loki", "Failed to parse: ${rawTarget?.prettifiedDescription()}.")
                                    null
                                }
                            }.toMutableSet()
                            try {
                                deferred.resolve(randomSnodePool.getRandomElement())
                            } catch (exception: Exception) {
                                Log.d("Loki", "Got an empty snode pool from: $target.")
                                deferred.reject(LokiAPI.Error.Generic)
                            }
                        } else {
                            Log.d("Loki", "Failed to update snode pool from: ${(rawTargets as List<*>?)?.prettifiedDescription()}.")
                            deferred.reject(LokiAPI.Error.Generic)
                        }
                    } catch (exception: Exception) {
                        deferred.reject(exception)
                    }
                }.start()
                return deferred.promise
            } else {
                return Promise.of(randomSnodePool.getRandomElement())
            }
        }

        internal fun getFileServerProxy(): Promise<LokiAPITarget, Exception> {
            val deferred = deferred<LokiAPITarget, Exception>(LokiAPI.sharedContext)
            Thread {
                getFileServerProxyInternal(deferred)
            }.start()
            return deferred.promise
        }

        /**
         * WARNING: This function blocks the calling thread.
         */
        private fun getFileServerProxyInternal(deferred: Deferred<LokiAPITarget, Exception>, failureCount: Int = 0) {
            if (deferred.promise.isDone()) { return }
            val candidate: LokiAPITarget
            try {
                candidate = getRandomSnode().get()
            } catch (e: Exception) {
                deferred.reject(e)
                return
            }
            val maxFailureCount = 3
            try {
                val version = getVersion(candidate).get()
                if (version >= "2.0.2") {
                    Log.d("Loki", "Using file server proxy with version number $version.")
                    deferred.resolve(candidate)
                } else {
                    Log.d("Loki", "Rejecting file server proxy with version number $version.")
                    getFileServerProxyInternal(deferred, failureCount)
                }
            } catch (e: Exception) {
                if (failureCount < maxFailureCount) {
                    getFileServerProxyInternal(deferred, failureCount + 1)
                } else {
                    deferred.reject(e)
                }
            }
        }

        private fun getVersion(snode: LokiAPITarget): Promise<String, Exception> {
            val version = snodeVersions[snode]
            if (version != null) { return Promise.of(version) }
            val deferred = deferred<String, Exception>()
            val url = "${snode.address}:${snode.port}/get_stats/v1"
            val request = Request.Builder().url(url).get()
            val connection = LokiHTTPClient(LokiAPI.defaultTimeout).getClearnetConnection()
            connection.newCall(request.build()).enqueue(object : Callback {

                override fun onResponse(call: Call, response: Response) {
                    when (response.code()) {
                        200 -> {
                            val bodyAsString = response.body()!!.string()
                            val body = JsonUtil.fromJson(bodyAsString, Map::class.java)
                            @Suppress("NAME_SHADOWING") val version = body?.get("version") as? String
                            if (version != null) {
                                snodeVersions[snode] = version
                                deferred.resolve(version)
                            } else {
                                deferred.reject(LokiAPI.Error.MissingSnodeVersion)
                            }
                        } else -> {
                            Log.d("Loki", "Couldn't reach $snode.")
                            deferred.reject(LokiAPI.Error.Generic)
                        }
                    }
                }

                override fun onFailure(call: Call, exception: IOException) {
                    Log.d("Loki", "Couldn't reach $snode.")
                    deferred.reject(exception)
                }
            })
            return deferred.promise
        }
        // endregion
    }

    // region Caching
    internal fun dropSnodeIfNeeded(target: LokiAPITarget, hexEncodedPublicKey: String) {
        val swarm = database.getSwarmCache(hexEncodedPublicKey)?.toMutableSet()
        if (swarm != null && swarm.contains(target)) {
            swarm.remove(target)
            database.setSwarmCache(hexEncodedPublicKey, swarm)
        }
    }
    // endregion

    // region Swarm API
    internal fun getSwarm(hexEncodedPublicKey: String): Promise<Set<LokiAPITarget>, Exception> {
        val cachedSwarm = database.getSwarmCache(hexEncodedPublicKey)
        if (cachedSwarm != null && cachedSwarm.size >= minimumSnodeCount) {
            val cachedSwarmCopy = mutableSetOf<LokiAPITarget>() // Workaround for a Kotlin compiler issue
            cachedSwarmCopy.addAll(cachedSwarm)
            return task { cachedSwarmCopy }
        } else {
            val parameters = mapOf( "pubKey" to hexEncodedPublicKey )
            return getRandomSnode().bind {
                LokiAPI.shared.invoke(LokiAPITarget.Method.GetSwarm, it, hexEncodedPublicKey, parameters)
            }.map(LokiAPI.sharedContext) {
                parseTargets(it).toSet()
            }.success {
                database.setSwarmCache(hexEncodedPublicKey, it)
            }
        }
    }

    internal fun getSingleTargetSnode(hexEncodedPublicKey: String): Promise<LokiAPITarget, Exception> {
        // SecureRandom() should be cryptographically secure
        return getSwarm(hexEncodedPublicKey).map { it.shuffled(SecureRandom()).random() }
    }

    internal fun getTargetSnodes(hexEncodedPublicKey: String): Promise<List<LokiAPITarget>, Exception> {
        // SecureRandom() should be cryptographically secure
        return getSwarm(hexEncodedPublicKey).map { it.shuffled(SecureRandom()).take(targetSnodeCount) }
    }
    // endregion

    // region Parsing
    private fun parseTargets(rawResponse: Any): List<LokiAPITarget> {
        val json = rawResponse as? Map<*, *>
        val rawSnodes = json?.get("snodes") as? List<*>
        if (rawSnodes != null) {
            return rawSnodes.mapNotNull { rawSnode ->
                val rawSnodeAsJSON = rawSnode as? Map<*, *>
                val address = rawSnodeAsJSON?.get("ip") as? String
                val portAsString = rawSnodeAsJSON?.get("port") as? String
                val port = portAsString?.toInt()
                val ed25519Key = rawSnodeAsJSON?.get("pubkey_ed25519") as? String
                val x25519Key = rawSnodeAsJSON?.get("pubkey_x25519") as? String
                if (address != null && port != null && ed25519Key != null && x25519Key != null && address != "0.0.0.0") {
                    LokiAPITarget("https://$address", port, LokiAPITarget.KeySet(ed25519Key, x25519Key))
                } else {
                    Log.d("Loki", "Failed to parse target from: ${rawSnode?.prettifiedDescription()}.")
                    null
                }
            }
        } else {
            Log.d("Loki", "Failed to parse targets from: ${rawResponse.prettifiedDescription()}.")
            return listOf()
        }
    }
    // endregion
}
