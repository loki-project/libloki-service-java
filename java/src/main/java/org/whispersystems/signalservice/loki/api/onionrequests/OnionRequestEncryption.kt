package org.whispersystems.signalservice.loki.api.onionrequests

import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.deferred
import org.whispersystems.curve25519.Curve25519
import org.whispersystems.libsignal.util.ByteUtil
import org.whispersystems.libsignal.util.Hex
import org.whispersystems.signalservice.internal.util.Base64
import org.whispersystems.signalservice.internal.util.JsonUtil
import org.whispersystems.signalservice.internal.util.Util
import org.whispersystems.signalservice.loki.api.Snode
import org.whispersystems.signalservice.loki.api.utilities.EncryptionResult
import org.whispersystems.signalservice.loki.api.utilities.EncryptionUtilities
import org.whispersystems.signalservice.loki.utilities.toHexString
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object OnionRequestEncryption {

    /**
     * Encrypts `payload` for `destination` and returns the result. Use this to build the core of an onion request.
     */
    internal fun encryptPayloadForDestination(payload: Map<*, *>, destination: OnionRequestAPI.Destination): Promise<EncryptionResult, Exception> {
        val deferred = deferred<EncryptionResult, Exception>()
        Thread {
            try {
                // Wrapping isn't needed for file server or open group onion requests
                when (destination) {
                    is OnionRequestAPI.Destination.Snode -> {
                        val snodeX25519PublicKey = destination.snode.publicKeySet!!.x25519Key
                        val payloadAsString = JsonUtil.toJson(payload) // Snodes only accept this as a string
                        val wrapper = mapOf( "body" to payloadAsString, "headers" to "" )
                        val plaintext = JsonUtil.toJson(wrapper).toByteArray()
                        val result = EncryptionUtilities.encryptForX25519PublicKey(plaintext, snodeX25519PublicKey)
                        deferred.resolve(result)
                    }
                    is OnionRequestAPI.Destination.Server -> {
                        val plaintext = JsonUtil.toJson(payload).toByteArray()
                        val result = EncryptionUtilities.encryptForX25519PublicKey(plaintext, destination.x25519PublicKey)
                        deferred.resolve(result)
                    }
                }
            } catch (exception: Exception) {
                deferred.reject(exception)
            }
        }.start()
        return deferred.promise
    }

    /**
     * Encrypts the previous encryption result (i.e. that of the hop after this one) for this hop. Use this to build the layers of an onion request.
     */
    internal fun encryptHop(lhs: OnionRequestAPI.Destination, rhs: OnionRequestAPI.Destination, previousEncryptionResult: EncryptionResult): Promise<EncryptionResult, Exception> {
        val deferred = deferred<EncryptionResult, Exception>()
        Thread {
            try {
                val payload: MutableMap<String, Any>
                when (rhs) {
                    is OnionRequestAPI.Destination.Snode -> {
                        payload = mutableMapOf( "destination" to rhs.snode.publicKeySet!!.ed25519Key )
                    }
                    is OnionRequestAPI.Destination.Server -> {
                        payload = mutableMapOf( "host" to rhs.host, "target" to "/loki/v1/lsrpc", "method" to "POST" )
                    }
                }
                payload["ciphertext"] = Base64.encodeBytes(previousEncryptionResult.ciphertext)
                payload["ephemeral_key"] = previousEncryptionResult.ephemeralPublicKey.toHexString()
                val x25519PublicKey: String
                when (lhs) {
                    is OnionRequestAPI.Destination.Snode -> {
                        x25519PublicKey = lhs.snode.publicKeySet!!.x25519Key
                    }
                    is OnionRequestAPI.Destination.Server -> {
                        x25519PublicKey = lhs.x25519PublicKey
                    }
                }
                val plaintext = JsonUtil.toJson(payload).toByteArray()
                val result = EncryptionUtilities.encryptForX25519PublicKey(plaintext, x25519PublicKey)
                deferred.resolve(result)
            } catch (exception: Exception) {
                deferred.reject(exception)
            }
        }.start()
        return deferred.promise
    }
}
