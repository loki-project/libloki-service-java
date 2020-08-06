package org.whispersystems.signalservice.loki.protocol.closedgroups

import org.whispersystems.signalservice.loki.utilities.toHexString

public class ClosedGroupSenderKey(public val chainKey: ByteArray, public val keyIndex: Int, public val publicKey: ByteArray) {

    override fun equals(other: Any?): Boolean {
        return if (other is ClosedGroupSenderKey) {
            chainKey.contentEquals(other.chainKey) && keyIndex == other.keyIndex && publicKey.contentEquals(other.publicKey)
        } else {
            false
        }
    }

    override fun hashCode(): Int {
        return chainKey.hashCode() xor keyIndex.hashCode() xor publicKey.hashCode()
    }

    override fun toString(): String {
        return "[ chainKey : ${chainKey.toHexString()}, keyIndex : $keyIndex, messageKeys : ${publicKey.toHexString()} ]"
    }
}
