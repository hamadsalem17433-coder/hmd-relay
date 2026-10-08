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
