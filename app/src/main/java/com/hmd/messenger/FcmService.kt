package com.hmd.messenger

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** يستقبل إشعار الإيقاظ (بدون محتوى) ثم يجلب الرسائل المشفرة من السيرفر. */
class FcmService : FirebaseMessagingService() {
    private val app get() = application as App

    override fun onNewToken(token: String) = app.relay.setFcmToken(token)
    override fun onMessageReceived(message: RemoteMessage) = app.relay.wakeForMessages()
}
