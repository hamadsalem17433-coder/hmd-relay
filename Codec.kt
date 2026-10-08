package com.hmd.messenger.net

import android.util.Base64

object Codec {
    fun enc(b: ByteArray): String {
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    fun dec(s: String): ByteArray {
        return Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
