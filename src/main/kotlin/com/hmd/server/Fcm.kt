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
 * يرسل نبضة إيقاظ صامتة عالية الأولوية مع إشعار نظام مباشر (Notification + Data High Priority Push).
 * هذا يضمن قيام خدمات Google Play Services بعرض الإشعار فوراً وتنبيه المستخدم أوتوماتيكياً حتى لو كان التطبيق مغلقاً تماماً.
 */
class FcmSender {

    private fun readConfig(name: String): String? {
        val envVal = System.getenv(name)?.trim()
        if (!envVal.isNullOrEmpty()) return envVal

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

    private val tokenLock = Any()
    @Volatile private var cachedAccessToken: String? = null
    @Volatile private var tokenExpiryTime: Long = 0L

    val enabled get() = !projectId.isNullOrEmpty() && creds != null

    init {
        println("[FCM] Initialized: enabled=$enabled, projectId=${projectId ?: "MISSING"}")
    }

    private fun getValidAccessToken(c: GoogleCredentials): String? {
        val now = System.currentTimeMillis()
        if (cachedAccessToken != null && now < tokenExpiryTime - 60_000) {
            return cachedAccessToken
        }
        synchronized(tokenLock) {
            val currentNow = System.currentTimeMillis()
            if (cachedAccessToken != null && currentNow < tokenExpiryTime - 60_000) {
                return cachedAccessToken
            }
            c.refreshIfExpired()
            val token = c.accessToken?.tokenValue ?: run {
                c.refresh()
                c.accessToken?.tokenValue
            }
            val exp = c.accessToken?.expirationTime?.time ?: (currentNow + 3600_000)
            cachedAccessToken = token
            tokenExpiryTime = exp
            return token
        }
    }

    /** يرجع false فقط إذا كان الرمز غير صالح (404 / UNREGISTERED) ويجب حذفه. */
    fun wake(token: String): Boolean {
        if (!enabled) {
            println("[FCM] FCM is disabled (missing FCM_PROJECT_ID or FCM_SERVICE_ACCOUNT_JSON)")
            return true
        }
        val c = creds ?: return true
        return try {
            val accessToken = getValidAccessToken(c) ?: run {
                println("[FCM] Failed to obtain valid access token from GoogleCredentials")
                return true
            }

            // إرسال Notification + Data بأولوية HIGH لتضمن خدمات أندرويد و Google Play عرض الإشعار والتطبيق مغلق
            val body = buildJsonObject {
                put("message", buildJsonObject {
                    put("token", token)
                    put("notification", buildJsonObject {
                        put("title", "HMD Messenger")
                        put("body", "رسالة جديدة")
                    })
                    put("android", buildJsonObject {
                        put("priority", "HIGH")
                        put("ttl", "60s")
                        put("notification", buildJsonObject {
                            put("channel_id", "messages")
                            put("sound", "default")
                            put("notification_priority", "PRIORITY_MAX")
                            put("visibility", "PUBLIC")
                        })
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
