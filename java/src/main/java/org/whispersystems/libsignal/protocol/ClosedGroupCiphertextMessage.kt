package org.whispersystems.libsignal.protocol

import com.google.protobuf.ByteString
import org.whispersystems.libsignal.logging.Log

class ClosedGroupCiphertextMessage(val ivAndCiphertext: ByteArray, val senderPublicKey: ByteArray, val keyIndex: Int) : CiphertextMessage {
    private val serialized: ByteArray

    companion object {

        fun from(serialized: ByteArray): ClosedGroupCiphertextMessage? {
            try {
                val proto = SignalProtos.ClosedGroupCiphertextMessage.parseFrom(serialized)
                return ClosedGroupCiphertextMessage(proto.ciphertext.toByteArray(), proto.senderPublicKey.toByteArray(), proto.keyIndex)
            } catch (exception: Exception) {
                Log.d("Loki", "Couldn't parse proto due to error: $exception.")
                return null
            }
        }
    }

    init {
        val builder = SignalProtos.ClosedGroupCiphertextMessage.newBuilder()
        builder.ciphertext = ByteString.copyFrom(ivAndCiphertext)
        builder.senderPublicKey = ByteString.copyFrom(senderPublicKey)
        builder.keyIndex = keyIndex
        serialized = builder.build().toByteArray()
    }

    override fun getType(): Int {
        return CiphertextMessage.CLOSED_GROUP_CIPHERTEXT
    }

    override fun serialize(): ByteArray {
        return serialized
    }
}
