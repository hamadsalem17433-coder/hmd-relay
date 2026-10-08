package com.hmd.messenger

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.hmd.messenger.media.AudioPlayer
import com.hmd.messenger.media.AudioRecorder
import com.hmd.messenger.ui.ChatAdapter
import com.hmd.messenger.ui.ChatMessageItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class MainActivity : Activity() {

    private val app get() = application as App
    private lateinit var recorder: AudioRecorder
    private lateinit var player: AudioPlayer
    private lateinit var adapter: ChatAdapter
    private lateinit var listView: ListView
    private lateinit var statusTv: TextView
    private var isRecording = false
    private val uiScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate()

        recorder = AudioRecorder(this)
        player = AudioPlayer(this)
        adapter = ChatAdapter(this, player)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212")) // Dark background
        }

        // ---------- 1. الشريط العلوي (Top Action Bar) ----------
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val appTitleTv = TextView(this).apply {
            text = "HMD Messenger"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        titleContainer.addView(appTitleTv)

        statusTv = TextView(this).apply {
            text = "🔴 غير متصل"
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 12f
        }
        titleContainer.addView(statusTv)
        topBar.addView(titleContainer)

        val audioCallBtn = Button(this).apply {
            text = "📞"
            textSize = 16f
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                if (checkPermissions()) {
                    val intent = Intent(this@MainActivity, CallActivity::class.java).apply {
                        putExtra("is_incoming", false)
                        putExtra("is_video", false)
                    }
                    startActivity(intent)
                } else requestPermissions()
            }
        }
        topBar.addView(audioCallBtn)

        val videoCallBtn = Button(this).apply {
            text = "📹"
            textSize = 16f
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                if (checkPermissions()) {
                    val intent = Intent(this@MainActivity, CallActivity::class.java).apply {
                        putExtra("is_incoming", false)
                        putExtra("is_video", true)
                    }
                    startActivity(intent)
                } else requestPermissions()
            }
        }
        topBar.addView(videoCallBtn)

        rootLayout.addView(topBar)

        // ---------- 2. قائمة المحادثة (Message List) ----------
        listView = ListView(this).apply {
            divider = null
            dividerHeight = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            adapter = this@MainActivity.adapter
        }
        rootLayout.addView(listView)

        // ---------- 3. شريط الإدخال السفلي (Bottom Input Bar) ----------
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            gravity = Gravity.CENTER_VERTICAL
        }

        val inputBg = GradientDrawable().apply {
            cornerRadius = 48f
            setColor(Color.parseColor("#2C2C2C"))
        }

        val inputEt = EditText(this).apply {
            hint = "اكتب رسالة مشفرة..."
            setHintTextColor(Color.parseColor("#9E9E9E"))
            setTextColor(Color.WHITE)
            background = inputBg
            setPadding(32, 20, 32, 20)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bottomBar.addView(inputEt)

        val sendBtn = Button(this).apply {
            text = "إرسال ➔"
            setTextColor(Color.parseColor("#80D8FF"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                val txt = inputEt.text.toString().trim()
                if (txt.isNotEmpty()) {
                    app.relay.send(txt)
                    inputEt.setText("")
                    refreshMessages()
                }
            }
        }
        bottomBar.addView(sendBtn)

        val voiceBtn = Button(this).apply {
            text = "🎤"
            textSize = 18f
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (checkPermissions()) {
                            if (recorder.start()) {
                                isRecording = true
                                text = "🔴"
                                Toast.makeText(this@MainActivity, "جارٍ تسجيل الملاحظة الصوتية...", Toast.LENGTH_SHORT).show()
                            }
                        } else requestPermissions()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isRecording) {
                            isRecording = false
                            text = "🎤"
                            val result = recorder.stop()
                            if (result != null && result.bytes.isNotEmpty()) {
                                app.relay.sendVoice(result.bytes, result.durationSec)
                                refreshMessages()
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        bottomBar.addView(voiceBtn)

        rootLayout.addView(bottomBar)

        setContentView(rootLayout)

        app.relay.start()

        app.relay.state.onEach { stateStr ->
            statusTv.text = when (stateStr) {
                "متصل" -> "🟢 متصل"
                "جارٍ الاتصال…" -> "🟡 جارٍ الاتصال..."
                else -> "🔴 غير متصل"
            }
        }.launchIn(uiScope)

        app.relay.onCallSignal = { type, data ->
            if (type == "call_offer") {
                runOnUiThread {
                    val intent = Intent(this, CallActivity::class.java).apply {
                        putExtra("is_incoming", true)
                        putExtra("signal_data", data.toString())
                    }
                    startActivity(intent)
                }
            }
        }

        refreshMessages()
    }

    private fun refreshMessages() {
        runOnUiThread {
            val entities = app.db.dao().allMessages()
            val chatItems = entities.map { ChatMessageItem(it) }
            adapter.setItems(chatItems)
            listView.setSelection(adapter.count - 1)
        }
    }

    private fun checkPermissions(): Boolean {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val cam = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        return mic && cam
    }

    private fun requestPermissions() {
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA), 101)
    }

    override fun onResume() {
        super.onResume()
        app.relay.foreground = true
        refreshMessages()
    }

    override fun onPause() {
        super.onPause()
        app.relay.foreground = false
        recorder.cleanup()
        player.stop()
    }
}
