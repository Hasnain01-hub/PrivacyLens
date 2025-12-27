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
                        AccessibilityEvent.TYPE_VIEW_FOCUSED
            
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            }
        }
        
        // Only monitor Instagram
        info.packageNames = arrayOf(INSTAGRAM_PACKAGE)
        
        serviceInfo = info
        
        // Initialize database
        database = Room.databaseBuilder(
            applicationContext,
            InstagramDatabase::class.java,
            "instagram-db"
        ).fallbackToDestructiveMigration().build()
        
        contactDao = database.contactDao()
        messageDao = database.messageDao()
        
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
        when {
            // Check if we're in Direct Messages tab
            className.contains("DirectInboxActivity", ignoreCase = true) ||
            className.contains("DirectThreadActivity", ignoreCase = true) ||
            className.contains("direct", ignoreCase = true) -> {
                if (!isInDirectMessages) {
                    Log.d(TAG, "Entered Instagram Direct Messages")
                    isInDirectMessages = true
                    // Start monitoring for contacts
                    ioScope.launch {
                        delay(1000) // Wait for UI to load
                        captureContacts()
                    }
                }
            }
            
            // Check if we're in main activity (not in DMs)
            className.contains("MainActivity", ignoreCase = true) ||
            className.contains("HomeActivity", ignoreCase = true) -> {
                if (isInDirectMessages) {
                    Log.d(TAG, "Exited Direct Messages")
                    isInDirectMessages = false
                    currentChatContact = null
                }
            }
            
            // Check if we're in a specific chat thread
            className.contains("thread", ignoreCase = true) ||
            className.contains("chat", ignoreCase = true) -> {
                Log.d(TAG, "In chat thread")
                detectChatOpen()
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
        
        // Common patterns for contact names in Instagram
        val contactPatterns = listOf(
            "row_thread_user_name",
            "direct_thread_title",
            "title",
            "username",
            "name"
        )
        
        fun searchNodes(node: AccessibilityNodeInfo) {
            // Check if this node might be a contact name
            if (node.text != null && node.text.isNotEmpty()) {
                val text = node.text.toString().trim()
                
                // Look for contact patterns in view ID or class
                val isContact = contactPatterns.any { pattern ->
                    node.viewIdResourceName?.contains(pattern, ignoreCase = true) == true ||
                    node.className?.toString()?.contains("textview", ignoreCase = true) == true
                }
                
                // Filter out system texts and common UI elements
                if (isContact && 
                    text.length in 1..50 && 
                    !text.contains("Instagram") &&
                    !text.contains("Search") &&
                    !text.contains("Messages")) {
                    contacts.add(text)
                }
            }
            
            // Search child nodes
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    searchNodes(child)
                    child.recycle()
                }
            }
        }
        
        searchNodes(rootNode)
        return contacts.distinct() // Remove duplicates
    }

    private fun findCurrentChatContact(): String? {
        return try {
            val rootNode = rootInActiveWindow ?: return null
            
            var contactName: String? = null
            
            // Look for contact name at top of chat
            fun searchForContact(node: AccessibilityNodeInfo) {
                if (node.text != null && node.text.isNotEmpty()) {
                    val text = node.text.toString().trim()
                    
                    // Check if this looks like a contact name (not a message)
                    if (text.length in 1..30 && 
                        !text.contains("Type") &&
                        !text.contains("Send") &&
                        !text.contains("Like") &&
                        node.viewIdResourceName?.contains("title") == true) {
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