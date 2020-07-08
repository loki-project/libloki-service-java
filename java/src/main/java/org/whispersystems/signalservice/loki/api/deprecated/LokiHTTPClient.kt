package org.whispersystems.signalservice.loki.api.deprecated

import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.deferred
import nl.komponents.kovenant.functional.map
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

internal open class LokiHTTPClient(private val timeout: Long) {

    internal data class Response(val isSuccess: Boolean, val statusCode: Int, val body: String?)

    companion object {
        internal val okHTTPCache = hashMapOf<Long, OkHttpClient>()
    }

    internal fun getClearnetConnection(): OkHttpClient {
        var connection = okHTTPCache[timeout]
        if (connection == null) {
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authorizationType: String?) { }
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authorizationType: String?) { }
                override fun getAcceptedIssuers(): Array<X509Certificate> {
                    return arrayOf()
                }
            }
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, arrayOf( trustManager ), SecureRandom())
            connection = OkHttpClient().newBuilder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(timeout, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .writeTimeout(timeout, TimeUnit.SECONDS)
                .build()
            okHTTPCache[timeout] = connection
        }
        return connection!!
    }

    internal fun execute(request: Request, client: OkHttpClient): Promise<okhttp3.Response, Exception> {
        val deferred = deferred<okhttp3.Response, Exception>()
        Thread {
            try {
                val response = client.newCall(request).execute()
                deferred.resolve(response)
            } catch (e: Exception) {
                deferred.reject(e)
            }
        }.start()
        return deferred.promise
    }

    internal open fun execute(request: Request): Promise<Response, Exception> {
        val connection = getClearnetConnection()
        return execute(request, connection).map {
            Response(it.isSuccessful, it.code(), it.body()?.string())
        }
    }

    internal open fun getBody(request: Request): ByteArray? {
        try {
            val copy = request.newBuilder().build()
            val buffer = Buffer()
            val body = copy.body() ?: return null
            body.writeTo(buffer)
            return buffer.readByteArray()
        } catch (e: IOException) {
            return null
        }
    }

    internal open fun getBodyAsString(request: Request): String? {
        val body = getBody(request)
        val charset = request.body()?.contentType()?.charset() ?: Charsets.UTF_8
        return body?.toString(charset)
    }

    internal open fun getCanonicalHeaders(request: Request): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        val contentType = request.body()?.contentType()
        if (contentType != null) {
            map["content-type"] = contentType.toString()
        }
        val headers = request.headers()
        for (name in headers.names()) {
            val value = headers.get(name)
            if (value != null) {
                if (value.toLowerCase(Locale.getDefault()) == "true" || value.toLowerCase(Locale.getDefault()) == "false") {
                    map[name] = value.toBoolean()
                } else if (value.toIntOrNull() != null) {
                    map[name] = value.toInt()
                } else {
                    map[name] = value
                }
            }
        }
        return map
    }
}

internal fun Int.isSuccessfulHTTPStatusCode(): Boolean {
    return this in 200..299
}
