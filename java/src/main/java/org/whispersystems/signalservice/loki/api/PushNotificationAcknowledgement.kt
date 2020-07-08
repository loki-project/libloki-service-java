package org.whispersystems.signalservice.loki.api

import okhttp3.*
import org.whispersystems.libsignal.logging.Log
import org.whispersystems.signalservice.internal.util.JsonUtil
import java.io.IOException

public class PushNotificationAcknowledgement private constructor(public val server: String) {
    private val connection = OkHttpClient()

    companion object {
        lateinit var shared: PushNotificationAcknowledgement

        fun configureIfNeeded(isDebugMode: Boolean) {
            if (::shared.isInitialized) { return; }
            val server = if (isDebugMode) "https://dev.apns.getsession.org" else "https://live.apns.getsession.org"
            shared = PushNotificationAcknowledgement(server)
        }
    }

    fun acknowledgeDeliveryForMessageWith(hash: String, expiration: Int, publicKey: String) {
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
