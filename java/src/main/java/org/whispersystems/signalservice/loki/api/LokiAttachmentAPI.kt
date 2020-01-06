package org.whispersystems.signalservice.loki.api

import okhttp3.OkHttpClient
import okhttp3.Request
import org.whispersystems.libsignal.logging.Log
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment
import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

object LokiAttachmentAPI {

  fun getAttachment(destination: File, url: String, maxByteCount: Int, listener: SignalServiceAttachment.ProgressListener?) {
    // Throws IOExceptions
    val outputStream = FileOutputStream(destination)
    getAttachment(outputStream, url, maxByteCount, listener)
  }

  fun getAttachment(outputStream: OutputStream, url: String, maxByteCount: Int, listener: SignalServiceAttachment.ProgressListener?) {
    // We need to throw PushNetworkException or NonSuccessfulResponseCodeException
    // as the underlying signal logic requires these to work correctly
    val connection = OkHttpClient()
        .newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    val request = Request.Builder().url(url).get()
    try {
      val response = connection.newCall(request.build()).execute()
      if (response.isSuccessful) {
        val body = response.body()
        if (body == null) {
          Log.d("Loki", "Couldn't parse attachment.")
          throw PushNetworkException("No response body!")
        }
        if (body.contentLength() > maxByteCount) {
          Log.d("Loki", "Attachment size limit exceeded.")
          throw PushNetworkException("Response exceeded max size!")
        }
        val input = body.byteStream()
        val buffer = ByteArray(32768)
        var bytesCopied = 0
        var bytes = input.read(buffer)
        while (bytes >= 0) {
          outputStream.write(buffer, 0, bytes)
          bytesCopied += bytes
          if (bytesCopied > maxByteCount) {
            Log.d("Loki", "Attachment size limit exceeded.")
            throw PushNetworkException("Response exceeded max size!")
          }
          listener?.onAttachmentProgress(body.contentLength(), bytesCopied.toLong())
          bytes = input.read(buffer)
        }
      } else {
        Log.d("Loki", "Couldn't parse attachment due to error: ${response.code()}.")
        throw NonSuccessfulResponseCodeException("Response: $response")
      }
    } catch (e: IOException) {
      Log.d("Loki", "Couldn't parse attachment.")
      throw if (e is NonSuccessfulResponseCodeException) e else PushNetworkException(e)
    }
  }
}