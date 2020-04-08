package org.whispersystems.signalservice.loki.api.onionrequests

import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.deferred
import org.whispersystems.curve25519.Curve25519
import org.whispersystems.libsignal.util.ByteUtil
import org.whispersystems.libsignal.util.Hex
import org.whispersystems.signalservice.internal.util.Base64
import org.whispersystems.signalservice.internal.util.JsonUtil
import org.whispersystems.signalservice.internal.util.Util
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object OnionRequestEncryption {
    internal val gcmTagSize = 128
    internal val ivSize = 12

    internal data class EncryptionResult(
        internal val ciphertext: ByteArray,
        internal val symmetricKey: ByteArray,
        internal val ephemeralPublicKey: ByteArray
    )

    /**
     * Sync. Don't call from the main thread.
     */
    private fun encryptUsingAESGCM(plaintext: ByteArray, symmetricKey: ByteArray): ByteArray {
        val iv = Util.getSecretBytes(ivSize)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(symmetricKey, "AES"), GCMParameterSpec(gcmTagSize, iv))
        return ByteUtil.combine(iv, cipher.doFinal(plaintext))
    }

    /**
     * Sync. Don't call from the main thread.
     */
    private fun encryptForSnode(plaintext: ByteArray, snode: Snode): EncryptionResult {
        val hexEncodedSnodeX25519PublicKey = snode.publicKeySet!!.x25519Key
        val snodeX25519PublicKey = Hex.fromStringCondensed(hexEncodedSnodeX25519PublicKey)
        val ephemeralKeyPair = Curve25519.getInstance(Curve25519.BEST).generateKeyPair()
        val ephemeralSharedSecret = Curve25519.getInstance(Curve25519.BEST).calculateAgreement(snodeX25519PublicKey, ephemeralKeyPair.privateKey)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("LOKI".toByteArray(), "HmacSHA256"))
        val symmetricKey = mac.doFinal(ephemeralSharedSecret)
        val ciphertext = encryptUsingAESGCM(plaintext, symmetricKey)
        return EncryptionResult(ciphertext, symmetricKey, ephemeralKeyPair.publicKey)
    }

    /**
     * Encrypts `payload` for `snode` and returns the result. Use this to build the core of an onion request.
     */
    internal fun encryptPayloadForTargetSnode(payload: Map<*, *>, snode: Snode): Promise<EncryptionResult, Exception> {
        val deferred = deferred<EncryptionResult, Exception>()
        Thread {
            try {
                val payloadAsString = JsonUtil.toJson(payload) // Snodes only accept this as a string
                val wrapper = mapOf( "body" to payloadAsString, "headers" to "" )
                val plaintext = JsonUtil.toJson(wrapper).toByteArray()
                val result = encryptForSnode(plaintext, snode)
                deferred.resolve(result)
            } catch (exception: Exception) {
                deferred.reject(exception)
            }
        }.start()
        return deferred.promise
    }

    /**
     * Encrypts the previous encryption result (i.e. that of the hop after this one) for this hop. Use this to build the layers of an onion request.
     */
    internal fun encryptHop(lhs: Snode, rhs: Snode, previousEncryptionResult: EncryptionResult): Promise<EncryptionResult, Exception> {
        val deferred = deferred<EncryptionResult, Exception>()
        Thread {
            try {
                val payload = mapOf(
                    "ciphertext" to Base64.encodeBytes(previousEncryptionResult.ciphertext),
                    "ephemeral_key" to Hex.toStringCondensed(previousEncryptionResult.ephemeralPublicKey),
                    "destination" to rhs.publicKeySet!!.ed25519Key
                )
                val plaintext = JsonUtil.toJson(payload).toByteArray()
                val result = encryptForSnode(plaintext, lhs)
                deferred.resolve(result)
            } catch (exception: Exception) {
                deferred.reject(exception)
            }
        }.start()
        return deferred.promise
    }
}
