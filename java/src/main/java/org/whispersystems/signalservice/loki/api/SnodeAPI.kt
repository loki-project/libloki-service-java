package org.whispersystems.signalservice.loki.api

import nl.komponents.kovenant.Kovenant
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.deferred
import nl.komponents.kovenant.functional.bind
import nl.komponents.kovenant.functional.map
import nl.komponents.kovenant.task
import org.whispersystems.libsignal.logging.Log
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import org.whispersystems.signalservice.internal.util.Base64
import org.whispersystems.signalservice.loki.api.onionrequests.OnionRequestAPI
import org.whispersystems.signalservice.loki.api.utilities.HTTP
import org.whispersystems.signalservice.loki.database.LokiAPIDatabaseProtocol
import org.whispersystems.signalservice.loki.utilities.Broadcaster
import org.whispersystems.signalservice.loki.utilities.createContext
import org.whispersystems.signalservice.loki.utilities.prettifiedDescription
import org.whispersystems.signalservice.loki.utilities.retryIfNeeded
import java.net.ConnectException
import java.net.SocketTimeoutException

class SnodeAPI private constructor(public var userPublicKey: String, public val database: LokiAPIDatabaseProtocol, public val broadcaster: Broadcaster) {

    companion object {
        val messageSendingContext = Kovenant.createContext("LokiAPIMessageSendingContext")
        val messagePollingContext = Kovenant.createContext("LokiAPIMessagePollingContext")
        /**
         * For operations that are shared between message sending and message polling.
         */
        val sharedContext = Kovenant.createContext("LokiAPISharedContext")

        // region Initialization
        lateinit var shared: SnodeAPI

        fun configureIfNeeded(userHexEncodedPublicKey: String, database: LokiAPIDatabaseProtocol, broadcaster: Broadcaster) {
            if (::shared.isInitialized) { return; }
            shared = SnodeAPI(userHexEncodedPublicKey, database, broadcaster)
        }
        // endregion

        // region Settings
        private val maxRetryCount = 4
        private val useOnionRequests = true

        internal var powDifficulty = 1
        // endregion

        // region User ID Caching

        // endregion
    }

    // region Error
    sealed class Error(val description: String) : Exception() {
        class HTTPRequestFailed(val code: Int) : Error("HTTP request failed with error code: $code.")
        object Generic : Error("An error occurred.")
        object ResponseBodyMissing: Error("Response body missing.")
        object MessageSigningFailed: Error("Failed to sign message.")
        /**
         * Only applicable to snode targets as proof of work isn't required for P2P messaging.
         */
        object ProofOfWorkCalculationFailed : Error("Failed to calculate proof of work.")
        object MessageConversionFailed : Error("Failed to convert Signal message to Loki message.")
        object ClockOutOfSync : Error("The user's clock is out of sync with the service node network.")
        object SnodeMigrated : Error("The snode previously associated with the given public key has migrated to a different swarm.")
        object InsufficientProofOfWork : Error("The proof of work is insufficient.")
        object TokenExpired : Error("The auth token being used has expired.")
        object ParsingFailed : Error("Couldn't parse JSON.")
        object MissingSnodeVersion : Error("Missing service node version.")
        class TargetPublicKeySetMissing(target: Snode) : Error("Missing public key set for: $target.")
    }
    // endregion

    // region Internal API
    /**
     * `hexEncodedPublicKey` is the hex encoded public key of the user the call is associated with. This is needed for swarm cache maintenance.
     */
    internal fun invoke(method: Snode.Method, snode: Snode, hexEncodedPublicKey: String, parameters: Map<String, String>): RawResponsePromise {
        val url = "${snode.address}:${snode.port}/storage_rpc/v1"
        if (useOnionRequests) {
            return OnionRequestAPI.sendOnionRequest(method, snode, hexEncodedPublicKey, parameters)
        } else {
            val deferred = deferred<Map<*, *>, Exception>()
            Thread {
                val payload = mapOf( "method" to method.rawValue, "params" to parameters )
                try {
                    val json = HTTP.execute(HTTP.Verb.POST, url, payload)
                    deferred.resolve(json)
                } catch (exception: Exception) {
                    if (exception is ConnectException || exception is SocketTimeoutException) {
                        dropSnodeIfNeeded(snode, hexEncodedPublicKey)
                    } else {
                        val httpRequestFailedException = exception as? HTTP.HTTPRequestFailedException
                        if (httpRequestFailedException != null) {
                            @Suppress("NAME_SHADOWING") val exception = handleSnodeError(httpRequestFailedException.statusCode, httpRequestFailedException.json, snode, hexEncodedPublicKey)
                            return@Thread deferred.reject(exception)
                        }
                        Log.d("Loki", "Unhandled exception: $exception.")
                    }
                    deferred.reject(exception)
                }
            }.start()
            return deferred.promise
        }
    }

    internal fun getRawMessages(snode: Snode): RawResponsePromise {
        val lastHashValue = database.getLastMessageHashValue(snode) ?: ""
        val parameters = mapOf( "pubKey" to userPublicKey, "lastHash" to lastHashValue )
        return invoke(Snode.Method.GetMessages, snode, userPublicKey, parameters)
    }
    // endregion

    // region Public API
    fun getMessages(): MessageListPromise {
        return retryIfNeeded(maxRetryCount) {
            SwarmAPI.shared.getSingleTargetSnode(userPublicKey).bind(messagePollingContext) { snode ->
                getRawMessages(snode).map(messagePollingContext) { parseRawMessagesResponse(it, snode) }
            }
        }
    }

    @kotlin.ExperimentalUnsignedTypes
    fun sendSignalMessage(message: SignalMessageInfo): Promise<Set<RawResponsePromise>, Exception> {
        val lokiMessage = LokiMessage.from(message) ?: return task { throw Error.MessageConversionFailed }
        val destination = lokiMessage.destination
        fun broadcast(event: String) {
            val dayInMs = 86400000
            if (message.ttl != dayInMs && message.ttl != 4 * dayInMs) { return }
            broadcaster.broadcast(event, message.timestamp)
        }
        broadcast("calculatingPoW")
        return lokiMessage.calculatePoW().bind { lokiMessageWithPoW ->
            broadcast("contactingNetwork")
            retryIfNeeded(maxRetryCount) {
                SwarmAPI.shared.getTargetSnodes(destination).map { swarm ->
                    swarm.map { snode ->
                        broadcast("sendingMessage")
                        val parameters = lokiMessage.toJSON()
                        retryIfNeeded(maxRetryCount) {
                            invoke(Snode.Method.SendMessage, snode, destination, parameters).map { rawResponse ->
                                val json = rawResponse as? Map<*, *>
                                val powDifficulty = json?.get("difficulty") as? Int
                                if (powDifficulty != null) {
                                    if (powDifficulty != SnodeAPI.powDifficulty && powDifficulty < 100) {
                                        Log.d("Loki", "Setting proof of work difficulty to $powDifficulty (snode: $snode).")
                                        SnodeAPI.powDifficulty = powDifficulty
                                    }
                                } else {
                                    Log.d("Loki", "Failed to update proof of work difficulty from: ${rawResponse.prettifiedDescription()}.")
                                }
                                rawResponse
                            }
                        }
                    }.toSet()
                }
            }
        }
    }
    // endregion

    // region Parsing

    // The parsing utilities below use a best attempt approach to parsing; they warn for parsing failures but don't throw exceptions.

    internal fun parseRawMessagesResponse(rawResponse: RawResponse, snode: Snode): List<Envelope> {
        val messages = rawResponse["messages"] as? List<*>
        if (messages != null) {
            updateLastMessageHashValueIfPossible(snode, messages)
            val newRawMessages = removeDuplicates(messages)
            return parseEnvelopes(newRawMessages)
        } else {
            return listOf()
        }
    }

    private fun updateLastMessageHashValueIfPossible(snode: Snode, rawMessages: List<*>) {
        val lastMessageAsJSON = rawMessages.lastOrNull() as? Map<*, *>
        val hashValue = lastMessageAsJSON?.get("hash") as? String
        val expiration = lastMessageAsJSON?.get("expiration") as? Int
        if (hashValue != null) {
            database.setLastMessageHashValue(snode, hashValue)
            if (expiration != null) {
                PushNotificationAcknowledgement.shared.acknowledgeDeliveryForMessageWith(hashValue, expiration, userPublicKey)
            }
        } else if (rawMessages.isNotEmpty()) {
            Log.d("Loki", "Failed to update last message hash value from: ${rawMessages.prettifiedDescription()}.")
        }
    }

    private fun removeDuplicates(rawMessages: List<*>): List<*> {
        val receivedMessageHashValues = database.getReceivedMessageHashValues()?.toMutableSet() ?: mutableSetOf()
        return rawMessages.filter { rawMessage ->
            val rawMessageAsJSON = rawMessage as? Map<*, *>
            val hashValue = rawMessageAsJSON?.get("hash") as? String
            if (hashValue != null) {
                val isDuplicate = receivedMessageHashValues.contains(hashValue)
                receivedMessageHashValues.add(hashValue)
                database.setReceivedMessageHashValues(receivedMessageHashValues)
                !isDuplicate
            } else {
                Log.d("Loki", "Missing hash value for message: ${rawMessage?.prettifiedDescription()}.")
                false
            }
        }
    }

    private fun parseEnvelopes(rawMessages: List<*>): List<Envelope> {
        return rawMessages.mapNotNull { rawMessage ->
            val rawMessageAsJSON = rawMessage as? Map<*, *>
            val base64EncodedData = rawMessageAsJSON?.get("data") as? String
            val data = base64EncodedData?.let { Base64.decode(it) }
            if (data != null) {
                try {
                    MessageWrapper.unwrap(data)
                } catch (e: Exception) {
                    Log.d("Loki", "Failed to unwrap data for message: ${rawMessage.prettifiedDescription()}.")
                    null
                }
            } else {
                Log.d("Loki", "Failed to decode data for message: ${rawMessage?.prettifiedDescription()}.")
                null
            }
        }
    }
    // endregion

    // region Error Handling
    private fun dropSnodeIfNeeded(snode: Snode, hexEncodedPublicKey: String) {
        val oldFailureCount = SwarmAPI.shared.snodeFailureCount[snode] ?: 0
        val newFailureCount = oldFailureCount + 1
        SwarmAPI.shared.snodeFailureCount[snode] = newFailureCount
        Log.d("Loki", "Couldn't reach snode at $snode; setting failure count to $newFailureCount.")
        if (newFailureCount >= SwarmAPI.snodeFailureThreshold) {
            Log.d("Loki", "Failure threshold reached for: $snode; dropping it.")
            SwarmAPI.shared.dropSnodeFromSwarmIfNeeded(snode, hexEncodedPublicKey)
            SwarmAPI.shared.snodePool = SwarmAPI.shared.snodePool.toMutableSet().minus(snode).toSet()
            SwarmAPI.shared.snodeFailureCount[snode] = 0
        }
    }

    internal fun handleSnodeError(statusCode: Int, json: Map<*, *>?, snode: Snode, hexEncodedPublicKey: String): Exception {
        when (statusCode) {
            400, 500, 503 -> { // Usually indicates that the snode isn't up to date
                dropSnodeIfNeeded(snode, hexEncodedPublicKey)
                return Error.HTTPRequestFailed(statusCode)
            }
            406 -> {
                Log.d("Loki", "The user's clock is out of sync with the service node network.")
                broadcaster.broadcast("clockOutOfSync")
                return Error.ClockOutOfSync
            }
            421 -> {
                // The snode isn't associated with the given public key anymore
                Log.d("Loki", "Invalidating swarm for: $hexEncodedPublicKey.")
                SwarmAPI.shared.dropSnodeFromSwarmIfNeeded(snode, hexEncodedPublicKey)
                return Error.SnodeMigrated
            }
            432 -> {
                // The PoW difficulty is too low
                val powDifficulty = json?.get("difficulty") as? Int
                if (powDifficulty != null && powDifficulty < 100) {
                    Log.d("Loki", "Setting proof of work difficulty to $powDifficulty (snode: $snode).")
                    SnodeAPI.powDifficulty = powDifficulty
                } else {
                    Log.d("Loki", "Failed to update proof of work difficulty.")
                }
                return Error.InsufficientProofOfWork
            }
            else -> {
                Log.d("Loki", "Unhandled response code: ${statusCode}.")
                return Error.Generic
            }
        }
    }
    // endregion
}

// region Convenience
typealias RawResponse = Map<*, *>
typealias MessageListPromise = Promise<List<Envelope>, Exception>
typealias RawResponsePromise = Promise<RawResponse, Exception>
// endregion
