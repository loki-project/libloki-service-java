package org.whispersystems.signalservice.loki.utilities

import okhttp3.Request
import okio.Buffer
import java.io.IOException
import java.util.*

internal fun Request.getCanonicalHeaders(): Map<String, Any> {
    val result = mutableMapOf<String, Any>()
    val contentType = body()?.contentType()
    if (contentType != null) {
        result["content-type"] = contentType.toString()
    }
    val headers = headers()
    for (name in headers.names()) {
        val value = headers.get(name)
        if (value != null) {
            if (value.toLowerCase(Locale.US) == "true" || value.toLowerCase(Locale.US) == "false") {
                result[name] = value.toBoolean()
            } else if (value.toIntOrNull() != null) {
                result[name] = value.toInt()
            } else {
                result[name] = value
            }
        }
    }
    return result
}

internal fun Request.getBody(): ByteArray? {
    try {
        val copy = newBuilder().build()
        val buffer = Buffer()
        val body = copy.body() ?: return null
        body.writeTo(buffer)
        return buffer.readByteArray()
    } catch (e: IOException) {
        return null
    }
}

internal fun Request.getBodyAsString(): String? {
    val body = getBody()
    val charset = body()?.contentType()?.charset() ?: Charsets.UTF_8
    return body?.toString(charset)
}
