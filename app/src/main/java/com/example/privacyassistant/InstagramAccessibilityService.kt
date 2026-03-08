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
import kotlinx.coroutines.sync.Mutex
import java.text.SimpleDateFormat
import java.util.*

data class MessageData(
    val messageText: String?,
    val isGift: Int
) {
    fun isValid(): Boolean {
        return !messageText.isNullOrBlank() || isGift == 1
    }
}


class InstagramAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "InstagramAccessibility"
        const val INSTAGRAM_PACKAGE = "com.instagram.android"
        const val ULTRA_DEEP_LOGGING = true
        private var instance: InstagramAccessibilityService? = null
        fun getInstance(): InstagramAccessibilityService? = instance
    }

    private lateinit var database: InstagramDatabase
    private lateinit var contactDao: ContactDao
    private lateinit var messageDao: MessageDao
    private lateinit var remoteSyncHelper: RemoteSyncHelper
    private lateinit var notificationHelper: NotificationHelper
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val scanMutex = Mutex()

    private var isInDirectMessages = false
    private var currentChatContact: String? = null
    private val dateGroupRootId = mutableMapOf<String, Long>() // "contactName|date" -> root message ID

    private val pendingMessages = mutableMapOf<String, PendingMessage>()

    data class DetectedMessage(
        val text: String,
        val assetType: String,
        val node: AccessibilityNodeInfo
    )

    data class PendingMessage(
        val contactName: String,
        val messageText: String,
        val isSentByUser: Boolean,
        val assetType: String,
        val detectedDesc: String,
        val firstSeenMs: Long
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
    remoteSyncHelper = RemoteSyncHelper(applicationContext)
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

            if (ULTRA_DEEP_LOGGING) {
                Log.d(TAG, "🔔 EVENT: $eventTypeName | class=${event.className} | pkg=$packageName")
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    val cls = event.className?.toString() ?: ""
                    val lower = cls.lowercase()
                    if (lower.contains("reel") || lower.contains("clip") || lower.contains("video") ||
                        lower.contains("call") || lower.contains("voip") || lower.contains("audio") ||
                        lower.contains("ring") || lower.contains("phone")) {
                        Log.d(TAG, "🎯 REEL/CALL ACTIVITY DETECTED: '$cls'")
                        val root = rootInActiveWindow
                        if (root != null) {
                            Log.d(TAG, "🌳 DUMPING UI TREE FOR REEL/CALL SCREEN:")
                            dumpUITree(root, maxDepth = 15)
                            root.recycle()
                        }
                    }
                }
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

data class ParsedGroupLabel(
    val date: String?,
    val time: String?,
    val epochMs: Long?
)

data class UiTimestamp(
    val raw: String,
    val parsedDate: String?,
    val parsedTime: String?,
    val epochMs: Long?,
    val y: Int,
    val x: Int
)

private fun parseInstagramGroupLabel(
    rawLabel: String,
    nowMs: Long = System.currentTimeMillis()
): ParsedGroupLabel? {
    val label = rawLabel.trim()
    if (label.isBlank()) return null

    val lower = label.lowercase()

    if (lower.startsWith("seen") || lower.startsWith("active") || lower.contains("typing")) return null

    val nowCal = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
    val dateFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
    val timeFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())

    fun calAtNoon(base: java.util.Calendar): java.util.Calendar {
        return (base.clone() as java.util.Calendar).apply {
            set(java.util.Calendar.HOUR_OF_DAY, 12)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
    }

    fun tryParseTimeOnly(s: String): Pair<Int, Int>? {
        val t = s.trim()
        val m = Regex("""^(\d{1,2}):(\d{2})(?:\s*([AaPp][Mm]))?$""").find(t) ?: return null
        var hh = m.groupValues[1].toInt()
        val mm = m.groupValues[2].toInt()
        val ampm = m.groupValues.getOrNull(3)?.lowercase()?.trim().orEmpty()
        if (ampm == "pm" && hh in 1..11) hh += 12
        if (ampm == "am" && hh == 12) hh = 0
        if (hh !in 0..23 || mm !in 0..59) return null
        return hh to mm
    }

    fun setTime(cal: java.util.Calendar, hh: Int, mm: Int): java.util.Calendar {
        return (cal.clone() as java.util.Calendar).apply {
            set(java.util.Calendar.HOUR_OF_DAY, hh)
            set(java.util.Calendar.MINUTE, mm)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
    }

    fun result(cal: java.util.Calendar, hasTime: Boolean): ParsedGroupLabel {
        return ParsedGroupLabel(
            date = dateFmt.format(cal.time),
            time = if (hasTime) timeFmt.format(cal.time) else null,
            epochMs = cal.timeInMillis
        )
    }

    fun dayOfWeekFromLabel(s: String): Int? {
        val map = mapOf(
            "mon" to java.util.Calendar.MONDAY, "monday" to java.util.Calendar.MONDAY,
            "tue" to java.util.Calendar.TUESDAY, "tues" to java.util.Calendar.TUESDAY, "tuesday" to java.util.Calendar.TUESDAY,
            "wed" to java.util.Calendar.WEDNESDAY, "wednesday" to java.util.Calendar.WEDNESDAY,
            "thu" to java.util.Calendar.THURSDAY, "thur" to java.util.Calendar.THURSDAY, "thurs" to java.util.Calendar.THURSDAY, "thursday" to java.util.Calendar.THURSDAY,
            "fri" to java.util.Calendar.FRIDAY, "friday" to java.util.Calendar.FRIDAY,
            "sat" to java.util.Calendar.SATURDAY, "saturday" to java.util.Calendar.SATURDAY,
            "sun" to java.util.Calendar.SUNDAY, "sunday" to java.util.Calendar.SUNDAY
        )
        return map[s.trim().lowercase()]
    }

    fun mostRecentDow(dow: Int, baseMs: Long): java.util.Calendar {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = baseMs }
        for (i in 0..7) {
            if (cal.get(java.util.Calendar.DAY_OF_WEEK) == dow) return cal
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        }
        return cal
    }

    fun monthFromName(s: String): Int? {
        val map = mapOf(
            "jan" to java.util.Calendar.JANUARY, "january" to java.util.Calendar.JANUARY,
            "feb" to java.util.Calendar.FEBRUARY, "february" to java.util.Calendar.FEBRUARY,
            "mar" to java.util.Calendar.MARCH, "march" to java.util.Calendar.MARCH,
            "apr" to java.util.Calendar.APRIL, "april" to java.util.Calendar.APRIL,
            "may" to java.util.Calendar.MAY,
            "jun" to java.util.Calendar.JUNE, "june" to java.util.Calendar.JUNE,
            "jul" to java.util.Calendar.JULY, "july" to java.util.Calendar.JULY,
            "aug" to java.util.Calendar.AUGUST, "august" to java.util.Calendar.AUGUST,
            "sep" to java.util.Calendar.SEPTEMBER, "sept" to java.util.Calendar.SEPTEMBER, "september" to java.util.Calendar.SEPTEMBER,
            "oct" to java.util.Calendar.OCTOBER, "october" to java.util.Calendar.OCTOBER,
            "nov" to java.util.Calendar.NOVEMBER, "november" to java.util.Calendar.NOVEMBER,
            "dec" to java.util.Calendar.DECEMBER, "december" to java.util.Calendar.DECEMBER
        )
        return map[s.trim().lowercase()]
    }

    fun guessYear(cal: java.util.Calendar) {
        if (cal.timeInMillis > nowMs) {
            cal.set(java.util.Calendar.YEAR, cal.get(java.util.Calendar.YEAR) - 1)
        }
    }

    tryParseTimeOnly(label)?.let { (hh, mm) ->
        val cal = setTime(nowCal, hh, mm)
        return result(cal, hasTime = true)
    }

    if (lower == "today") {
        val cal = calAtNoon(nowCal)
        return result(cal, hasTime = false)
    }

    run {
        val m = Regex("""^today\s+(\d{1,2}):(\d{2})(?:\s*([AaPp][Mm]))?$""", RegexOption.IGNORE_CASE).find(label)
        if (m != null) {
            var hh = m.groupValues[1].toInt()
            val mm = m.groupValues[2].toInt()
            val ampm = m.groupValues.getOrNull(3)?.lowercase()?.trim().orEmpty()
            if (ampm == "pm" && hh in 1..11) hh += 12
            if (ampm == "am" && hh == 12) hh = 0
            val cal = setTime(nowCal, hh, mm)
            return result(cal, hasTime = true)
        }
    }

    if (lower == "yesterday") {
        val cal = calAtNoon(nowCal)
        cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        return result(cal, hasTime = false)
    }

    run {
        val m = Regex("""^yesterday\s+(\d{1,2}):(\d{2})(?:\s*([AaPp][Mm]))?$""", RegexOption.IGNORE_CASE).find(label)
        if (m != null) {
            var hh = m.groupValues[1].toInt()
            val mm = m.groupValues[2].toInt()
            val ampm = m.groupValues.getOrNull(3)?.lowercase()?.trim().orEmpty()
            if (ampm == "pm" && hh in 1..11) hh += 12
            if (ampm == "am" && hh == 12) hh = 0
            val cal = (nowCal.clone() as java.util.Calendar)
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
            val withTime = setTime(cal, hh, mm)
            return result(withTime, hasTime = true)
        }
    }

    run {
        val m = Regex(
            """^(mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun|monday|tuesday|wednesday|thursday|friday|saturday|sunday),?\s*(\d{1,2}):(\d{2})(?:\s*([AaPp][Mm]))?$""",
            RegexOption.IGNORE_CASE
        ).find(label)
        if (m != null) {
            val dow = dayOfWeekFromLabel(m.groupValues[1]) ?: return@run
            var hh = m.groupValues[2].toInt()
            val mm = m.groupValues[3].toInt()
            val ampm = m.groupValues.getOrNull(4)?.lowercase()?.trim().orEmpty()
            if (ampm == "pm" && hh in 1..11) hh += 12
            if (ampm == "am" && hh == 12) hh = 0
            val cal = mostRecentDow(dow, nowMs)
            val withTime = setTime(cal, hh, mm)
            return result(withTime, hasTime = true)
        }
    }

    dayOfWeekFromLabel(label)?.let { dow ->
        val cal = mostRecentDow(dow, nowMs)
        return result(calAtNoon(cal), hasTime = false)
    }

    run {
        val m = Regex(
            """^(mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\s+(\d{1,2})$""",
            RegexOption.IGNORE_CASE
        ).find(label)
        if (m != null) {
            val dow = dayOfWeekFromLabel(m.groupValues[1]) ?: return@run
            val dom = m.groupValues[2].toInt()
            val probe = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
            for (i in 0..14) {
                val c = (probe.clone() as java.util.Calendar).apply {
                    set(java.util.Calendar.DAY_OF_MONTH, dom)
                    set(java.util.Calendar.HOUR_OF_DAY, 12)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                if (c.get(java.util.Calendar.DAY_OF_MONTH) == dom &&
                    c.get(java.util.Calendar.DAY_OF_WEEK) == dow &&
                    c.timeInMillis <= nowMs
                ) {
                    return result(c, hasTime = false)
                }
                probe.add(java.util.Calendar.MONTH, -1)
            }
        }
    }

    if (lower == "last year") {
        val c = calAtNoon(nowCal)
        c.add(java.util.Calendar.YEAR, -1)
        return result(c, hasTime = false)
    }

    run {
        val m = Regex("""^(\d{1,2})\s+(\w+)\s+(\d{4})$""").find(label)
        if (m != null) {
            val dom = m.groupValues[1].toInt()
            val month = monthFromName(m.groupValues[2]) ?: return@run
            val year = m.groupValues[3].toInt()
            val c = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.YEAR, year)
                set(java.util.Calendar.MONTH, month)
                set(java.util.Calendar.DAY_OF_MONTH, dom)
                set(java.util.Calendar.HOUR_OF_DAY, 12)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            if (c.get(java.util.Calendar.DAY_OF_MONTH) == dom) {
                return result(c, hasTime = false)
            }
        }
    }

    run {
        val m = Regex("""^(\d{1,2})\s+(\w+),\s*(\d{1,2}):(\d{2})(?:\s*([AaPp][Mm]))?$""").find(label)
        if (m != null) {
            val dom = m.groupValues[1].toInt()
            val month = monthFromName(m.groupValues[2]) ?: return@run
            var hh = m.groupValues[3].toInt()
            val mm = m.groupValues[4].toInt()
            val ampm = m.groupValues.getOrNull(5)?.lowercase()?.trim().orEmpty()
            if (ampm == "pm" && hh in 1..11) hh += 12
            if (ampm == "am" && hh == 12) hh = 0
            val currentYear = nowCal.get(java.util.Calendar.YEAR)
            val c = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.YEAR, currentYear)
                set(java.util.Calendar.MONTH, month)
                set(java.util.Calendar.DAY_OF_MONTH, dom)
                set(java.util.Calendar.HOUR_OF_DAY, hh)
                set(java.util.Calendar.MINUTE, mm)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            if (c.get(java.util.Calendar.DAY_OF_MONTH) == dom) {
                guessYear(c)
                return result(c, hasTime = true)
            }
        }
    }

    run {
        val m = Regex("""^(\w+)\s+(\d{1,2}),?\s+(\d{4})$""").find(label)
        if (m != null) {
            val month = monthFromName(m.groupValues[1]) ?: return@run
            val dom = m.groupValues[2].toInt()
            val year = m.groupValues[3].toInt()
            val c = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.YEAR, year)
                set(java.util.Calendar.MONTH, month)
                set(java.util.Calendar.DAY_OF_MONTH, dom)
                set(java.util.Calendar.HOUR_OF_DAY, 12)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            if (c.get(java.util.Calendar.DAY_OF_MONTH) == dom) {
                return result(c, hasTime = false)
            }
        }
    }

    run {
        val m = Regex("""^(\d{1,2})\s+(\w+)$""").find(label)
        if (m != null) {
            val dom = m.groupValues[1].toInt()
            val month = monthFromName(m.groupValues[2]) ?: return@run
            val currentYear = nowCal.get(java.util.Calendar.YEAR)
            val c = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.YEAR, currentYear)
                set(java.util.Calendar.MONTH, month)
                set(java.util.Calendar.DAY_OF_MONTH, dom)
                set(java.util.Calendar.HOUR_OF_DAY, 12)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            if (c.get(java.util.Calendar.DAY_OF_MONTH) == dom) {
                guessYear(c)
                return result(c, hasTime = false)
            }
        }
    }

    return null
}

private fun collectUiTimestamps(rootNode: AccessibilityNodeInfo): List<UiTimestamp> {
    val out = mutableListOf<UiTimestamp>()

    fun looksLikeTimestampText(t: String): Boolean {
        val s = t.trim()
        if (s.isBlank()) return false
        val lower = s.lowercase()

        if (lower.startsWith("seen") || lower.contains("type a message") || lower == "messages") return false

        val dowNames = "mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun|monday|tuesday|wednesday|thursday|friday|saturday|sunday"
        val monthNames = "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec"

        return Regex("""^\d{1,2}:\d{2}(\s*[AaPp][Mm])?$""").matches(s) ||
                lower == "today" ||
                lower == "yesterday" ||
                lower == "last year" ||
                Regex("""^today\s+\d{1,2}:\d{2}(\s*[AaPp][Mm])?$""", RegexOption.IGNORE_CASE).matches(s) ||
                Regex("""^yesterday\s+\d{1,2}:\d{2}(\s*[AaPp][Mm])?$""", RegexOption.IGNORE_CASE).matches(s) ||
                Regex("""^($dowNames)(\s+\d{1,2})?$""", RegexOption.IGNORE_CASE).matches(s) ||
                Regex("""^($dowNames),?\s*\d{1,2}:\d{2}(\s*[AaPp][Mm])?$""", RegexOption.IGNORE_CASE).matches(s) ||
                Regex("""^\d{1,2}\s+($monthNames)\w*\s+\d{4}$""", RegexOption.IGNORE_CASE).matches(s) ||
                Regex("""^\d{1,2}\s+($monthNames)\w*,\s*\d{1,2}:\d{2}(\s*[AaPp][Mm])?$""", RegexOption.IGNORE_CASE).matches(s) ||
                Regex("""^($monthNames)\w*\s+\d{1,2},?\s+\d{4}$""", RegexOption.IGNORE_CASE).matches(s) ||
                Regex("""^\d{1,2}\s+($monthNames)\w*$""", RegexOption.IGNORE_CASE).matches(s)
    }

    fun walk(n: AccessibilityNodeInfo, depth: Int = 0) {
        if (depth > 18) return

        val text = n.text?.toString()?.trim().orEmpty()
        val viewId = n.viewIdResourceName.orEmpty()
        val viewIdShort = viewId.substringAfterLast('/').substringAfterLast(':')
        val className = n.className?.toString().orEmpty()

        val maybeTimestamp =
            (viewIdShort.contains("timestamp", ignoreCase = true) || looksLikeTimestampText(text)) &&
            className.contains("TextView", ignoreCase = true) &&
            text.isNotBlank()

        if (maybeTimestamp) {
            val bounds = android.graphics.Rect()
            n.getBoundsInScreen(bounds)
            val parsed = parseInstagramGroupLabel(text)
            out.add(UiTimestamp(
                raw = text,
                parsedDate = parsed?.date,
                parsedTime = parsed?.time,
                epochMs = parsed?.epochMs,
                y = bounds.centerY(),
                x = bounds.centerX()
            ))
        }

        for (i in 0 until n.childCount) {
            n.getChild(i)?.let { c ->
                walk(c, depth + 1)
                c.recycle()
            }
        }
    }

    walk(rootNode)
    return out
}

private fun pickBestGroupLabel(
    messageBounds: android.graphics.Rect,
    uiTimestamps: List<UiTimestamp>
): UiTimestamp? {
    if (uiTimestamps.isEmpty()) return null

    val msgY = messageBounds.centerY()

    return uiTimestamps
        .filter { it.y < msgY && it.parsedDate != null }
        .maxByOrNull { it.y }
}


private fun extractMessageData(messageNode: AccessibilityNodeInfo): MessageData {
    var messageText: String? = null
    var isGift = 0

    messageText = extractMessageText(messageNode)
    val contentDesc = messageNode.contentDescription?.toString() ?: ""
    val viewId = messageNode.viewIdResourceName ?: ""

    if (contentDesc.contains("gift", ignoreCase = true) ||
        contentDesc.contains("sent a gift", ignoreCase = true) ||
        viewId.contains("gift", ignoreCase = true) ||
        (messageText.isNullOrBlank() && contentDesc.contains("sent", ignoreCase = true))) {
        isGift = 1
        if (messageText.isNullOrBlank()) {
            messageText = null
        }
    }

    return MessageData(messageText, isGift)
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
                pendingMessages.clear()
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
                        val timeStr = msg.readableTime
                        val refStr = if (msg.groupLabelMessageId != null) " ref=#${msg.groupLabelMessageId}" else ""
                        out.println("  [${timeStr}] [$direction] [$assetLabel] $content$refStr")
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
            if (isInInboxView()) {
                currentChatContact = null
                return@launch
            }

            val contactName = findCurrentChatContact()
            Log.d(TAG, "🔍 CHAT CONTACT DETECTION: found='$contactName' current='$currentChatContact'")
            if (contactName != null && contactName != currentChatContact) {
                Log.d(TAG, "📱 OPENED CHAT WITH: '$contactName'")
                currentChatContact = contactName

                pendingMessages.clear()
                saveContact(contactName)
                delay(800)
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


private suspend fun captureNewMessages() {
    if (!scanMutex.tryLock()) {
        Log.d(TAG, "⏭️ SCAN already in progress, skipping")
        return
    }
    try {
        val rootNode = rootInActiveWindow ?: return
        val contactName = currentChatContact ?: "Unknown"

        if (ULTRA_DEEP_LOGGING) {
            Log.d(TAG, "🌳 === FULL UI TREE DUMP (chat with '$contactName') ===")
            dumpUITree(rootNode)
            Log.d(TAG, "🌳 === END UI TREE DUMP ===")
        }

        val detectedMessages = findMessageNodes(rootNode)

        if (detectedMessages.isEmpty()) {
            rootNode.recycle()
            return
        }

        if (ULTRA_DEEP_LOGGING) {
            Log.d(TAG, "╔════════════════════════════════════════════╗")
            Log.d(TAG, "║ SCAN SUMMARY                               ║")
            Log.d(TAG, "╚════════════════════════════════════════════╝")
            Log.d(TAG, "  Contact: $contactName")
            Log.d(TAG, "  Detected messages: ${detectedMessages.size}")
            detectedMessages.forEachIndexed { index, msg ->
                Log.d(TAG, "    ${index + 1}. [${msg.assetType}] '${msg.text.take(30)}'")
            }
        }

        val uiTimestamps = collectUiTimestamps(rootNode)

        if (ULTRA_DEEP_LOGGING) {
            Log.d(TAG, "⏱️ UI TIMESTAMPS FOUND: ${uiTimestamps.size}")
            uiTimestamps.take(10).forEach { ts ->
                Log.d(TAG, "   - '${ts.raw}' -> ${ts.epochMs ?: "unparsed"} (x=${ts.x}, y=${ts.y})")
            }

        }

        data class MsgInfo(
            val detected: DetectedMessage,
            val isSentByUser: Boolean,
            val messageText: String,
            val assetType: String,
            val screenY: Int,
            val fingerprintBase: String
        )

        val msgInfos = mutableListOf<MsgInfo>()

        detectedMessages.forEach { detected ->
            val assetType = detected.assetType

            if (assetType == AssetType.TEXT && detected.text.isBlank()) {
                detected.node.recycle()
                return@forEach
            }

            val isSentByUser = isMessageSentByUser(detected.node)
            val messageText = when (assetType) {
                AssetType.TEXT -> detected.text
                else -> ""
            }

            val bounds = android.graphics.Rect()
            detected.node.getBoundsInScreen(bounds)

            val fpBase = when (assetType) {
                AssetType.TEXT -> "t|$isSentByUser|${messageText.trim()}"
                else -> {
                    val d = normalizeDesc(detected.text.orEmpty())
                    "m|$assetType|$isSentByUser|$d"
                }
            }

            msgInfos.add(MsgInfo(detected, isSentByUser, messageText, assetType, bounds.centerY(), fpBase))
        }

        var newMessageCount = 0

        data class LabeledMsgInfo(val mi: MsgInfo, val label: UiTimestamp?)

        val labeledMsgs = msgInfos.map { mi ->
            val bounds = android.graphics.Rect()
            mi.detected.node.getBoundsInScreen(bounds)
            val label = pickBestGroupLabel(bounds, uiTimestamps)
            LabeledMsgInfo(mi, if (label?.parsedDate != null) label else null)
        }

        val LABEL_TOLERANCE = 120
        val allMsgYs = msgInfos.map { it.screenY }

        val labelsWithMessages = labeledMsgs
            .filter { it.label != null }
            .map { "${it.label!!.parsedDate}|${it.label!!.parsedTime ?: ""}" }
            .toSet()

        val finalLabeled = labeledMsgs.map { lm ->
            if (lm.label != null) return@map lm

            val msgY = lm.mi.screenY
            val candidateLabel = uiTimestamps
                .filter { it.y in msgY..(msgY + LABEL_TOLERANCE) && it.parsedDate != null }
                .minByOrNull { it.y }

            if (candidateLabel != null) {
                val candidateKey = "${candidateLabel.parsedDate}|${candidateLabel.parsedTime ?: ""}"

                val closestMsgAboveLabel = allMsgYs
                    .filter { it < candidateLabel.y }
                    .maxOrNull()

                val isClosest = closestMsgAboveLabel != null && closestMsgAboveLabel == msgY
                val labelAlreadyHasMsgs = candidateKey in labelsWithMessages

                if (isClosest && !labelAlreadyHasMsgs) {
                    if (ULTRA_DEEP_LOGGING) {
                        Log.d(TAG, "   🔧 QUIRK FIX: msg y=$msgY assigned to label '${candidateLabel.raw}' y=${candidateLabel.y}")
                    }
                    LabeledMsgInfo(lm.mi, candidateLabel)
                } else {
                    if (ULTRA_DEEP_LOGGING) {
                        Log.d(TAG, "   ⚠️ SKIPPED QUIRK: msg y=$msgY, label '${candidateLabel.raw}' y=${candidateLabel.y} (closest=$isClosest, labelHasMsgs=$labelAlreadyHasMsgs)")
                    }
                    lm
                }
            } else {
                lm
            }
        }

        val withLabel = finalLabeled.filter { it.label != null }
        val withoutLabel = finalLabeled.filter { it.label == null }

        if (ULTRA_DEEP_LOGGING) {
            Log.d(TAG, "🔄 PROCESSING ${msgInfos.size} messages: ${withLabel.size} with label, ${withoutLabel.size} without")
            uiTimestamps.forEach { ts ->
                Log.d(TAG, "   📅 '${ts.raw}' y=${ts.y} date=${ts.parsedDate} time=${ts.parsedTime}")
            }
            Log.d(TAG, "   No-label messages: ${withoutLabel.size} (skipped)")
        }

        val byLabelKey = withLabel.groupBy { lm ->
            val l = lm.label!!
            "${l.parsedDate}|${l.parsedTime ?: ""}"
        }

        byLabelKey.forEach { (labelKey, group) ->
            val sortedByY = group.sortedBy { it.mi.screenY }
            val label = sortedByY[0].label!!

            val existingFirstFromDB: Long? = if (label.parsedTime != null) {
                messageDao.getFirstMessageIdByDateAndTime(contactName, label.parsedDate!!, label.parsedTime!!)
            } else {
                messageDao.getFirstMessageIdByDate(contactName, label.parsedDate!!)
            }
            var firstId = existingFirstFromDB

            if (ULTRA_DEEP_LOGGING) {
                Log.d(TAG, "   📦 LABEL GROUP '$labelKey': ${sortedByY.size} msgs, existingFirstId=$firstId")
            }

            sortedByY.forEachIndexed { index, lm ->
                val mi = lm.mi
                val isTopmost = (index == 0)

                val fp: String = if (mi.assetType == AssetType.TEXT) {
                    sha256("$contactName|t|${mi.isSentByUser}|${mi.messageText.trim()}|${label.parsedDate}|")
                } else {
                    val mediaOrd = sortedByY.take(index + 1).count {
                        it.mi.assetType == mi.assetType && it.mi.isSentByUser == mi.isSentByUser
                    } - 1
                    val d = normalizeDesc(mi.detected.text.orEmpty())
                    sha256("$contactName|m|${mi.assetType}|${mi.isSentByUser}|$d|${label.parsedDate}||$mediaOrd")
                }

                val shouldBeFirst = if (firstId == null) {
                    true
                } else if (isTopmost && existingFirstFromDB != null) {
                    true
                } else {
                    false
                }

                val msgTime = if (shouldBeFirst) label.parsedTime else null
                val msgGroupRef = if (shouldBeFirst) null else firstId

                val msgDuration = if (mi.assetType == AssetType.VOICE_NOTE) {
                    Regex("""\((\d+:\d{2})\)""").find(mi.detected.text)?.groupValues?.get(1)
                } else null

                val message = Message(
                    id = 0, contactName = contactName, messageText = mi.messageText,
                    date = label.parsedDate, time = msgTime, groupLabelMessageId = msgGroupRef,
                    isSentByUser = mi.isSentByUser,
                    assetType = mi.assetType, fingerprint = fp,
                    duration = msgDuration
                )

                val messageId = messageDao.insert(message)
                if (messageId != -1L) {
                    newMessageCount++

                    if (shouldBeFirst) {
                        if (existingFirstFromDB != null && isTopmost) {
                            messageDao.demoteFromFirst(existingFirstFromDB, messageId)
                            messageDao.reassignGroupRef(contactName, existingFirstFromDB, messageId)
                            Log.d(TAG, "  🔄 REASSIGNED first: old=#$existingFirstFromDB → new=#$messageId")
                        }
                        firstId = messageId
                    }
            val savedMessage = message.copy(id = messageId)
            ioScope.launch { remoteSyncHelper.syncMessage(savedMessage) }

                    val direction = if (mi.isSentByUser) "SENT" else "RECEIVED"
                    Log.d(TAG, "  ✅ SAVED [${mi.assetType}] [$direction]: '${mi.detected.text.take(50)}' (ID: $messageId, date=${label.parsedDate}, time=$msgTime, groupRef=$msgGroupRef)")
                } else {
                    if (firstId == null) {
                        val existingId = messageDao.getIdByFingerprint(contactName, fp)
                        if (existingId != null) firstId = existingId
                    }
                }
            }
        }

        if (withoutLabel.isNotEmpty() && ULTRA_DEEP_LOGGING) {
            Log.d(TAG, "  ⏭️ SKIPPED ${withoutLabel.size} messages without label (will capture on next scan with label)")
        }

        msgInfos.forEach { mi ->
            try { mi.detected.node.recycle() } catch (_: Exception) {}
        }

        if (newMessageCount > 0) {
            Log.d(TAG, "📨 Saved $newMessageCount new messages for '$contactName'")
        }

        rootNode.recycle()
    } catch (e: Exception) {
        Log.e(TAG, "Error capturing messages: ${e.message}", e)
    } finally {
        scanMutex.unlock()
    }
}

    private fun dumpUITree(node: AccessibilityNodeInfo?, depth: Int = 0, maxDepth: Int = 15) {
        if (node == null || depth > maxDepth) return

        val indent = "  ".repeat(depth)
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName?.substringAfterLast(':') ?: ""
        val className = node.className?.toString()?.substringAfterLast('.') ?: ""
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)

        val info = StringBuilder()
        info.append("$indent[$className]")
        if (viewId.isNotEmpty()) info.append(" ID:$viewId")
        if (text.isNotEmpty()) info.append(" Text:'${text.take(80)}'")
        if (contentDesc.isNotEmpty()) info.append(" Desc:'${contentDesc.take(80)}'")
        info.append(" bounds=(${bounds.left},${bounds.top},${bounds.right},${bounds.bottom})")

        Log.d(TAG, info.toString())

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
            // Sync to remote — fire and forget, non-blocking
            remoteSyncHelper.syncContact(contact)
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
            val timestampWithPrefix = Regex("^(today|yesterday|mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\s+\\d{1,2}:\\d{2}(\\s*[AaPp][Mm])?$", RegexOption.IGNORE_CASE)
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
                !timestampWithPrefix.matches(text) &&
                !paginationPattern.matches(text) &&
                !text.startsWith("Today", ignoreCase = true) &&
                !text.startsWith("Yesterday", ignoreCase = true) &&
                !text.equals("Call back", ignoreCase = true) &&
                !text.contains("call started", ignoreCase = true) &&
                !text.contains("call ended", ignoreCase = true) &&
                !text.contains("Missed", ignoreCase = true) &&
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
            "header_title",
            "thread_title",
            "direct_thread_header_title"
        )

        val excludedHeaderPatterns = listOf(
            "igds_action_bar_title",
            "action_bar_title"
        )
        
        fun searchForContact(node: AccessibilityNodeInfo, depth: Int = 0) {
            if (contactName != null) return

            if (node.text != null && node.text.isNotEmpty()) {
                val text = node.text.toString().trim()
                val viewId = node.viewIdResourceName ?: ""
                
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
                    !text.contains("For you", ignoreCase = true) &&
                    !text.contains("Chats", ignoreCase = true) &&
                    !text.contains("Channels", ignoreCase = true) &&
                    !text.contains("Notes", ignoreCase = true) &&
                    !text.contains("Groups", ignoreCase = true) &&
                    !text.contains("Messages", ignoreCase = true) &&
                    !text.contains("Direct", ignoreCase = true) &&
                    !text.contains("Instagram", ignoreCase = true) &&
                    !text.contains("Search", ignoreCase = true) &&
                    !text.contains("Create", ignoreCase = true) &&
                    !text.contains("Avatar", ignoreCase = true) &&
                    !text.contains("Sticker", ignoreCase = true) &&
                    !text.contains("GIF", ignoreCase = true) &&
                    !text.contains("Photo", ignoreCase = true) &&
                    !text.contains("Gallery", ignoreCase = true) &&
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


    private fun isInsideReplyBar(node: AccessibilityNodeInfo): Boolean {
    var current: AccessibilityNodeInfo? = node
    var depth = 0

    var hasComposerAncestor = false

    while (current != null && depth < 12) {
        val viewId = current.viewIdResourceName ?: ""
        val viewIdShort = viewId.substringAfterLast('/').substringAfterLast(':')
        val className = current.className?.toString() ?: ""

        if (
            viewIdShort.contains("composer", ignoreCase = true) ||
            viewIdShort.contains("thread_composer", ignoreCase = true) ||
            viewIdShort.contains("row_thread_composer", ignoreCase = true) ||
            viewIdShort.contains("composer_edittext", ignoreCase = true) ||
            className.contains("EditText", ignoreCase = true)
        ) {
            hasComposerAncestor = true
        }

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




    data class RawCallEntry(
        val title: String,
        val time: String?,
        val y: Int,
        val node: AccessibilityNodeInfo
    )

    private fun findMessageNodes(rootNode: AccessibilityNodeInfo): List<DetectedMessage> {
        val messages = mutableListOf<DetectedMessage>()
        val processedMediaIds = mutableSetOf<String>()
        val rawCallEntries = mutableListOf<RawCallEntry>()

        val messageViewIdPatterns = listOf(
            "direct_text_message_text_view",
            "message_content"
        )

        val validMediaViewIds = listOf(
            "animated_image",
            "media_flat",
            "media_container",
            "message_content_voice_playback_timer",
        )

        val excludedViewIds = listOf(
            "message_status",
            "timestamp",
            "composer_edittext",
            "row_thread_composer_edittext",
            "seen_indicator",
            "status",
            "hint",
            "placeholder",
            "avatar",
            "profile",
            "action_bar",
            "toolbar",
            "button",
            "icon",
            "row_thread_composer_voice"
        )

        val excludedContentDesc = listOf(
            "navigate up",
            "more options",
            "camera",
            "gallery",
            "send message",
            "like message",
            "liked",
            "audio call",
            "video call",
            "press and hold"
        )

        fun searchNodes(node: AccessibilityNodeInfo, depth: Int = 0) {
            val viewId = node.viewIdResourceName ?: ""
            val className = node.className?.toString() ?: ""
            val contentDesc = node.contentDescription?.toString() ?: ""
            val text = node.text?.toString() ?: ""
            val viewIdShort = viewId.substringAfterLast('/').substringAfterLast(':')

            if (ULTRA_DEEP_LOGGING) {
                val allLower = "$contentDesc $text $viewIdShort".lowercase()
                if (allLower.contains("reel") || allLower.contains("clip") ||
                    allLower.contains("call") || allLower.contains("voip") ||
                    allLower.contains("missed") || allLower.contains("rang") ||
                    allLower.contains("ended") || allLower.contains("duration") ||
                    allLower.contains("shared a reel") || allLower.contains("sent a reel")) {
                    val bounds = android.graphics.Rect()
                    node.getBoundsInScreen(bounds)
                    Log.d(TAG, "🎯 REEL/CALL NODE: viewId='$viewIdShort' class='${className.substringAfterLast('.')}' desc='${contentDesc.take(80)}' text='${text.take(80)}' pos=(${bounds.left},${bounds.top})")
                }
            }

            if (viewIdShort.equals("reel_share_item_view", ignoreCase = true)) {
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                val reelId = "reel-${bounds.top}-${bounds.left}"

                if (!processedMediaIds.contains(reelId)) {
                    processedMediaIds.add(reelId)

                    val reelDesc = if (contentDesc.isNotBlank()) contentDesc else "Shared a reel"

                    messages.add(
                        DetectedMessage(
                            text = reelDesc,
                            assetType = AssetType.REEL,
                            node = AccessibilityNodeInfo.obtain(node)
                        )
                    )
                    Log.d(TAG, "🎬 REEL DETECTED: '$reelDesc' at pos(${bounds.left},${bounds.top})")
                }
                return
            }

            if (viewIdShort.equals("call_state_icon", ignoreCase = true)) {
                val parent = node.parent
                if (parent != null) {
                    var callTitle: String? = null
                    var callTime: String? = null

                    for (i in 0 until parent.childCount) {
                        val sibling = parent.getChild(i) ?: continue
                        val sibViewId = sibling.viewIdResourceName?.substringAfterLast('/').orEmpty().substringAfterLast(':')
                        if (sibViewId.equals("title", ignoreCase = true) && sibling.text != null) {
                            callTitle = sibling.text.toString().trim()
                        }
                        if (sibViewId.equals("subtitle", ignoreCase = true) && sibling.text != null) {
                            callTime = sibling.text.toString().trim()
                        }
                        sibling.recycle()
                    }

                    if (callTitle != null) {
                        val bounds = android.graphics.Rect()
                        node.getBoundsInScreen(bounds)
                        val callId = "call-${bounds.top}-${bounds.left}"

                        if (!processedMediaIds.contains(callId)) {
                            processedMediaIds.add(callId)
                            rawCallEntries.add(RawCallEntry(
                                title = callTitle,
                                time = callTime,
                                y = bounds.centerY(),
                                node = AccessibilityNodeInfo.obtain(node)
                            ))
                            Log.d(TAG, "📞 CALL ENTRY: '$callTitle' time='$callTime' y=${bounds.centerY()}")
                        }
                    }
                    parent.recycle()
                }
                return
            }

            val looksLikeGift = nodeContainsGiftKeyword(node)

                        if (looksLikeGift) {
                            val bounds = android.graphics.Rect()
                            node.getBoundsInScreen(bounds)
                            val giftId = "gift-${bounds.top}-${bounds.left}"

                            if (!processedMediaIds.contains(giftId)) {
                                processedMediaIds.add(giftId)

                                val giftDesc = if (contentDesc.isNotBlank()) contentDesc else "Gift"

                                messages.add(
                                    DetectedMessage(
                                        text = giftDesc,
                                        assetType = AssetType.GIFT,
                                        node = AccessibilityNodeInfo.obtain(node)
                                    )
                                )
                                Log.d(TAG, "🎁 GIFT DETECTED: '$giftDesc' at pos(${bounds.left},${bounds.top})")
                            }
                             return
                        }

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

            val classShort = className.substringAfterLast('.')
            val mediaRelatedKeywords = listOf("media", "image", "video", "photo", "picture", "thumbnail", "preview", "playback", "player")
            val isMediaRelatedViewId = mediaRelatedKeywords.any { keyword ->
                viewIdShort.contains(keyword, ignoreCase = true)
            }

            if (isMediaRelatedViewId && viewIdShort.isNotBlank()) {
                Log.d(TAG, "📷 MEDIA-RELATED VIEW: viewId='$viewIdShort' | class='$classShort' | desc='${contentDesc.take(50)}'")
            }

            if (className.contains("ImageView", ignoreCase = true)) {
                val hasDesc = contentDesc.isNotBlank()
                if (hasDesc) {
                    Log.d(TAG, "🔍 IMAGEVIEW: desc='$contentDesc' | viewId='$viewIdShort'")
                } else if (viewIdShort.isNotBlank() && !viewIdShort.contains("avatar") && !viewIdShort.contains("icon")) {
                    Log.d(TAG, "🔍 IMAGEVIEW (no desc): viewId='$viewIdShort'")
                }
            }

            if (className.contains("Video", ignoreCase = true) || className.contains("Player", ignoreCase = true)) {
                Log.d(TAG, "🎬 VIDEO VIEW: viewId='$viewIdShort' | class='$classShort' | desc='${contentDesc.take(50)}'")
            }

            val matchesValidMediaViewId = validMediaViewIds.any { pattern ->
                viewIdShort.equals(pattern, ignoreCase = true)
            }

            val isMediaNode = matchesValidMediaViewId
            if (isMediaNode && viewIdShort.equals("message_content_voice_playback_timer", ignoreCase = true)) {
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                val mediaId = "voice_note-${bounds.top}-${bounds.left}"
                if (!processedMediaIds.contains(mediaId)) {
                    processedMediaIds.add(mediaId)
                    val durationText = text.takeIf { it.isNotBlank() }
                    Log.d(TAG, "🎤 VOICE MESSAGE DETECTED: viewId='$viewIdShort' text='$text' duration='$durationText' pos=(${bounds.left},${bounds.top})")
                    val voiceText = if (durationText != null) "Voice message ($durationText)" else "Voice message"
                    messages.add(DetectedMessage(
                        text = voiceText,
                        assetType = AssetType.VOICE_NOTE,
                        node = AccessibilityNodeInfo.obtain(node)
                    ))
                    Log.d(TAG, "✓ DETECTED [voice_note]: '$voiceText'")
                }
            }

            if (isMediaNode && (contentDesc.isNotBlank() || viewIdShort.equals("animated_image", ignoreCase = true))) {

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
            val descForKey = if (contentDesc.isNotBlank()) contentDesc else "animated_image"
            messages.add(
                DetectedMessage(
                    text = descForKey,
                    assetType = assetType,
                    node = AccessibilityNodeInfo.obtain(node)
                )
            )
            Log.d(TAG, "✓ DETECTED [$assetType]: '$descForKey' at pos(${bounds.left},${bounds.top})")
        }
    }
}

            if (node.text != null && node.text.isNotEmpty()) {
                val text = node.text.toString().trim()

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
                    !text.equals("Call back", ignoreCase = true) &&
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

                    if (!isInsideReplyBar(node)) {
                        messages.add(DetectedMessage(
                            text = text,
                            assetType = AssetType.TEXT,
                            node = AccessibilityNodeInfo.obtain(node)
                        ))
                        if (ULTRA_DEEP_LOGGING) {
                            Log.d(TAG, "  ✅ ADDED to detection list")
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
                if (quotedMessageText != null) return  // Already found, stop
                node.getChild(i)?.let { child ->
                    searchNodes(child, depth + 1)
                    child.recycle()
                }
            }
        }

        searchNodes(rootNode)

        // Process call entries: only keep "started" ones
        for (entry in rawCallEntries) {
            if (entry.title.contains("started", ignoreCase = true)) {
                val callAssetType = if (entry.title.contains("video", ignoreCase = true)) AssetType.VIDEO_CALL else AssetType.AUDIO_CALL
                val callText = if (entry.time != null) "${entry.title} (${entry.time})" else entry.title

                messages.add(
                    DetectedMessage(
                        text = callText,
                        assetType = callAssetType,
                        node = AccessibilityNodeInfo.obtain(entry.node)
                    )
                )
                Log.d(TAG, "📞 CALL DETECTED [$callAssetType]: '$callText'")
            }
        }

        rawCallEntries.forEach { try { it.node.recycle() } catch (_: Exception) {} }

        return messages
    }

    private fun extractMessageText(node: AccessibilityNodeInfo): String {
        return node.text?.toString()?.trim() ?: ""
    }

    private fun detectMediaType(node: AccessibilityNodeInfo): String {
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val className = node.className?.toString()?.lowercase() ?: ""

        return when {
            contentDesc.contains("gif") || viewId.contains("gif") -> AssetType.GIF
            contentDesc.contains("video") || viewId.contains("video") ||
            contentDesc.contains("reel") -> AssetType.VIDEO
            contentDesc.contains("voice") || contentDesc.contains("audio") ||
            viewId.contains("voice") || viewId.contains("audio") ||
            contentDesc.contains("voice message") -> AssetType.VOICE_NOTE
            contentDesc.contains("sticker") || viewId.contains("sticker") -> AssetType.STICKER
            contentDesc.contains("photo") || contentDesc.contains("image") ||
            viewId.contains("photo") || viewId.contains("image") ||
            className.contains("imageview") -> AssetType.IMAGE
            else -> AssetType.TEXT
        }
    }

    private fun detectMediaTypeFromHierarchy(node: AccessibilityNodeInfo): String {
        var assetType = detectMediaType(node)
        if (assetType != AssetType.TEXT) {
            return assetType
        }

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
