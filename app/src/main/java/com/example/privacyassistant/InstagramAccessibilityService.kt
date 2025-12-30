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
    private val ioScope = CoroutineScope(Dispatchers.IO)

    private var isInDirectMessages = false
    private var currentChatContact: String? = null
    private val processedMessages = mutableSetOf<String>() // Track processed messages to avoid duplicates

    // Debouncing to prevent rapid-fire captures
    private var lastMessageCaptureTime = 0L
    private var lastContactCaptureTime = 0L
    private val MESSAGE_CAPTURE_DEBOUNCE_MS = 500L // Wait 500ms between message captures
    private val CONTACT_CAPTURE_DEBOUNCE_MS = 2000L // Wait 2s between contact captures

    override fun onServiceConnected() {
    super.onServiceConnected()
    instance = this
    Log.d(TAG, "Instagram Accessibility Service Connected")
    
    // Configure service
    val info = AccessibilityServiceInfo().apply {
        eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED  // Added for better detection
        
        feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        notificationTimeout = 100
        flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS  // Added to capture more nodes
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        }
    }
    
    // Only monitor Instagram
    info.packageNames = arrayOf(INSTAGRAM_PACKAGE)
    
    serviceInfo = info
    
    // Initialize database using singleton pattern
    database = InstagramDatabase.getInstance(applicationContext)
    contactDao = database.contactDao()
    messageDao = database.messageDao()
    
    // Verify database is accessible
    ioScope.launch {
        try {
            val contacts = contactDao.getAllContacts()
            Log.d(TAG, "Database initialized. Existing contacts: ${contacts.size}")
            
            // Log database path for verification
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

            Log.v(TAG, "Event: $eventTypeName | InDMs: $isInDirectMessages | Chat: $currentChatContact")

            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    val className = event.className?.toString() ?: ""
                    Log.d(TAG, "━━━ WINDOW CHANGED: $className ━━━")

                    handleWindowChange(className)
                }

                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    if (isInDirectMessages) {
                        Log.v(TAG, "Content changed in DMs")
                        handleContentChange()
                    }
                }

                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    // Handle clicks to detect chat openings
                    if (isInDirectMessages) {
                        Log.v(TAG, "View clicked in DMs")
                        detectChatOpen()
                    }
                }

                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    // Handle focus changes (like opening a chat)
                    if (isInDirectMessages) {
                        Log.v(TAG, "View focused in DMs")
                        detectChatOpen()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing event: ${e.message}", e)
        }
    }

    private fun handleWindowChange(className: String) {
    Log.d(TAG, "Window change detected: $className")

    // Instagram activity patterns (2024/2025)
    val dmActivityPatterns = listOf(
        "DirectInboxActivity",
        "DirectThreadActivity",
        "DirectInboxFragment",
        "DirectThreadFragment",
        "ModalActivity",  // Instagram now uses this for DMs!
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
        // Check if we're in Direct Messages
        dmActivityPatterns.any { pattern ->
            className.contains(pattern, ignoreCase = true)
        } -> {
            // For ModalActivity, we need to verify it's actually DMs by inspecting content
            if (className.contains("ModalActivity", ignoreCase = true)) {
                Log.d(TAG, "ModalActivity detected - inspecting UI to confirm DMs...")
                ioScope.launch {
                    delay(800) // Wait for UI to load
                    if (inspectUIForDMs()) {
                        if (!isInDirectMessages) {
                            Log.d(TAG, ">>> ENTERED Instagram Direct Messages (via ModalActivity) <<<")
                            isInDirectMessages = true
                            delay(1000)
                            // Detect if we're in a specific chat or the inbox
                            detectChatOpen()

                            // Only capture contacts if we're NOT in a specific chat (i.e., we're in the inbox)
                            if (currentChatContact == null) {
                                captureContacts()
                            } else {
                                Log.d(TAG, "Entered directly into chat with $currentChatContact, skipping contact list capture")
                            }
                        }
                    } else {
                        Log.d(TAG, "ModalActivity is NOT DMs, ignoring")
                    }
                }
            } else {
                // Known DM activity - directly enter DMs
                if (!isInDirectMessages) {
                    Log.d(TAG, ">>> ENTERED Instagram Direct Messages <<<")
                    isInDirectMessages = true
                    ioScope.launch {
                        delay(1500)
                        captureContacts()
                    }
                }

                // Check if it's a specific thread (chat)
                if (className.contains("thread", ignoreCase = true)) {
                    Log.d(TAG, ">>> IN CHAT THREAD <<<")
                    detectChatOpen()
                }
            }
        }

        // Check if we left DMs
        mainActivityPatterns.any { pattern ->
            className.contains(pattern, ignoreCase = true)
        } -> {
            if (isInDirectMessages) {
                Log.d(TAG, ">>> EXITED Direct Messages <<<")
                isInDirectMessages = false
                currentChatContact = null
                processedMessages.clear()
            }
        }
    }
}

    private fun inspectUIForDMs(): Boolean {
        // Inspect the UI tree to determine if we're actually in DMs
        try {
            val rootNode = rootInActiveWindow ?: return false

            var isDMScreen = false
            var foundIndicators = mutableListOf<String>()

            // DM screen indicators
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
                if (depth > 15) return // Don't search too deep

                // Check text content
                val text = node.text?.toString() ?: ""
                val contentDesc = node.contentDescription?.toString() ?: ""
                val viewId = node.viewIdResourceName ?: ""

                // Look for DM-specific patterns
                dmIndicators.forEach { indicator ->
                    if (text.contains(indicator, ignoreCase = true) ||
                        contentDesc.contains(indicator, ignoreCase = true) ||
                        viewId.contains(indicator, ignoreCase = true)) {
                        foundIndicators.add("$indicator (at depth $depth)")
                        isDMScreen = true
                    }
                }

                // Search children
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { child ->
                        searchForDMIndicators(child, depth + 1)
                        child.recycle()
                    }
                }
            }

            searchForDMIndicators(rootNode)

            if (isDMScreen) {
                Log.d(TAG, "✓ Confirmed DM screen. Found indicators: ${foundIndicators.take(5).joinToString(", ")}")
            } else {
                Log.w(TAG, "✗ Not a DM screen. No DM indicators found. Dumping UI tree for analysis...")
                dumpUITree(rootNode, maxDepth = 6)
            }

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
                // We're in a chat, capture new messages (with debouncing)
                if (currentTime - lastMessageCaptureTime >= MESSAGE_CAPTURE_DEBOUNCE_MS) {
                    lastMessageCaptureTime = currentTime
                    captureNewMessages()
                } else {
                    Log.v(TAG, "Debouncing message capture (too soon)")
                }
            } else {
                // Check if we're actually in inbox or just haven't detected chat contact yet
                if (isInInboxView()) {
                    // We're in inbox, capture contacts (with debouncing)
                    if (currentTime - lastContactCaptureTime >= CONTACT_CAPTURE_DEBOUNCE_MS) {
                        lastContactCaptureTime = currentTime
                        captureContacts()
                    } else {
                        Log.v(TAG, "Debouncing contact capture (too soon)")
                    }
                } else {
                    // We're in a chat but haven't detected contact yet - try to detect it
                    Log.v(TAG, "In chat but contact not detected, attempting detection...")
                    detectChatOpen()
                }
            }
        }
    }

    private fun isInInboxView(): Boolean {
        // Check if we're in the inbox (list of conversations) vs individual chat
        try {
            val rootNode = rootInActiveWindow ?: return false
            var hasInboxIndicators = false
            var hasChatIndicators = false

            // Indicators of inbox view
            val inboxIndicators = listOf("Direct", "Messages", "Chats")
            // Indicators of individual chat view
            val chatIndicators = listOf("Type a message", "composer", "thread_composer")

            fun checkView(node: AccessibilityNodeInfo, depth: Int = 0) {
                if (depth > 10) return

                val text = node.text?.toString() ?: ""
                val viewId = node.viewIdResourceName ?: ""

                // Check for chat composer (means we're in a chat, not inbox)
                if (text.contains("Type a message", ignoreCase = true) ||
                    viewId.contains("composer", ignoreCase = true)) {
                    hasChatIndicators = true
                }

                // Check for inbox indicators
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

            // If we found chat composer, we're in a chat (not inbox)
            val inInbox = !hasChatIndicators
            Log.v(TAG, "View check: inInbox=$inInbox (hasChatIndicators=$hasChatIndicators, hasInboxIndicators=$hasInboxIndicators)")
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
            delay(500) // Wait a bit for chat to load
            val contactName = findCurrentChatContact()
            if (contactName != null && contactName != currentChatContact) {
                Log.d(TAG, "Opened chat with: $contactName")
                currentChatContact = contactName
                // Clear processed messages for this new chat
                processedMessages.clear()
                
                // Save contact if not already saved
                saveContact(contactName)
            }
        }
    }

    private suspend fun captureContacts() {
        // DISABLED: Inbox contact scanning is unreliable (captures stories, suggestions, etc.)
        // Contacts are now ONLY saved when you open a chat with them (via detectChatOpen)
        Log.d(TAG, "📋 Contact capture from inbox is disabled - contacts saved only when opening chats")
        return

        /* OLD IMPLEMENTATION - DISABLED
        try {
            // GUARD: Never capture contacts if we're in a chat
            if (currentChatContact != null) {
                Log.w(TAG, "⚠️ Skipping contact capture - already in chat with $currentChatContact")
                return
            }

            val rootNode = rootInActiveWindow ?: return

            // Double-check we're actually in inbox, not a chat
            if (!isInInboxView()) {
                Log.w(TAG, "⚠️ Skipping contact capture - not in inbox view")
                rootNode.recycle()
                return
            }

            Log.d(TAG, "📋 Capturing contacts from inbox...")

            // Find contact names in the inbox
            val contacts = findContactNames(rootNode)
            Log.d(TAG, "Found ${contacts.size} potential contacts")

            contacts.forEach { contactName ->
                if (contactName.isNotBlank()) {
                    saveContact(contactName)
                }
            }

            rootNode.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing contacts: ${e.message}")
        }
        */
    }

    private suspend fun captureNewMessages() {
        try {
            val rootNode = rootInActiveWindow ?: return
            val contactName = currentChatContact ?: "Unknown"

            // Find all message nodes
            val messages = findMessageNodes(rootNode)
            var newMessageCount = 0

            // DEBUG: If no messages found, dump UI tree to help troubleshoot
            if (messages.isEmpty()) {
                Log.w(TAG, "⚠️ No messages found in UI tree. Dumping UI structure for debugging...")
                dumpUITree(rootNode, maxDepth = 8)
            }

            messages.forEach { messageNode ->
                val messageText = extractMessageText(messageNode)

                if (messageText.isNotBlank()) {
                    // Check both in-memory cache AND database for duplicates
                    val messageHash = "$contactName-$messageText"
                    val existsInMemory = processedMessages.contains(messageHash)

                    // Check database for existing message
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

                        val messageId = messageDao.insert(message)
                        newMessageCount++
                        val direction = if (isSentByUser) "SENT" else "RECEIVED"
                        Log.d(TAG, "✓ Saved NEW message [$direction] from $contactName (ID: $messageId): '$messageText'")
                    } else {
                        Log.v(TAG, "⊘ Skipped DUPLICATE message from $contactName: '$messageText'")
                    }
                }
            }

            if (newMessageCount > 0) {
                val totalMessages = messageDao.getMessageCount()
                Log.d(TAG, ">>> Captured $newMessageCount new messages. Total in DB: $totalMessages <<<")
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

        // Only log nodes with useful information
        if (text.isNotEmpty() || viewId.isNotEmpty() || contentDesc.isNotEmpty()) {
            val info = StringBuilder()
            info.append("$indent[$className]")
            if (viewId.isNotEmpty()) info.append(" ID:$viewId")
            if (text.isNotEmpty()) info.append(" Text:'${text.take(50)}'")
            if (contentDesc.isNotEmpty()) info.append(" Desc:'${contentDesc.take(50)}'")

            Log.d(TAG, info.toString())
        }

        // Recursively dump children
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                dumpUITree(child, depth + 1, maxDepth)
                child.recycle()
            }
        }
    }

    private suspend fun saveContact(contactName: String) {
        try {
            // Check if contact already exists
            val existing = contactDao.getContactByName(contactName)
            if (existing == null) {
                val contact = Contact(
                    id = 0,
                    name = contactName,
                    timestamp = System.currentTimeMillis()
                )
                contactDao.insert(contact)
                Log.d(TAG, "Saved new contact: $contactName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving contact: ${e.message}")
        }
    }

    private fun findContactNames(rootNode: AccessibilityNodeInfo): List<String> {
    val contacts = mutableListOf<String>()
    
    // Updated patterns for Instagram 2024/2025 UI
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
    
    // Class patterns that typically contain usernames
    val classPatterns = listOf(
        "TextView",
        "AppCompatTextView"
    )
    
    fun searchNodes(node: AccessibilityNodeInfo, depth: Int = 0) {
        // Log node details for debugging (remove in production)
        if (node.text != null && node.text.isNotEmpty()) {
            Log.v(TAG, "Node[depth=$depth]: text='${node.text}', viewId='${node.viewIdResourceName}', class='${node.className}'")
        }
        
        // Check if this node might be a contact name
        if (node.text != null && node.text.isNotEmpty()) {
            val text = node.text.toString().trim()
            val viewId = node.viewIdResourceName ?: ""
            val className = node.className?.toString() ?: ""
            
            // Look for contact patterns in view ID
            val matchesViewIdPattern = contactPatterns.any { pattern ->
                viewId.contains(pattern, ignoreCase = true)
            }
            
            // Check class type
            val matchesClassPattern = classPatterns.any { pattern ->
                className.contains(pattern, ignoreCase = true)
            }
            
            // Filter criteria for valid contact names
            val timePattern = Regex("^\\d{1,2}:\\d{2}$")
            val paginationPattern = Regex("^\\d+/\\d+$")  // Match "1/7", "2/5", etc.

            val isValidContact = text.length in 3..30 &&  // Minimum 3 chars (was 1)
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
                !text.contains("For you", ignoreCase = true) &&  // Instagram recommendations tab
                !text.contains("Chats", ignoreCase = true) &&     // Tab label
                !text.contains("Channels", ignoreCase = true) &&  // Tab label
                !text.contains("Notes", ignoreCase = true) &&     // Tab label
                !text.contains("Groups", ignoreCase = true) &&    // Tab label
                !text.equals("Your story", ignoreCase = true) &&  // Story UI
                !text.equals("Your note", ignoreCase = true) &&   // Notes UI
                !text.startsWith("Your ", ignoreCase = true) &&   // Any "Your X" UI elements
                !text.contains("...", ignoreCase = true) &&       // Truncated captions/titles
                !text.contains(" of ", ignoreCase = true) &&      // Likely captions like "Best song of 2025"
                text.count { it == ' ' } <= 1 &&                  // Max 1 space (usernames rarely have multiple spaces)
                !text.all { it.isDigit() } && // Exclude pure numbers
                !timePattern.matches(text) && // Exclude timestamps "16:04"
                !paginationPattern.matches(text) && // Exclude pagination "1/7"
                text != "@" // Allow @usernames but not just "@"
            
            if ((matchesViewIdPattern || matchesClassPattern) && isValidContact) {
                contacts.add(text)
                Log.d(TAG, "Found contact: $text (viewId: $viewId)")
            }
        }
        
        // Search child nodes
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
        
        // Updated patterns for chat header
        val headerPatterns = listOf(
            "title",
            "action_bar_title",
            "thread_title",
            "header_title",
            "toolbar_title",
            "direct_thread_header_title"
        )
        
        fun searchForContact(node: AccessibilityNodeInfo, depth: Int = 0) {
            if (contactName != null) return // Already found
            
            if (node.text != null && node.text.isNotEmpty()) {
                val text = node.text.toString().trim()
                val viewId = node.viewIdResourceName ?: ""
                
                // Check header patterns
                val isHeader = headerPatterns.any { pattern ->
                    viewId.contains(pattern, ignoreCase = true)
                }
                
                // Also check by position - header is usually at top (depth < 10)
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
                    !text.all { it.isDigit() }
                
                if (isHeader && isValidContactName) {
                    contactName = text
                    Log.d(TAG, "Found chat contact in header: $text")
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

        // Patterns that indicate actual message content nodes
        val messageViewIdPatterns = listOf(
            "direct_text_message_text_view",  // This is the actual message text!
            "message_content"
        )

        // ViewIDs to EXCLUDE (not messages)
        val excludedViewIds = listOf(
            "message_status",  // Timestamps like "16:04"
            "timestamp",
            "seen_indicator",
            "status",
            "hint",
            "placeholder"
        )

        fun searchNodes(node: AccessibilityNodeInfo, depth: Int = 0) {
            // Check if node contains message text
            if (node.text != null && node.text.isNotEmpty()) {
                val text = node.text.toString().trim()
                val viewId = node.viewIdResourceName ?: ""
                val className = node.className?.toString() ?: ""

                // EXCLUDE nodes with specific ViewIDs (timestamps, status, etc.)
                val isExcludedViewId = excludedViewIds.any { excluded ->
                    viewId.contains(excluded, ignoreCase = true)
                }

                if (isExcludedViewId) {
                    // Skip this node entirely
                    for (i in 0 until node.childCount) {
                        node.getChild(i)?.let { child ->
                            searchNodes(child, depth + 1)
                            child.recycle()
                        }
                    }
                    return
                }

                // Check if this looks like a message node by ViewID
                val matchesMessageViewId = messageViewIdPatterns.any { pattern ->
                    viewId.contains(pattern, ignoreCase = true)
                }

                val isTextView = className.contains("TextView", ignoreCase = true)

                // Regex to detect time patterns (HH:MM format)
                val timePattern = Regex("^\\d{1,2}:\\d{2}$")

                // Filter out UI elements and system text
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
                    !timePattern.matches(text) // Exclude timestamps like "16:04"

                // Only accept if it matches the specific message ViewID pattern AND passes all filters
                if (matchesMessageViewId && isTextView && isNotUIElement && text.length in 1..1000) {
                    // Log for debugging
                    Log.v(TAG, "✓ Valid message [depth=$depth]: '$text' (viewId: $viewId)")
                    messages.add(AccessibilityNodeInfo.obtain(node))
                } else if (text.length < 100) {
                    // Log rejected messages for debugging (only short text to avoid spam)
                    val reason = when {
                        !matchesMessageViewId -> "wrong ViewID"
                        !isTextView -> "not TextView"
                        !isNotUIElement -> "UI element"
                        timePattern.matches(text) -> "timestamp"
                        else -> "unknown"
                    }
                    Log.v(TAG, "✗ Rejected [$reason]: '$text' (viewId: ${viewId.substringAfterLast(':')})")
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
        // Instagram places sent messages on the RIGHT side, received on the LEFT
        // Check message bubble position, not the text node itself

        try {
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val centerX = screenWidth / 2

            // First check content descriptions and ViewIDs
            var currentNode: AccessibilityNodeInfo? = node
            var depth = 0
            val maxDepth = 8  // Check more parent levels

            var messageBubbleBounds: android.graphics.Rect? = null
            var bestBounds = android.graphics.Rect()
            node.getBoundsInScreen(bestBounds)

            while (currentNode != null && depth < maxDepth) {
                // Check content descriptions
                val contentDesc = currentNode.contentDescription?.toString() ?: ""
                if (contentDesc.contains("you sent", ignoreCase = true) ||
                    contentDesc.contains("sent by you", ignoreCase = true) ||
                    contentDesc.contains("your message", ignoreCase = true)) {
                    if (currentNode != node) currentNode.recycle()
                    Log.d(TAG, "📍 Detected SENT via contentDesc")
                    return true
                }

                if (contentDesc.contains("sent you", ignoreCase = true) ||
                    contentDesc.contains("received", ignoreCase = true) ||
                    contentDesc.contains("their message", ignoreCase = true)) {
                    if (currentNode != node) currentNode.recycle()
                    Log.d(TAG, "📍 Detected RECEIVED via contentDesc")
                    return false
                }

                // Check view ID patterns
                val viewId = currentNode.viewIdResourceName ?: ""
                if (viewId.contains("outgoing", ignoreCase = true) ||
                    viewId.contains("sent", ignoreCase = true)) {
                    if (currentNode != node) currentNode.recycle()
                    Log.d(TAG, "📍 Detected SENT via viewId")
                    return true
                }

                if (viewId.contains("incoming", ignoreCase = true) ||
                    viewId.contains("received", ignoreCase = true)) {
                    if (currentNode != node) currentNode.recycle()
                    Log.d(TAG, "📍 Detected RECEIVED via viewId")
                    return false
                }

                // Get bounds of parent - look for message bubble container
                val bounds = android.graphics.Rect()
                currentNode.getBoundsInScreen(bounds)

                // Message bubble should NOT span full width
                // If width is less than 80% of screen, it's likely the actual bubble
                val width = bounds.width()
                if (width > 100 && width < screenWidth * 0.8) {
                    messageBubbleBounds = bounds
                    Log.v(TAG, "Found bubble candidate at depth $depth: width=$width, bounds=$bounds")
                }

                // Move to parent
                val parent = currentNode.parent
                if (currentNode != node) {
                    currentNode.recycle()
                }
                currentNode = parent
                depth++
            }

            // Clean up
            if (currentNode != null && currentNode != node) {
                currentNode.recycle()
            }

            // Use the best bounds we found (prefer bubble, fallback to text)
            val finalBounds = messageBubbleBounds ?: bestBounds
            val left = finalBounds.left
            val right = finalBounds.right

            // Check which side of screen the message is on
            // Sent messages: left edge should be > 20% of screen
            // Received messages: right edge should be < 80% of screen
            val isSent = left > (screenWidth * 0.2)

            val position = if (isSent) "RIGHT (sent)" else "LEFT (received)"
            Log.d(TAG, "📍 Position: msg='${node.text?.toString()?.take(20)}', left=$left, right=$right, screenWidth=$screenWidth, position=$position, isSent=$isSent")

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
    
    // Helper function to check if service is running
    fun isMonitoring(): Boolean = isInDirectMessages
}