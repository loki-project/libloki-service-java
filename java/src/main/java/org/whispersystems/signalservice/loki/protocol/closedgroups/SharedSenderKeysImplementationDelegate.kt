package org.whispersystems.signalservice.loki.protocol.closedgroups

interface SharedSenderKeysImplementationDelegate {

    fun requestSenderKey(groupPublicKey: String, senderPublicKey: String)
}
