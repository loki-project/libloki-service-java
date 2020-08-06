package org.whispersystems.signalservice.loki.protocol.closedgroups

interface SharedSenderKeysDatabaseProtocol {

    fun getClosedGroupRatchet(groupPublicKey: String, senderPublicKey: String): ClosedGroupRatchet?
    fun setClosedGroupRatchet(groupPublicKey: String, senderPublicKey: String, ratchet: ClosedGroupRatchet)
    fun getClosedGroupPublicKeys(): Set<String>
    fun getClosedGroupPrivateKey(groupPublicKey: String): String?
}
