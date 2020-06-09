package org.whispersystems.signalservice.loki.api

import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.deferred
import nl.komponents.kovenant.functional.bind
import nl.komponents.kovenant.functional.map
import nl.komponents.kovenant.task
import org.whispersystems.libsignal.logging.Log
import org.whispersystems.signalservice.loki.api.utilities.HTTP
import org.whispersystems.signalservice.loki.database.LokiAPIDatabaseProtocol
import org.whispersystems.signalservice.loki.utilities.getRandomElement
import org.whispersystems.signalservice.loki.utilities.prettifiedDescription
import java.security.SecureRandom

class LokiSwarmAPI private constructor(private val database: LokiAPIDatabaseProtocol) {
    internal var snodeFailureCount: MutableMap<LokiAPITarget, Int> = mutableMapOf()
    internal var snodeVersions: MutableMap<LokiAPITarget, String> = mutableMapOf()

    internal var snodePool: Set<LokiAPITarget>
        get() = database.getSnodePool()
        set(newValue) { database.setSnodePool(newValue) }

    companion object {
        private val seedNodePool: Set<String> = setOf( "https://storage.seed1.loki.network", "https://storage.seed3.loki.network", "https://public.loki.foundation" )

        // region Settings
        private val minimumSnodePoolCount = 32
        private val minimumSwarmSnodeCount = 2
        private val targetSwarmSnodeCount = 3

        /**
         * A snode is kicked out of a swarm and/or the snode pool if it fails this many times.
         */
        internal val snodeFailureThreshold = 2
        // endregion

        // region Initialization
        lateinit var shared: LokiSwarmAPI

        fun configureIfNeeded(database: LokiAPIDatabaseProtocol) {
            if (::shared.isInitialized) { return; }
            shared = LokiSwarmAPI(database)
        }
        // endregion
    }

    // region Swarm API
    internal fun getRandomSnode(): Promise<LokiAPITarget, Exception> {
        if (snodePool.count() < minimumSnodePoolCount) {
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
                        val snodePool = rawTargets.mapNotNull { rawTarget ->
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
                        Log.d("Loki", "Persisting snode pool to database.")
                        this.snodePool = snodePool
                        try {
                            deferred.resolve(snodePool.getRandomElement())
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
            return Promise.of(snodePool.getRandomElement())
        }
    }

    internal fun getSwarm(hexEncodedPublicKey: String): Promise<Set<LokiAPITarget>, Exception> {
        val cachedSwarm = database.getSwarm(hexEncodedPublicKey)
        if (cachedSwarm != null && cachedSwarm.size >= minimumSwarmSnodeCount) {
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
                database.setSwarm(hexEncodedPublicKey, it)
            }
        }
    }

    internal fun dropSnodeFromSwarmIfNeeded(target: LokiAPITarget, hexEncodedPublicKey: String) {
        val swarm = database.getSwarm(hexEncodedPublicKey)?.toMutableSet()
        if (swarm != null && swarm.contains(target)) {
            swarm.remove(target)
            database.setSwarm(hexEncodedPublicKey, swarm)
        }
    }

    internal fun getSingleTargetSnode(hexEncodedPublicKey: String): Promise<LokiAPITarget, Exception> {
        // SecureRandom() should be cryptographically secure
        return getSwarm(hexEncodedPublicKey).map { it.shuffled(SecureRandom()).random() }
    }

    internal fun getTargetSnodes(hexEncodedPublicKey: String): Promise<List<LokiAPITarget>, Exception> {
        // SecureRandom() should be cryptographically secure
        return getSwarm(hexEncodedPublicKey).map { it.shuffled(SecureRandom()).take(targetSwarmSnodeCount) }
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
