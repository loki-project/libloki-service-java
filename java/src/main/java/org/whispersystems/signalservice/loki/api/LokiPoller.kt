package org.whispersystems.signalservice.loki.api

import nl.komponents.kovenant.*
import nl.komponents.kovenant.functional.bind
import org.whispersystems.libsignal.logging.Log
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import org.whispersystems.signalservice.loki.database.LokiAPIDatabaseProtocol
import java.security.SecureRandom
import java.util.*

private class PromiseCanceledException : Exception("Promise canceled.")

class LokiPoller(private val userHexEncodedPublicKey: String, private val database: LokiAPIDatabaseProtocol, private val onMessagesReceived: (List<SignalServiceProtos.Envelope>) -> Unit) {
    private var hasStarted: Boolean = false
    private val usedSnodes: MutableSet<LokiAPITarget> = mutableSetOf()

    private var isCatchUp: Boolean = false

    // region Settings
    companion object {
        private val retryInterval: Long = 1 * 1000
    }
    // endregion

    fun isCatchUp(): Boolean {
        return isCatchUp
    }

    fun shouldCatchUp() {
        isCatchUp = false
    }

    // region Public API
    fun startIfNeeded() {
        if (hasStarted) { return }
        Log.d("Loki", "Started polling.")
        hasStarted = true
        setUpPolling()
    }

    fun stopIfNeeded() {
        Log.d("Loki", "Stopped polling.")
        hasStarted = false
        usedSnodes.clear()
    }
    // endregion

    // region Private API
    private fun setUpPolling() {
        val thread = Thread.currentThread()
        LokiSwarmAPI.shared.getSwarm(userHexEncodedPublicKey).bind(LokiAPI.messagePollingContext) {
            usedSnodes.clear()
            val deferred = deferred<Unit, Exception>(LokiAPI.messagePollingContext)
            pollNextSnode(deferred)
            deferred.promise
        }.always {
            Timer().schedule(object : TimerTask() {

                override fun run() {
                    thread.run { setUpPolling() }
                }
            }, retryInterval)
        }
    }

    private fun pollNextSnode(deferred: Deferred<Unit, Exception>) {
        val swarm = database.getSwarm(userHexEncodedPublicKey) ?: setOf()
        val unusedSnodes = swarm.subtract(usedSnodes)
        if (unusedSnodes.isNotEmpty()) {
            val index = SecureRandom().nextInt(unusedSnodes.size)
            val nextSnode = unusedSnodes.elementAt(index)
            usedSnodes.add(nextSnode)
            Log.d("Loki", "Polling $nextSnode.")
            poll(nextSnode, deferred).fail { exception ->
                if (exception is PromiseCanceledException) {
                    Log.d("Loki", "Polling $nextSnode canceled.")
                } else {
                    Log.d("Loki", "Polling $nextSnode failed; dropping it and switching to next snode.")
                    LokiSwarmAPI.shared.dropSnodeFromSwarmIfNeeded(nextSnode, userHexEncodedPublicKey)
                    pollNextSnode(deferred)
                }
            }
        } else {
            deferred.resolve()
        }
    }

    private fun poll(target: LokiAPITarget, deferred: Deferred<Unit, Exception>): Promise<Unit, Exception> {
        return LokiAPI.shared.getRawMessages(target, false).bind(LokiAPI.messagePollingContext) { rawResponse ->
            if (deferred.promise.isDone()) {
                // The long polling connection has been canceled; don't recurse
                isCatchUp = true
                task { Unit }
            } else {
                val messages = LokiAPI.shared.parseRawMessagesResponse(rawResponse, target)
                onMessagesReceived(messages)
                poll(target, deferred)
            }
        }
    }
    // endregion
}
