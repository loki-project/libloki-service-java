package org.whispersystems.signalservice.loki.api

import nl.komponents.kovenant.*
import nl.komponents.kovenant.functional.bind
import org.whispersystems.libsignal.logging.Log
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import org.whispersystems.signalservice.loki.utilities.Broadcaster
import java.security.SecureRandom
import java.util.*

private class PromiseCanceledException : Exception("Promise canceled.")

class LokiLongPoller(private val userHexEncodedPublicKey: String, private val database: LokiAPIDatabaseProtocol, private val broadcaster: Broadcaster, private val onMessagesReceived: (List<SignalServiceProtos.Envelope>) -> Unit) {
    private var hasStarted: Boolean = false
    private var hasStopped: Boolean = false
    private var connections: Set<Promise<*, Exception>> = setOf()
    private val usedSnodes: MutableSet<LokiAPITarget> = mutableSetOf()

    // region Settings
    companion object {
        private val connectionCount = 3
        private val retryInterval: Long = 4 * 1000
    }
    // endregion

    // region Public API
    fun startIfNeeded() {
        if (hasStarted) { return }
        Log.d("Loki", "Started long polling.")
        hasStarted = true
        hasStopped = false
        openConnections()
    }

    fun stopIfNeeded() {
        if (hasStopped) { return }
        Log.d("Loki", "Stopped long polling.")
        hasStarted = false
        hasStopped = true
        connections.forEach { Kovenant.cancel(it, PromiseCanceledException()) }
        usedSnodes.clear()
    }
    // endregion

    // region Private API
    private fun openConnections() {
        if (hasStopped) { return }
        val thread = Thread.currentThread()
        LokiSwarmAPI(database, broadcaster).getSwarm(userHexEncodedPublicKey).bind {
            usedSnodes.clear()
            connections = (0 until connectionCount).map {
                val deferred = deferred<Unit, Exception>()
                openConnectionToNextSnode(deferred)
                deferred.promise
            }.toSet()
            all(connections.toList(), cancelOthersOnError = false)
        }.always {
            Timer().schedule(object : TimerTask() {

                override fun run() {
                    thread.run { openConnections() }
                }
            }, retryInterval)
        }
    }

    private fun openConnectionToNextSnode(deferred: Deferred<Unit, Exception>) {
        val swarm = database.getSwarmCache(userHexEncodedPublicKey) ?: setOf()
        val unusedSnodes = swarm.subtract(usedSnodes)
        if (unusedSnodes.isNotEmpty()) {
            val index = SecureRandom().nextInt(unusedSnodes.size)
            val nextSnode = unusedSnodes.elementAt(index)
            usedSnodes.add(nextSnode)
            Log.d("Loki", "Opening long polling connection to $nextSnode.")
            longPoll(nextSnode, deferred).fail { exception ->
                if (exception is PromiseCanceledException) {
                    Log.d("Loki", "Long polling connection to $nextSnode canceled.")
                } else {
                    Log.d("Loki", "Long polling connection to $nextSnode failed; dropping it and switching to next snode.")
                    LokiSwarmAPI(database, broadcaster).dropIfNeeded(nextSnode, userHexEncodedPublicKey)
                    openConnectionToNextSnode(deferred)
                }
            }
        } else {
            deferred.resolve()
        }
    }

    private fun longPoll(target: LokiAPITarget, deferred: Deferred<Unit, Exception>): Promise<Unit, Exception> {
        return LokiAPI(userHexEncodedPublicKey, database, broadcaster).getRawMessages(target, true).bind(LokiAPI.messagePollingContext) { rawResponse ->
            if (deferred.promise.isDone()) {
                // The long polling connection has been canceled; don't recurse
                task { Unit }
            } else {
                val messages = LokiAPI(userHexEncodedPublicKey, database, broadcaster).parseRawMessagesResponse(rawResponse, target)
                onMessagesReceived(messages)
                longPoll(target, deferred)
            }
        }
    }
    // endregion
}
