package org.whispersystems.signalservice.loki.api.http

import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.whispersystems.libsignal.logging.Log
import org.whispersystems.signalservice.internal.util.JsonUtil

object HTTP {
    private val connection = OkHttpClient()

    const val timeout: Long = 20

    class HTTPRequestFailedException(val statusCode: Int, val json: Map<*, *>?)
        : kotlin.Exception("HTTP request failed with status code $statusCode.")

    enum class Verb(val rawValue: String) {
        GET("GET"), PUT("PUT"), POST("POST"), DELETE("DELETE")
    }

    /**
     * Sync. Don't call from the main thread.
     */
    fun execute(verb: Verb, url: String, parameters: Map<String, Any>? = null): Map<*, *> {
        val request = Request.Builder().url(url)
        when (verb) {
            Verb.GET -> request.get()
            Verb.PUT, Verb.POST -> {
                if (parameters == null) { throw Exception("Invalid JSON.") }
                val contentType = MediaType.get("application/json; charset=utf-8")
                val body = RequestBody.create(contentType, JsonUtil.toJson(parameters))
                if (verb == Verb.PUT) request.put(body) else request.post(body)
            }
            Verb.DELETE -> request.delete()
        }
        try {
            val response = connection.newCall(request.build()).execute()
            when (val statusCode = response.code()) {
                200 -> {
                    val bodyAsString = response.body()?.string() ?: throw Exception("An error occurred.")
                    try {
                        return JsonUtil.fromJson(bodyAsString, Map::class.java)
                    } catch (exception: Exception) {
                        Log.d("Loki", "Couldn't parse JSON returned by ${verb.rawValue} request to $url.")
                        throw Exception("Invalid JSON.")
                    }
                }
                else -> {
                    Log.d("Loki", "${verb.rawValue} request to $url failed with status code: $statusCode.")
                    throw HTTPRequestFailedException(statusCode, null)
                }
            }
        } catch (exception: Exception) {
            Log.d("Loki", "${verb.rawValue} request to $url failed due to error: ${exception.localizedMessage}.")
            // Override the actual error so that we can correctly catch failed requests in sendOnionRequest(invoking:on:with:)
            throw HTTPRequestFailedException(0, null)
        }
    }
}
