package com.hmd.messenger.call

import android.content.Context
import com.hmd.messenger.net.RelayClient
import org.json.JSONObject

enum class CallType {
    AUDIO, VIDEO
}

enum class CallState {
    IDLE, OUTGOING_CALLING, INCOMING_CALLING, CONNECTED, ENDED
}

interface CallListener {
    fun onCallStateChanged(state: CallState)
    fun onSignalReceived(type: String, data: JSONObject)
}

class CallManager(
    private val context: Context,
    private val relay: RelayClient
) {
    var currentState: CallState = CallState.IDLE
        private set

    var callType: CallType = CallType.AUDIO
        private set

    private var listener: CallListener? = null

    fun setListener(l: CallListener?) {
        this.listener = l
    }

    fun startCall(type: CallType) {
        if (currentState != CallState.IDLE) return
        callType = type
        currentState = CallState.OUTGOING_CALLING
        listener?.onCallStateChanged(currentState)

        val offerData = JSONObject().apply {
            put("call_type", type.name)
            put("sdp", "v=0\r\no=- 12345 2 IN IP4 127.0.0.1\r\ns=HMD_Call\r\nt=0 0\r\na=sendrecv")
        }
        relay.sendCallSignal("call_offer", offerData)
    }

    fun answerCall() {
        if (currentState != CallState.INCOMING_CALLING) return
        currentState = CallState.CONNECTED
        listener?.onCallStateChanged(currentState)

        val answerData = JSONObject().apply {
            put("sdp", "v=0\r\no=- 54321 2 IN IP4 127.0.0.1\r\ns=HMD_Call\r\nt=0 0\r\na=sendrecv")
        }
        relay.sendCallSignal("call_answer", answerData)
    }

    fun endCall() {
        if (currentState == CallState.IDLE) return
        currentState = CallState.ENDED
        listener?.onCallStateChanged(currentState)

        val endData = JSONObject().apply {
            put("reason", "user_hangup")
        }
        relay.sendCallSignal("call_end", endData)
        currentState = CallState.IDLE
    }

    fun handleIncomingSignal(type: String, data: JSONObject) {
        when (type) {
            "call_offer" -> {
                if (currentState == CallState.IDLE) {
                    callType = if (data.optString("call_type") == "VIDEO") CallType.VIDEO else CallType.AUDIO
                    currentState = CallState.INCOMING_CALLING
                    listener?.onCallStateChanged(currentState)
                    listener?.onSignalReceived(type, data)
                }
            }
            "call_answer" -> {
                if (currentState == CallState.OUTGOING_CALLING) {
                    currentState = CallState.CONNECTED
                    listener?.onCallStateChanged(currentState)
                    listener?.onSignalReceived(type, data)
                }
            }
            "call_ice" -> {
                listener?.onSignalReceived(type, data)
            }
            "call_end" -> {
                currentState = CallState.ENDED
                listener?.onCallStateChanged(currentState)
                currentState = CallState.IDLE
            }
        }
    }
}
