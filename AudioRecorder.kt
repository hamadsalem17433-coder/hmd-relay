package com.hmd.messenger.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class AudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var tempFile: File? = null
    private var startTimeMs: Long = 0

    fun start(): Boolean {
        return try {
            val file = File(context.cacheDir, "temp_voice_${System.currentTimeMillis()}.aac")
            tempFile = file

            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = rec
            startTimeMs = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            cleanup()
            false
        }
    }

    fun stop(): RecordResult? {
        val rec = recorder ?: return null
        val file = tempFile ?: return null
        return try {
            rec.stop()
            rec.release()
            recorder = null

            val durationSec = ((System.currentTimeMillis() - startTimeMs) / 1000).toInt().coerceAtLeast(1)
            val bytes = file.readBytes()
            file.delete()
            RecordResult(bytes, durationSec)
        } catch (e: Exception) {
            e.printStackTrace()
            cleanup()
            null
        }
    }

    fun cleanup() {
        try {
            recorder?.release()
        } catch (e: Exception) { }
        recorder = null
        tempFile?.delete()
        tempFile = null
    }

    data class RecordResult(val bytes: ByteArray, val durationSec: Int)
}
