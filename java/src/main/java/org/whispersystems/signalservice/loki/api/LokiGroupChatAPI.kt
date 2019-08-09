package org.whispersystems.signalservice.loki.api

import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.deferred
import okhttp3.*
import org.whispersystems.libsignal.logging.Log
import org.whispersystems.signalservice.internal.util.JsonUtil
import org.whispersystems.signalservice.loki.utilities.prettifiedDescription
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

public class LokiGroupChatAPI(private val userHexEncodedPublicKey: String, private val database: LokiAPIDatabaseProtocol) {

    companion object {
        private val serverURL = "https://chat.lokinet.org"
        private val batchCount = 8
        @JvmStatic
        public val publicChatID: Long = 1
    }

    public fun getMessages(groupID: Long): Promise<List<LokiGroupMessage>, Exception> {
        Log.d("Loki", "Getting messages for group chat with ID: $groupID.")
        val queryParameters = "include_annotations=1&count=-$batchCount"
        val url = "$serverURL/channels/$groupID/messages?$queryParameters"
        val request = Request.Builder().url(url).get()
        val connection = OkHttpClient()
        val deferred = deferred<List<LokiGroupMessage>, Exception>()
        connection.newCall(request.build()).enqueue(object : Callback {

            override fun onResponse(call: Call, response: Response) {
                when (response.code()) {
                    200 -> {
                        try {
                            val bodyAsString = response.body()!!.string()
                            val body = JsonUtil.fromJson(bodyAsString, Map::class.java)
                            val messagesAsJSON = body["data"] as List<*>
                            val messages = messagesAsJSON.mapNotNull { messageAsJSON ->
                                try {
                                    val x1 = messageAsJSON as Map<*, *>
                                    val x2 = x1["annotations"] as List<*>
                                    val x3 = x2.first() as Map<*, *>
                                    val x4 = x3["value"] as Map<*, *>
                                    val serverID = x1["id"] as? Long ?: (x1["id"] as Int).toLong()
                                    val hexEncodedPublicKey = x4["source"] as String
                                    if (hexEncodedPublicKey == userHexEncodedPublicKey) return@mapNotNull null
                                    val displayName = x4["from"] as String
                                    @Suppress("NAME_SHADOWING") val body = x1["text"] as String
                                    val timestamp = x4["timestamp"] as Long
                                    LokiGroupMessage(serverID, hexEncodedPublicKey, displayName, body, timestamp)
                                } catch (exception: Exception) {
                                    Log.d("Loki", "Couldn't parse message from: ${messageAsJSON?.prettifiedDescription() ?: "null"}.")
                                    return@mapNotNull null
                                }
                            }
                            deferred.resolve(messages)
                        } catch (exception: Exception) {
                            Log.d("Loki", "Couldn't parse messages for group chat with ID: $groupID.")
                            deferred.reject(exception)
                        }
                    }
                    else -> {
                        Log.d("Loki", "Couldn't reach group chat server.")
                        deferred.reject(LokiAPI.Error.Generic)
                    }
                }
            }

            override fun onFailure(call: Call, exception: IOException) {
                Log.d("Loki", "Couldn't reach group chat server.")
                deferred.reject(exception)
            }
        })
        return deferred.promise
    }

    public fun sendMessage(message: LokiGroupMessage, groupID: Long): Promise<LokiGroupMessage, Exception> {
        Log.d("Loki", "Sending message to group chat with ID: $groupID.")
        val url = "$serverURL/channels/$groupID/messages"
        val parameters = message.toJSON()
        val body = RequestBody.create(MediaType.get("application/json"), parameters)
        val request = Request.Builder().url(url).header("Authorization", "Bearer loki").post(body)
        val connection = OkHttpClient()
        val deferred = deferred<LokiGroupMessage, Exception>()
        connection.newCall(request.build()).enqueue(object : Callback {

            override fun onResponse(call: Call, response: Response) {
                when (response.code()) {
                    200 -> {
                        try {
                            val bodyAsString = response.body()!!.string()
                            @Suppress("NAME_SHADOWING") val body = JsonUtil.fromJson(bodyAsString, Map::class.java)
                            val messageAsJSON = body["data"] as Map<*, *>
                            val serverID = messageAsJSON["id"] as? Long ?: (messageAsJSON["id"] as Int).toLong()
                            val displayName = database.getUserDisplayName() ?: "Anonymous"
                            val text = messageAsJSON["text"] as String
                            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                            val dateAsString = messageAsJSON["created_at"] as String
                            val timestamp = format.parse(dateAsString).time
                            @Suppress("NAME_SHADOWING") val message = LokiGroupMessage(serverID, userHexEncodedPublicKey, displayName, text, timestamp)
                            deferred.resolve(message)
                        } catch (exception: Exception) {
                            Log.d("Loki", "Couldn't parse message for group chat with ID: $groupID.")
                            deferred.reject(exception)
                        }
                    }
                    else -> {
                        Log.d("Loki", "Couldn't reach group chat server.")
                        deferred.reject(LokiAPI.Error.Generic)
                    }
                }
            }

            override fun onFailure(call: Call, exception: IOException) {
                Log.d("Loki", "Couldn't reach group chat server.")
                deferred.reject(exception)
            }
        })
        return deferred.promise
    }
}