package com.hmd.messenger

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.hmd.messenger.call.CallListener
import com.hmd.messenger.call.CallManager
import com.hmd.messenger.call.CallState
import com.hmd.messenger.call.CallType
import org.json.JSONObject

class CallActivity : Activity(), CallListener {

    private val app get() = application as App
    private lateinit var callManager: CallManager

    private lateinit var statusTv: TextView
    private lateinit var callerNameTv: TextView
    private lateinit var answerBtn: Button
    private lateinit var endBtn: Button
    private lateinit var muteBtn: Button
    private var isMuted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        callManager = CallManager(this, app.relay)
        callManager.setListener(this)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0E14")) // Sleek deep slate
            gravity = Gravity.CENTER
            setPadding(48, 64, 48, 64)
        }

        // ---------- أيقونة / صورة المتصل الدائرية ----------
        val avatarBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#1F2633"))
        }

        val avatarTv = TextView(this).apply {
            text = "👤"
            textSize = 56f
            gravity = Gravity.CENTER
            background = avatarBg
            layoutParams = LinearLayout.LayoutParams(240, 240).apply {
                bottomMargin = 32
            }
        }
        rootLayout.addView(avatarTv)

        callerNameTv = TextView(this).apply {
            text = "HMD Peer"
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        rootLayout.addView(callerNameTv)

        statusTv = TextView(this).apply {
            text = "جارٍ بدء الاتصال..."
            setTextColor(Color.parseColor("#8696A0"))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 80)
        }
        rootLayout.addView(statusTv)

        // ---------- أزرار الإجراءات العصرية (Action Buttons) ----------
        val buttonsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val btnCircleGreen = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#00A884"))
        }

        val btnCircleRed = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#DC3545"))
        }

        val btnCircleGray = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#1F2633"))
        }

        answerBtn = Button(this).apply {
            text = "📞"
            textSize = 22f
            background = btnCircleGreen
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(136, 136).apply {
                rightMargin = 32
            }
            setOnClickListener {
                callManager.answerCall()
            }
        }
        buttonsLayout.addView(answerBtn)

        muteBtn = Button(this).apply {
            text = "🎙️"
            textSize = 22f
            background = btnCircleGray
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(136, 136).apply {
                rightMargin = 32
            }
            setOnClickListener {
                isMuted = !isMuted
                text = if (isMuted) "🔇" else "🎙️"
                Toast.makeText(this@CallActivity, if (isMuted) "تم كتم الصوت" else "تم تفعيل الصوت", Toast.LENGTH_SHORT).show()
            }
        }
        buttonsLayout.addView(muteBtn)

        endBtn = Button(this).apply {
            text = "❌"
            textSize = 22f
            background = btnCircleRed
            layoutParams = LinearLayout.LayoutParams(136, 136)
            setOnClickListener {
                callManager.endCall()
                finish()
            }
        }
        buttonsLayout.addView(endBtn)

        rootLayout.addView(buttonsLayout)
        setContentView(rootLayout)

        val isIncoming = intent.getBooleanExtra("is_incoming", false)
        val isVideo = intent.getBooleanExtra("is_video", false)

        if (isIncoming) {
            val signalStr = intent.getStringExtra("signal_data")
            val signalData = if (signalStr != null) JSONObject(signalStr) else JSONObject()
            callManager.handleIncomingSignal("call_offer", signalData)
        } else {
            val callType = if (isVideo) CallType.VIDEO else CallType.AUDIO
            callManager.startCall(callType)
        }

        app.relay.onCallSignal = { type, data ->
            runOnUiThread {
                callManager.handleIncomingSignal(type, data)
            }
        }
    }

    override fun onCallStateChanged(state: CallState) {
        runOnUiThread {
            when (state) {
                CallState.OUTGOING_CALLING -> {
                    statusTv.text = "جارٍ الاتصال (مكالمة ${callTypeStr()})..."
                    answerBtn.visibility = View.GONE
                    muteBtn.visibility = View.VISIBLE
                }
                CallState.INCOMING_CALLING -> {
                    statusTv.text = "مكالمة ${callTypeStr()} واردة..."
                    answerBtn.visibility = View.VISIBLE
                    muteBtn.visibility = View.GONE
                }
                CallState.CONNECTED -> {
                    statusTv.text = "متصل الآن (مكالمة ${callTypeStr()}) 🟢"
                    answerBtn.visibility = View.GONE
                    muteBtn.visibility = View.VISIBLE
                }
                CallState.ENDED, CallState.IDLE -> {
                    statusTv.text = "انتهت المكالمة"
                    finish()
                }
            }
        }
    }

    override fun onSignalReceived(type: String, data: JSONObject) { }

    private fun callTypeStr() = if (callManager.callType == CallType.VIDEO) "مرئية 📹" else "صوتية 📞"

    override fun onDestroy() {
        super.onDestroy()
        app.relay.onCallSignal = null
    }
}
