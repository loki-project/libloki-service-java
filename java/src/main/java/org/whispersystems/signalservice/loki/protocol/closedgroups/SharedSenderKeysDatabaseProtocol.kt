package org.whispersystems.signalservice.loki.protocol.closedgroups

interface SharedSenderKeysDatabaseProtocol {

    // region Ratchets & Sender Keys
    fun getClosedGroupRatchet(groupPublicKey: String, senderPublicKey: String): ClosedGroupRatchet?
    fun setClosedGroupRatchet(groupPublicKey: String, senderPublicKey: String, ratchet: ClosedGroupRatchet)
    fun removeAllClosedGroupRatchets(groupPublicKey: String)
    fun getAllClosedGroupSenderKeys(groupPublicKey: String): Set<ClosedGroupSenderKey>
    // endregion

    // region Private & Public Keys
    fun getClosedGroupPrivateKey(groupPublicKey: String): String?
    fun setClosedGroupPrivateKey(groupPublicKey: String, groupPrivateKey: String)
    fun removeClosedGroupPrivateKey(groupPublicKey: String)
    fun getAllClosedGroupPublicKeys(): Set<String>
    // endregion

    // region Convenience
    fun isSSKBasedClosedGroup(groupPublicKey: String): Boolean
    // endregion
}
