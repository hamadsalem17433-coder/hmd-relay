package com.hmd.server

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * خادم تمرير (Relay) لهاتفين فقط.
 *
 * - الغرفة (room) والرمز المميّز (token) مشتقان على الهاتفين من سر الاقتران. الخادم لا يعرف السر
 *   ولا مفتاح تشفير الرسائل، ويرى فقط حمولات مشفرة لا يستطيع فكها.
 * - أول من يتصل بغرفة جديدة يسجّل بصمة رمزها (SHA-256). بعد ذلك لا يدخل إلا من يملك الرمز نفسه.
 * - كل غرفة تقبل جهازين فقط.
 * - الرسائل العادية تُخزَّن في ذاكرة الخادم حتى يؤكد الطرف الآخر استلامها (ack).
 *   الذاكرة تضيع عند إعادة التشغيل، لذلك التطبيق يعيد إرسال أي رسالة لم يصله تأكيد استلامها.
 * - الرسائل العابرة (eph): مكالمات وتأكيدات استلام، تُمرَّر فقط إن كان الطرف الآخر متصلًا.
 */

private const val MAX_PAYLOAD_CHARS = 8_000_000
private const val MAX_QUEUE_MSGS = 500
private const val MAX_QUEUE_BYTES = 40_000_000L
private const val MAX_ROOMS = 1_000
private const val ROOM_TTL_MS = 30L * 24 * 3600 * 1000

private val ID_RE = Regex("^[A-Za-z0-9_-]{8,64}$")
private val B64_RE = Regex("^[A-Za-z0-9_-]{43}$")
private val DEV_RE = Regex("^[a-f0-9]{32}$")

data class Env(val id: String, val sender: String, val payload: String)

class Room(val tokenHash: ByteArray) {
    val devices = LinkedHashSet<String>() // لا أكثر من 2، يُعدَّل داخل synchronized(room)
    val queue = LinkedHashMap<String, Env>()
    var bytes = 0L
    @Volatile var lastSeen = System.currentTimeMillis()
}

enum class Enq { OK, FULL, DUP }

class RateLimiter(private val max: Int, private val windowMs: Long) {
    private val requests = ConcurrentHashMap<String, MutableList<Long>>()

    fun allow(key: String): Boolean {
        val now = System.currentTimeMillis()
        val list = requests.computeIfAbsent(key) { mutableListOf() }
        synchronized(list) {
            list.removeAll { now - it > windowMs }
            if (list.size >= max) return false
            list.add(now)
            return true
        }
    }

    fun sweep() {
        val now = System.currentTimeMillis()
        requests.entries.removeIf { (_, list) ->
            synchronized(list) { list.removeAll { now - it > windowMs }; list.isEmpty() }
        }
    }
}

private fun JsonObject.str(k: String): String? = this[k]?.jsonPrimitive?.contentOrNull
private fun envJson(e: Env) =
    buildJsonObject { put("type", "msg"); put("id", e.id); put("payload", e.payload) }.toString()

fun main() {
    embeddedServer(Netty, port = System.getenv("PORT")?.toInt() ?: 8080, module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    val rooms = ConcurrentHashMap<String, Room>()
    val sessions = ConcurrentHashMap<String, WebSocketSession>() // "room|device" -> اتصال حيّ
    val fcmTokens = ConcurrentHashMap<String, String>()
    val fcm = FcmSender()
    val connLimiter = RateLimiter(max = 30, windowMs = 60_000) // محاولات اتصال لكل عنوان IP

    install(WebSockets) {
        maxFrameSize = 12_000_000L
        pingPeriod = Duration.ofSeconds(20)
        timeout = Duration.ofSeconds(60)
    }

    launch {
        while (isActive) {
            delay(600_000)
            connLimiter.sweep()
            val now = System.currentTimeMillis()
            rooms.entries.removeIf { (id, r) ->
                now - r.lastSeen > ROOM_TTL_MS && sessions.keys.none { it.startsWith("$id|") }
            }
        }
    }

    routing {
        get("/health") { call.respondText("ok") }

        webSocket("/ws") {
            val fwd = call.request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()
            val ip = if (!fwd.isNullOrBlank() && fwd.matches(Regex("^[0-9a-fA-F:.]+$"))) fwd else call.request.local.remoteHost
            if (!connLimiter.allow(ip)) return@webSocket close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "rate"))

            val auth = (withTimeoutOrNull(10_000L) { incoming.receive() } as? Frame.Text)
                ?.let { runCatching { Json.parseToJsonElement(it.readText()).jsonObject }.getOrNull() }
            val roomId = auth?.str("room")
            val token = auth?.str("token")
            val device = auth?.str("device")
            if (auth?.str("type") != "auth" || roomId == null || token == null || device == null ||
                !B64_RE.matches(roomId) || !B64_RE.matches(token) || !DEV_RE.matches(device)
            ) return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "auth"))

            val th = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
            val room = rooms[roomId] ?: run {
                if (rooms.size >= MAX_ROOMS) return@webSocket close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "full"))
                rooms.computeIfAbsent(roomId) { Room(th) }
            }
            if (!MessageDigest.isEqual(room.tokenHash, th)) {
                return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "auth"))
            }
            val admitted = synchronized(room) {
                when {
                    room.devices.contains(device) -> true
                    room.devices.size < 2 -> { room.devices.add(device); true }
                    else -> false
                }
            }
            if (!admitted) return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "room_full"))

            val me = "$roomId|$device"
            room.lastSeen = System.currentTimeMillis()
            sessions.put(me, this)?.close(CloseReason(CloseReason.Codes.NORMAL, "replaced")) // اتصال واحد لكل جهاز
            val peerId = synchronized(room) { room.devices.firstOrNull { it != device } }
            val peerLive = peerId?.let { sessions["$roomId|$it"] }
            send(Frame.Text(buildJsonObject { put("type", "ready"); put("peer", peerLive != null) }.toString()))
            // أبلغ الشريك (إن كان متصلًا) أنني صرت متصلًا
            peerLive?.let { runCatching { it.send(Frame.Text("""{"type":"presence","online":true}""")) } }
            val pending = synchronized(room) { room.queue.values.filter { it.sender != device } }
            pending.forEach { send(Frame.Text(envJson(it))) }

            var winStart = System.currentTimeMillis()
            var winCount = 0
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val now = System.currentTimeMillis()
                    if (now - winStart > 10_000) { winStart = now; winCount = 0 }
                    if (++winCount > 1_500) { // حماية من الإغراق (المكالمات تحتاج عشرات الرسائل في الثانية)
                        close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "flood")); break
                    }
                    val o = runCatching { Json.parseToJsonElement(frame.readText()).jsonObject }.getOrNull() ?: continue
                    room.lastSeen = now

                    when (o.str("type")) {
                        "fcm" -> o.str("token")?.takeIf { it.length in 20..4096 }?.let { fcmTokens[me] = it }

                        "msg" -> {
                            val id = o.str("id")?.takeIf { ID_RE.matches(it) } ?: continue
                            val payload = o.str("payload")?.takeIf { it.isNotEmpty() && it.length <= MAX_PAYLOAD_CHARS } ?: continue
                            val eph = o["eph"]?.jsonPrimitive?.booleanOrNull == true
                            val peer = synchronized(room) { room.devices.firstOrNull { it != device } }
                            val env = Env(id, device, payload)

                            if (eph) { // عابرة: تمرَّر إن كان الطرف متصلًا وإلا تُهمل
                                peer?.let { sessions["$roomId|$it"] }?.let { s -> runCatching { s.send(Frame.Text(envJson(env))) } }
                                continue
                            }

                            val res = synchronized(room) {
                                when {
                                    room.queue.containsKey(id) -> Enq.DUP
                                    room.queue.size >= MAX_QUEUE_MSGS || room.bytes + payload.length > MAX_QUEUE_BYTES -> Enq.FULL
                                    else -> { room.queue[id] = env; room.bytes += payload.length; Enq.OK }
                                }
                            }
                            when (res) {
                                Enq.FULL -> {
                                    send(Frame.Text(buildJsonObject { put("type", "error"); put("id", id); put("reason", "queue_full") }.toString()))
                                    continue
                                }
                                Enq.OK -> if (peer != null) {
                                    val live = sessions["$roomId|$peer"]
                                    if (live != null) runCatching { live.send(Frame.Text(envJson(env))) }
                                    else fcmTokens["$roomId|$peer"]?.let { t -> // الطرف غير متصل: أيقظه بإشعار بلا محتوى
                                        launch(NonCancellable + Dispatchers.IO) { if (!fcm.wake(t)) fcmTokens.remove("$roomId|$peer", t) }
                                    }
                                }
                                Enq.DUP -> {}
                            }
                            send(Frame.Text(buildJsonObject { put("type", "sent"); put("id", id) }.toString()))
                        }

                        "ack" -> o.str("id")?.let { id ->
                            synchronized(room) {
                                val e = room.queue[id]
                                if (e != null && e.sender != device) { room.queue.remove(id); room.bytes -= e.payload.length }
                            }
                        }
                    }
                }
            } finally {
                if (sessions.remove(me, this)) {
                    // أبلغ الشريك أنني انقطعت (NonCancellable: لأن هذه الكوروتين ملغاة عند إغلاق الاتصال)
                    withContext(NonCancellable) {
                        val pid = synchronized(room) { room.devices.firstOrNull { it != device } }
                        pid?.let { sessions["$roomId|$it"] }?.let {
                            runCatching { it.send(Frame.Text("""{"type":"presence","online":false}""")) }
                        }
                    }
                }
            }
        }
    }
}
