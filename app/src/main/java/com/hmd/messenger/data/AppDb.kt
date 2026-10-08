package com.hmd.messenger.data

import android.content.Context

data class MessageEntity(
    val id: String,
    val text: String,
    val outgoing: Boolean,
    val ts: Long,
    val status: Int, // 0: pending, 1: sent/received
    val isVoice: Boolean = false,
    val durationSec: Int = 0
)

data class PeerInfo(
    val server: String,
    val dhPub: String,
    val signPub: String
)

interface MessageDao {
    fun peerNow(): PeerInfo?
    fun savePeer(peer: PeerInfo)
    fun insert(m: MessageEntity): Long
    fun pending(): List<MessageEntity>
    fun markSent(id: String)
    fun allMessages(): List<MessageEntity>
}

class AppDb private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("hmd_messages_db", Context.MODE_PRIVATE)
    private val messages = mutableListOf<MessageEntity>()

    val daoImpl = object : MessageDao {
        override fun peerNow(): PeerInfo? {
            val server = prefs.getString("peer_server", "https://hmd-relay-server.onrender.com") ?: "https://hmd-relay-server.onrender.com"
            val dhPub = prefs.getString("peer_dh_pub", "sample_dh_pub_key_32_bytes_test") ?: "sample_dh_pub_key_32_bytes_test"
            val signPub = prefs.getString("peer_sign_pub", "sample_sign_pub_key_32_bytes_test") ?: "sample_sign_pub_key_32_bytes_test"
            return PeerInfo(server, dhPub, signPub)
        }

        override fun savePeer(peer: PeerInfo) {
            prefs.edit()
                .putString("peer_server", peer.server)
                .putString("peer_dh_pub", peer.dhPub)
                .putString("peer_sign_pub", peer.signPub)
                .apply()
        }

        @Synchronized
        override fun insert(m: MessageEntity): Long {
            if (messages.any { it.id == m.id }) return -1L
            messages.add(m)
            return messages.size.toLong()
        }

        @Synchronized
        override fun pending(): List<MessageEntity> {
            return messages.filter { it.outgoing && it.status == 0 }
        }

        @Synchronized
        override fun markSent(id: String) {
            val idx = messages.indexOfFirst { it.id == id }
            if (idx != -1) {
                val old = messages[idx]
                messages[idx] = old.copy(status = 1)
            }
        }

        @Synchronized
        override fun allMessages(): List<MessageEntity> {
            return messages.toList()
        }
    }

    fun dao(): MessageDao = daoImpl

    companion object {
        @Volatile private var INSTANCE: AppDb? = null

        fun get(context: Context): AppDb {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppDb(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
