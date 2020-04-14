package org.whispersystems.signalservice.loki.utilities

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

object FileUtilities {

  fun downloadFile(destination: File, url: String, maxSize: Int, listener: SignalServiceAttachment.ProgressListener?) {
    val outputStream = FileOutputStream(destination) // Throws
    downloadFile(outputStream, url, maxSize, listener)
  }

  fun downloadFile(outputStream: OutputStream, url: String, maxSize: Int, listener: SignalServiceAttachment.ProgressListener?) {
    // We need to throw a PushNetworkException or NonSuccessfulResponseCodeException
    // because the underlying Signal logic requires these to work correctly
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
          throw PushNetworkException("Missing response body.")
        }
        if (body.contentLength() > maxSize) {
          Log.d("Loki", "Attachment size limit exceeded.")
          throw PushNetworkException("Max response size exceeded.")
        }
        val input = body.byteStream()
        val buffer = ByteArray(32768)
        var count = 0
        var bytes = input.read(buffer)
        while (bytes >= 0) {
          outputStream.write(buffer, 0, bytes)
          count += bytes
          if (count > maxSize) {
            Log.d("Loki", "Attachment size limit exceeded.")
            throw PushNetworkException("Max response size exceeded.")
          }
          listener?.onAttachmentProgress(body.contentLength(), count.toLong())
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
