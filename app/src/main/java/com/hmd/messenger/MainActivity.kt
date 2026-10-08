package com.hmd.messenger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hmd.messenger.media.AudioPlayer
import com.hmd.messenger.media.AudioRecorder
import com.hmd.messenger.ui.ChatAdapter
import com.hmd.messenger.ui.ChatMessageItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class MainActivity : AppCompatActivity() {

    private val app get() = application as App
    private lateinit var recorder: AudioRecorder
    private lateinit var player: AudioPlayer
    private lateinit var adapter: ChatAdapter
    private lateinit var listView: ListView
    private lateinit var statusTv: TextView
    private var isRecording = false
    private val uiScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        recorder = AudioRecorder(this)
        player = AudioPlayer(this)
        adapter = ChatAdapter(this, player)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0E14")) // Sleek deep slate
        }

        // ---------- 1. الشريط العلوي (Top Action Bar) ----------
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 20, 24, 20)
            setBackgroundColor(Color.parseColor("#151921"))
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
            setTypeface(null, Typeface.BOLD)
        }
        titleContainer.addView(appTitleTv)

        statusTv = TextView(this).apply {
            text = "🔴 غير متصل"
            setTextColor(Color.parseColor("#8696A0"))
            textSize = 12f
        }
        titleContainer.addView(statusTv)
        topBar.addView(titleContainer)

        val btnCircleBg = {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1F2634"))
            }
        }

        val btnPillBg = {
            GradientDrawable().apply {
                cornerRadius = 32f
                setColor(Color.parseColor("#1F2634"))
            }
        }

        val audioCallBtn = Button(this).apply {
            text = "📞"
            textSize = 15f
            background = btnCircleBg()
            layoutParams = LinearLayout.LayoutParams(80, 80).apply {
                rightMargin = 12
            }
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
            textSize = 15f
            background = btnCircleBg()
            layoutParams = LinearLayout.LayoutParams(80, 80).apply {
                rightMargin = 12
            }
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

        val qrBtn = Button(this).apply {
            text = "📱 QR"
            textSize = 12f
            setTextColor(Color.parseColor("#53BDEB"))
            background = btnPillBg()
            setPadding(16, 0, 16, 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 80)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, QrActivity::class.java))
            }
        }
        topBar.addView(qrBtn)

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
            setBackgroundColor(Color.parseColor("#151921"))
            gravity = Gravity.CENTER_VERTICAL
        }

        val inputBg = GradientDrawable().apply {
            cornerRadius = 48f
            setColor(Color.parseColor("#1F2633"))
        }

        val inputEt = EditText(this).apply {
            hint = "اكتب رسالة مشفرة..."
            setHintTextColor(Color.parseColor("#8A99AD"))
            setTextColor(Color.WHITE)
            background = inputBg
            setPadding(32, 20, 32, 20)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = 12
            }
        }
        bottomBar.addView(inputEt)

        val sendBtnBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#00A884")) // Emerald Accent
        }

        val sendBtn = Button(this).apply {
            text = "➔"
            textSize = 18f
            setTextColor(Color.WHITE)
            background = sendBtnBg
            layoutParams = LinearLayout.LayoutParams(88, 88).apply {
                rightMargin = 12
            }
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

        val voiceBtnBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#1F2633"))
        }

        val voiceBtn = Button(this).apply {
            text = "🎤"
            textSize = 16f
            background = voiceBtnBg
            layoutParams = LinearLayout.LayoutParams(88, 88)
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (checkPermissions()) {
                            if (recorder.start()) {
                                isRecording = true
                                text = "🔴"
                                (background as? GradientDrawable)?.setColor(Color.parseColor("#DC3545"))
                                Toast.makeText(this@MainActivity, "جارٍ تسجيل الملاحظة الصوتية...", Toast.LENGTH_SHORT).show()
                            }
                        } else requestPermissions()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isRecording) {
                            isRecording = false
                            text = "🎤"
                            (background as? GradientDrawable)?.setColor(Color.parseColor("#1F2633"))
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
            if (adapter.count > 0) {
                listView.setSelection(adapter.count - 1)
            }
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
        if (::recorder.isInitialized) recorder.cleanup()
        if (::player.isInitialized) player.stop()
    }
}
