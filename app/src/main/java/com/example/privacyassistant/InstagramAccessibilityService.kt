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

class InstagramAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "InstagramAccessibility"
        const val INSTAGRAM_PACKAGE = "com.instagram.android"
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
    private val processedMessages = mutableSetOf<String>()

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
                processedMessages.clear()
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
                        out.println("  [${msg.readableTime}] [$direction] ${msg.messageText}")
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
            delay(500)
            val contactName = findCurrentChatContact()
            if (contactName != null && contactName != currentChatContact) {
                currentChatContact = contactName
                processedMessages.clear()
                saveContact(contactName)
            }
        }
    }

    private suspend fun captureContacts() {
        // DISABLED: Inbox contact scanning is unreliable
        // Contacts are now ONLY saved when you open a chat with them
        return
    }

    private suspend fun captureNewMessages() {
        try {
            val rootNode = rootInActiveWindow ?: return
            val contactName = currentChatContact ?: "Unknown"

            val messages = findMessageNodes(rootNode)
            var newMessageCount = 0

            messages.forEach { messageNode ->
                val messageText = extractMessageText(messageNode)

                if (messageText.isNotBlank()) {
                    val messageHash = "$contactName-$messageText"
                    val existsInMemory = processedMessages.contains(messageHash)
                    val existsInDb = messageDao.findDuplicateMessage(contactName, messageText) != null

                    if (!existsInMemory && !existsInDb) {
                        processedMessages.add(messageHash)

                        val isSentByUser = isMessageSentByUser(messageNode)
                        val timestamp = System.currentTimeMillis()

                        val message = Message(
                            id = 0,
                            contactName = contactName,
                            messageText = messageText,
                            timestamp = timestamp,
                            isSentByUser = isSentByUser
                        )

                        messageDao.insert(message)
                        newMessageCount++
                    }
                }
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

        val headerPatterns = listOf(
            "title",
            "action_bar_title",
            "thread_title",
            "header_title",
            "toolbar_title",
            "direct_thread_header_title"
        )

        fun searchForContact(node: AccessibilityNodeInfo, depth: Int = 0) {
            if (contactName != null) return

            if (node.text != null && node.text.isNotEmpty()) {
                val text = node.text.toString().trim()
                val viewId = node.viewIdResourceName ?: ""

                val isHeader = headerPatterns.any { pattern ->
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
                    !text.contains("For you", ignoreCase = true) &&
                    !text.contains("Chats", ignoreCase = true) &&
                    !text.contains("Channels", ignoreCase = true) &&
                    !text.contains("Notes", ignoreCase = true) &&
                    !text.contains("Groups", ignoreCase = true) &&
                    !text.contains("Messages", ignoreCase = true) &&
                    !text.contains("Direct", ignoreCase = true) &&
                    !text.contains("Instagram", ignoreCase = true) &&
                    !text.contains("Search", ignoreCase = true) &&
                    !text.all { it.isDigit() }

                if (isHeader && isValidContactName) {
                    contactName = text
                    return
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

    private fun findMessageNodes(rootNode: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val messages = mutableListOf<AccessibilityNodeInfo>()

        val messageViewIdPatterns = listOf(
            "direct_text_message_text_view",
            "message_content"
        )

        val excludedViewIds = listOf(
            "message_status",
            "timestamp",
            "seen_indicator",
            "status",
            "hint",
            "placeholder"
        )

        fun searchNodes(node: AccessibilityNodeInfo, depth: Int = 0) {
            if (node.text != null && node.text.isNotEmpty()) {
                val text = node.text.toString().trim()
                val viewId = node.viewIdResourceName ?: ""
                val className = node.className?.toString() ?: ""

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
                    messages.add(AccessibilityNodeInfo.obtain(node))
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
