package org.whispersystems.signalservice.loki.api

import com.fasterxml.jackson.databind.JsonNode
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.deferred
import nl.komponents.kovenant.functional.bind
import nl.komponents.kovenant.functional.map
import nl.komponents.kovenant.then
import okhttp3.*
import org.whispersystems.libsignal.logging.Log
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment
import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException
import org.whispersystems.signalservice.internal.push.PushAttachmentData
import org.whispersystems.signalservice.internal.push.http.DigestingRequestBody
import org.whispersystems.signalservice.internal.push.http.OutputStreamFactory
import org.whispersystems.signalservice.internal.util.Base64
import org.whispersystems.signalservice.internal.util.Hex
import org.whispersystems.signalservice.internal.util.JsonUtil
import org.whispersystems.signalservice.internal.util.concurrent.SettableFuture
import org.whispersystems.signalservice.loki.crypto.DiffieHellman
import org.whispersystems.signalservice.loki.utilities.removing05PrefixIfNeeded
import java.io.IOException
import java.io.InputStream
import java.util.*

/**
 * Abstract base class that provides utilities for .NET based APIs.
 */
open class LokiDotNetAPI(private val userHexEncodedPublicKey: String, private val userPrivateKey: ByteArray, private val apiDatabase: LokiAPIDatabaseProtocol) {

    internal enum class HTTPVerb { GET, PUT, POST, DELETE, PATCH }

    companion object {
        private val authRequestCache = hashMapOf<String, Promise<String, Exception>>()
        private var connection = OkHttpClient()

        @JvmStatic
        public fun setCache(cache: Cache) {
            connection = OkHttpClient.Builder().cache(cache).build()
        }
    }

    public sealed class Error(val description: String) : Exception() {
        object Generic : Error("An error occurred.")
        object ParsingFailed : Error("Failed to parse object from JSON.")
    }

    public fun getAuthToken(server: String): Promise<String, Exception> {
        val token = apiDatabase.getAuthToken(server)
        if (token != null) { return Promise.of(token) }
        // Avoid multiple token requests to the server by caching
        var promise = authRequestCache[server]
        if (promise == null) {
            promise = requestNewAuthToken(server).bind { submitAuthToken(it, server) }.then { newToken ->
                apiDatabase.setAuthToken(server, newToken)
                newToken
            }.always {
                authRequestCache.remove(server)
            }
            authRequestCache[server] = promise
        }
        return promise
    }

    private fun requestNewAuthToken(server: String): Promise<String, Exception> {
        Log.d("Loki", "Requesting auth token for server: $server.")
        val parameters: Map<String, Any> = mapOf( "pubKey" to userHexEncodedPublicKey )
        return execute(HTTPVerb.GET, server, "loki/v1/get_challenge", false, parameters).map { response ->
            try {
                val bodyAsString = response.body()!!.string()
                @Suppress("NAME_SHADOWING") val body = JsonUtil.fromJson(bodyAsString, Map::class.java)
                val base64EncodedChallenge = body["cipherText64"] as String
                val challenge = Base64.decode(base64EncodedChallenge)
                val base64EncodedServerPublicKey = body["serverPubKey64"] as String
                var serverPublicKey = Base64.decode(base64EncodedServerPublicKey)
                // Discard the "05" prefix if needed
                if (serverPublicKey.count() == 33) {
                    val hexEncodedServerPublicKey = Hex.toStringCondensed(serverPublicKey)
                    serverPublicKey = Hex.fromStringCondensed(hexEncodedServerPublicKey.removing05PrefixIfNeeded())
                }
                // The challenge is prefixed by the 16 bit IV
                val tokenAsData = DiffieHellman.decrypt(challenge, serverPublicKey, userPrivateKey)
                val token = tokenAsData.toString(Charsets.UTF_8)
                token
            } catch (exception: Exception) {
                Log.d("Loki", "Couldn't parse auth token for server: $server.")
                throw exception
            }
        }
    }

    private fun submitAuthToken(token: String, server: String): Promise<String, Exception> {
        Log.d("Loki", "Submitting auth token for server: $server.")
        val parameters = mapOf( "pubKey" to userHexEncodedPublicKey, "token" to token )
        return execute(HTTPVerb.POST, server, "loki/v1/submit_challenge", false, parameters).map { token }
    }

    internal fun execute(verb: HTTPVerb, server: String, endpoint: String, isAuthRequired: Boolean = true, parameters: Map<String, Any> = mapOf()): Promise<Response, Exception> {
        val deferred = deferred<Response, Exception>()
        val sanitizedEndpoint = endpoint.removePrefix("/")
        fun execute(token: String?) {
            var url = "$server/$sanitizedEndpoint"
            if (verb == HTTPVerb.GET || verb == HTTPVerb.DELETE) {
                val queryParameters = parameters.map { "${it.key}=${it.value}" }.joinToString("&")
                if (queryParameters.isNotEmpty()) {
                    url += "?$queryParameters"
                }
            }
            var request = Request.Builder().url(url)
            if (isAuthRequired) {
                if (token == null) { throw IllegalStateException() }
                request = request.header("Authorization", "Bearer $token")
            }
            when (verb) {
                HTTPVerb.GET -> request = request.get()
                HTTPVerb.DELETE -> request = request.delete()
                else -> {
                    val parametersAsJSON = JsonUtil.toJson(parameters)
                    val body = RequestBody.create(MediaType.get("application/json"), parametersAsJSON)
                    when (verb) {
                        HTTPVerb.PUT -> request = request.put(body)
                        HTTPVerb.POST -> request = request.post(body)
                        HTTPVerb.PATCH -> request = request.patch(body)
                        else -> throw IllegalStateException()
                    }
                }
            }
            Thread {
                connection.newCall(request.build()).enqueue(object : Callback {

                    override fun onResponse(call: Call, response: Response) {
                        when (response.code()) {
                            in 200..299 -> deferred.resolve(response)
                            401 -> {
                                apiDatabase.setAuthToken(server, null)
                                deferred.reject(LokiAPI.Error.TokenExpired)
                            }
                            else -> deferred.reject(LokiAPI.Error.HTTPRequestFailed(response.code()))
                        }
                    }

                    override fun onFailure(call: Call, exception: IOException) {
                        Log.d("Loki", "Couldn't reach server: $server.")
                        deferred.reject(exception)
                    }
                })
            }.start()
        }
        if (isAuthRequired) {
            getAuthToken(server).success { execute(it) }.fail { deferred.reject(it) }
        } else {
            execute(null)
        }
        return deferred.promise
    }

    internal fun getUserProfiles(hexEncodedPublicKeys: Set<String>, server: String, includeAnnotations: Boolean): Promise<JsonNode, Exception> {
        val parameters = mapOf( "include_user_annotations" to includeAnnotations.toInt(), "ids" to hexEncodedPublicKeys.joinToString { "@$it" } )
        return execute(HTTPVerb.GET, server, "users", false, parameters).map { rawResponse ->
            val bodyAsString = rawResponse.body()!!.string()
            val body = JsonUtil.fromJson(bodyAsString)
            val data = body.get("data")
            if (data == null) {
                Log.d("Loki", "Couldn't parse user profiles for: $hexEncodedPublicKeys from: $rawResponse.")
                throw Error.ParsingFailed
            }
            data
        }
    }

    internal fun setSelfAnnotation(server: String, type: String, newValue: Any?): Promise<Response, Exception> {
        val annotation = mutableMapOf<String, Any>( "type" to type )
        if (newValue != null) { annotation["value"] = newValue }
        val parameters = mapOf( "annotations" to listOf( annotation ) )
        return execute(HTTPVerb.PATCH, server, "users/me", parameters = parameters)
    }

    fun uploadAttachment(server: String, attachment: PushAttachmentData): Triple<Long, String, ByteArray> {
        return upload(server, attachment.data, "application/octet-stream", attachment.dataSize, attachment.outputStreamFactory, attachment.listener)
    }

    fun upload(server: String, data: InputStream, contentType: String, length: Long, outputStreamFactory: OutputStreamFactory, progressListener: SignalServiceAttachment.ProgressListener?): Triple<Long, String, ByteArray> {
        // This function mimicks what Signal does in PushServiceSocket
        val future = SettableFuture<Triple<Long, String, ByteArray>>()
        getAuthToken(server).then { token ->
            val file = DigestingRequestBody(data, outputStreamFactory, contentType, length, progressListener)
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("type", "network.loki")
                .addFormDataPart("Content-Type", contentType)
                .addFormDataPart("content", UUID.randomUUID().toString(), file)
                .build()
            val request = Request.Builder().url("$server/files").post(body)
            request.addHeader("Authorization", "Bearer $token")
            connection.newCall(request.build()).enqueue(object : Callback {

                override fun onResponse(call: Call, response: Response) {
                    when (response.code()) {
                        in 200..299 -> {
                            val bodyAsString = response.body()!!.string()
                            val body = JsonUtil.fromJson(bodyAsString)
                            val data = body.get("data")
                            if (data == null) {
                                Log.d("Loki", "Couldn't parse attachment from: $response.")
                                future.setException(LokiAPI.Error.ParsingFailed)
                            }
                            val id = data.get("id").asLong()
                            val url = data.get("url").asText()
                            if (url.isEmpty()) {
                                Log.d("Loki", "Couldn't parse attachment from: $response.")
                                future.setException(LokiAPI.Error.ParsingFailed)
                            }
                            future.set(Triple(id, url, file.transmittedDigest))
                        }
                        401 -> {
                            apiDatabase.setAuthToken(server, null)
                            future.setException(LokiAPI.Error.TokenExpired)
                        }
                        else -> future.setException(LokiAPI.Error.HTTPRequestFailed(response.code()))
                    }
                }

                override fun onFailure(call: Call, exception: IOException) {
                    Log.d("Loki", "Couldn't reach server: $server.")
                    future.setException(exception)
                }
            })
        }
        try {
            return future.get()
        } catch (exception: Exception) {
            val nestedException = exception.cause ?: exception
            if (nestedException is LokiAPI.Error.HTTPRequestFailed) {
                throw NonSuccessfulResponseCodeException("Request returned with ${nestedException.code}.")
            }
            throw PushNetworkException(exception)
        }
    }

    // upload profile photo
    // refer to the upload function above
    fun uploadProfilePhoto(server: String, profilePhoto: ByteArray): Triple<Long, String, ByteArray> {
        val future = SettableFuture<Triple<Long, String, ByteArray>>()
        val contentType = "application/binary"
        getAuthToken(server).then { token ->
            // val file = DigestingRequestBody(data, outputStreamFactory, contentType, length, null)
            val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("type", "network.loki")
                    .addFormDataPart("Content-Type", contentType)
                    .addFormDataPart("content", UUID.randomUUID().toString(), RequestBody.create(MediaType.parse("image/*"), profilePhoto))
                    .build()
            val request = Request.Builder().url("$server/files").post(body)
            request.addHeader("Authorization", "Bearer $token")
            val connection = OkHttpClient()
            connection.newCall(request.build()).enqueue(object : Callback {

                override fun onResponse(call: Call, response: Response) {
                    when (response.code()) {
                        in 200..299 -> {
                            val bodyAsString = response.body()!!.string()
                            val body = JsonUtil.fromJson(bodyAsString)
                            val data = body.get("data")
                            if (data == null) {
                                Log.d("Loki", "Couldn't parse profile photo from: $response.")
                                future.setException(LokiAPI.Error.ParsingFailed)
                            }
                            val id = data.get("id").asLong()
                            val url = data.get("url").asText()
                            if (url.isEmpty()) {
                                Log.d("Loki", "Couldn't parse profile photo from: $response.")
                                future.setException(LokiAPI.Error.ParsingFailed)
                            }
                            future.set(Triple(id, url, profilePhoto))
                            //future.set(Triple(id, url, file.transmittedDigest))
                        }
                        401 -> {
                            apiDatabase.setAuthToken(server, null)
                            future.setException(LokiAPI.Error.TokenExpired)
                        }
                        else -> future.setException(LokiAPI.Error.HTTPRequestFailed(response.code()))
                    }
                }

                override fun onFailure(call: Call, exception: IOException) {
                    Log.d("Loki", "Couldn't reach server: $server.")
                    future.setException(exception)
                }
            })
        }
        try {
            return future.get()
        } catch (exception: Exception) {
            val nestedException = exception.cause ?: exception
            if (nestedException is LokiAPI.Error.HTTPRequestFailed) {
                throw NonSuccessfulResponseCodeException("Request returned with ${nestedException.code}.")
            }
            throw PushNetworkException(exception)
        }
    }
}

private fun Boolean.toInt(): Int { return if (this) 1 else 0 }
