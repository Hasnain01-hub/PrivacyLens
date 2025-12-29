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
            
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    val className = event.className?.toString() ?: ""
                    Log.d(TAG, "Window changed: $className")
                    
                    handleWindowChange(className)
                }
                
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    if (isInDirectMessages) {
                        handleContentChange()
                    }
                }
                
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    // Handle clicks to detect chat openings
                    if (isInDirectMessages) {
                        detectChatOpen()
                    }
                }
                
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    // Handle focus changes (like opening a chat)
                    if (isInDirectMessages) {
                        detectChatOpen()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing event: ${e.message}")
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
            if (!isInDirectMessages) {
                Log.d(TAG, ">>> ENTERED Instagram Direct Messages <<<")
                isInDirectMessages = true
                ioScope.launch {
                    delay(1500) // Wait longer for UI to fully load
                    captureContacts()
                }
            }
            
            // Check if it's a specific thread (chat)
            if (className.contains("thread", ignoreCase = true)) {
                Log.d(TAG, ">>> IN CHAT THREAD <<<")
                detectChatOpen()
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

    private fun handleContentChange() {
        ioScope.launch {
            if (currentChatContact != null) {
                // We're in a chat, capture new messages
                captureNewMessages()
            } else {
                // We're in inbox, capture contacts
                captureContacts()
            }
        }
    }
fun exportDataToFile() {
    ioScope.launch {
        try {
            val contacts = contactDao.getAllContacts()
            val allContactNames = messageDao.getAllContactNames()
            
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
                    out.println("- ${contact.name} (saved: ${java.util.Date(contact.timestamp)})")
                }
                out.println()
                
                out.println("=== MESSAGES BY CONTACT ===")
                allContactNames.forEach { contactName ->
                    val messages = messageDao.getMessagesByContact(contactName)
                    out.println("\n[$contactName] - ${messages.size} messages")
                    messages.forEach { msg ->
                        val direction = if (msg.isSentByUser) "SENT" else "RECEIVED"
                        out.println("  [$direction] ${msg.messageText}")
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
        try {
            val rootNode = rootInActiveWindow ?: return
            
            // Find contact names in the inbox
            val contacts = findContactNames(rootNode)
            contacts.forEach { contactName ->
                if (contactName.isNotBlank()) {
                    saveContact(contactName)
                }
            }
            
            rootNode.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing contacts: ${e.message}")
        }
    }

    private suspend fun captureNewMessages() {
        try {
            val rootNode = rootInActiveWindow ?: return
            
            // Find all message nodes
            val messages = findMessageNodes(rootNode)
            messages.forEach { messageNode ->
                val messageText = extractMessageText(messageNode)
                val messageHash = "$currentChatContact-$messageText"
                
                // Check if we've already processed this message
                if (messageText.isNotBlank() && !processedMessages.contains(messageHash)) {
                    processedMessages.add(messageHash)
                    
                    val isSentByUser = isMessageSentByUser(messageNode)
                    val timestamp = System.currentTimeMillis()
                    
                    val message = Message(
                        id = 0,
                        contactName = currentChatContact ?: "Unknown",
                        messageText = messageText,
                        timestamp = timestamp,
                        isSentByUser = isSentByUser
                    )
                    
                    messageDao.insert(message)
                    Log.d(TAG, "Saved message from ${currentChatContact}: $messageText")
                }
            }
            
            rootNode.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing messages: ${e.message}")
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
            val isValidContact = text.length in 1..50 &&
                !text.contains("Instagram", ignoreCase = true) &&
                !text.contains("Search", ignoreCase = true) &&
                !text.contains("Messages", ignoreCase = true) &&
                !text.contains("Direct", ignoreCase = true) &&
                !text.contains("New message", ignoreCase = true) &&
                !text.contains("Requests", ignoreCase = true) &&
                !text.contains("General", ignoreCase = true) &&
                !text.contains("Primary", ignoreCase = true) &&
                !text.startsWith("@").not() || text.startsWith("@") && // Allow @usernames
                !text.all { it.isDigit() } // Exclude pure numbers
            
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
        
        fun searchNodes(node: AccessibilityNodeInfo) {
            // Check if node contains message text
            if (node.text != null && node.text.isNotEmpty()) {
                val text = node.text.toString().trim()
                
                // Filter out non-message text
                if (text.length in 1..500 && 
                    !text.contains("Instagram") &&
                    !text.contains("Message") &&
                    !text.contains("Search") &&
                    (node.className?.toString()?.contains("textview", ignoreCase = true) == true ||
                     node.viewIdResourceName?.contains("message") == true ||
                     node.viewIdResourceName?.contains("text") == true)) {
                    
                    messages.add(AccessibilityNodeInfo.obtain(node))
                }
            }
            
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    searchNodes(child)
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
        // This is a simplified check - Instagram's UI may vary
        // Look for indicators that message is sent by user
        return node.contentDescription?.toString()?.contains("sent", ignoreCase = true) == true ||
               node.parent?.contentDescription?.toString()?.contains("sent", ignoreCase = true) == true ||
               node.viewIdResourceName?.contains("sender") == true
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