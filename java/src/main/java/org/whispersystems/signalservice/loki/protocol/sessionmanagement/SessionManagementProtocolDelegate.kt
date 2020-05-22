package org.whispersystems.signalservice.loki.protocol.sessionmanagement

interface SessionManagementProtocolDelegate {

    fun sendSessionRequest(publicKey: String)
}
