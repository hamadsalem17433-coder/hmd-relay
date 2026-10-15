package com.hmd.server

import com.google.auth.oauth2.GoogleCredentials
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * يرسل إشعار إيقاظ منبثق (Notification + Data) لضمان قيام أندرويد بعرض الإشعار فوراً حتى لو كان التطبيق مغلقاً.
 * متغيرات البيئة: FCM_PROJECT_ID و FCM_SERVICE_ACCOUNT_JSON (نص ملف الـ service account كاملًا).
 */
class FcmSender {
    private val projectId = System.getenv("FCM_PROJECT_ID")
    private val creds: GoogleCredentials? = System.getenv("FCM_SERVICE_ACCOUNT_JSON")?.let { jsonStr ->
        runCatching {
            GoogleCredentials.fromStream(jsonStr.byteInputStream())
                .createScoped("https://www.googleapis.com/auth/firebase.messaging")
        }.getOrNull()
    }
    private val http = HttpClient.newHttpClient()

    val enabled get() = projectId != null && creds != null

    /** يرجع false فقط إذا كان الرمز غير صالح ويجب حذفه. */
    fun wake(token: String): Boolean {
        if (!enabled) {
            println("[FCM] FCM is disabled (missing project ID or service account JSON)")
            return true
        }
        val c = creds ?: return true
        return try {
            c.refreshIfExpired()
            val accessToken = c.accessToken?.tokenValue ?: run {
                c.refresh()
                c.accessToken?.tokenValue
            }

            if (accessToken == null) {
                println("[FCM] Failed to obtain valid access token from GoogleCredentials")
                return true
            }

            val body = buildJsonObject {
                put("message", buildJsonObject {
                    put("token", token)
                    put("notification", buildJsonObject {
                        put("title", "HMD Messenger")
                        put("body", "رسالة جديدة")
                    })
                    put("android", buildJsonObject {
                        put("priority", "HIGH")
                        put("notification", buildJsonObject {
                            put("channel_id", "messages")
                            put("sound", "default")
                            put("notification_priority", "PRIORITY_MAX")
                        })
                    })
                    put("data", buildJsonObject {
                        put("wake", "1")
                    })
                })
            }.toString()

            val req = HttpRequest.newBuilder(URI("https://fcm.googleapis.com/v1/projects/$projectId/messages:send"))
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build()

            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            println("[FCM] Push response status: ${res.statusCode()}, body: ${res.body()}")
            res.statusCode() != 404
        } catch (e: Exception) {
            println("[FCM] Error sending FCM push notification: ${e.message}")
            e.printStackTrace()
            true
        }
    }
}
