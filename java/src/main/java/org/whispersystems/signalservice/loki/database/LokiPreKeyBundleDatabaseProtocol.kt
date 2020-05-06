package org.whispersystems.signalservice.loki.database

import org.whispersystems.libsignal.state.PreKeyBundle

interface LokiPreKeyBundleDatabaseProtocol {

    fun getPreKeyBundle(hexEncodedPublicKey: String): PreKeyBundle?
    fun removePreKeyBundle(hexEncodedPublicKey: String)
}
