package org.whispersystems.libsignal.loki

import org.whispersystems.libsignal.protocol.CiphertextMessage

class LokiFriendRequestMessage(private val paddedMessageBody: ByteArray) : CiphertextMessage {

    override fun serialize(): ByteArray {
        return paddedMessageBody
    }

    override fun getType(): Int {
        return CiphertextMessage.LOKI_FRIEND_REQUEST_TYPE
    }
}
