package org.whispersystems.signalservice.loki.api

import com.fasterxml.jackson.databind.JsonNode
import nl.komponents.kovenant.Kovenant
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.deferred
import nl.komponents.kovenant.functional.bind
import nl.komponents.kovenant.functional.map
import nl.komponents.kovenant.then
import okhttp3.*
import org.whispersystems.libsignal.logging.Log
import org.whispersystems.signalservice.api.crypto.ProfileCipherOutputStream
import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException
import org.whispersystems.signalservice.api.util.StreamDetails
import org.whispersystems.signalservice.internal.push.ProfileAvatarData
import org.whispersystems.signalservice.internal.push.PushAttachmentData
import org.whispersystems.signalservice.internal.push.http.DigestingRequestBody
import org.whispersystems.signalservice.internal.push.http.ProfileCipherOutputStreamFactory
import org.whispersystems.signalservice.internal.util.Base64
import org.whispersystems.signalservice.internal.util.Hex
import org.whispersystems.signalservice.internal.util.JsonUtil
import org.whispersystems.signalservice.internal.util.concurrent.SettableFuture
import org.whispersystems.signalservice.loki.crypto.DiffieHellman
import org.whispersystems.signalservice.loki.utilities.BasicOutputStreamFactory
import org.whispersystems.signalservice.loki.utilities.createContext
import org.whispersystems.signalservice.loki.utilities.removing05PrefixIfNeeded
import java.io.ByteArrayInputStream
import java.io.IOException
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

    // Context for network requests
    val networkContext = Kovenant.createContext("network", 8)
    val workContext = Kovenant.createContext("work", 8)

    public sealed class Error(val description: String) : Exception() {
        object Generic : Error("An error occurred.")
        object ParsingFailed : Error("Failed to parse object from JSON.")
    }

    public data class UploadResult(val id: Long, val url: String, val digest: ByteArray?)

    public fun getAuthToken(server: String): Promise<String, Exception> {
        val token = apiDatabase.getAuthToken(server)
        if (token != null) { return Promise.of(token) }
        // Avoid multiple token requests to the server by caching
        var promise = authRequestCache[server]
        if (promise == null) {
            promise = requestNewAuthToken(server).bind(workContext) { submitAuthToken(it, server) }.then(workContext) { newToken ->
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
        return execute(HTTPVerb.GET, server, "loki/v1/get_challenge", false, parameters).map(workContext) { response ->
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
        return execute(HTTPVerb.POST, server, "loki/v1/submit_challenge", false, parameters).map(workContext) { token }
    }

    internal fun execute(verb: HTTPVerb, server: String, endpoint: String, isAuthRequired: Boolean = true, parameters: Map<String, Any> = mapOf()): Promise<Response, Exception> {
        val deferred = deferred<Response, Exception>(networkContext)
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
        return execute(HTTPVerb.GET, server, "users", false, parameters).map(workContext) { rawResponse ->
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

    @Throws(PushNetworkException::class, NonSuccessfulResponseCodeException::class)
    fun uploadAttachment(server: String, attachment: PushAttachmentData): UploadResult {
        // This function mimicks what Signal does in PushServiceSocket
        val contentType = "application/octet-stream"
        val file = DigestingRequestBody(attachment.data, attachment.outputStreamFactory, contentType, attachment.dataSize, attachment.listener)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("type", "network.loki")
            .addFormDataPart("Content-Type", contentType)
            .addFormDataPart("content", UUID.randomUUID().toString(), file) // avatar
            .build()
        val request = Request.Builder().url("$server/files").post(body)
        return upload(server, request) { jsonAsString ->
            val json = JsonUtil.fromJson(jsonAsString)
            val data = json.get("data")
            if (data == null) {
                Log.d("Loki", "Couldn't parse attachment from: $jsonAsString.")
                throw LokiAPI.Error.ParsingFailed
            }
            val id = data.get("id").asLong()
            val url = data.get("url").asText()
            if (url.isEmpty()) {
                Log.d("Loki", "Couldn't parse upload from: $jsonAsString.")
                throw LokiAPI.Error.ParsingFailed
            }
            UploadResult(id, url, file.transmittedDigest)
        }
    }

    @Throws(PushNetworkException::class, NonSuccessfulResponseCodeException::class)
    fun uploadProfilePicture(server: String, key: ByteArray, avatar: StreamDetails): UploadResult {
        val avatarData = ProfileAvatarData(avatar.stream, ProfileCipherOutputStream.getCiphertextLength(avatar.length), avatar.contentType, ProfileCipherOutputStreamFactory(key))
        return uploadProfilePicture(server, avatarData)
    }

    @Throws(PushNetworkException::class, NonSuccessfulResponseCodeException::class)
    fun uploadPublicProfilePicture(server: String, data: ByteArray): UploadResult {
        val avatarData = ProfileAvatarData(ByteArrayInputStream(data), data.size.toLong(), "image/jpg", BasicOutputStreamFactory())
        return uploadProfilePicture(server, avatarData)
    }

    @Throws(PushNetworkException::class, NonSuccessfulResponseCodeException::class)
    private fun uploadProfilePicture(server: String, avatar: ProfileAvatarData): UploadResult {
        val file = DigestingRequestBody(avatar.data, avatar.outputStreamFactory, avatar.contentType, avatar.dataLength, null)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("type", "network.loki")
            .addFormDataPart("Content-Type", "application/octet-stream")
            .addFormDataPart("avatar", UUID.randomUUID().toString(), file)
            .build()
        val request = Request.Builder().url("$server/users/me/avatar").post(body)
        return upload(server, request) { jsonAsString ->
            val json = JsonUtil.fromJson(jsonAsString)
            val data = json.get("data")
            if (data == null || !data.hasNonNull("avatar_image")) {
                Log.d("Loki", "Couldn't parse profile picture from: $jsonAsString.")
                throw LokiAPI.Error.ParsingFailed
            }
            val id = data.get("id").asLong()
            val url = data.get("avatar_image").get("url").asText("")
            if (url.isEmpty()) {
                Log.d("Loki", "Couldn't parse profile picture from: $jsonAsString.")
                throw LokiAPI.Error.ParsingFailed
            }
            UploadResult(id, url, file.transmittedDigest)
        }
    }

    @Throws(PushNetworkException::class, NonSuccessfulResponseCodeException::class)
    private fun upload(server: String, request: Request.Builder, process: (String) -> UploadResult): UploadResult {
        val future = SettableFuture<UploadResult>()
        getAuthToken(server).then(networkContext) { token ->
            request.addHeader("Authorization", "Bearer $token")
            connection.newCall(request.build()).enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    when (response.code()) {
                        in 200..299 -> {
                            try {
                                val jsonAsString = response.body()!!.string()
                                val result = process(jsonAsString)
                                future.set(result)
                            } catch (e: Exception) {
                                future.setException(e)
                            }
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
