package org.whispersystems.signalservice.loki.api

import nl.komponents.kovenant.functional.map
import okhttp3.*
import org.whispersystems.libsignal.logging.Log
import org.whispersystems.signalservice.internal.util.Base64
import org.whispersystems.signalservice.internal.util.JsonUtil
import org.whispersystems.signalservice.loki.api.onionrequests.OnionRequestAPI
import org.whispersystems.signalservice.loki.protocol.meta.TTLUtilities
import org.whispersystems.signalservice.loki.utilities.removing05PrefixIfNeeded
import org.whispersystems.signalservice.loki.utilities.toHexString
import java.io.IOException

public class PushNotificationAcknowledgement private constructor(public val server: String) {
    public val PNServerPublicKey = "642a6585919742e5a2d4dc51244964fbcd8bcab2b75612407de58b810740d049"

    companion object {
        lateinit var shared: PushNotificationAcknowledgement

        fun configureIfNeeded(isDebugMode: Boolean) {
            if (::shared.isInitialized) { return; }
            val server = if (isDebugMode) "https://staging.apns.getsession.org" else "https://live.apns.getsession.org"
            shared = PushNotificationAcknowledgement(server)
        }
    }

    fun notify(messageInfo: SignalMessageInfo) {
        val message = LokiMessage.from(messageInfo)!!
        if (message.ttl != TTLUtilities.getTTL(TTLUtilities.MessageType.Regular)) { return }
        val parameters = mapOf( "data" to message.data, "send_to" to message.recipientPublicKey )
        val url = "${server}/notify"
        val body = RequestBody.create(MediaType.get("application/json"), JsonUtil.toJson(parameters))
        val request = Request.Builder().url(url).post(body)
        val promise = OnionRequestAPI.sendOnionRequest(request.build(), server, PNServerPublicKey).map { json ->
            val code = json["code"] as? Int
            if (code == null || code == 0) {
                Log.d("Loki", "[Loki] Couldn't notify message due to error: ${json["message"] as? String ?: "null"}.")
            }
        }
        promise.fail { exception ->
            Log.d("Loki", "[Loki] Couldn't notify message due to error: ${exception}.")
        }
    }
}
