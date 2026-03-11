package com.example.privacyassistant

import android.content.Context
import android.util.Log
import javax.crypto.SecretKey

class RemoteSyncHelper(context: Context) {

    companion object {
        private const val TAG = "RemoteSyncHelper"
        // Store your API token securely — here via BuildConfig; use EncryptedSharedPreferences in prod
        private val AUTH_HEADER = "Bearer fb7c49e7f8a806cebb49bf854ea3bf6297927ba168b355ad9f9d7b552464450c"
    }

    // Derive encryption key from a per-installation secret.
    // In production, store this in Android Keystore.
    private val encryptionKey: SecretKey by lazy {
        val secret = "this-is-cipher-secret" 
        CryptoHelper.deriveKey(secret)
    }

    // ── Contact sync ──────────────────────────────────────────

    suspend fun syncContact(contact: Contact) {
        try {
            val contactKey = CryptoHelper.hmacContactKey(contact.name, encryptionKey)
            val nameEnc = CryptoHelper.encrypt(contact.name, encryptionKey)

            val payload = ContactPayload(
                contactKey = contactKey,
                nameEnc = nameEnc,
                timestamp = contact.timestamp
            )

            val response = RemoteApiService.api.addContact(payload, AUTH_HEADER)
            if (response.isSuccessful) {
                Log.d(TAG, "✅ Contact synced: key=${contactKey.take(8)}…")
            } else {
                Log.w(TAG, "⚠️ Contact sync failed: HTTP ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Contact sync error: ${e.message}")
            // Silent fail — local DB is source of truth
        }
    }

    // ── Message sync ──────────────────────────────────────────

    suspend fun syncMessage(message: Message) {
        try {
            val contactKey = CryptoHelper.hmacContactKey(message.contactName, encryptionKey)
            // Encrypt message text; for non-text assets store asset type as placeholder
            val plaintext = message.messageText.ifBlank { "[${message.assetType}]" }
            val messageTextEnc = CryptoHelper.encrypt(plaintext, encryptionKey)

            val payload = MessagePayload(
                contactKey = contactKey,
                messageTextEnc = messageTextEnc,
                date = message.date,
                time = message.time,
                groupLabelMessageId = message.groupLabelMessageId,
                isSentByUser = message.isSentByUser,
                assetType = message.assetType,
                fingerprint = message.fingerprint,   // already SHA-256, safe to send as-is
                duration = message.duration
            )

            val response = RemoteApiService.api.addMessage(payload, AUTH_HEADER)
            if (response.isSuccessful) {
                val body = response.body()
                Log.d(TAG, "✅ Message synced: id=${body?.id} inserted=${body?.inserted}")
            } else {
                Log.w(TAG, "⚠️ Message sync failed: HTTP ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Message sync error: ${e.message}")
        }
    }

    suspend fun syncContactRelation(contactName: String, relationIds: List<String>) {
    try {
        val contactKey = CryptoHelper.hmacContactKey(contactName, encryptionKey)
        val contactNameEnc = CryptoHelper.encrypt(contactName, encryptionKey)

        val payload = ContactRelationPayload(
            contactKey = contactKey,
            contactNameEnc = contactNameEnc,
            relationIds = relationIds, 
            classifiedAtMs = System.currentTimeMillis()
        )

        val response = RemoteApiService.api.upsertContactRelation(payload, AUTH_HEADER)
        if (response.isSuccessful) {
            Log.d(TAG, "✅ Relation synced: ${contactKey.take(8)}… → ${relationIds}")
        } else {
            Log.w(TAG, "⚠️ Relation sync failed: HTTP ${response.code()}")
        }
    } catch (e: Exception) {
        Log.e(TAG, "❌ Relation sync error: ${e.message}")
    }
}
}