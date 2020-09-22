package org.whispersystems.signalservice.loki.api

import nl.komponents.kovenant.functional.map
import okhttp3.*
import org.whispersystems.libsignal.logging.Log
import org.whispersystems.signalservice.internal.util.JsonUtil
import org.whispersystems.signalservice.loki.api.onionrequests.OnionRequestAPI
import java.io.IOException

public class PushNotificationAPI private constructor(public val server: String) {
    private val connection = OkHttpClient()

    companion object {
        public val pnServerPublicKey = "642a6585919742e5a2d4dc51244964fbcd8bcab2b75612407de58b810740d049"

        lateinit var shared: PushNotificationAPI

        public fun configureIfNeeded(isDebugMode: Boolean) {
            if (::shared.isInitialized) { return; }
            val server = if (isDebugMode) "https://dev.apns.getsession.org" else "https://live.apns.getsession.org"
            shared = PushNotificationAPI(server)
        }
    }

    public fun notify(messageInfo: SignalMessageInfo) {
        val message = LokiMessage.from(messageInfo) ?: return
        val parameters = mapOf( "data" to message.data, "send_to" to message.recipientPublicKey )
        val url = "${server}/notify"
        val body = RequestBody.create(MediaType.get("application/json"), JsonUtil.toJson(parameters))
        val request = Request.Builder().url(url).post(body)
        OnionRequestAPI.sendOnionRequest(request.build(), server, PushNotificationAPI.pnServerPublicKey).map { json ->
            val code = json["code"] as? Int
            if (code == null || code == 0) {
                Log.d("Loki", "[Loki] Couldn't notify PN server due to error: ${json["message"] as? String ?: "null"}.")
            }
        }.fail { exception ->
            Log.d("Loki", "[Loki] Couldn't notify PN server due to error: $exception.")
        }
    }

    fun acknowledgeDelivery(hash: String, expiration: Int, publicKey: String) {
        val parameters = mapOf( "hash" to hash, "pubKey" to publicKey, "expiration" to expiration )
        val url = "${server}/acknowledge_message_delivery"
        val body = RequestBody.create(MediaType.get("application/json"), JsonUtil.toJson(parameters))
        val request = Request.Builder().url(url).post(body).build()
        connection.newCall(request).enqueue(object : Callback {

            override fun onResponse(call: Call, response: Response) {
                when (response.code()) {
                    200 -> {
                        val bodyAsString = response.body()!!.string()
                        val json = JsonUtil.fromJson(bodyAsString, Map::class.java)
                        val code  = json?.get("code") as? Int
                        if (code == null || code == 0) {
                            Log.d("Loki", "Couldn't acknowledge delivery for message with hash: $hash due to error: ${json?.get("message") as? String ?: "null"}.")
                        }
                    }
                }
            }

            override fun onFailure(call: Call, exception: IOException) {
                Log.d("Loki", "Couldn't acknowledge delivery for message with hash: $hash.")
            }
        })
    }
}
