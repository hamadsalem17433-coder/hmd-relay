package com.hmd.messenger.crypto

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

object MessageCrypto {

    fun deriveKey(identity: IdentityManager, peerDhPub: ByteArray): ByteArray {
        val myKeys = identity.getOrCreate()
        val md = MessageDigest.getInstance("SHA-256")
        md.update(myKeys.dhSecret)
        md.update(peerDhPub)
        return md.digest()
    }

    fun encrypt(key: ByteArray, id: String, body: ByteArray): ByteArray {
        val iv = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).copyOf(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        return cipher.doFinal(body)
    }

    fun decrypt(key: ByteArray, id: String, payload: ByteArray): ByteArray {
        val iv = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).copyOf(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        return cipher.doFinal(payload)
    }
}
