package org.whispersystems.signalservice.loki.utilities

import org.whispersystems.libsignal.IdentityKeyPair
import org.whispersystems.libsignal.ecc.ECKeyPair

val IdentityKeyPair.hexEncodedPublicKey: String
    get() = publicKey.serialize().joinToString("") { String.format("%02x", it) }

val IdentityKeyPair.hexEncodedPrivateKey: String
    get() = privateKey.serialize().joinToString("") { String.format("%02x", it) }

val ECKeyPair.hexEncodedPublicKey: String
    get() = publicKey.serialize().joinToString("") { String.format("%02x", it) }

val ECKeyPair.hexEncodedPrivateKey: String
    get() = privateKey.serialize().joinToString("") { String.format("%02x", it) }