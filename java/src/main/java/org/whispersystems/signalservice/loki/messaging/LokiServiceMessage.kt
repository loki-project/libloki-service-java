package org.whispersystems.signalservice.loki.messaging

import org.whispersystems.libsignal.IdentityKey
import org.whispersystems.libsignal.ecc.Curve
import org.whispersystems.libsignal.state.PreKeyBundle

data class LokiServiceMessage(val preKeyBundleMessage: LokiServicePreKeyBundleMessage?, val addressMessage: LokiServiceAddressMessage?) {
    fun isValid(): Boolean {
        return preKeyBundleMessage != null || addressMessage != null
    }
}

data class LokiServicePreKeyBundleMessage(
    val identityKey: ByteArray,
    val deviceID: Int,
    val preKeyID: Int,
    val signedKeyID: Int,
    val preKey: ByteArray,
    val signedKey: ByteArray,
    val signature: ByteArray
) {

    constructor(preKeyBundle: PreKeyBundle) : this(preKeyBundle.identityKey.serialize(), preKeyBundle.deviceId, preKeyBundle.preKeyId,
        preKeyBundle.signedPreKeyId, preKeyBundle.preKey.serialize(), preKeyBundle.signedPreKey.serialize(), preKeyBundle.signedPreKeySignature)

    fun getPreKeyBundle(registrationID: Int): PreKeyBundle {
        return PreKeyBundle(registrationID, deviceID, preKeyID, Curve.decodePoint(preKey, 0), signedKeyID, Curve.decodePoint(signedKey, 0), signature, IdentityKey(identityKey, 0))
    }
}

data class LokiServiceAddressMessage(val p2pAddress: String?, val p2pPort: Int?) {
    val isReachable get() = p2pAddress != null && p2pPort != null
}