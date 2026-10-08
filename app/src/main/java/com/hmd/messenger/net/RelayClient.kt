package com.hmd.messenger.net

import com.hmd.messenger.crypto.IdentityManager
import com.hmd.messenger.crypto.MessageCrypto
import com.hmd.messenger.data.AppDb
import com.hmd.messenger.data.MessageEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * اتصال WebSocket بالسيرفر: مصادقة بالتحدي، إرسال/استلام مشفر، إعادة اتصال تلقائية.
 * الإرسال يمرّ دائمًا عبر قاعدة البيانات (outbox): الرسالة تُحفظ أولًا ثم تُرسل عند توفر الاتصال.
 */
class RelayClient(private val identity: IdentityManager, private val db: AppDb) {

    val state = MutableStateFlow("غير متصل")

    /** تضبطه الواجهة: true عندما يكون التطبيق ظاهرًا. */
    @Volatile var foreground = false
    /** يُستدعى عند وصول رسالة جديدة (لعرض إشعار عندما يكون التطبيق بالخلفية). */
    @Volatile var onIncoming: ((String) -> Unit)? = null
    /** يُستدعى عند وصول إشارة مكالمة جديدة (Signaling). */
    @Volatile var onCallSignal: ((type: String, data: JSONObject) -> Unit)? = null
    @Volatile private var fcmToken: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var ready = false
    @Volatile private var gen = 0
    @Volatile private var key: ByteArray? = null
    private var attempt = 0

    @Volatile private var wakeJob: Job? = null

    fun start() {
        if (running) return
        running = true
        scope.launch { connect() }
    }

    fun stop() {
        running = false; gen++; ready = false
        wakeJob?.cancel(); wakeJob = null
        ws?.close(1000, null); ws = null
        state.value = "غير متصل"
    }

    /** رمز الإشعارات: يُرسل للسيرفر عند جاهزية الاتصال. */
    fun setFcmToken(t: String) {
        fcmToken = t
        if (ready) ws?.send(JSONObject().put("type", "fcm").put("token", t).toString())
    }

    /** عند وصول إشعار إيقاظ والتطبيق بالخلفية: اتصل لفترة قصيرة لجلب الرسائل. */
    fun wakeForMessages() {
        if (foreground) return
        start()
        wakeJob?.cancel()
        wakeJob = scope.launch {
            delay(20_000)
            if (!foreground) stop()
        }
    }

    /** يحفظ الرسالة النصية محليًا ثم يرسلها إن كان الاتصال جاهزًا. */
    fun send(text: String) {
        scope.launch {
            val m = MessageEntity(UUID.randomUUID().toString(), text, true, System.currentTimeMillis(), 0, isVoice = false)
            db.dao().insert(m)
            if (ready) flush()
        }
    }

    /** يحفظ المقطع الصوتي المشفر محليًا ثم يرسله إن كان الاتصال جاهزًا. */
    fun sendVoice(audioBytes: ByteArray, durationSec: Int) {
        scope.launch {
            val encodedAudio = Codec.enc(audioBytes)
            val m = MessageEntity(
                UUID.randomUUID().toString(),
                encodedAudio,
                true,
                System.currentTimeMillis(),
                0,
                isVoice = true,
                durationSec = durationSec
            )
            db.dao().insert(m)
            if (ready) flush()
        }
    }

    /** يرسل إشارات بروتوكول المكالمات الصوتية والمرئية المشفرة عبر الاتصال الحي. */
    fun sendCallSignal(signalType: String, data: JSONObject) {
        scope.launch {
            val body = JSONObject().apply {
                put("sig_type", signalType)
                put("sig_data", data)
                put("ts", System.currentTimeMillis())
            }.toString().toByteArray()
            val mId = UUID.randomUUID().toString()
            val k = key ?: return@launch
            val payload = Codec.enc(MessageCrypto.encrypt(k, mId, body))
            ws?.send(JSONObject().put("type", "msg").put("id", mId).put("payload", payload).toString())
        }
    }

    private suspend fun connect() {
        val peer = db.dao().peerNow() ?: run { running = false; return }
        key = MessageCrypto.deriveKey(identity, Codec.dec(peer.dhPub))
        val g = ++gen
        state.value = "جارٍ الاتصال…"
        ws = http.newWebSocket(
            Request.Builder().url(peer.server.replaceFirst("http", "ws") + "/ws").build(),
            listener(g)
        )
    }

    private fun lost(g: Int) {
        if (g != gen) return
        ready = false; ws = null
        state.value = "غير متصل"
        if (!running) return
        scope.launch {
            delay(minOf(30_000L, 1000L shl minOf(attempt++, 5)))
            if (running && g == gen) connect()
        }
    }

    private suspend fun flush() {
        val k = key ?: return
        for (m in db.dao().pending()) {
            val body = JSONObject().apply {
                put("t", m.text)
                put("ts", m.ts)
                if (m.isVoice) {
                    put("v", 1)
                    put("dur", m.durationSec)
                }
            }.toString().toByteArray()
            val payload = Codec.enc(MessageCrypto.encrypt(k, m.id, body))
            val ok = ws?.send(JSONObject().put("type", "msg").put("id", m.id).put("payload", payload).toString()) ?: false
            if (!ok) return
        }
    }

    private suspend fun receive(socket: WebSocket, id: String, payload: String) {
        try {
            val plain = MessageCrypto.decrypt(key ?: return, id, Codec.dec(payload))
            val o = JSONObject(String(plain))
            val sigType = o.optString("sig_type")

            if (sigType.isNotEmpty() && sigType.startsWith("call_")) {
                val sigData = o.optJSONObject("sig_data") ?: JSONObject()
                onCallSignal?.invoke(sigType, sigData)
                if (sigType == "call_offer" && !foreground) {
                    val callKind = if (sigData.optString("call_type") == "VIDEO") "مرئية 📹" else "صوتية 📞"
                    onIncoming?.invoke("مكالمة $callKind واردة...")
                }
            } else {
                val text = o.getString("t")
                val isVoice = o.optInt("v", 0) == 1
                val durationSec = o.optInt("dur", 0)
                val entity = MessageEntity(id, text, false, o.getLong("ts"), 1, isVoice, durationSec)
                val row = db.dao().insert(entity) // IGNORE يمنع التكرار
                if (row != -1L && !foreground) {
                    val notifText = if (isVoice) "رسالة صوتية 🎤 ($durationSec ث)" else text
                    onIncoming?.invoke(notifText)
                }
            }
        } catch (e: Exception) {
            // رسالة غير قابلة للفك: نُدرج تنبيهاً محلياً في المحادثة ونؤكد استلامها للسيرفر
            runCatching {
                db.dao().insert(MessageEntity(id, "[رسالة تعذر فك تشفيرها]", false, System.currentTimeMillis(), 1))
            }
        }
        socket.send(JSONObject().put("type", "ack").put("id", id).toString())
    }

    private fun listener(g: Int) = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (g != gen) return
            val o = try { JSONObject(text) } catch (e: Exception) { return }
            when (o.optString("type")) {
                "challenge" -> {
                    val sig = identity.sign(Codec.dec(o.getString("nonce")) + "hmd-auth-v1".toByteArray())
                    webSocket.send(JSONObject().put("type", "auth")
                        .put("signPub", Codec.enc(identity.getOrCreate().signPublic))
                        .put("sig", Codec.enc(sig)).toString())
                }
                "ready" -> {
                    ready = true; attempt = 0; state.value = "متصل"
                    fcmToken?.let { webSocket.send(JSONObject().put("type", "fcm").put("token", it).toString()) }
                    scope.launch { flush() }
                }
                "sent" -> scope.launch { db.dao().markSent(o.getString("id")) }
                "msg" -> scope.launch { receive(webSocket, o.getString("id"), o.getString("payload")) }
            }
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(g)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(g)
    }
}
