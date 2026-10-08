package com.hmd.messenger

import android.app.Application
import com.google.firebase.messaging.FirebaseMessaging
import com.hmd.messenger.crypto.IdentityManager
import com.hmd.messenger.data.AppDb
import com.hmd.messenger.net.RelayClient
import com.hmd.messenger.ui.Notifier

class App : Application() {
    val identity by lazy { IdentityManager(this) }
    val db by lazy { AppDb.get(this) }
    val relay by lazy { RelayClient(identity, db) }

    override fun onCreate() {
        super.onCreate()
        val notifier = Notifier(this)
        relay.onIncoming = { notifier.show(it) }
        FirebaseMessaging.getInstance().token.addOnSuccessListener { relay.setFcmToken(it) }
    }
}
