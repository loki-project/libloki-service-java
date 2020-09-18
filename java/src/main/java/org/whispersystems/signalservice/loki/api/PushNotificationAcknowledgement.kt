package org.whispersystems.signalservice.loki.api

import nl.komponents.kovenant.functional.map
import okhttp3.*
import org.whispersystems.libsignal.logging.Log
import org.whispersystems.signalservice.internal.util.JsonUtil
import org.whispersystems.signalservice.loki.api.onionrequests.OnionRequestAPI
import org.whispersystems.signalservice.loki.protocol.meta.TTLUtilities

public class PushNotificationAcknowledgement private constructor(public val server: String) {

    companion object {
        public val pnServerPublicKey = "642a6585919742e5a2d4dc51244964fbcd8bcab2b75612407de58b810740d049"

        lateinit var shared: PushNotificationAcknowledgement

        public fun configureIfNeeded(isDebugMode: Boolean) {
            if (::shared.isInitialized) { return; }
            val server = if (isDebugMode) "https://staging.apns.getsession.org" else "https://live.apns.getsession.org"
            shared = PushNotificationAcknowledgement(server)
        }
    }

    public fun notify(messageInfo: SignalMessageInfo) {
        val message = LokiMessage.from(messageInfo) ?: return
        val parameters = mapOf( "data" to message.data, "send_to" to message.recipientPublicKey )
        val url = "${server}/notify"
        val body = RequestBody.create(MediaType.get("application/json"), JsonUtil.toJson(parameters))
        val request = Request.Builder().url(url).post(body)
        OnionRequestAPI.sendOnionRequest(request.build(), server, PushNotificationAcknowledgement.pnServerPublicKey).map { json ->
            val code = json["code"] as? Int
            if (code == null || code == 0) {
                Log.d("Loki", "[Loki] Couldn't notify PN server due to error: ${json["message"] as? String ?: "null"}.")
            }
        }.fail { exception ->
            Log.d("Loki", "[Loki] Couldn't notify PN server due to error: $exception.")
        }
    }
}
