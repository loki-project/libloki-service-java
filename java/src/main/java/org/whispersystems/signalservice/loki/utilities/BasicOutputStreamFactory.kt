package org.whispersystems.signalservice.loki.utilities

import org.whispersystems.signalservice.api.crypto.DigestingOutputStream
import org.whispersystems.signalservice.internal.push.http.OutputStreamFactory
import java.io.OutputStream

/**
 * A DigestingOutputStream Factory which copies the input directly to the output without modification.
 *
 * For encrypted attachments, see `AttachmentCipherOutputStreamFactory`.
 * For encrypted profile, see `ProfileCipherOutputStreamFactory`.
 */
class BasicOutputStreamFactory : OutputStreamFactory {
  override fun createFor(outputStream: OutputStream?): DigestingOutputStream {
    return object : DigestingOutputStream(outputStream) { }
  }
}