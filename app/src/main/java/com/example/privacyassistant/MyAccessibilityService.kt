package com.example.privacyassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MyAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "InstagramAssistant"
        const val INSTAGRAM_PACKAGE = "com.instagram.android"
    }

    private lateinit var notificationHelper: NotificationHelper
    private lateinit var database: InstagramDatabase
    private lateinit var contactDao: ContactDao
    private lateinit var messageDao: MessageDao
    private val ioScope = CoroutineScope(Dispatchers.IO)
    
    // Message monitoring states
    private var isInDirectMessages = false
    private var currentChatContact: String? = null
    private val processedMessages = mutableSetOf<String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Instagram Assistant Service Connected")
        
        // Configure service for BOTH post detection AND message monitoring
        val info = AccessibilityServiceInfo().apply {
            // Events for BOTH functionalities
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_CLICKED or
                        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_FOCUSED
            
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            }
            
            // Target Instagram only
            packageNames = arrayOf(INSTAGRAM_PACKAGE)
        }
        
        serviceInfo = info
        
        // Initialize notification helper (for post detection)
        notificationHelper = NotificationHelper(this)
        
        // Initialize database (for message monitoring)
        try {
            database = Room.databaseBuilder(
                applicationContext,
                InstagramDatabase::class.java,
                "instagram-db"
            ).fallbackToDestructiveMigration().build()
            
            contactDao = database.contactDao()
            messageDao = database.messageDao()
            Log.d(TAG, "Database initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing database: ${e.message}")
        }
        
        Log.d(TAG, "Service ready - Will monitor: 1) Post button clicks 2) Messages")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let {
            val packageName = event.packageName?.toString() ?: ""
            
            // Only process Instagram events
            if (packageName != INSTAGRAM_PACKAGE) return
            
            // Log event for debugging (optional)
            // Log.d(TAG, "Event: ${getEventTypeName(event.eventType)}, Class: ${event.className}")
            
            // Handle BOTH functionalities based on event type
            when (event.eventType) {
                // 1. POST DETECTION: When user clicks post button
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    val viewId = event.source?.viewIdResourceName ?: ""
                    Log.d(TAG, "Clicked view ID: $viewId")
                    
                    // Check for Instagram post button (original functionality)
                    if (viewId.contains("creation_tab") || 
                        viewId.contains("camera") || 
                        viewId.contains("compose")) {
                        Log.d(TAG, "Instagram POST action detected!")
                        notificationHelper.showInstagramPostAssistantNotification()
                    }
                    
                    // Also check for message-related clicks
                    if (isInDirectMessages) {
                        handleMessageClick(event)
                    }
                }
                
                // 2. MESSAGE MONITORING: When screen changes
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    val className = event.className?.toString() ?: ""
                    Log.d(TAG, "Window changed to: $className")
                    handleWindowChange(className)
                }
                
                // 3. MESSAGE MONITORING: When content updates
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    if (isInDirectMessages) {
                        Log.d(TAG, "Content changed in messages")
                        handleContentChange()
                    }
                }
                
                // 4. MESSAGE MONITORING: When user focuses on chat
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    if (isInDirectMessages) {
                        Log.d(TAG, "View focused in messages")
                        detectChatOpen()
                    }
                }
            }
        }
    }

    // ============ MESSAGE MONITORING FUNCTIONS ============
    
    private fun handleWindowChange(className: String) {
        when {
            // Entering Direct Messages
            className.contains("DirectInboxActivity", ignoreCase = true) ||
            className.contains("DirectThreadActivity", ignoreCase = true) ||
            className.contains("direct", ignoreCase = true) -> {
                if (!isInDirectMessages) {
                    isInDirectMessages = true
                    Log.d(TAG, "✅ Entered Instagram Messages")
                    // Start capturing contacts after a short delay
                    ioScope.launch {
                        delay(1000) // Wait for UI to load
                        captureContacts()
                    }
                }
            }
            
            // Exiting Direct Messages (back to main)
            className.contains("MainActivity", ignoreCase = true) ||
            className.contains("HomeActivity", ignoreCase = true) -> {
                if (isInDirectMessages) {
                    isInDirectMessages = false
                    currentChatContact = null
                    Log.d(TAG, "✅ Exited Instagram Messages")
                }
            }
            
            // In a specific chat
            className.contains("thread", ignoreCase = true) ||
            className.contains("chat", ignoreCase = true) -> {
                Log.d(TAG, "📱 In chat thread")
                detectChatOpen()
            }
        }
    }

    private fun handleMessageClick(event: AccessibilityEvent) {
        // Check if clicked on a contact in messages list
        val clickedText = event.source?.text?.toString() ?: ""
        if (clickedText.isNotBlank() && clickedText.length < 50) {
            Log.d(TAG, "Clicked on potential contact: $clickedText")
            ioScope.launch {
                saveContact(clickedText)
            }
        }
    }

    private fun handleContentChange() {
        ioScope.launch {
            if (currentChatContact != null) {
                // We're in a chat - capture new messages
                captureNewMessages()
            } else {
                // We're in inbox - capture contacts
                captureContacts()
            }
        }
    }

    private fun detectChatOpen() {
        ioScope.launch {
            delay(500) // Wait for chat to load
            val contactName = findCurrentChatContact()
            if (contactName != null && contactName != currentChatContact) {
                currentChatContact = contactName
                processedMessages.clear() // Clear for new chat
                Log.d(TAG, "💬 Opened chat with: $contactName")
                saveContact(contactName)
            }
        }
    }

    private suspend fun captureContacts() {
        try {
            val rootNode = rootInActiveWindow ?: return
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
            val messages = findMessageNodes(rootNode)
            
            messages.forEach { messageNode ->
                val messageText = extractMessageText(messageNode)
                val messageHash = "$currentChatContact-$messageText-${System.currentTimeMillis()}"
                
                // Avoid duplicate messages
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
                    Log.d(TAG, "💾 Saved message: $messageText")
                }
            }
            
            rootNode.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing messages: ${e.message}")
        }
    }

    private suspend fun saveContact(contactName: String) {
        try {
            // Simple validation
            if (contactName.length > 50 || 
                contactName.contains("Instagram") ||
                contactName.contains("Search") ||
                contactName.contains("Messages")) {
                return
            }
            
            val existing = contactDao.getContactByName(contactName)
            if (existing == null) {
                val contact = Contact(
                    id = 0,
                    name = contactName,
                    timestamp = System.currentTimeMillis()
                )
                contactDao.insert(contact)
                Log.d(TAG, "✅ Saved contact: $contactName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving contact: ${e.message}")
        }
    }

    // Helper functions for message monitoring
    private fun findContactNames(rootNode: AccessibilityNodeInfo): List<String> {
        val contacts = mutableListOf<String>()
        
        fun searchNodes(node: AccessibilityNodeInfo) {
            if (node.text != null && node.text.isNotEmpty()) {
                val text = node.text.toString().trim()
                if (text.length in 1..30 && 
                    !text.contains("Instagram") &&
                    !text.contains("Search") &&
                    !text.contains("Messages")) {
                    contacts.add(text)
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
        return contacts.distinct()
    }

    private fun findCurrentChatContact(): String? {
        return try {
            val rootNode = rootInActiveWindow ?: return null
            var contactName: String? = null
            
            fun searchForContact(node: AccessibilityNodeInfo) {
                if (node.text != null && node.text.isNotEmpty()) {
                    val text = node.text.toString().trim()
                    if (text.length in 1..30 && 
                        !text.contains("Type") &&
                        !text.contains("Send") &&
                        !text.contains("Like")) {
                        contactName = text
                    }
                }
                
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { child ->
                        searchForContact(child)
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
            if (node.text != null && node.text.isNotEmpty()) {
                val text = node.text.toString().trim()
                if (text.length in 1..500 && 
                    !text.contains("Instagram") &&
                    !text.contains("Message") &&
                    !text.contains("Search")) {
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
        // Simple check - can be improved
        return node.contentDescription?.toString()?.contains("sent", ignoreCase = true) == true ||
               node.parent?.contentDescription?.toString()?.contains("sent", ignoreCase = true) == true
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }
}