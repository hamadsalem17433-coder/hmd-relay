package com.hmd.server

import com.google.auth.oauth2.GoogleCredentials
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * يرسل نبضة إيقاظ صامتة عالية الأولوية (Data-Only High Priority Push).
 * هذا يضمن قيام أندرويد بتشغيل FcmService.onMessageReceived حتى والتطبيق مغلق تماماً،
 * ليتصل التطبيق بالخادم ويفك تشفير الرسالة أو يشغّل رنين المكالمة الواردة.
 */
class FcmSender {

    private fun readConfig(name: String): String? {
        // 1. المحاولة الأولى: القراءة من Environment Variables
        val envVal = System.getenv(name)?.trim()
        if (!envVal.isNullOrEmpty()) return envVal

        // 2. المحاولة الثانية: القراءة من Secret Files في Render (/etc/secrets/)
        val secretFile = File("/etc/secrets/$name")
        if (secretFile.exists() && secretFile.canRead()) {
            val fileVal = runCatching { secretFile.readText().trim() }.getOrNull()
            if (!fileVal.isNullOrEmpty()) return fileVal
        }
        return null
    }

    private val projectId: String? = readConfig("FCM_PROJECT_ID")

    private val creds: GoogleCredentials? = readConfig("FCM_SERVICE_ACCOUNT_JSON")?.let { jsonStr ->
        runCatching {
            GoogleCredentials.fromStream(jsonStr.byteInputStream())
                .createScoped("https://www.googleapis.com/auth/firebase.messaging")
        }.onFailure { e ->
            println("[FCM] Failed to parse FCM_SERVICE_ACCOUNT_JSON: ${e.message}")
        }.getOrNull()
    }

    private val http = HttpClient.newHttpClient()

    val enabled get() = !projectId.isNullOrEmpty() && creds != null

    init {
        println("[FCM] Initialized: enabled=$enabled, projectId=${projectId ?: "MISSING"}")
    }

    /** يرجع false فقط إذا كان الرمز غير صالح (404 / UNREGISTERED) ويجب حذفه. */
    fun wake(token: String): Boolean {
        if (!enabled) {
            println("[FCM] FCM is disabled (missing FCM_PROJECT_ID or FCM_SERVICE_ACCOUNT_JSON)")
            return true
        }
        val c = creds ?: return true
        return try {
            synchronized(c) {
                c.refreshIfExpired()
            }
            val accessToken = c.accessToken?.tokenValue ?: run {
                synchronized(c) {
                    c.refresh()
                    c.accessToken?.tokenValue
                }
            }

            if (accessToken == null) {
                println("[FCM] Failed to obtain valid access token from GoogleCredentials")
                return true
            }

            // إرسال Data-Only Message بأولوية HIGH لإجبار أندرويد على إيقاظ FcmService والتطبيق مغلق
            val body = buildJsonObject {
                put("message", buildJsonObject {
                    put("token", token)
                    put("android", buildJsonObject {
                        put("priority", "HIGH")
                        put("ttl", "60s")
                    })
                    put("data", buildJsonObject {
                        put("wake", "1")
                        put("ts", System.currentTimeMillis().toString())
                    })
                })
            }.toString()

            val req = HttpRequest.newBuilder(URI("https://fcm.googleapis.com/v1/projects/$projectId/messages:send"))
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()

            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            println("[FCM] Push response status: ${res.statusCode()}, body: ${res.body()}")
            res.statusCode() != 404 && !res.body().contains("UNREGISTERED")
        } catch (e: Exception) {
            println("[FCM] Error sending FCM push notification: ${e.message}")
            e.printStackTrace()
            true
        }
    }
}
