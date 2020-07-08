package org.whispersystems.signalservice.loki.api

import com.fasterxml.jackson.databind.JsonNode
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.functional.bind
import nl.komponents.kovenant.functional.map
import nl.komponents.kovenant.then
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import org.whispersystems.libsignal.logging.Log
import org.whispersystems.libsignal.loki.DiffieHellman
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
import org.whispersystems.signalservice.loki.api.deprecated.LokiHTTPClient
import org.whispersystems.signalservice.loki.api.fileserver.LokiFileServerAPI
import org.whispersystems.signalservice.loki.api.deprecated.LokiFileServerProxy
import org.whispersystems.signalservice.loki.database.LokiAPIDatabaseProtocol
import org.whispersystems.signalservice.loki.utilities.recover
import org.whispersystems.signalservice.loki.utilities.removing05PrefixIfNeeded
import org.whispersystems.signalservice.loki.utilities.retryIfNeeded
import java.util.*

/**
 * Base class that provides utilities for .NET based APIs.
 */
open class LokiDotNetAPI(private val userPublicKey: String, private val userPrivateKey: ByteArray, private val apiDatabase: LokiAPIDatabaseProtocol) {

    internal enum class HTTPVerb { GET, PUT, POST, DELETE, PATCH }

    companion object {
        private val authTokenRequestCache = hashMapOf<String, Promise<String, Exception>>()
    }

    public data class UploadResult(val id: Long, val url: String, val digest: ByteArray?)

    public fun getAuthToken(server: String): Promise<String, Exception> {
        val token = apiDatabase.getAuthToken(server)
        if (token != null) { return Promise.of(token) }
        // Avoid multiple token requests to the server by caching
        var promise = authTokenRequestCache[server]
        if (promise == null) {
            promise = requestNewAuthToken(server).bind { submitAuthToken(it, server) }.then { newToken ->
                apiDatabase.setAuthToken(server, newToken)
                newToken
            }.always {
                authTokenRequestCache.remove(server)
            }
            authTokenRequestCache[server] = promise
        }
        return promise
    }

    private fun requestNewAuthToken(server: String): Promise<String, Exception> {
        Log.d("Loki", "Requesting auth token for server: $server.")
        val parameters: Map<String, Any> = mapOf( "pubKey" to userPublicKey )
        return execute(HTTPVerb.GET, server, "loki/v1/get_challenge", false, parameters).map(SnodeAPI.sharedContext) { response ->
            try {
                val bodyAsString = response.body!!
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
        val parameters = mapOf( "pubKey" to userPublicKey, "token" to token )
        return execute(HTTPVerb.POST, server, "loki/v1/submit_challenge", false, parameters).map { token }
    }

    internal fun execute(verb: HTTPVerb, server: String, endpoint: String, isAuthRequired: Boolean = true, parameters: Map<String, Any> = mapOf()): Promise<LokiHTTPClient.Response, Exception> {
        fun execute(token: String?): Promise<LokiHTTPClient.Response, Exception> {
            val sanitizedEndpoint = endpoint.removePrefix("/")
            var url = "$server/$sanitizedEndpoint"
            if (verb == HTTPVerb.GET || verb == HTTPVerb.DELETE) {
                val queryParameters = parameters.map { "${it.key}=${it.value}" }.joinToString("&")
                if (queryParameters.isNotEmpty()) { url += "?$queryParameters" }
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
            return LokiFileServerProxy(server).execute(request.build()).map { response ->
                if (!response.isSuccess) {
                    if (response.statusCode == 401 || response.statusCode == 403) {
                        apiDatabase.setAuthToken(server, null)
                        throw SnodeAPI.Error.TokenExpired
                    }
                    throw SnodeAPI.Error.HTTPRequestFailed(response.statusCode)
                }
                return@map response
            }
        }
        return if (isAuthRequired) {
            getAuthToken(server).bind { execute(it) }
        } else {
            execute(null)
        }
    }

    internal fun getUserProfiles(hexEncodedPublicKeys: Set<String>, server: String, includeAnnotations: Boolean): Promise<JsonNode, Exception> {
        val parameters = mapOf( "include_user_annotations" to includeAnnotations.toInt(), "ids" to hexEncodedPublicKeys.joinToString { "@$it" } )
        return execute(HTTPVerb.GET, server, "users", parameters = parameters).map { rawResponse ->
            val bodyAsString = rawResponse.body!!
            val body = JsonUtil.fromJson(bodyAsString)
            val data = body.get("data")
            if (data == null) {
                Log.d("Loki", "Couldn't parse user profiles for: $hexEncodedPublicKeys from: $rawResponse.")
                throw SnodeAPI.Error.ParsingFailed
            }
            data
        }
    }

    internal fun setSelfAnnotation(server: String, type: String, newValue: Any?): Promise<LokiHTTPClient.Response, Exception> {
        val annotation = mutableMapOf<String, Any>( "type" to type )
        if (newValue != null) { annotation["value"] = newValue }
        val parameters = mapOf( "annotations" to listOf( annotation ) )
        return execute(HTTPVerb.PATCH, server, "users/me", parameters = parameters)
    }

    @Throws(PushNetworkException::class, NonSuccessfulResponseCodeException::class)
    fun uploadAttachment(server: String, attachment: PushAttachmentData): UploadResult {
        // This function mimics what Signal does in PushServiceSocket
        val contentType = "application/octet-stream"
        val file = DigestingRequestBody(attachment.data, attachment.outputStreamFactory, contentType, attachment.dataSize, attachment.listener)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("type", "network.loki")
            .addFormDataPart("Content-Type", contentType)
            .addFormDataPart("content", UUID.randomUUID().toString(), file)
            .build()
        val request = Request.Builder().url("$server/files").post(body)
        return retryIfNeeded(8) {
            upload(server, request) { jsonAsString ->
                val json = JsonUtil.fromJson(jsonAsString)
                val data = json.get("data")
                if (data == null) {
                    Log.d("Loki", "Couldn't parse attachment from: $jsonAsString.")
                    throw SnodeAPI.Error.ParsingFailed
                }
                val id = data.get("id").asLong()
                val url = data.get("url").asText()
                if (url.isEmpty()) {
                    Log.d("Loki", "Couldn't parse upload from: $jsonAsString.")
                    throw SnodeAPI.Error.ParsingFailed
                }
                UploadResult(id, url, file.transmittedDigest)
            }
        }.get()
    }

    @Throws(PushNetworkException::class, NonSuccessfulResponseCodeException::class)
    fun uploadProfilePicture(server: String, key: ByteArray, profilePicture: StreamDetails, setLastProfilePictureUpload: () -> Unit): UploadResult {
        val profilePictureUploadData = ProfileAvatarData(profilePicture.stream, ProfileCipherOutputStream.getCiphertextLength(profilePicture.length), profilePicture.contentType, ProfileCipherOutputStreamFactory(key))
        val file = DigestingRequestBody(profilePictureUploadData.data, profilePictureUploadData.outputStreamFactory,
            profilePictureUploadData.contentType, profilePictureUploadData.dataLength, null)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("type", "network.loki")
            .addFormDataPart("Content-Type", "application/octet-stream")
            .addFormDataPart("content", UUID.randomUUID().toString(), file)
            .build()
        val request = Request.Builder().url("$server/files").post(body)
        return retryIfNeeded(8) {
            upload(server, request) { jsonAsString ->
                val json = JsonUtil.fromJson(jsonAsString)
                val data = json.get("data")
                if (data == null) {
                    Log.d("Loki", "Couldn't parse profile picture from: $jsonAsString.")
                    throw SnodeAPI.Error.ParsingFailed
                }
                val id = data.get("id").asLong()
                val url = data.get("url").asText()
                if (url.isEmpty()) {
                    Log.d("Loki", "Couldn't parse profile picture from: $jsonAsString.")
                    throw SnodeAPI.Error.ParsingFailed
                }
                setLastProfilePictureUpload()
                UploadResult(id, url, file.transmittedDigest)
            }
        }.get()
    }

    @Throws(PushNetworkException::class, NonSuccessfulResponseCodeException::class)
    private fun upload(server: String, request: Request.Builder, parse: (String) -> UploadResult): Promise<UploadResult, Exception> {
        val promise: Promise<LokiHTTPClient.Response, Exception>
        if (server == LokiFileServerAPI.shared.server) {
            request.addHeader("Authorization", "Bearer loki")
            // Uploads to the Loki File Server shouldn't include any personally identifiable information, so use a dummy auth token
            promise = LokiFileServerProxy(server, true).execute(request.build())
        } else {
            promise = getAuthToken(server).bind { token ->
                request.addHeader("Authorization", "Bearer $token")
                LokiFileServerProxy(server, true).execute(request.build())
            }
        }
        return promise.map { response ->
            if (!response.isSuccess) {
                if (response.statusCode == 401) {
                    apiDatabase.setAuthToken(server, null)
                    throw SnodeAPI.Error.TokenExpired
                }
                throw SnodeAPI.Error.HTTPRequestFailed(response.statusCode)
            }
            val jsonAsString = response.body ?: throw SnodeAPI.Error.ParsingFailed
            parse(jsonAsString)
        }.recover { exception ->
            val nestedException = exception.cause ?: exception
            if (nestedException is SnodeAPI.Error.HTTPRequestFailed) {
                throw NonSuccessfulResponseCodeException("Request returned with status code ${nestedException.code}.")
            }
            throw PushNetworkException(exception)
        }
    }
}

private fun Boolean.toInt(): Int { return if (this) 1 else 0 }
