package org.whispersystems.signalservice.loki.api.onionrequests

import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.all
import nl.komponents.kovenant.deferred
import nl.komponents.kovenant.functional.bind
import nl.komponents.kovenant.functional.map
import org.whispersystems.libsignal.logging.Log
import org.whispersystems.signalservice.loki.api.LokiAPI
import org.whispersystems.signalservice.loki.api.LokiAPITarget
import org.whispersystems.signalservice.loki.api.LokiSwarmAPI
import org.whispersystems.signalservice.loki.api.http.HTTP
import org.whispersystems.signalservice.loki.utilities.getRandomElement
import org.whispersystems.signalservice.loki.utilities.getRandomElementOrNull

// region Type Aliases
private typealias Path = List<LokiAPITarget>
private typealias Snode = LokiAPITarget
// endregion

/**
 * See the "Onion Requests" section of [The Session Whitepaper](https://arxiv.org/pdf/2002.04609.pdf) for more information.
 */
object OnionRequestAPI {
    private var guardSnodes = setOf<Snode>()
    private var paths = setOf<Path>()

    private val snodePool: Set<Snode>
        get() {
            val unreliableSnodes = LokiSwarmAPI.failureCount.keys
            return LokiSwarmAPI.randomSnodePool.minus(unreliableSnodes)
        }

    // region Settings
    private val pathCount = 2 // A main path and a backup path for the case where the target snode is in the main path
    /**
     * The number of snodes (including the guard snode) in a path.
     */
    private val pathSize = 1

    private val guardSnodeCount
        get() = pathCount // One per path
    // endregion

    class HTTPRequestFailedAtTargetSnodeException(val statusCode: Int, val json: Map<*, *>)
        : Exception("HTTP request failed at target snode with status code $statusCode.")
    class InsufficientSnodesException : Exception("Couldn't find enough snodes to build a path.")

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
            return LokiSwarmAPI.getRandomSnode().bind(LokiAPI.sharedContext) { // Just used to populate the snode pool
                var unusedSnodes = snodePool
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
                all(promises).map(LokiAPI.sharedContext) { guardSnodes ->
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
    private fun buildPaths(): Promise<Set<Path>, Exception> {
        Log.d("Loki", "Building onion request paths.")
        return LokiSwarmAPI.getRandomSnode().bind(LokiAPI.sharedContext) { // Just used to populate the snode pool
            getGuardSnodes().map(LokiAPI.sharedContext) { guardSnodes ->
                var unusedSnodes = snodePool.minus(guardSnodes)
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
                }.toSet()
            }
        }
    }

    /**
     * Returns a `Path` to be used for building an onion request. Builds new paths as needed.
     */
    private fun getPath(snodeToExclude: Snode): Promise<Path, Exception> {
        if (pathSize < 1) { throw Exception("Can't build path of size zero.") }
        fun getPath(): Path {
            val filteredPaths = paths.filter { !it.contains(snodeToExclude) }
            return filteredPaths.getRandomElement()
        }
        if (paths.count() >= pathCount) {
            return Promise.of(getPath())
        } else {
            return buildPaths().map(LokiAPI.sharedContext) { paths ->
                OnionRequestAPI.paths = paths
                getPath()
            }
        }
    }

    private fun dropPathContaining(snode: Snode) {
        paths = paths.filter { !it.contains(snode) }.toSet()
    }
    // endregion
}
