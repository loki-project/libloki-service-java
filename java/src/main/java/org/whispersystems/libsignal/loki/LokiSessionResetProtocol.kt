package org.whispersystems.libsignal.loki

import org.whispersystems.libsignal.protocol.PreKeySignalMessage

interface LokiSessionResetProtocol {

    fun getSessionResetStatus(hexEncodedPublicKey: String): LokiSessionResetStatus
    fun setSessionResetStatus(hexEncodedPublicKey: String, sessionResetStatus: LokiSessionResetStatus)
    fun validatePreKeySignalMessage(sender: String, message: PreKeySignalMessage)
    fun onNewSessionAdopted(hexEncodedPublicKey: String, oldSessionResetStatus: LokiSessionResetStatus)
}
