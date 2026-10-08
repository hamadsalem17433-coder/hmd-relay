package com.hmd.messenger.crypto

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

data class IdentityKeys(
    val signPublic: ByteArray,
    val signSecret: ByteArray,
    val dhPublic: ByteArray,
    val dhSecret: ByteArray
)

class IdentityManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("hmd_identity", Context.MODE_PRIVATE)

    @Synchronized
    fun getOrCreate(): IdentityKeys {
        val signPub = prefs.getString("sign_pub", null)
        val signSec = prefs.getString("sign_sec", null)
        val dhPub = prefs.getString("dh_pub", null)
        val dhSec = prefs.getString("dh_sec", null)

        if (signPub != null && signSec != null && dhPub != null && dhSec != null) {
            return IdentityKeys(
                dec(signPub), dec(signSec), dec(dhPub), dec(dhSec)
            )
        }

        val sr = SecureRandom()
        val sPub = ByteArray(32).also { sr.nextBytes(it) }
        val sSec = ByteArray(32).also { sr.nextBytes(it) }
        val dPub = ByteArray(32).also { sr.nextBytes(it) }
        val dSec = ByteArray(32).also { sr.nextBytes(it) }

        prefs.edit()
            .putString("sign_pub", enc(sPub))
            .putString("sign_sec", enc(sSec))
            .putString("dh_pub", enc(dPub))
            .putString("dh_sec", enc(dSec))
            .apply()

        return IdentityKeys(sPub, sSec, dPub, dSec)
    }

    fun sign(msg: ByteArray): ByteArray {
        val keys = getOrCreate()
        // مبسط للتوقيع: HMAC / SHA-256 بالتوازي مع مفتاح التوقيع
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(keys.signSecret, "HmacSHA256"))
        return mac.doFinal(msg)
    }

    private fun enc(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun dec(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
