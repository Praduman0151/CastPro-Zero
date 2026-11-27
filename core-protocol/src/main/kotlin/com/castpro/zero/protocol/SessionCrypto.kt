package com.castpro.zero.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class SessionKeys(
    val sessionId: Long,
    val controlKey: ByteArray,
    val videoKey: ByteArray,
    val audioKey: ByteArray
)

/**
 * Forward-secret ephemeral ECDH (P-256) + HMAC-SHA256 PSK key derivation and fast
 * thread-local AEAD sealing (ChaCha20-Poly1305 with AES-256-GCM fallback).
 */
object PacketSeal {
    const val TAG_BYTES = 16
    const val NONCE_BYTES = 12
    const val CONTROL_FLAG_ENCRYPTED = 1

    private val chaChaSupported: Boolean by lazy {
        try {
            Cipher.getInstance("ChaCha20-Poly1305")
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun newCipher(): Cipher =
        if (chaChaSupported) Cipher.getInstance("ChaCha20-Poly1305")
        else Cipher.getInstance("AES/GCM/NoPadding")

    private val threadLocalSealCipher: ThreadLocal<Cipher> = ThreadLocal.withInitial { newCipher() }
    private val threadLocalOpenCipher: ThreadLocal<Cipher> = ThreadLocal.withInitial { newCipher() }

    fun generateEcdhKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    fun ecdhSharedSecret(privateKey: PrivateKey, peerPublicKeyBytes: ByteArray): ByteArray {
        return try {
            val kf = KeyFactory.getInstance("EC")
            val pub = kf.generatePublic(X509EncodedKeySpec(peerPublicKeyBytes))
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(privateKey)
            ka.doPhase(pub, true)
            ka.generateSecret()
        } catch (e: Exception) {
            throw ProtocolException("Invalid peer ECDH key", e)
        }
    }

    fun pairProofHex(
        pairingToken: String,
        sessionId: Long,
        clientNonce: ByteArray,
        serverNonce: ByteArray
    ): String {
        val msg = ByteBuffer.allocate(16 + 8 + clientNonce.size + serverNonce.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put("CPZ0-PAIR-AUTHv2".toByteArray(Charsets.UTF_8))
            .putLong(sessionId)
            .put(clientNonce)
            .put(serverNonce)
            .array()
        val mac = hmacSha256(pairingToken.toByteArray(Charsets.UTF_8), msg)
        return mac.joinToString("") { "%02x".format(it) }
    }

    fun deriveSessionKeys(
        sessionId: Long,
        sharedSecret: ByteArray,
        pairingToken: String,
        clientNonce: ByteArray,
        serverNonce: ByteArray
    ): SessionKeys {
        val salt = MessageDigest.getInstance("SHA-256").run {
            update(clientNonce)
            update(serverNonce)
            update(pairingToken.toByteArray(Charsets.UTF_8))
            digest()
        }
        val prk = hmacSha256(salt, sharedSecret)
        val sidBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(sessionId).array()
        val controlKey = hkdfExpand(prk, "CPZ0-CTL".toByteArray(Charsets.UTF_8) + sidBytes, 32)
        val videoKey = hkdfExpand(prk, "CPZ0-VID".toByteArray(Charsets.UTF_8) + sidBytes, 32)
        val audioKey = hkdfExpand(prk, "CPZ0-AUD".toByteArray(Charsets.UTF_8) + sidBytes, 32)
        return SessionKeys(sessionId, controlKey, videoKey, audioKey)
    }

    fun videoNonce(frameId: Long, fragmentIndex: Int, fragmentCount: Int): ByteArray =
        ByteBuffer.allocate(NONCE_BYTES).order(ByteOrder.BIG_ENDIAN)
            .putInt(0x0100_0000)
            .putInt(frameId.toInt())
            .putShort(fragmentIndex.toShort())
            .putShort(fragmentCount.toShort())
            .array()

    fun audioNonce(sequence: Long): ByteArray =
        ByteBuffer.allocate(NONCE_BYTES).order(ByteOrder.BIG_ENDIAN)
            .putInt(0x0200_0000)
            .putLong(sequence)
            .array()

    fun controlNonce(direction: Int, sequence: Long): ByteArray =
        ByteBuffer.allocate(NONCE_BYTES).order(ByteOrder.BIG_ENDIAN)
            .put(0x03.toByte())
            .put(direction.toByte())
            .putShort(0)
            .putLong(sequence)
            .array()

    fun seal(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray?,
        plaintext: ByteArray,
        length: Int = plaintext.size,
        offset: Int = 0,
        aadOffset: Int = 0,
        aadLength: Int = aad?.size ?: 0
    ): ByteArray {
        require(key.size == 32 && nonce.size == NONCE_BYTES)
        require(offset >= 0 && length >= 0 && offset + length <= plaintext.size)
        val cipher = threadLocalSealCipher.get()
        val spec = if (chaChaSupported) IvParameterSpec(nonce) else GCMParameterSpec(TAG_BYTES * 8, nonce)
        val keyAlgo = if (chaChaSupported) "ChaCha20" else "AES"
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, keyAlgo), spec)
        if (aad != null && aadLength > 0) cipher.updateAAD(aad, aadOffset, aadLength)
        return cipher.doFinal(plaintext, offset, length)
    }

    /** Encrypts directly into `dest[destOffset..]` with zero intermediate array allocations. */
    fun sealInto(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        aadOffset: Int,
        aadLength: Int,
        plaintext: ByteArray,
        offset: Int,
        length: Int,
        dest: ByteArray,
        destOffset: Int
    ): Int {
        require(key.size == 32 && nonce.size == NONCE_BYTES)
        val cipher = threadLocalSealCipher.get()
        val spec = if (chaChaSupported) IvParameterSpec(nonce) else GCMParameterSpec(TAG_BYTES * 8, nonce)
        val keyAlgo = if (chaChaSupported) "ChaCha20" else "AES"
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, keyAlgo), spec)
        if (aadLength > 0) cipher.updateAAD(aad, aadOffset, aadLength)
        return cipher.doFinal(plaintext, offset, length, dest, destOffset)
    }

    fun open(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray?,
        ciphertext: ByteArray,
        length: Int = ciphertext.size,
        offset: Int = 0,
        aadOffset: Int = 0,
        aadLength: Int = aad?.size ?: 0
    ): ByteArray {
        require(key.size == 32 && nonce.size == NONCE_BYTES)
        if (offset < 0 || length < TAG_BYTES || offset + length > ciphertext.size) {
            throw ProtocolException("Packet too small for authentication tag")
        }
        return try {
            var cipher = threadLocalOpenCipher.get()
            val spec = if (chaChaSupported) IvParameterSpec(nonce) else GCMParameterSpec(TAG_BYTES * 8, nonce)
            val keySpec = SecretKeySpec(key, if (chaChaSupported) "ChaCha20" else "AES")
            try {
                cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)
            } catch (e: java.security.InvalidKeyException) {
                // SunJCE ChaCha20Cipher rejects repeating identical (key, nonce) on the same instance even for decrypt.
                if (e.message?.contains("nonce", ignoreCase = true) == true) {
                    cipher = newCipher().also { threadLocalOpenCipher.set(it) }
                    cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)
                } else {
                    throw e
                }
            }
            if (aad != null && aadLength > 0) cipher.updateAAD(aad, aadOffset, aadLength)
            cipher.doFinal(ciphertext, offset, length)
        } catch (e: Exception) {
            throw ProtocolException("Packet authentication failed", e)
        }
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(1.toByte())
        return mac.doFinal().copyOf(length)
    }
}
