package com.example.privacyassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

data class MessageData(
    val messageText: String?,
    val isGift: Int,
    val replyToText: String?
) {
    fun isValid(): Boolean {
        // Valid if has text, is a gift, or is a reply
        return !messageText.isNullOrBlank() || isGift == 1 || replyToText != null
    }
}


class InstagramAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "InstagramAccessibility"
        const val INSTAGRAM_PACKAGE = "com.instagram.android"
        const val ULTRA_DEEP_LOGGING = true // Set to false to reduce log noise
        private var instance: InstagramAccessibilityService? = null
        fun getInstance(): InstagramAccessibilityService? = instance
    }

    private lateinit var database: InstagramDatabase
    private lateinit var contactDao: ContactDao
    private lateinit var messageDao: MessageDao
    private lateinit var notificationHelper: NotificationHelper
    private val ioScope = CoroutineScope(Dispatchers.IO)

    private var isInDirectMessages = false
    private var currentChatContact: String? = null

    // Text message deduplication: track text with timestamps
    // Key: "contactName-text-isSentByUser", Value: timestamp
    private val textMessageCache = mutableMapOf<String, Long>()

    // Media deduplication: track content descriptions with timestamps
    // Key: "contactName-assetType-contentDescription", Value: timestamp
    private val mediaContentCache = mutableMapOf<String, Long>()

    // Time windows for deduplication
    private val SENT_MESSAGE_DUPLICATE_WINDOW_MS = 60000L // 60 seconds for sent messages (longer because they stay visible and get rescanned)
    private val RECEIVED_MESSAGE_DUPLICATE_WINDOW_MS = 300000L // 5 minutes for received messages (prevents re-saving when scrolling or returning to chat)

    // Store reply context between scans with timestamp
    // Triple: (replyText, replyMessageID, timestamp when stored)
    private var pendingReplyContext: Triple<String, Long, Long>? = null
    private val REPLY_CONTEXT_TIMEOUT_MS = 60000L // Keep reply context for 60 seconds to allow multiple replies in sequence

    // Track recent messages for position-based reply inference
    // List of (messageText, messageID, timestamp, yPosition, isSentByUser)
    private val recentMessagesForReplyInference = mutableListOf<RecentMessageInfo>()
    private val REPLY_INFERENCE_WINDOW_MS = 15000L // 15 seconds window for inferring replies

    data class RecentMessageInfo(
        val messageText: String,
        val messageId: Long,
        val timestamp: Long,
        val yPosition: Int,
        val isSentByUser: Boolean
    )

    // Data class to hold detected message info
    data class DetectedMessage(
        val text: String,
        val assetType: String,
        val node: AccessibilityNodeInfo,
        val quotedMessageText: String? = null  // For received messages that are replies
    )

    private var lastMessageCaptureTime = 0L
    private var lastContactCaptureTime = 0L
    private var lastPostDetectionTime = 0L
    private val MESSAGE_CAPTURE_DEBOUNCE_MS = 500L
    private val CONTACT_CAPTURE_DEBOUNCE_MS = 2000L
    private val POST_DETECTION_DEBOUNCE_MS = 3000L

    override fun onServiceConnected() {
    super.onServiceConnected()
    instance = this
    Log.d(TAG, "Instagram Accessibility Service Connected")

    notificationHelper = NotificationHelper(this)

    val info = AccessibilityServiceInfo().apply {
        eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED

        feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        notificationTimeout = 100
        flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        }
    }

    info.packageNames = arrayOf(INSTAGRAM_PACKAGE)
    serviceInfo = info

    database = InstagramDatabase.getInstance(applicationContext)
    contactDao = database.contactDao()
    messageDao = database.messageDao()

    ioScope.launch {
        try {
            val contacts = contactDao.getAllContacts()
            Log.d(TAG, "Database initialized. Existing contacts: ${contacts.size}")

            val dbPath = applicationContext.getDatabasePath("instagram-db").absolutePath
            Log.d(TAG, "Database path: $dbPath")
        } catch (e: Exception) {
            Log.e(TAG, "Database initialization error: ${e.message}")
        }
    }

    Log.d(TAG, "Service initialized and ready to monitor Instagram")
}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            val packageName = event.packageName?.toString()

            if (packageName != INSTAGRAM_PACKAGE) return

            val eventTypeName = when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
                AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> "VIEW_FOCUSED"
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> "VIEW_SCROLLED"
                else -> "EVENT_${event.eventType}"
            }

            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    val className = event.className?.toString() ?: ""
                    handleWindowChange(className)
                }

                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    if (isInDirectMessages) {
                        handleContentChange()
                    }
                }

                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    val source = event.source
                    val viewId = source?.viewIdResourceName ?: ""
                    val contentDesc = source?.contentDescription?.toString() ?: ""
                    val text = source?.text?.toString() ?: ""
                    val className = source?.className?.toString() ?: ""

                    val isPostButton = contentDesc.contains("create", ignoreCase = true) &&
                                       contentDesc.contains("post", ignoreCase = true)

                    if (isPostButton) {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastPostDetectionTime >= POST_DETECTION_DEBOUNCE_MS) {
                            lastPostDetectionTime = currentTime
                            notificationHelper.showInstagramPostAssistantNotification()
                        }
                    }

                    if (isInDirectMessages) {
                        detectChatOpen()
                    }
                }

                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    if (isInDirectMessages) {
                        detectChatOpen()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing event: ${e.message}", e)
        }
    }


private fun extractMessageData(messageNode: AccessibilityNodeInfo): MessageData {
    var messageText: String? = null
    var replyText: String? = null
    var isGift = 0
    
    // Extract main message text
    messageText = extractMessageText(messageNode)
    
    // Check for gift indicators
    val contentDesc = messageNode.contentDescription?.toString() ?: ""
    val viewId = messageNode.viewIdResourceName ?: ""
    
    if (contentDesc.contains("gift", ignoreCase = true) ||
        contentDesc.contains("sent a gift", ignoreCase = true) ||
        viewId.contains("gift", ignoreCase = true) ||
        (messageText.isNullOrBlank() && contentDesc.contains("sent", ignoreCase = true))) {
        isGift = 1
        if (messageText.isNullOrBlank()) {
            messageText = null // Explicitly null for gifts without text
        }
    }
    
    // Look for reply context in parent/sibling nodes
    replyText = findReplyContext(messageNode)
    
    return MessageData(messageText, isGift, replyText)
}

private fun findReplyContext(messageNode: AccessibilityNodeInfo): String? {
    try {
        // Check the message node's parent hierarchy for reply indicators
        var currentNode: AccessibilityNodeInfo? = messageNode.parent
        var depth = 0
        val maxDepth = 5
        
        while (currentNode != null && depth < maxDepth) {
            // Look for reply container
            val viewId = currentNode.viewIdResourceName ?: ""
            val contentDesc = currentNode.contentDescription?.toString() ?: ""
            
            if (viewId.contains("reply", ignoreCase = true) ||
                contentDesc.contains("replying to", ignoreCase = true) ||
                contentDesc.contains("replied to", ignoreCase = true)) {
                
                // Search children for the original message text
                val replyText = findReplyTextInNode(currentNode)
                if (replyText != null) {
                    currentNode.recycle()
                    return replyText
                }
            }
            
            val parent = currentNode.parent
            currentNode.recycle()
            currentNode = parent
            depth++
        }
        
        currentNode?.recycle()
        
        // Also check siblings for reply preview
        val parentNode = messageNode.parent
        if (parentNode != null) {
            for (i in 0 until parentNode.childCount) {
                val sibling = parentNode.getChild(i)
                if (sibling != null) {
                    val siblingViewId = sibling.viewIdResourceName ?: ""
                    if (siblingViewId.contains("reply", ignoreCase = true) ||
                        siblingViewId.contains("quoted", ignoreCase = true)) {
                        val replyText = findReplyTextInNode(sibling)
                        sibling.recycle()
                        if (replyText != null) {
                            parentNode.recycle()
                            return replyText
                        }
                    }
                    sibling.recycle()
                }
            }
            parentNode.recycle()
        }
        
    } catch (e: Exception) {
        Log.e(TAG, "Error finding reply context: ${e.message}")
    }
    
    return null
}

private fun findReplyTextInNode(node: AccessibilityNodeInfo): String? {
    // Search this node and immediate children for reply text
    val text = node.text?.toString()?.trim()
    if (!text.isNullOrBlank() && text.length in 1..200) {
        return text
    }
    
    for (i in 0 until node.childCount) {
        val child = node.getChild(i)
        if (child != null) {
            val childText = child.text?.toString()?.trim()
            child.recycle()
            if (!childText.isNullOrBlank() && childText.length in 1..200) {
                return childText
            }
        }
    }
    
    return null
}

    private fun handleWindowChange(className: String) {
    if (className.contains("MediaCaptureActivity", ignoreCase = true)) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastPostDetectionTime >= POST_DETECTION_DEBOUNCE_MS) {
            lastPostDetectionTime = currentTime
            notificationHelper.showInstagramPostAssistantNotification()
        }
    }

    val dmActivityPatterns = listOf(
        "DirectInboxActivity",
        "DirectThreadActivity",
        "DirectInboxFragment",
        "DirectThreadFragment",
        "ModalActivity",
        "direct",
        "inbox",
        "thread"
    )

    val mainActivityPatterns = listOf(
        "MainActivity",
        "MainTabActivity",
        "HomeActivity",
        "FeedActivity",
        "ProfileActivity",
        "ExploreActivity",
        "ReelsActivity"
    )

    when {
        dmActivityPatterns.any { pattern ->
            className.contains(pattern, ignoreCase = true)
        } -> {
            if (className.contains("ModalActivity", ignoreCase = true)) {
                ioScope.launch {
                    delay(800)
                    if (inspectUIForDMs()) {
                        if (!isInDirectMessages) {
                            isInDirectMessages = true
                            delay(1000)
                            detectChatOpen()

                            if (currentChatContact == null) {
                                captureContacts()
                            }
                        }
                    }
                }
            } else {
                if (!isInDirectMessages) {
                    isInDirectMessages = true
                    ioScope.launch {
                        delay(1500)
                        captureContacts()
                    }
                }

                if (className.contains("thread", ignoreCase = true)) {
                    detectChatOpen()
                }
            }
        }

        mainActivityPatterns.any { pattern ->
            className.contains(pattern, ignoreCase = true)
        } -> {
            if (isInDirectMessages) {
                isInDirectMessages = false
                currentChatContact = null
                textMessageCache.clear()
                mediaContentCache.clear()
                pendingReplyContext = null
                recentMessagesForReplyInference.clear()
            }
        }
    }
}

    private fun inspectUIForDMs(): Boolean {
        try {
            val rootNode = rootInActiveWindow ?: return false

            var isDMScreen = false
            var foundIndicators = mutableListOf<String>()

            val dmIndicators = listOf(
                "Type a message",
                "Message",
                "Direct",
                "thread",
                "message",
                "composer",
                "direct_thread",
                "inbox"
            )

            fun searchForDMIndicators(node: AccessibilityNodeInfo, depth: Int = 0) {
                if (depth > 15) return

                val text = node.text?.toString() ?: ""
                val contentDesc = node.contentDescription?.toString() ?: ""
                val viewId = node.viewIdResourceName ?: ""

                dmIndicators.forEach { indicator ->
                    if (text.contains(indicator, ignoreCase = true) ||
                        contentDesc.contains(indicator, ignoreCase = true) ||
                        viewId.contains(indicator, ignoreCase = true)) {
                        foundIndicators.add("$indicator (at depth $depth)")
                        isDMScreen = true
                    }
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { child ->
                        searchForDMIndicators(child, depth + 1)
                        child.recycle()
                    }
                }
            }

            searchForDMIndicators(rootNode)
            rootNode.recycle()
            return isDMScreen
        } catch (e: Exception) {
            Log.e(TAG, "Error inspecting UI for DMs: ${e.message}", e)
            return false
        }
    }

    private fun handleContentChange() {
        val currentTime = System.currentTimeMillis()

        ioScope.launch {
            if (currentChatContact != null) {
                if (currentTime - lastMessageCaptureTime >= MESSAGE_CAPTURE_DEBOUNCE_MS) {
                    lastMessageCaptureTime = currentTime
                    captureNewMessages()
                }
            } else {
                if (isInInboxView()) {
                    if (currentTime - lastContactCaptureTime >= CONTACT_CAPTURE_DEBOUNCE_MS) {
                        lastContactCaptureTime = currentTime
                        captureContacts()
                    }
                } else {
                    detectChatOpen()
                }
            }
        }
    }

    private fun isInInboxView(): Boolean {
        try {
            val rootNode = rootInActiveWindow ?: return false
            var hasInboxIndicators = false
            var hasChatIndicators = false

            val inboxIndicators = listOf("Direct", "Messages", "Chats")
            val chatIndicators = listOf("Type a message", "composer", "thread_composer")

            fun checkView(node: AccessibilityNodeInfo, depth: Int = 0) {
                if (depth > 10) return

                val text = node.text?.toString() ?: ""
                val viewId = node.viewIdResourceName ?: ""

                if (text.contains("Type a message", ignoreCase = true) ||
                    viewId.contains("composer", ignoreCase = true)) {
                    hasChatIndicators = true
                }

                if (!hasChatIndicators) {
                    inboxIndicators.forEach { indicator ->
                        if (text.equals(indicator, ignoreCase = true)) {
                            hasInboxIndicators = true
                        }
                    }
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { child ->
                        checkView(child, depth + 1)
                        child.recycle()
                    }
                }
            }

            checkView(rootNode)
            rootNode.recycle()

            val inInbox = !hasChatIndicators
            return inInbox

        } catch (e: Exception) {
            Log.e(TAG, "Error checking inbox view: ${e.message}")
            return false
        }
    }

fun exportDataToFile() {
    ioScope.launch {
        try {
            val contacts = contactDao.getAllContactsWithReadableTime()
            val messages = messageDao.getAllMessagesWithReadableTime()

            val exportFile = java.io.File(
                applicationContext.getExternalFilesDir(null),
                "instagram_data_export_${System.currentTimeMillis()}.txt"
            )

            exportFile.printWriter().use { out ->
                out.println("=== Instagram Monitor Data Export ===")
                out.println("Export Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
                out.println()

                out.println("=== CONTACTS (${contacts.size}) ===")
                contacts.forEach { contact ->
                    out.println("- ${contact.name} (captured: ${contact.readableTime})")
                }
                out.println()

                out.println("=== ALL MESSAGES (${messages.size}) ===")
                val groupedMessages = messages.groupBy { it.contactName }
                groupedMessages.forEach { (contactName, msgs) ->
                    out.println("\n[$contactName] - ${msgs.size} messages")
                    msgs.forEach { msg ->
                        val direction = if (msg.isSentByUser) "SENT" else "RECEIVED"
                        val assetLabel = msg.assetType.uppercase()
                        val content = if (msg.assetType == AssetType.TEXT) msg.messageText else "[${msg.assetType}]"
                        out.println("  [${msg.readableTime}] [$direction] [$assetLabel] $content")
                    }
                }
            }

            Log.d(TAG, "Data exported to: ${exportFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}")
        }
    }
}

    private fun detectChatOpen() {
        ioScope.launch {
            delay(500) // Wait a bit for chat to load

            // Only detect chat contact if we're actually in a chat (not the inbox)
            // This prevents capturing the user's own username from the inbox header
            if (isInInboxView()) {
                // Log.d(TAG, "In inbox view, skipping chat contact detection")
                currentChatContact = null
                return@launch
            }

            val contactName = findCurrentChatContact()
            Log.d(TAG, "🔍 CHAT CONTACT DETECTION: found='$contactName' current='$currentChatContact'")
            if (contactName != null && contactName != currentChatContact) {
                Log.d(TAG, "📱 OPENED CHAT WITH: '$contactName'")
                currentChatContact = contactName
                // Clear caches for this new chat
                textMessageCache.clear()
                mediaContentCache.clear()
                pendingReplyContext = null
                recentMessagesForReplyInference.clear()

                // Save contact if not already saved
                saveContact(contactName)

                // Auto-capture messages after chat loads
                delay(800) // Wait a bit longer for messages to load
                captureNewMessages()
                Log.d(TAG, "📨 Auto-captured messages on chat open")
            }
        }
    }

    private suspend fun captureContacts() {
        try {
            val rootNode = rootInActiveWindow ?: return
            val contactNames = findContactNames(rootNode)

            contactNames.forEach { name ->
                saveContact(name)
            }

            rootNode.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing contacts: ${e.message}")
        }
        return
    }
    
private fun sha256(s: String): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

private fun normalizeDesc(raw: String): String =
    raw.trim()
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .replace(Regex("^.+? sent (a|an) "), "sent ")

private fun buildFingerprint(
    contactName: String,
    assetType: String,
    isSentByUser: Boolean,
    messageText: String?,
    detectedDesc: String?,
    replyMessageID: Long?
): String {
    val base = when (assetType) {
        AssetType.TEXT -> "t|$isSentByUser|${messageText.orEmpty().trim()}"
        else -> {
            // media fingerprint should NOT use bounds (bounds change when you reopen/scroll)
            val d = normalizeDesc(detectedDesc.orEmpty())
            "m|$assetType|$isSentByUser|$d"
        }
    }

    // Include reply id if available so same text can exist once as reply and once normal (optional)
    val replyPart = replyMessageID?.let { "|r:$it" } ?: ""

    return sha256("$contactName|$base$replyPart")
}

private suspend fun captureNewMessages() {
    try {
        val rootNode = rootInActiveWindow ?: return
        val contactName = currentChatContact ?: "Unknown"

            // Detect and store reply context if present
            detectAndStoreReplyContext(rootNode, contactName)

            // Find all message nodes (text and media)
            val detectedMessages = findMessageNodes(rootNode)
            var newMessageCount = 0

            if (ULTRA_DEEP_LOGGING) {
                Log.d(TAG, "╔════════════════════════════════════════════╗")
                Log.d(TAG, "║ SCAN SUMMARY                               ║")
                Log.d(TAG, "╚════════════════════════════════════════════╝")
                Log.d(TAG, "  Contact: $contactName")
                Log.d(TAG, "  Detected messages: ${detectedMessages.size}")
                detectedMessages.forEachIndexed { index, msg ->
                    Log.d(TAG, "    ${index + 1}. [${ msg.assetType}] '${msg.text.take(30)}'")
                }
            }

            // DEBUG: If no messages found, dump UI tree to help troubleshoot
            // if (detectedMessages.isEmpty()) {
            //     Log.w(TAG, "⚠️ No messages found in UI tree. Dumping UI structure for debugging...")
            //     dumpUITree(rootNode, maxDepth = 8)
            // }

            val currentTime = System.currentTimeMillis()

            // Clean up old entries from caches (keep only last 60 seconds)
            val cacheCleanupThreshold = currentTime - maxOf(
                SENT_MESSAGE_DUPLICATE_WINDOW_MS,
                RECEIVED_MESSAGE_DUPLICATE_WINDOW_MS
            )
            textMessageCache.entries.removeIf { it.value < cacheCleanupThreshold }
            mediaContentCache.entries.removeIf { it.value < cacheCleanupThreshold }
            // Process each detected message
            detectedMessages.forEach { detected ->
                val assetType = detected.assetType

                // Determine if message is sent by user (needed for deduplication and storage)
                val isSentByUser = isMessageSentByUser(detected.node)

                // For text messages: store the actual text in messageText
                // For media messages: store EMPTY string in messageText (user requirement)
                val messageText = when (assetType) {
                        AssetType.TEXT -> detected.text

                        else -> ""                          // other media stays empty
                    }

                if (assetType == AssetType.TEXT && detected.text.isBlank()) {
                    // Skip blank text messages
                    detected.node.recycle()
                    return@forEach
                }

                var shouldSave = false
                val direction = if (isSentByUser) "SENT" else "RECEIVED"

                if (ULTRA_DEEP_LOGGING) {
                    Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    Log.d(TAG, "🔍 PROCESSING MESSAGE:")
                    Log.d(TAG, "  Text: '${detected.text.take(50)}'")
                    Log.d(TAG, "  Type: $assetType")
                    Log.d(TAG, "  Direction: $direction")
                }

                if (assetType == AssetType.TEXT) {
                    // For text: use time-based deduplication
                    // IMPORTANT: Include isSentByUser to distinguish between:
                    // - "Hi" sent by friend
                    // - "Hi" sent by user
                    // Use longer window for RECEIVED messages (they shouldn't duplicate)
                    // Use shorter window for SENT messages (you might send same text again)
                    val textKey = "$contactName-${detected.text}-$isSentByUser"
                    val lastSeenTime = textMessageCache[textKey]
                    val duplicateWindow = if (isSentByUser) {
                        SENT_MESSAGE_DUPLICATE_WINDOW_MS
                    } else {
                        RECEIVED_MESSAGE_DUPLICATE_WINDOW_MS
                    }

                    if (ULTRA_DEEP_LOGGING) {
                        Log.d(TAG, "  Deduplication key: '$textKey'")
                        Log.d(TAG, "  Last seen: ${if (lastSeenTime != null) "${currentTime - lastSeenTime}ms ago" else "never"}")
                        Log.d(TAG, "  Window: ${duplicateWindow}ms (${if (isSentByUser) "SENT" else "RECEIVED"})")
                    }

                    if (lastSeenTime == null || (currentTime - lastSeenTime) > duplicateWindow) {
                        // Either never seen before, or seen long ago → safe to save
                        shouldSave = true
                        textMessageCache[textKey] = currentTime
                        if (ULTRA_DEEP_LOGGING) {
                            Log.d(TAG, "  ✅ DECISION: SAVE (${if (lastSeenTime == null) "first time" else "outside window"})")
                        }
                    } else {
                        // Seen recently → likely scroll duplicate, skip
                        Log.d(TAG, "  ⏭️ DECISION: SKIP - seen ${currentTime - lastSeenTime}ms ago (within ${duplicateWindow}ms window)")
                    }
                } else {
                    // For media: use content description to uniquely identify each media item
                    // This allows us to:
                    // - Skip the SAME media when detected again due to scrolling
                    // - Save DIFFERENT media even if sent close together
                    val bounds = android.graphics.Rect()
detected.node.getBoundsInScreen(bounds)

// Normalize content description (Instagram often repeats the same string or includes usernames)
val rawDesc = detected.text.orEmpty().trim()
val normalizedDesc = rawDesc
    .lowercase()
    .replace(Regex("\\s+"), " ")
    .replace(Regex("^.+? sent (a|an) "), "sent ")

val mediaKey = if (normalizedDesc.isNotBlank()) {
    "$contactName-$assetType-$isSentByUser-$normalizedDesc"
} else {
    // If desc is empty, DON'T try to dedupe here by bounds; let DB fingerprint handle it
    "$contactName-$assetType-$isSentByUser-no_desc"
}

val lastSeenTime = mediaContentCache[mediaKey]
if (lastSeenTime == null || (currentTime - lastSeenTime) > RECEIVED_MESSAGE_DUPLICATE_WINDOW_MS) {
    shouldSave = true
    mediaContentCache[mediaKey] = currentTime
} else {
    Log.d(TAG, "⏭️ SKIP [$assetType] duplicate ${currentTime - lastSeenTime}ms ago key='$mediaKey'")
}
}

                if (shouldSave) {
                    val timestamp = System.currentTimeMillis()

                    // Get Y position of this message for reply inference
                    val bounds = android.graphics.Rect()
                    detected.node.getBoundsInScreen(bounds)
                    val messageYPosition = bounds.top

                    // Determine reply context based on message direction
                    var replyMessage: String? = null
                    var replyMessageID: Long? = null

                    if (isSentByUser) {
                        // For SENT messages: use the global reply context from compose area
                        if (pendingReplyContext != null) {
                            replyMessage = pendingReplyContext!!.first
                            replyMessageID = pendingReplyContext!!.second
                            Log.d(TAG, "  💬 APPLYING REPLY CONTEXT (SENT): '$replyMessage' (ID: $replyMessageID)")
                            // DON'T clear the context after using it - allow multiple messages to reply to the same message
                            // Context will expire naturally after REPLY_CONTEXT_TIMEOUT_MS (60s)
                        } else if (ULTRA_DEEP_LOGGING) {
                            Log.d(TAG, "  ℹ️ No pending reply context")
                        }
                    } else {
                        // For RECEIVED messages: try multiple methods to detect replies

                        // Method 1: Check if the message bubble contained a quoted message (Instagram's native reply UI)
                        if (detected.quotedMessageText != null) {
                            if (ULTRA_DEEP_LOGGING) {
                                Log.d(TAG, "  🔍 RECEIVED message has quoted text: '${detected.quotedMessageText}'")
                            }
                            // Look up the original message in database
                            val originalMessage = messageDao.findMessageByText(contactName, detected.quotedMessageText)
                            if (originalMessage != null) {
                                replyMessage = detected.quotedMessageText
                                replyMessageID = originalMessage.id
                                Log.d(TAG, "  💬 APPLYING REPLY CONTEXT (RECEIVED - Native UI): '$replyMessage' (ID: $replyMessageID)")
                            } else if (ULTRA_DEEP_LOGGING) {
                                Log.d(TAG, "  ⚠️ Quoted message not found in DB: '${detected.quotedMessageText}'")
                            }
                        }

                        // Method 2: If no quoted text found, try position-based inference
                        if (replyMessage == null) {
                            val inferredReply = inferReplyFromPosition(messageYPosition, timestamp, contactName)
                            if (inferredReply != null) {
                                replyMessage = inferredReply.first
                                replyMessageID = inferredReply.second
                                Log.d(TAG, "  💬 APPLYING REPLY CONTEXT (RECEIVED - Position Inference): '$replyMessage' (ID: $replyMessageID)")
                            }
                        }
                    }

                        val fp = buildFingerprint(
                            contactName = contactName,
                            assetType = assetType,
                            isSentByUser = isSentByUser,
                            messageText = messageText,
                            detectedDesc = if (assetType == AssetType.TEXT) null else detected.text,
                            replyMessageID = replyMessageID
                        )

                        // ✅ hard DB dedupe (persists forever)
                        val existingId = messageDao.getExistingIdByFingerprint(contactName, fp)
                        if (existingId != null) {
                            Log.d(TAG, "⏭️ SKIP (DB duplicate) [$assetType] existingId=$existingId fp=$fp")
                            detected.node.recycle()
                            return@forEach
                        }
                    val message = Message(
                        id = 0,
                        contactName = contactName,
                        messageText = messageText,  // Empty for media, actual text for text messages
                        timestamp = timestamp,
                        isSentByUser = isSentByUser,
                        assetType = assetType,
                        replyMessage = replyMessage,
                        replyMessageID = replyMessageID,
                        fingerprint = fp
                    )

                    if (ULTRA_DEEP_LOGGING) {
                        Log.d(TAG, "  💾 SAVING TO DATABASE:")
                        Log.d(TAG, "    contactName: $contactName")
                        Log.d(TAG, "    messageText: '${messageText.take(50)}'")
                        Log.d(TAG, "    assetType: $assetType")
                        Log.d(TAG, "    isSentByUser: $isSentByUser")
                        Log.d(TAG, "    replyMessage: ${replyMessage ?: "null"}")
                        Log.d(TAG, "    replyMessageID: ${replyMessageID ?: "null"}")
                        Log.d(TAG, "    fingerprint: $fp")
                    }

                    val messageId = messageDao.insert(message)
                    newMessageCount++

                    val preview = if (assetType == AssetType.TEXT) messageText.take(50) else detected.text.take(50)
                    val replyInfo = if (replyMessageID != null) " [REPLY TO: $replyMessageID]" else ""
                    Log.d(TAG, "  ✅ SAVED [$assetType] [$direction]: '$preview' (DB ID: $messageId)$replyInfo")

                    // Store this message for position-based reply inference
                    // Only store text messages (media messages don't typically get replied to by text)
                    if (assetType == AssetType.TEXT && messageText.isNotBlank()) {
                        recentMessagesForReplyInference.add(
                            RecentMessageInfo(
                                messageText = messageText,
                                messageId = messageId,
                                timestamp = timestamp,
                                yPosition = messageYPosition,
                                isSentByUser = isSentByUser
                            )
                        )
                        if (ULTRA_DEEP_LOGGING) {
                            Log.d(TAG, "  📌 Stored for reply inference: Y=$messageYPosition, sent=$isSentByUser")
                        }
                    }

                    if (ULTRA_DEEP_LOGGING) {
                        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    }
                }

                // Recycle the node after use
                detected.node.recycle()
            }

        rootNode.recycle()
    } catch (e: Exception) {
        Log.e(TAG, "Error capturing messages: ${e.message}", e)
    }
}

    private fun dumpUITree(node: AccessibilityNodeInfo?, depth: Int = 0, maxDepth: Int = 10) {
        if (node == null || depth > maxDepth) return

        val indent = "  ".repeat(depth)
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName?.substringAfterLast(':') ?: ""
        val className = node.className?.toString()?.substringAfterLast('.') ?: ""

        if (text.isNotEmpty() || viewId.isNotEmpty() || contentDesc.isNotEmpty()) {
            val info = StringBuilder()
            info.append("$indent[$className]")
            if (viewId.isNotEmpty()) info.append(" ID:$viewId")
            if (text.isNotEmpty()) info.append(" Text:'${text.take(50)}'")
            if (contentDesc.isNotEmpty()) info.append(" Desc:'${contentDesc.take(50)}'")

            Log.d(TAG, info.toString())
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                dumpUITree(child, depth + 1, maxDepth)
                child.recycle()
            }
        }
    }

    private suspend fun saveContact(contactName: String) {
        try {
            val existing = contactDao.getContactByName(contactName)
            if (existing == null) {
                val contact = Contact(
                    id = 0,
                    name = contactName,
                    timestamp = System.currentTimeMillis()
                )
                contactDao.insert(contact)
                Log.d(TAG, "👤 SAVED NEW CONTACT: '$contactName'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving contact: ${e.message}")
        }
    }

    private fun findContactNames(rootNode: AccessibilityNodeInfo): List<String> {
    val contacts = mutableListOf<String>()

    val contactPatterns = listOf(
        "row_thread_user_name",
        "direct_thread_title",
        "title",
        "username",
        "name",
        "user_name",
        "thread_title",
        "inbox_row_username",
        "direct_inbox_row_user_name",
        "action_bar_title",
        "header_title"
    )

    val classPatterns = listOf(
        "TextView",
        "AppCompatTextView"
    )

    fun searchNodes(node: AccessibilityNodeInfo, depth: Int = 0) {
        if (node.text != null && node.text.isNotEmpty()) {
            val text = node.text.toString().trim()
            val viewId = node.viewIdResourceName ?: ""
            val className = node.className?.toString() ?: ""

            val matchesViewIdPattern = contactPatterns.any { pattern ->
                viewId.contains(pattern, ignoreCase = true)
            }

            val matchesClassPattern = classPatterns.any { pattern ->
                className.contains(pattern, ignoreCase = true)
            }

            val timePattern = Regex("^\\d{1,2}:\\d{2}$")
            val paginationPattern = Regex("^\\d+/\\d+$")

            val isValidContact = text.length in 3..30 &&
                !text.contains("Instagram", ignoreCase = true) &&
                !text.contains("Search", ignoreCase = true) &&
                !text.contains("Messages", ignoreCase = true) &&
                !text.contains("Direct", ignoreCase = true) &&
                !text.contains("New message", ignoreCase = true) &&
                !text.contains("Requests", ignoreCase = true) &&
                !text.contains("General", ignoreCase = true) &&
                !text.contains("Primary", ignoreCase = true) &&
                !text.contains("Tap and hold", ignoreCase = true) &&
                !text.contains("Seen", ignoreCase = true) &&
                !text.contains("Active", ignoreCase = true) &&
                !text.contains("Type a message", ignoreCase = true) &&
                !text.contains("For you", ignoreCase = true) &&
                !text.contains("Chats", ignoreCase = true) &&
                !text.contains("Channels", ignoreCase = true) &&
                !text.contains("Notes", ignoreCase = true) &&
                !text.contains("Groups", ignoreCase = true) &&
                !text.equals("Your story", ignoreCase = true) &&
                !text.equals("Your note", ignoreCase = true) &&
                !text.startsWith("Your ", ignoreCase = true) &&
                !text.contains("...", ignoreCase = true) &&
                !text.contains(" of ", ignoreCase = true) &&
                text.count { it == ' ' } <= 1 &&
                !text.all { it.isDigit() } &&
                !timePattern.matches(text) &&
                !paginationPattern.matches(text) &&
                text != "@"

            if ((matchesViewIdPattern || matchesClassPattern) && isValidContact) {
                contacts.add(text)
            }
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                searchNodes(child, depth + 1)
                child.recycle()
            }
        }
    }

    searchNodes(rootNode)
    return contacts.distinct()
}

    private fun findCurrentChatContact(): String? {
    return try {
        val rootNode = rootInActiveWindow ?: return null

        var contactName: String? = null
        
        // Updated patterns for chat header - ONLY specific chat header titles
        val headerPatterns = listOf(
            "header_title",           // Contact name in chat header
            "thread_title",           // Thread title
            "direct_thread_header_title"
        )

        // ViewIDs to EXCLUDE (these show the logged-in user's name, not contact)
        val excludedHeaderPatterns = listOf(
            "igds_action_bar_title",  // This shows YOUR username, not the contact
            "action_bar_title"        // General action bar (can show your username)
        )
        
        fun searchForContact(node: AccessibilityNodeInfo, depth: Int = 0) {
            if (contactName != null) return

            if (node.text != null && node.text.isNotEmpty()) {
                val text = node.text.toString().trim()
                val viewId = node.viewIdResourceName ?: ""
                
                // Check header patterns but EXCLUDE patterns that show logged-in user
                val isExcludedHeader = excludedHeaderPatterns.any { pattern ->
                    viewId.contains(pattern, ignoreCase = true)
                }
                val isHeader = !isExcludedHeader && headerPatterns.any { pattern ->
                    viewId.contains(pattern, ignoreCase = true)
                }

                val isValidContactName = text.length in 1..30 &&
                    !text.contains("Type", ignoreCase = true) &&
                    !text.contains("Send", ignoreCase = true) &&
                    !text.contains("Like", ignoreCase = true) &&
                    !text.contains("Camera", ignoreCase = true) &&
                    !text.contains("Voice", ignoreCase = true) &&
                    !text.contains("Active", ignoreCase = true) &&
                    !text.contains("Online", ignoreCase = true) &&
                    !text.contains("ago", ignoreCase = true) &&
                    !text.contains("For you", ignoreCase = true) &&  // Instagram tabs
                    !text.contains("Chats", ignoreCase = true) &&     // Tab label
                    !text.contains("Channels", ignoreCase = true) &&  // Tab label
                    !text.contains("Notes", ignoreCase = true) &&     // Tab label
                    !text.contains("Groups", ignoreCase = true) &&    // Tab label
                    !text.contains("Messages", ignoreCase = true) &&  // Tab label
                    !text.contains("Direct", ignoreCase = true) &&    // Tab label
                    !text.contains("Instagram", ignoreCase = true) && // App name
                    !text.contains("Search", ignoreCase = true) &&    // Search UI
                    !text.contains("Create", ignoreCase = true) &&    // UI buttons like "Create your avatar"
                    !text.contains("Avatar", ignoreCase = true) &&    // Avatar UI
                    !text.contains("Sticker", ignoreCase = true) &&   // Sticker UI
                    !text.contains("GIF", ignoreCase = true) &&       // GIF picker
                    !text.contains("Photo", ignoreCase = true) &&     // Photo picker
                    !text.contains("Gallery", ignoreCase = true) &&   // Gallery UI
                    !text.all { it.isDigit() }

                if (isHeader && isValidContactName) {
                    contactName = text
                    Log.d(TAG, "✓ HEADER CONTACT: '$text' (viewId: ${viewId.substringAfterLast(':')})")
                    return
                } else if (isHeader && !isValidContactName) {
                    Log.d(TAG, "⊘ REJECTED HEADER: '$text' (viewId: ${viewId.substringAfterLast(':')})")
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    searchForContact(child, depth + 1)
                    child.recycle()
                }
            }
        }

        searchForContact(rootNode)
        rootNode.recycle()
        contactName
    } catch (e: Exception) {
        Log.e(TAG, "Error finding chat contact: ${e.message}")
        null
    }
}

    /**
     * Detects if user is replying to a message by checking for the reply bar.
     * Looks up the original message in the database and stores the reply context.
     */
    private suspend fun detectAndStoreReplyContext(rootNode: AccessibilityNodeInfo, contactName: String) {
        var quotedMessageText: String? = null

        fun searchForQuotedMessage(node: AccessibilityNodeInfo) {
            val viewId = node.viewIdResourceName ?: ""
            val viewIdShort = viewId.substringAfterLast('/')?.substringAfterLast(':') ?: ""

            // Look for the quoted_message_subtitle which contains the replied-to message text
            if (viewIdShort.equals("quoted_message_subtitle", ignoreCase = true)) {
                val text = node.text?.toString()
                if (!text.isNullOrBlank()) {
                    quotedMessageText = text
                    return  // Found it, stop searching
                }
            }

            // Recursively search children
            for (i in 0 until node.childCount) {
                if (quotedMessageText != null) return  // Already found, stop
                node.getChild(i)?.let { child ->
                    searchForQuotedMessage(child)
                    child.recycle()
                }
            }
        }

        searchForQuotedMessage(rootNode)

        val currentTime = System.currentTimeMillis()

        if (ULTRA_DEEP_LOGGING) {
            Log.d(TAG, "╔════════════════════════════════════════════╗")
            Log.d(TAG, "║ REPLY CONTEXT DETECTION                    ║")
            Log.d(TAG, "╚════════════════════════════════════════════╝")
            Log.d(TAG, "  Quoted message found: ${quotedMessageText ?: "none"}")
            Log.d(TAG, "  Current pending context: ${pendingReplyContext?.let { "'${it.first}' (ID: ${it.second}, age: ${currentTime - it.third}ms)" } ?: "none"}")
        }

        // If reply bar found, look up the original message and store context with timestamp
        if (!quotedMessageText.isNullOrBlank()) {
            val originalMessage = messageDao.findMessageByText(contactName, quotedMessageText)
            if (ULTRA_DEEP_LOGGING) {
                Log.d(TAG, "  DB lookup for '$quotedMessageText': ${if (originalMessage != null) "FOUND (ID: ${originalMessage.id})" else "NOT FOUND"}")
            }

            if (originalMessage != null) {
                // Only update if we don't already have this context
                if (pendingReplyContext == null || pendingReplyContext!!.first != quotedMessageText) {
                    pendingReplyContext = Triple(quotedMessageText, originalMessage.id, currentTime)
                    Log.d(TAG, "  🔄 REPLY CONTEXT STORED: '$quotedMessageText' (ID: ${originalMessage.id})")
                } else if (ULTRA_DEEP_LOGGING) {
                    Log.d(TAG, "  ℹ️ Context unchanged (already stored)")
                }
            } else {
                Log.w(TAG, "  ⚠️ Reply bar detected but original message not found in DB: '$quotedMessageText'")
            }
        } else {
            // No reply bar visible - check if existing context has timed out
            pendingReplyContext?.let { context ->
                val age = currentTime - context.third
                if (ULTRA_DEEP_LOGGING) {
                    Log.d(TAG, "  No reply bar visible, context age: ${age}ms (timeout: ${REPLY_CONTEXT_TIMEOUT_MS}ms)")
                }
                if (age > REPLY_CONTEXT_TIMEOUT_MS) {
                    Log.d(TAG, "  ⏱️ Reply context EXPIRED (${age}ms old)")
                    pendingReplyContext = null
                } else if (ULTRA_DEEP_LOGGING) {
                    Log.d(TAG, "  ✓ Context preserved (still within timeout)")
                }
                // Don't clear if still within timeout - message might still be detected
            }
        }
    }

    /**
     * Infers reply context for RECEIVED messages based on position heuristics.
     * If a RECEIVED message appears shortly after and BELOW a SENT message,
     * it's likely a reply to that message.
     */
    private fun inferReplyFromPosition(messageYPosition: Int, messageTimestamp: Long, contactName: String): Pair<String, Long>? {
        // Clean up old entries (older than inference window)
        val cutoffTime = messageTimestamp - REPLY_INFERENCE_WINDOW_MS
        recentMessagesForReplyInference.removeAll { it.timestamp < cutoffTime }

        // Find recent SENT messages (by user) that are ABOVE this received message
        val candidateMessages = recentMessagesForReplyInference
            .filter { it.isSentByUser } // Only look at user's messages
            .filter { it.yPosition < messageYPosition } // Must be ABOVE (smaller Y)
            .filter { messageTimestamp - it.timestamp < REPLY_INFERENCE_WINDOW_MS } // Within time window
            .sortedByDescending { it.timestamp } // Most recent first

        if (candidateMessages.isNotEmpty()) {
            val mostRecentAbove = candidateMessages.first()
            val timeDiff = messageTimestamp - mostRecentAbove.timestamp

            if (ULTRA_DEEP_LOGGING) {
                Log.d(TAG, "  🔍 POSITION-BASED REPLY INFERENCE:")
                Log.d(TAG, "    Received message Y: $messageYPosition")
                Log.d(TAG, "    Found ${candidateMessages.size} candidate(s) above")
                Log.d(TAG, "    Best candidate: '${mostRecentAbove.messageText}' (Y: ${mostRecentAbove.yPosition}, ${timeDiff}ms ago)")
            }

            // If the time difference is reasonable (not too long), infer it's a reply
            if (timeDiff < REPLY_INFERENCE_WINDOW_MS) {
                Log.d(TAG, "  💡 INFERRED REPLY: This received message is likely replying to '${mostRecentAbove.messageText}'")
                return Pair(mostRecentAbove.messageText, mostRecentAbove.messageId)
            }
        } else if (ULTRA_DEEP_LOGGING) {
            Log.d(TAG, "  ℹ️ No recent SENT messages found above this RECEIVED message for reply inference")
        }

        return null
    }

    /**
     * Dumps the UI hierarchy of a node for debugging (non-recursive, compact format)
     */
    private fun dumpNodeHierarchy(node: AccessibilityNodeInfo, prefix: String = "", maxDepth: Int = 6) {
        if (maxDepth <= 0) return

        var current: AccessibilityNodeInfo? = node
        var depth = 0
        val hierarchy = mutableListOf<String>()

        // Walk up ancestors
        while (current != null && depth < 6) {
            val viewId = current.viewIdResourceName ?: ""
            val viewIdShort = viewId.substringAfterLast('/').substringAfterLast(':')
            val text = current.text?.toString() ?: ""
            val className = current.className?.toString()?.substringAfterLast('.') ?: ""

            val info = buildString {
                append(className)
                if (viewIdShort.isNotBlank()) append("[$viewIdShort]")
                if (text.isNotBlank() && text.length <= 20) append("='$text'")
            }

            if (info.isNotBlank()) {
                hierarchy.add(0, info) // Add to beginning to show from root to leaf
            }

            current = current.parent
            depth++
        }

        Log.d(TAG, "$prefix ${hierarchy.joinToString(" > ")}")

        // Also dump direct children
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                val viewId = child.viewIdResourceName ?: ""
                val viewIdShort = viewId.substringAfterLast('/').substringAfterLast(':')
                val text = child.text?.toString() ?: ""
                val className = child.className?.toString()?.substringAfterLast('.') ?: ""

                if (viewIdShort.isNotBlank() || text.isNotBlank()) {
                    Log.d(TAG, "  $prefix   CHILD[$i]: $className[$viewIdShort] text='${text.take(30)}'")
                }
                child.recycle()
            }
        }
    }

    /**
     * Detects if a message bubble contains a quoted message (for received replies).
     * Returns the quoted message text if found, null otherwise.
     */
    private fun findQuotedMessageInBubble(messageNode: AccessibilityNodeInfo): String? {
        var quotedText: String? = null

        // Search the message node and its siblings/ancestors for quoted message indicators
        var current: AccessibilityNodeInfo? = messageNode.parent
        var depth = 0

        while (current != null && depth < 8) {
            // Check this node and its children for quoted message text
            fun searchForQuoted(node: AccessibilityNodeInfo, searchDepth: Int = 0) {
                if (quotedText != null || searchDepth > 5) return

                val viewId = node.viewIdResourceName ?: ""
                val viewIdShort = viewId.substringAfterLast('/').substringAfterLast(':')
                val text = node.text?.toString() ?: ""
                val className = node.className?.toString() ?: ""

                // Look for common quoted message viewIds in received message bubbles
                // These are different from the compose reply bar
                val isQuotedMessageIndicator = viewIdShort.equals("quoted_message_subtitle", ignoreCase = true) ||
                                               viewIdShort.equals("message_quoted_content", ignoreCase = true) ||
                                               viewIdShort.equals("replied_message_text", ignoreCase = true) ||
                                               viewIdShort.equals("quoted_text", ignoreCase = true) ||
                                               viewIdShort.equals("message_reply_snippet", ignoreCase = true)

                // Also check for TextView with small text size (quoted messages typically use smaller font)
                // inside a message bubble but not the main message text
                if (isQuotedMessageIndicator && text.isNotBlank()) {
                    quotedText = text
                    if (ULTRA_DEEP_LOGGING) {
                        Log.d(TAG, "  🔍 Found quoted message in bubble: '$text' (viewId: $viewIdShort)")
                    }
                    return
                }

                // Recursively search children
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { child ->
                        searchForQuoted(child, searchDepth + 1)
                        child.recycle()
                    }
                }
            }

            searchForQuoted(current)

            current = current.parent
            depth++
        }

        // If we didn't find anything and ultra deep logging is on, dump the hierarchy for debugging
        if (quotedText == null && ULTRA_DEEP_LOGGING) {
            Log.d(TAG, "  ⚠️ NO QUOTED MESSAGE FOUND - Dumping UI hierarchy:")
            dumpNodeHierarchy(messageNode, "    ")
        }

        return quotedText
    }

    /**
     * Checks if a node is inside the reply bar (quoted message preview).
     * These should NOT be detected as new messages.
     */
    private fun isInsideReplyBar(node: AccessibilityNodeInfo): Boolean {
    var current: AccessibilityNodeInfo? = node
    var depth = 0

    var hasComposerAncestor = false

    while (current != null && depth < 12) {
        val viewId = current.viewIdResourceName ?: ""
        val viewIdShort = viewId.substringAfterLast('/').substringAfterLast(':')
        val className = current.className?.toString() ?: ""

        // ✅ Anchor: if we are near/inside the message input area, treat as composer context
        if (
            viewIdShort.contains("composer", ignoreCase = true) ||
            viewIdShort.contains("thread_composer", ignoreCase = true) ||
            viewIdShort.contains("row_thread_composer", ignoreCase = true) ||
            viewIdShort.contains("composer_edittext", ignoreCase = true) ||
            className.contains("EditText", ignoreCase = true)
        ) {
            hasComposerAncestor = true
        }

        // ✅ Only exclude quoted/reply nodes if they are inside composer area
        if (hasComposerAncestor) {
            if (
                viewIdShort.equals("message_reply_bar", ignoreCase = true) ||
                viewIdShort.equals("quoted_message_subtitle", ignoreCase = true) ||
                viewIdShort.equals("quoted_message_info", ignoreCase = true) ||
                viewIdShort.equals("quoted_message_container", ignoreCase = true)
            ) {
                if (ULTRA_DEEP_LOGGING) {
                    Log.d(TAG, "🚫 NODE IS INSIDE COMPOSER REPLY BAR: ancestor='$viewIdShort' depth=$depth")
                }
                return true
            }
        }

        current = current.parent
        depth++
    }

    return false
}


fun nodeContainsGiftKeyword(n: AccessibilityNodeInfo?, depth: Int = 0): Boolean {
    if (n == null || depth > 6) return false
    val t = n.text?.toString()?.lowercase() ?: ""
    val d = n.contentDescription?.toString()?.lowercase() ?: ""
    val id = (n.viewIdResourceName ?: "").lowercase()

    // add more keywords if your device language differs (DE: "geschenk", etc.)
    val hit = id.contains("gift") ||
            d.contains("gift") || d.contains("sent a gift") ||
            t.contains("gift") || t.contains("sent a gift")

    if (hit) return true

    for (i in 0 until n.childCount) {
        val c = n.getChild(i)
        val found = nodeContainsGiftKeyword(c, depth + 1)
        c?.recycle()
        if (found) return true
    }
    return false
}




    private fun findMessageNodes(rootNode: AccessibilityNodeInfo): List<DetectedMessage> {
        val messages = mutableListOf<DetectedMessage>()
        val processedMediaIds = mutableSetOf<String>() // Track media to avoid duplicates within same scan

        // Patterns that indicate actual message content nodes (text messages)
        val messageViewIdPatterns = listOf(
            "direct_text_message_text_view",
            "message_content"
        )

        // ONLY these viewIds contain ACTUAL media messages
        val validMediaViewIds = listOf(
            "animated_image",    // Stickers and GIFs
            "media_flat",        // Photos and videos
            "media_container",   // Photos and videos (FrameLayout container with content desc)
            "message_content_voice_playback_timer",   // Voice messages (TextView with this specific ID)
        )

        // ViewIDs to EXCLUDE (not messages)
        val excludedViewIds = listOf(
            "message_status",
            "timestamp",
            "composer_edittext",  // Message input field
            "row_thread_composer_edittext",  // Message input field
            "seen_indicator",
            "status",
            "hint",
            "placeholder",
            "avatar",  // Profile pictures
            "profile",
            "action_bar",  // Header elements
            "toolbar",
            "button",  // UI buttons
            "icon",
            "row_thread_composer_voice"  // Voice record button (not actual voice message)
        )

        // Content descriptions to EXCLUDE (UI elements, not media messages)
        // Use specific phrases to avoid false positives
        val excludedContentDesc = listOf(
            "navigate up",       // Back button
            "more options",
            "camera",
            "gallery",
            "send message",
            "like message",
            "liked",
            "audio call",        // Call button
            "video call",        // Call button
            "press and hold"     // Voice record hint
        )

        fun searchNodes(node: AccessibilityNodeInfo, depth: Int = 0) {
            val viewId = node.viewIdResourceName ?: ""
            val className = node.className?.toString() ?: ""
            val contentDesc = node.contentDescription?.toString() ?: ""
            val text = node.text?.toString() ?: ""
            // Extract just the ID name: "com.instagram.android:id/animated_image" -> "animated_image"
            // or "id/animated_image" -> "animated_image"
            val viewIdShort = viewId.substringAfterLast('/').substringAfterLast(':')
            val looksLikeGift = nodeContainsGiftKeyword(node)

                        if (looksLikeGift) {
                            // Use position to avoid duplicates within the same scan
                            val bounds = android.graphics.Rect()
                            node.getBoundsInScreen(bounds)
                            val giftId = "gift-${bounds.top}-${bounds.left}"

                            if (!processedMediaIds.contains(giftId)) {
                                processedMediaIds.add(giftId)

                                // Keep detected.text as contentDesc for logging/dedup keys;
                                // DB messageText will be set to null in captureNewMessages()
                                val giftDesc = if (contentDesc.isNotBlank()) contentDesc else "Gift"

                                val quotedMessageText = findQuotedMessageInBubble(node)
                                messages.add(
                                    DetectedMessage(
                                        text = giftDesc,
                                        assetType = AssetType.GIFT,
                                        node = AccessibilityNodeInfo.obtain(node),
                                        quotedMessageText = quotedMessageText
                                    )
                                )
                                Log.d(TAG, "🎁 GIFT DETECTED: '$giftDesc' at pos(${bounds.left},${bounds.top})")
                            }
                             return
                        }

            // EXCLUDE nodes with specific ViewIDs (timestamps, status, etc.)
            val isExcludedViewId = excludedViewIds.any { excluded ->
                viewId.contains(excluded, ignoreCase = true)
            }

            if (isExcludedViewId) {
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { child ->
                        searchNodes(child, depth + 1)
                        child.recycle()
                    }
                }
                return
            }

            // LOG ALL views with interesting viewIds for debugging (to find image/video viewIds)
            val classShort = className.substringAfterLast('.')

            // Log views that might be media-related
            val mediaRelatedKeywords = listOf("media", "image", "video", "photo", "picture", "thumbnail", "preview", "playback", "player")
            val isMediaRelatedViewId = mediaRelatedKeywords.any { keyword ->
                viewIdShort.contains(keyword, ignoreCase = true)
            }

            if (isMediaRelatedViewId && viewIdShort.isNotBlank()) {
                Log.d(TAG, "📷 MEDIA-RELATED VIEW: viewId='$viewIdShort' | class='$classShort' | desc='${contentDesc.take(50)}'")
            }

            // Log ImageViews
            if (className.contains("ImageView", ignoreCase = true)) {
                val hasDesc = contentDesc.isNotBlank()
                if (hasDesc) {
                    Log.d(TAG, "🔍 IMAGEVIEW: desc='$contentDesc' | viewId='$viewIdShort'")
                } else if (viewIdShort.isNotBlank() && !viewIdShort.contains("avatar") && !viewIdShort.contains("icon")) {
                    Log.d(TAG, "🔍 IMAGEVIEW (no desc): viewId='$viewIdShort'")
                }
            }

            // Log VideoViews or any view with "video" in class name
            if (className.contains("Video", ignoreCase = true) || className.contains("Player", ignoreCase = true)) {
                Log.d(TAG, "🎬 VIDEO VIEW: viewId='$viewIdShort' | class='$classShort' | desc='${contentDesc.take(50)}'")
            }

            // Check for MEDIA messages - ONLY from specific valid viewIds
            val matchesValidMediaViewId = validMediaViewIds.any { pattern ->
                viewIdShort.equals(pattern, ignoreCase = true)
            }

            // ONLY accept media from valid viewIds
            // Note: media_container is used for both photos and videos - differentiated by content desc
            val isMediaNode = matchesValidMediaViewId

            // Special case: Voice messages (message_content_voice_playback_timer) have no content description
            if (isMediaNode && viewIdShort.equals("message_content_voice_playback_timer", ignoreCase = true)) {
                // Use position-based deduplication like other media types
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                // Create unique identifier using position
                val mediaId = "voice_note-${bounds.top}-${bounds.left}"
                if (!processedMediaIds.contains(mediaId)) {
                    processedMediaIds.add(mediaId)
                    Log.d(TAG, "🎤 VOICE MESSAGE DETECTED: viewId='$viewIdShort' pos=(${bounds.left},${bounds.top})")
                    // Use generic text for database duplicate detection
                    val voiceText = "Voice message"
                    // Check for quoted message in bubble
                    val quotedMessageText = findQuotedMessageInBubble(node)
                    messages.add(DetectedMessage(
                        text = voiceText,
                        assetType = AssetType.VOICE_NOTE,
                        node = AccessibilityNodeInfo.obtain(node),
                        quotedMessageText = quotedMessageText
                    ))
                    Log.d(TAG, "✓ DETECTED [voice_note]: '$voiceText'${if (quotedMessageText != null) " (reply to: '$quotedMessageText')" else ""}")
                }
            }

            if (isMediaNode && (contentDesc.isNotBlank() || viewIdShort.equals("animated_image", ignoreCase = true))) {

    // If animated_image has no desc, treat it as GIF (or STICKER if your UI indicates sticker)
    val contentDescLower = contentDesc.lowercase()
    val assetType = when {
        viewIdShort.equals("animated_image", ignoreCase = true) && contentDesc.isBlank() -> AssetType.GIF
        contentDescLower.contains("sent a sticker") || contentDescLower.startsWith("sticker") -> AssetType.STICKER
        contentDescLower.contains("sent a gif") -> AssetType.GIF
        contentDescLower.contains("sent a video") || contentDescLower.contains("sent a reel") -> AssetType.VIDEO
        contentDescLower.contains("sent a voice") || contentDescLower.contains("voice message") -> AssetType.VOICE_NOTE
        contentDescLower.contains("sent a photo") || contentDescLower.contains("sent a image") || contentDescLower.contains("sent an image") -> AssetType.IMAGE
        else -> null
    }

    if (assetType != null) {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        val mediaId = "$assetType-${bounds.top}-${bounds.left}"
        if (!processedMediaIds.contains(mediaId)) {
            processedMediaIds.add(mediaId)
            val quotedMessageText = findQuotedMessageInBubble(node)

            // if no desc, store a small placeholder for logging/dedup signature
            val descForKey = if (contentDesc.isNotBlank()) contentDesc else "animated_image"
            messages.add(
                DetectedMessage(
                    text = descForKey,
                    assetType = assetType,
                    node = AccessibilityNodeInfo.obtain(node),
                    quotedMessageText = quotedMessageText
                )
            )
            Log.d(TAG, "✓ DETECTED [$assetType]: '$descForKey' at pos(${bounds.left},${bounds.top})")
        }
    }
}

            // Check for TEXT messages
            if (node.text != null && node.text.isNotEmpty()) {
                val text = node.text.toString().trim()

                // Check if this looks like a text message node by ViewID
                val matchesMessageViewId = messageViewIdPatterns.any { pattern ->
                    viewId.contains(pattern, ignoreCase = true)
                }

                val isTextView = className.contains("TextView", ignoreCase = true)
                val timePattern = Regex("^\\d{1,2}:\\d{2}$")

                val isNotUIElement = !text.contains("Type a message", ignoreCase = true) &&
                    !text.contains("Send", ignoreCase = true) &&
                    !text.contains("Like", ignoreCase = true) &&
                    !text.contains("Camera", ignoreCase = true) &&
                    !text.contains("Voice", ignoreCase = true) &&
                    !text.contains("Tap and hold", ignoreCase = true) &&
                    !text.contains("Seen just now", ignoreCase = true) &&
                    !text.contains("Seen", ignoreCase = true) &&
                    !text.contains("Active now", ignoreCase = true) &&
                    !text.contains("Active", ignoreCase = true) &&
                    !text.contains("Typing", ignoreCase = true) &&
                    !text.equals("Instagram", ignoreCase = true) &&
                    !text.equals("Search", ignoreCase = true) &&
                    !text.equals("Messages", ignoreCase = true) &&
                    !text.equals("Direct", ignoreCase = true) &&
                    !timePattern.matches(text)

                if (matchesMessageViewId && isTextView && isNotUIElement && text.length in 1..1000) {
                    if (ULTRA_DEEP_LOGGING) {
                        Log.d(TAG, "📋 TEXT NODE FOUND: '$text' | viewId='$viewIdShort'")
                    }

                    // CRITICAL: Exclude messages inside the reply bar (quoted message previews)
                    if (!isInsideReplyBar(node)) {
                        // Check if this message bubble contains a quoted message (for received replies)
                        val quotedMessageText = findQuotedMessageInBubble(node)

                        messages.add(DetectedMessage(
                            text = text,
                            assetType = AssetType.TEXT,
                            node = AccessibilityNodeInfo.obtain(node),
                            quotedMessageText = quotedMessageText
                        ))
                        if (ULTRA_DEEP_LOGGING) {
                            Log.d(TAG, "  ✅ ADDED to detection list${if (quotedMessageText != null) " (has quote: '$quotedMessageText')" else ""}")
                        }
                    } else {
                        Log.d(TAG, "  ⊘ EXCLUDED (inside reply bar): '$text'")
                    }
                } else if (ULTRA_DEEP_LOGGING && node.text != null && node.text.isNotEmpty()) {
                    val text = node.text.toString().trim()
                    val reason = when {
                        !matchesMessageViewId -> "viewId doesn't match message patterns"
                        !isTextView -> "not a TextView"
                        !isNotUIElement -> "is UI element"
                        text.length !in 1..1000 -> "text length out of range (${text.length})"
                        else -> "unknown"
                    }
                    Log.v(TAG, "  ⏭️ SKIPPED TEXT NODE: '$text' | Reason: $reason")
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    searchNodes(child, depth + 1)
                    child.recycle()
                }
            }
        }

        searchNodes(rootNode)
        return messages
    }

    private fun extractMessageText(node: AccessibilityNodeInfo): String {
        return node.text?.toString()?.trim() ?: ""
    }

    /**
     * Detects the type of media from an accessibility node.
     * Checks content descriptions and view IDs for media indicators.
     */
    private fun detectMediaType(node: AccessibilityNodeInfo): String {
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val className = node.className?.toString()?.lowercase() ?: ""

        // Check content description for media indicators
        return when {
            // GIF detection (check first as GIFs may also contain "image")
            contentDesc.contains("gif") || viewId.contains("gif") -> AssetType.GIF

            // Video detection
            contentDesc.contains("video") || viewId.contains("video") ||
            contentDesc.contains("reel") -> AssetType.VIDEO

            // Voice note detection
            contentDesc.contains("voice") || contentDesc.contains("audio") ||
            viewId.contains("voice") || viewId.contains("audio") ||
            contentDesc.contains("voice message") -> AssetType.VOICE_NOTE

            // Sticker detection
            contentDesc.contains("sticker") || viewId.contains("sticker") -> AssetType.STICKER

            // Image detection (photos)
            contentDesc.contains("photo") || contentDesc.contains("image") ||
            viewId.contains("photo") || viewId.contains("image") ||
            className.contains("imageview") -> AssetType.IMAGE

            // Default to text if no media indicators found
            else -> AssetType.TEXT
        }
    }

    /**
     * Checks if a node or its ancestors represent a media message.
     * Returns the asset type if media is detected.
     */
    private fun detectMediaTypeFromHierarchy(node: AccessibilityNodeInfo): String {
        // First check the node itself
        var assetType = detectMediaType(node)
        if (assetType != AssetType.TEXT) {
            return assetType
        }

        // Check parent nodes for media containers
        var currentNode: AccessibilityNodeInfo? = node.parent
        var depth = 0
        val maxDepth = 5

        while (currentNode != null && depth < maxDepth) {
            assetType = detectMediaType(currentNode)
            if (assetType != AssetType.TEXT) {
                currentNode.recycle()
                return assetType
            }

            val parent = currentNode.parent
            currentNode.recycle()
            currentNode = parent
            depth++
        }

        currentNode?.recycle()
        return AssetType.TEXT
    }

    private fun isMessageSentByUser(node: AccessibilityNodeInfo): Boolean {
        try {
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels

            var currentNode: AccessibilityNodeInfo? = node
            var depth = 0
            val maxDepth = 8

            var messageBubbleBounds: android.graphics.Rect? = null
            var bestBounds = android.graphics.Rect()
            node.getBoundsInScreen(bestBounds)

            while (currentNode != null && depth < maxDepth) {
                val contentDesc = currentNode.contentDescription?.toString() ?: ""
                if (contentDesc.contains("you sent", ignoreCase = true) ||
                    contentDesc.contains("sent by you", ignoreCase = true) ||
                    contentDesc.contains("your message", ignoreCase = true)) {
                    if (currentNode != node) currentNode.recycle()
                    return true
                }

                if (contentDesc.contains("sent you", ignoreCase = true) ||
                    contentDesc.contains("received", ignoreCase = true) ||
                    contentDesc.contains("their message", ignoreCase = true)) {
                    if (currentNode != node) currentNode.recycle()
                    return false
                }

                val viewId = currentNode.viewIdResourceName ?: ""
                if (viewId.contains("outgoing", ignoreCase = true) ||
                    viewId.contains("sent", ignoreCase = true)) {
                    if (currentNode != node) currentNode.recycle()
                    return true
                }

                if (viewId.contains("incoming", ignoreCase = true) ||
                    viewId.contains("received", ignoreCase = true)) {
                    if (currentNode != node) currentNode.recycle()
                    return false
                }

                val bounds = android.graphics.Rect()
                currentNode.getBoundsInScreen(bounds)

                val width = bounds.width()
                if (width > 100 && width < screenWidth * 0.8) {
                    messageBubbleBounds = bounds
                }

                val parent = currentNode.parent
                if (currentNode != node) {
                    currentNode.recycle()
                }
                currentNode = parent
                depth++
            }

            if (currentNode != null && currentNode != node) {
                currentNode.recycle()
            }

            val finalBounds = messageBubbleBounds ?: bestBounds
            val left = finalBounds.left

            val isSent = left > (screenWidth * 0.2)
            return isSent

        } catch (e: Exception) {
            Log.e(TAG, "Error detecting message sender: ${e.message}", e)
            return false
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        instance = null
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }

    fun isMonitoring(): Boolean = isInDirectMessages
}
