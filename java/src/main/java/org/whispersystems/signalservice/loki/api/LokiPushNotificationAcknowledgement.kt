package org.whispersystems.signalservice.loki.api

import okhttp3.*
import org.whispersystems.libsignal.logging.Log
import org.whispersystems.signalservice.internal.util.JsonUtil
import java.io.IOException

object LokiPushNotificationAcknowledgement {
    //const val server = "https://live.apns.getsession.org/"
    const val server = "https://dev.apns.getsession.org/"
    private val connection = OkHttpClient()

    fun acknowledgeDeliveryForMessageWith(hash: String, expiration: Int, hexEncodedPublicKey: String) {
        val parameters = mapOf("hash" to hash, "pubKey" to hexEncodedPublicKey, "expiration" to expiration)
        val url = "${server}acknowledge_message_delivery"
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
                            Log.d("Loki", "Couldn't acknowledge the delivery for message due to error: ${json?.get("message") as? String}.")
                        }
                    }
                }
            }

            override fun onFailure(call: Call, exception: IOException) {
                Log.d("Loki", "Couldn't acknowledge the delivery for message with last hash: ${hash}")
            }
        })
    }
}
