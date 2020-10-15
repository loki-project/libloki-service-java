package org.whispersystems.signalservice.loki.utilities

import okhttp3.HttpUrl
import okhttp3.Request
import org.whispersystems.libsignal.logging.Log
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment
import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException
import org.whispersystems.signalservice.loki.api.fileserver.FileServerAPI
import org.whispersystems.signalservice.loki.api.onionrequests.OnionRequestAPI
import java.io.*

object DownloadUtilities {

    /**
     * Blocks the calling thread.
     */
    fun downloadFile(destination: File, url: String, maxSize: Int, listener: SignalServiceAttachment.ProgressListener?) {
        val outputStream = FileOutputStream(destination) // Throws
        var remainingAttempts = 4
        var exception: Exception? = null
        while (remainingAttempts > 0) {
            remainingAttempts -= 1
            try {
                downloadFile(outputStream, url, maxSize, listener)
                exception = null
                break
            } catch (e: Exception) {
                exception = e
            }
        }
        if (exception != null) { throw exception }
    }

    /**
     * Blocks the calling thread.
     */
    fun downloadFile(outputStream: OutputStream, url: String, maxSize: Int, listener: SignalServiceAttachment.ProgressListener?) {
        // We need to throw a PushNetworkException or NonSuccessfulResponseCodeException
        // because the underlying Signal logic requires these to work correctly
        var host = "https://" + HttpUrl.get(url).host()
        val sanitizedURL: String
        if (host.contains(FileServerAPI.fileStorageBucketURL)) {
            sanitizedURL = url.replace(FileServerAPI.fileStorageBucketURL, FileServerAPI.shared.server + "/loki/v1")
            host = FileServerAPI.shared.server
        } else {
            sanitizedURL = url.replace(host, "$host/loki/v1")
        }
        val request = Request.Builder().url(sanitizedURL).get()
        try {
            val serverPublicKey = if (host.contains(FileServerAPI.shared.server)) FileServerAPI.fileServerPublicKey
                else FileServerAPI.shared.getPublicKeyForOpenGroupServer(host).get()
            val json = OnionRequestAPI.sendOnionRequest(request.build(), host, serverPublicKey, false).get()
            val data = json["data"] as? ArrayList<Int>
            if (data == null) {
                Log.d("Loki", "Couldn't parse attachment from: $json.")
                throw PushNetworkException("Missing response body.")
            }
            val body = data.map { it.toByte() }.toByteArray()
            if (body.size > maxSize) {
                Log.d("Loki", "Attachment size limit exceeded.")
                throw PushNetworkException("Max response size exceeded.")
            }
            val input = body.inputStream()
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
                listener?.onAttachmentProgress(body.size.toLong(), count.toLong())
                bytes = input.read(buffer)
            }
        } catch (e: Exception) {
            Log.d("Loki", "Couldn't download attachment due to error: $e.")
            throw if (e is NonSuccessfulResponseCodeException) e else PushNetworkException(e)
        }
    }
}
