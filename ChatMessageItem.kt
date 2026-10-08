package com.hmd.messenger.ui

import com.hmd.messenger.data.MessageEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMessageItem(
    val entity: MessageEntity
) {
    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("h:mm a", Locale("ar"))
            return sdf.format(Date(entity.ts))
        }

    val statusIcon: String
        get() = if (entity.status == 1) "✓✓" else "✓"

    val formattedDuration: String
        get() {
            val min = entity.durationSec / 60
            val sec = entity.durationSec % 60
            return String.format(Locale.getDefault(), "%d:%02d", min, sec)
        }
}
