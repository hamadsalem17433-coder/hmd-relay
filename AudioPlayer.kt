package com.hmd.messenger.media

import android.content.Context
import android.media.MediaPlayer
import java.io.File

class AudioPlayer(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null

    fun play(bytes: ByteArray, onComplete: () -> Unit = {}) {
        stop()
        try {
            val tempFile = File(context.cacheDir, "play_voice_${System.currentTimeMillis()}.aac")
            tempFile.writeBytes(bytes)

            val mp = MediaPlayer()
            mp.setDataSource(tempFile.absolutePath)
            mp.prepare()
            mp.setOnCompletionListener {
                tempFile.delete()
                onComplete()
            }
            mp.start()
            mediaPlayer = mp
        } catch (e: Exception) {
            e.printStackTrace()
            onComplete()
        }
    }

    fun stop() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) { }
        mediaPlayer = null
    }
}
