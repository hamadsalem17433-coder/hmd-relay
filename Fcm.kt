package com.hmd.server

import com.google.auth.oauth2.GoogleCredentials
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * يرسل إشعار "إيقاظ" فقط (data: wake=1) بدون أي محتوى للرسالة.
 * متغيرات البيئة: FCM_PROJECT_ID و FCM_SERVICE_ACCOUNT_JSON (نص ملف الـ service account كاملًا).
 * إن لم تُضبط يعمل السيرفر بدون إشعارات.
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
        if (!enabled) return true
        return try {
            creds!!.refreshIfExpired()
            val body = buildJsonObject {
                put("message", buildJsonObject {
                    put("token", token)
                    put("android", buildJsonObject { put("priority", "HIGH") })
                    put("data", buildJsonObject { put("wake", "1") })
                })
            }.toString()
            val req = HttpRequest.newBuilder(URI("https://fcm.googleapis.com/v1/projects/$projectId/messages:send"))
                .header("Authorization", "Bearer ${creds.accessToken.tokenValue}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build()
            http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() != 404
        } catch (e: Exception) { true }
    }
}
