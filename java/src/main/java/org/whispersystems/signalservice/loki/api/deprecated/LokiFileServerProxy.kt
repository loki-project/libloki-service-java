package org.whispersystems.signalservice.loki.api.deprecated

import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.deferred
import nl.komponents.kovenant.functional.bind
import nl.komponents.kovenant.functional.map
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import org.whispersystems.curve25519.Curve25519
import org.whispersystems.libsignal.logging.Log
import org.whispersystems.libsignal.loki.DiffieHellman
import org.whispersystems.signalservice.internal.util.Base64
import org.whispersystems.signalservice.internal.util.Hex
import org.whispersystems.signalservice.internal.util.JsonUtil
import org.whispersystems.signalservice.loki.api.SnodeAPI
import org.whispersystems.signalservice.loki.api.SwarmAPI
import org.whispersystems.signalservice.loki.api.fileserver.LokiFileServerAPI
import org.whispersystems.signalservice.loki.utilities.removing05PrefixIfNeeded

internal class LokiFileServerProxy(val server: String, private val isFileUpload: Boolean = false) : LokiHTTPClient(60) {

    private val keyPair = curve.generateKeyPair()

    companion object {
        // The Loki file server public keys are hard coded for now
        private val lokiServerPublicKey = Base64.decode("BWJQnVm97sQE3Q1InB4Vuo+U/T1hmwHBv0ipkiv8tzEc").removing05PrefixIfNeeded()
        private val curve = Curve25519.getInstance(Curve25519.BEST)
    }

    override fun execute(request: Request): Promise<Response, Exception> {
        if (server != LokiFileServerAPI.shared.server) { return super.execute(request) }
        val keyPair = this.keyPair
        val body = getRequestBody(request)
        val canonicalHeaders = getCanonicalHeaders(request)
        val deferred = deferred<Response, Exception>()
        Thread {
            val symmetricKey = curve.calculateAgreement(lokiServerPublicKey, keyPair.privateKey)
            // Kovenant propagates a context to chained promises, so LokiPublicChatAPI.sharedContext should be used for all of the below
            SwarmAPI.shared.getRandomSnode().bind(SnodeAPI.sharedContext) { proxy ->
                val url = "${proxy.address}:${proxy.port}/file_proxy"
                Log.d("Loki", "Proxying file server request through $proxy.")
                val endpoint = request.url().toString().removePrefix(server).removePrefix("/")
                val unencryptedProxyRequestBody = mapOf("body" to body, "endpoint" to endpoint, "method" to request.method(), "headers" to canonicalHeaders)
                val ivAndCipherText = DiffieHellman.encrypt(JsonUtil.toJson(unencryptedProxyRequestBody).toByteArray(Charsets.UTF_8), symmetricKey)
                val proxyRequestBody = mapOf("cipherText64" to Base64.encodeBytes(ivAndCipherText))
                val headers = mapOf("X-Loki-File-Server-Ephemeral-Key" to getBase64EncodedPublicKey(keyPair.publicKey))
                val proxyRequest = Request.Builder()
                    .url(url)
                    .post(RequestBody.create(MediaType.get("application/json"), JsonUtil.toJson(proxyRequestBody)))
                    .header("X-Loki-File-Server-Target", "/loki/v1/secure_rpc")
                    .header("X-Loki-File-Server-Verb", "POST")
                    .header("X-Loki-File-Server-Headers", JsonUtil.toJson(headers))
                    .header("Connection", "close")
                    .build()
                execute(proxyRequest, getClearnetConnection())
            }.map(SnodeAPI.sharedContext) { response ->
                var statusCode = response.code()
                var body: String? = response.body()?.string()
                if (response.isSuccessful && body != null) {
                    try {
                        val info = unwrap(body)
                        statusCode = info.first
                        if (statusCode.isSuccessfulHTTPStatusCode()) {
                            val base64Data = info.second!!
                            val ivAndCipherText = Base64.decode(base64Data)
                            val decryptedBody = DiffieHellman.decrypt(ivAndCipherText, symmetricKey)
                            body = decryptedBody.toString(Charsets.UTF_8)
                            // The decrypted request should have an inner status code
                            try {
                                val innerInfo = unwrap(body)
                                statusCode = innerInfo.first
                            } catch (e: Exception) {
                                // Do nothing
                            }
                        }
                    } catch (e: Error) {
                        statusCode = -1
                        body = "Failed to parse JSON"
                    }
                }
                return@map Response(statusCode.isSuccessfulHTTPStatusCode(), statusCode, body)
            }.success {
                deferred.resolve(it)
            }.fail {
                deferred.reject(it)
            }
        }.start()
        return deferred.promise
    }

    private fun unwrap(body: String): Pair<Int, String?> {
        val json = JsonUtil.fromJson(body)
        val code = json.get("meta").get("code").asInt()
        val data = if (json.hasNonNull("data")) json.get("data").asText() else null
        return Pair(code, data)
    }

    private fun getBase64EncodedPublicKey(data: ByteArray): String {
        var string = Hex.toStringCondensed(data)
        // The file server expects an 05 prefixed public key
        if (data.size == 32) { string = "05$string" }
        val sessionID = Hex.fromStringCondensed(string)
        return Base64.encodeBytes(sessionID)
    }

    private fun getRequestBody(request: Request): Any? {
        val requestBody = request.body()
        val body = super.getBody(request)
        if (requestBody is MultipartBody && body != null) {
            return mapOf( "fileUpload" to Base64.encodeBytes(body) )
        }
        val charset = requestBody?.contentType()?.charset() ?: Charsets.UTF_8
        return body?.toString(charset)
    }
}
