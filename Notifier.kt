package com.hmd.messenger.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hmd.messenger.MainActivity

class Notifier(private val ctx: Context) {
    init {
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CH, "الرسائل", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    fun show(text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        
        val intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val open = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // على شاشة القفل لا يظهر النص الأصلي حمايةً للخصوصية
        val publicVersion = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("HMD Messenger")
            .setContentText("رسالة جديدة")
            .build()

        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("HMD Messenger")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(ctx).notify(System.currentTimeMillis().toInt(), n)
    }

    private companion object { const val CH = "messages" }
}
