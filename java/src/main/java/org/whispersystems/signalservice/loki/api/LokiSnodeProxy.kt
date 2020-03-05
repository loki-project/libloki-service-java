package org.whispersystems.signalservice.loki.api

import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.deferred
import nl.komponents.kovenant.functional.bind
import nl.komponents.kovenant.functional.map
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import org.whispersystems.curve25519.Curve25519
import org.whispersystems.libsignal.logging.Log
import org.whispersystems.libsignal.loki.DiffieHellman
import org.whispersystems.signalservice.internal.util.Base64
import org.whispersystems.signalservice.internal.util.Hex
import org.whispersystems.signalservice.internal.util.JsonUtil

public class LokiSnodeProxy(private val target: LokiAPITarget, timeout: Long) : LokiHTTPClient(timeout) {

    private val keyPair by lazy { curve.generateKeyPair() }

    // region Settings
    companion object {
        private val curve = Curve25519.getInstance(Curve25519.BEST)
    }
    // endregion

    // region Error
    sealed class Error(val description: String) : Exception() {
        class TargetPublicKeySetMissing(target: LokiAPITarget) : Error("Missing public key set for: $target.")
    }
    // endregion

    // region Proxying
    override fun execute(request: Request): Promise<Response, Exception> {
        val targetHexEncodedPublicKeySet = target.publicKeySet ?: return Promise.ofFail(Error.TargetPublicKeySetMissing(target))
        val keyPair = this.keyPair
        val requestBodyAsString = getBodyAsString(request)
        val canonicalRequestHeaders = getCanonicalHeaders(request)
        val deferred = deferred<Response, Exception>()
        Thread {
            val symmetricKey = curve.calculateAgreement(Hex.fromStringCondensed(targetHexEncodedPublicKeySet.encryptionKey), keyPair.privateKey)
            lateinit var proxy: LokiAPITarget
            LokiSwarmAPI.getRandomSnode().bind(LokiAPI.sharedWorkContext) { p ->
                proxy = p
                val url = "${proxy.address}:${proxy.port}/proxy"
                Log.d("Loki", "Proxying request to $target through $proxy.")
                val unencryptedProxyRequestBody = mapOf( "method" to request.method(), "body" to requestBodyAsString, "headers" to canonicalRequestHeaders )
                val ivAndCipherText = DiffieHellman.encrypt(JsonUtil.toJson(unencryptedProxyRequestBody).toByteArray(Charsets.UTF_8), symmetricKey)
                val proxyRequest = Request.Builder()
                    .url(url)
                    .post(RequestBody.create(MediaType.get("application/octet-stream"), ivAndCipherText))
                    .header("X-Sender-Public-Key", Hex.toStringCondensed(keyPair.publicKey))
                    .header("X-Target-Snode-Key", targetHexEncodedPublicKeySet.idKey)
                    .build()
                execute(proxyRequest, getClearnetConnection())
            }.map(LokiAPI.sharedWorkContext) { response ->
                if (response.code() == 404) {
                    // Prune snodes that don't implement the proxying endpoint
                    LokiSwarmAPI.randomSnodePool.remove(proxy)
                }
                var statusCode = response.code()
                var body: String? = response.body()?.string()
                if (response.isSuccessful && body != null) {
                    val cipherText = Base64.decode(body)
                    val decryptedBody = DiffieHellman.decrypt(cipherText, symmetricKey)
                    val bodyAsString = decryptedBody.toString(Charsets.UTF_8)
                    val json = JsonUtil.fromJson(bodyAsString)
                    statusCode = json.get("status").asInt()
                    if (json.hasNonNull("body")) {
                        body = json.get("body").asText()
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
    // endregion
}
