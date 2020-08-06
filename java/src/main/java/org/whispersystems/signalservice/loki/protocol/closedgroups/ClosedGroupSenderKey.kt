package org.whispersystems.signalservice.loki.protocol.closedgroups

public class ClosedGroupSenderKey(public val chainKey: String, public val keyIndex: Int, public val publicKey: String) {

    override fun equals(other: Any?): Boolean {
        return if (other is ClosedGroupSenderKey) {
            chainKey == other.chainKey && keyIndex == other.keyIndex && publicKey == other.publicKey
        } else {
            false
        }
    }

    override fun hashCode(): Int {
        return chainKey.hashCode() xor keyIndex.hashCode() xor publicKey.hashCode()
    }

    override fun toString(): String {
        return "[ chainKey : $chainKey, keyIndex : $keyIndex, messageKeys : $publicKey ]"
    }
}
