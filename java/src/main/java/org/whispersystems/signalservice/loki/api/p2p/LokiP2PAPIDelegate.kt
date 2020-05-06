package org.whispersystems.signalservice.loki.api.p2p

interface LokiP2PAPIDelegate {

    fun ping(contactHexEncodedPublicKey: String)
}
