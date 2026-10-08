package com.hmd.server

import com.google.crypto.tink.subtle.Ed25519Verify
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

@Serializable data class CreateReq(val pairId: String, val token: String, val signPub: String, val dhPub: String, val expiresAt: Long)
@Serializable data class JoinReq(val pairId: String, val token: String, val signPub: String, val dhPub: String)
@Serializable data class SignedReq(val pairId: String, val signPub: String, val sig: String)
@Serializable data class KeysResp(val signPub: String, val dhPub: String)
@Serializable data class ActiveResp(val active: Boolean)

private val enc = Base64.getUrlEncoder().withoutPadding()
private val dec = Base64.getUrlDecoder()
private fun b64(b: ByteArray) = enc.encodeToString(b)
private fun key32(s: String): ByteArray? = runCatching { dec.decode(s) }.getOrNull()?.takeIf { it.size == 32 }

private fun verify(signPub: String, sig: String, msg: ByteArray): Boolean = runCatching {
    Ed25519Verify(dec.decode(signPub)).verify(dec.decode(sig), msg); true
}.getOrDefault(false)

/** تحديد عنوان IP بشكل آمن للحد من الإغراق والطلبات المزيفة */
private fun ApplicationCall.clientIp(): String {
    val forwarded = request.headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()
    return if (!forwarded.isNullOrBlank() && forwarded.matches(Regex("^[0-9a-fA-F:.]+$"))) forwarded
    else request.local.remoteHost
}

fun main() {
    embeddedServer(Netty, port = System.getenv("PORT")?.toInt() ?: 8080, module = Application::module).start(wait = true)
}

fun Application.module() {
    val repo = createRepo()
    val sessions = ConcurrentHashMap<String, WebSocketSession>() // signPub -> اتصال حيّ (على هذا السيرفر فقط)
    val fcm = FcmSender()
    val limiter = RateLimiter(max = 30, windowMs = 60_000)

    install(ContentNegotiation) { json() }
    install(WebSockets) { maxFrameSize = 1_048_576; pingPeriod = 20.seconds; timeout = 60.seconds }

    launch { while (isActive) { delay(60_000); runCatching { repo.cleanup() }; limiter.sweep() } }

    routing {
        get("/health") { call.respondText("ok") }

        // ---------- الاقتران ----------
        post("/pair/create") {
            if (!limiter.allow(call.clientIp())) return@post call.respond(HttpStatusCode.TooManyRequests)
            val r = call.receive<CreateReq>()
            val now = System.currentTimeMillis()
            if (key32(r.signPub) == null || key32(r.dhPub) == null || r.pairId.length !in 16..64 ||
                r.token.length < 20 || r.expiresAt !in now..(now + 5 * 60_000)
            ) return@post call.respond(HttpStatusCode.BadRequest)
            val ok = repo.createPair(r.pairId, sha256(r.token), r.expiresAt, Keys(r.signPub, r.dhPub))
            call.respond(if (ok) HttpStatusCode.Created else HttpStatusCode.BadRequest)
        }

        post("/pair/join") {
            if (!limiter.allow(call.clientIp())) return@post call.respond(HttpStatusCode.TooManyRequests)
            val r = call.receive<JoinReq>()
            val now = System.currentTimeMillis()
            val p = repo.pair(r.pairId)
            if (p == null || key32(r.signPub) == null || key32(r.dhPub) == null ||
                !tokenMatches(p, r.token) || now > p.expiresAt
            ) return@post call.respond(HttpStatusCode.BadRequest)
            if (p.tokenUsed || !repo.join(r.pairId, Keys(r.signPub, r.dhPub), now))
                return@post call.respond(HttpStatusCode.Conflict)
            call.respond(KeysResp(p.a.sign, p.a.dh))
        }

        get("/pair/{id}/status") {
            val p = call.parameters["id"]?.let { repo.pair(it) }
            val token = call.request.headers["X-Pair-Token"]
            if (p == null || token == null || !tokenMatches(p, token)) return@get call.respond(HttpStatusCode.NotFound)
            val b = p.b ?: return@get call.respond(HttpStatusCode.NoContent)
            call.respond(KeysResp(b.sign, b.dh))
        }

        post("/pair/confirm") {
            val r = call.receive<SignedReq>()
            val p = repo.pair(r.pairId)
            if (p == null || !p.owns(r.signPub) || !verify(r.signPub, r.sig, "hmd-confirm-v1:${r.pairId}".toByteArray()))
                return@post call.respond(HttpStatusCode.Forbidden)
            call.respond(ActiveResp(repo.confirm(r.pairId, r.signPub)))
        }

        post("/pair/cancel") {
            val r = call.receive<SignedReq>()
            val p = repo.pair(r.pairId)
            if (p == null || !p.owns(r.signPub) || !verify(r.signPub, r.sig, "hmd-cancel-v1:${r.pairId}".toByteArray()))
                return@post call.respond(HttpStatusCode.Forbidden)
            repo.remove(r.pairId)
            call.respond(HttpStatusCode.NoContent)
        }

        // ---------- المصادقة + الـ Relay ----------
        webSocket("/ws") {
            val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
            send(Frame.Text(buildJsonObject { put("type", "challenge"); put("nonce", b64(nonce)) }.toString()))

            val auth = (withTimeoutOrNull(10.seconds) { incoming.receive() } as? Frame.Text)
                ?.let { runCatching { Json.parseToJsonElement(it.readText()).jsonObject }.getOrNull() }
            val me = auth?.get("signPub")?.jsonPrimitive?.contentOrNull
            val sig = auth?.get("sig")?.jsonPrimitive?.contentOrNull
            val pair = me?.let { repo.pairByDevice(it) }
            if (auth?.get("type")?.jsonPrimitive?.contentOrNull != "auth" || me == null || sig == null ||
                pair == null || !pair.active || !verify(me, sig, nonce + "hmd-auth-v1".toByteArray())
            ) return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "auth"))

            sessions.put(me, this)?.close() // اتصال واحد لكل جهاز
            send(Frame.Text("""{"type":"ready"}"""))
            repo.pending(me).forEach { send(Frame.Text(envelopeJson(it))) }

            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val o = runCatching { Json.parseToJsonElement(frame.readText()).jsonObject }.getOrNull() ?: continue
                    val type = o["type"]?.jsonPrimitive?.contentOrNull
                    if (type == "fcm") {
                        o["token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length in 20..4096 }?.let { repo.setFcm(me, it) }
                        continue
                    }
                    val id = o["id"]?.jsonPrimitive?.contentOrNull ?: continue
                    when (type) {
                        "msg" -> {
                            val payload = o["payload"]?.jsonPrimitive?.contentOrNull ?: continue
                            val peer = pair.peerOf(me) ?: continue
                            val env = Envelope(id, me, payload)
                            when (repo.enqueue(peer, env)) {
                                Enq.FULL -> {
                                    send(Frame.Text(buildJsonObject { put("type", "error"); put("id", id); put("reason", "queue_full") }.toString()))
                                    continue
                                }
                                Enq.OK -> {
                                    val live = sessions[peer]
                                    if (live != null) runCatching { live.send(Frame.Text(envelopeJson(env))) }
                                    else repo.fcm(peer)?.let { t -> // الطرف غير متصل: أيقظه
                                        launch(NonCancellable + Dispatchers.IO) { if (!fcm.wake(t)) repo.dropFcm(peer, t) }
                                    }
                                }
                                Enq.DUP -> {}
                            }
                            send(Frame.Text(buildJsonObject { put("type", "sent"); put("id", id) }.toString()))
                        }
                        "ack" -> repo.ack(me, id)
                    }
                }
            } finally {
                sessions.remove(me, this)
            }
        }
    }
}

private fun envelopeJson(e: Envelope) =
    buildJsonObject { put("type", "msg"); put("id", e.id); put("payload", e.payload) }.toString()

// ---------- Helper Data Structures & In-Memory Repository ----------

data class Keys(val sign: String, val dh: String)

data class PairData(
    val id: String,
    val tokenHash: String,
    val expiresAt: Long,
    val a: Keys,
    var b: Keys? = null,
    var active: Boolean = false,
    var tokenUsed: Boolean = false
) {
    fun owns(signPub: String): Boolean = a.sign == signPub || b?.sign == signPub
    fun peerOf(signPub: String): String? = if (a.sign == signPub) b?.sign else if (b?.sign == signPub) a.sign else null
}

data class Envelope(val id: String, val sender: String, val payload: String)

enum class Enq { OK, FULL, DUP }

fun sha256(s: String): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    return Base64.getUrlEncoder().withoutPadding().encodeToString(md.digest(s.toByteArray()))
}

fun tokenMatches(p: PairData, token: String): Boolean = p.tokenHash == sha256(token)

class RateLimiter(private val max: Int, private val windowMs: Long) {
    private val requests = ConcurrentHashMap<String, MutableList<Long>>()

    fun allow(ip: String): Boolean {
        val now = System.currentTimeMillis()
        val list = requests.computeIfAbsent(ip) { mutableListOf() }
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
            synchronized(list) {
                list.removeAll { now - it > windowMs }
                list.isEmpty()
            }
        }
    }
}

interface ServerRepo {
    fun cleanup()
    fun createPair(pairId: String, tokenHash: String, expiresAt: Long, a: Keys): Boolean
    fun pair(pairId: String): PairData?
    fun pairByDevice(signPub: String): PairData?
    fun join(pairId: String, b: Keys, now: Long): Boolean
    fun confirm(pairId: String, signPub: String): Boolean
    fun remove(pairId: String)
    fun enqueue(peerSignPub: String, env: Envelope): Enq
    fun pending(signPub: String): List<Envelope>
    fun ack(signPub: String, msgId: String)
    fun setFcm(signPub: String, token: String)
    fun fcm(signPub: String): String?
    fun dropFcm(signPub: String, token: String)
}

fun createRepo(): ServerRepo = InMemRepo()

private class InMemRepo : ServerRepo {
    private val pairs = ConcurrentHashMap<String, PairData>()
    private val deviceToPair = ConcurrentHashMap<String, String>()
    private val queues = ConcurrentHashMap<String, ConcurrentHashMap<String, Envelope>>()
    private val fcmTokens = ConcurrentHashMap<String, String>()

    override fun cleanup() {
        val now = System.currentTimeMillis()
        pairs.entries.removeIf { (_, p) ->
            val expired = !p.active && now > p.expiresAt
            if (expired) {
                deviceToPair.remove(p.a.sign)
                p.b?.sign?.let { deviceToPair.remove(it) }
            }
            expired
        }
    }

    override fun createPair(pairId: String, tokenHash: String, expiresAt: Long, a: Keys): Boolean {
        if (pairs.containsKey(pairId)) return false
        val p = PairData(pairId, tokenHash, expiresAt, a)
        pairs[pairId] = p
        deviceToPair[a.sign] = pairId
        return true
    }

    override fun pair(pairId: String): PairData? = pairs[pairId]

    override fun pairByDevice(signPub: String): PairData? {
        val pid = deviceToPair[signPub] ?: return null
        return pairs[pid]
    }

    override fun join(pairId: String, b: Keys, now: Long): Boolean {
        val p = pairs[pairId] ?: return false
        if (p.tokenUsed || now > p.expiresAt) return false
        p.b = b
        p.tokenUsed = true
        deviceToPair[b.sign] = pairId
        return true
    }

    override fun confirm(pairId: String, signPub: String): Boolean {
        val p = pairs[pairId] ?: return false
        if (!p.owns(signPub)) return false
        p.active = true
        return true
    }

    override fun remove(pairId: String) {
        val p = pairs.remove(pairId) ?: return
        deviceToPair.remove(p.a.sign)
        p.b?.sign?.let { deviceToPair.remove(it) }
        queues.remove(p.a.sign)
        p.b?.sign?.let { queues.remove(it) }
        fcmTokens.remove(p.a.sign)
        p.b?.sign?.let { fcmTokens.remove(it) }
    }

    override fun enqueue(peerSignPub: String, env: Envelope): Enq {
        val q = queues.computeIfAbsent(peerSignPub) { ConcurrentHashMap() }
        if (q.containsKey(env.id)) return Enq.DUP
        if (q.size >= 500) return Enq.FULL
        q[env.id] = env
        return Enq.OK
    }

    override fun pending(signPub: String): List<Envelope> {
        return queues[signPub]?.values?.toList() ?: emptyList()
    }

    override fun ack(signPub: String, msgId: String) {
        queues[signPub]?.remove(msgId)
    }

    override fun setFcm(signPub: String, token: String) {
        fcmTokens[signPub] = token
    }

    override fun fcm(signPub: String): String? = fcmTokens[signPub]

    override fun dropFcm(signPub: String, token: String) {
        fcmTokens.remove(signPub, token)
    }
}
