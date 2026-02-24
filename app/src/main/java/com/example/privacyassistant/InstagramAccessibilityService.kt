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
        const val ULTRA_DEEP_LOGGING = true // Set to false to reduce log noise
        private var instance: InstagramAccessibilityService? = null
        fun getInstance(): InstagramAccessibilityService? = instance
    }

    private lateinit var database: InstagramDatabase
    private lateinit var contactDao: ContactDao
    private lateinit var messageDao: MessageDao
    private lateinit var notificationHelper: NotificationHelper
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val scanMutex = Mutex()

    private var isInDirectMessages = false
    private var currentChatContact: String? = null

    // Count-based dedup for media: tracks how many of each media type have been saved
    // Key: "contactName|assetType|isSentByUser", Value: saved count
    // Initialized from DB on chat open, incremented when saving new media
    private val mediaSavedCounts = mutableMapOf<String, Int>()

    // Tracks how many of each media type were visible on the PREVIOUS scan
    // Used to detect when new media appears (currentVisible > prevVisible)
    private val prevVisibleMediaCounts = mutableMapOf<String, Int>()
    private var mediaCalibrated = false  // false = first scan uses DB count, true = use prevVisible

    // Cache text fingerprints to avoid wasting auto-increment IDs on repeated IGNORE inserts
    private val savedTextFingerprints = mutableSetOf<String>()

    // Pending messages: waiting for group timestamp label to scroll into view before saving.
    // Key = fingerprint. Both text and media messages can be deferred.
    private val pendingMessages = mutableMapOf<String, PendingMessage>()
    private val PENDING_TIMEOUT_MS = 30_000L  // 30s before fallback save with null date

    // Data class to hold detected message info
    data class DetectedMessage(
        val text: String,
        val assetType: String,
        val node: AccessibilityNodeInfo
    )

    // Data class for messages waiting for their group timestamp label to become visible
    data class PendingMessage(
        val contactName: String,
        val messageText: String,
        val isSentByUser: Boolean,
        val assetType: String,
        val fingerprint: String,
        val firstSeenMs: Long,
        val mediaGroupKey: String? = null  // non-null for media, used for count tracking on flush
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

// Parsed result from a group label
data class ParsedGroupLabel(
    val date: String?,  // "yyyy-MM-dd", null if unparseable
    val time: String?,  // "HH:mm:ss", null if label has no time component
    val epochMs: Long?  // for ordering/comparison (noon if no time)
)

data class UiTimestamp(
    val raw: String,
    val parsedDate: String?,   // "yyyy-MM-dd"
    val parsedTime: String?,   // "HH:mm:ss" or null if label has no time
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

    // Ignore "Seen", "Active", etc.
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

    // Helper for year guessing: if date is in the future, use previous year
    fun guessYear(cal: java.util.Calendar) {
        if (cal.timeInMillis > nowMs) {
            cal.set(java.util.Calendar.YEAR, cal.get(java.util.Calendar.YEAR) - 1)
        }
    }

    // 1) Pure time: "13:45" / "1:45 PM" → today's date + exact time
    tryParseTimeOnly(label)?.let { (hh, mm) ->
        val cal = setTime(nowCal, hh, mm)
        return result(cal, hasTime = true)
    }

    // 2) "Yesterday"
    if (lower == "yesterday") {
        val cal = calAtNoon(nowCal)
        cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        return result(cal, hasTime = false)
    }

    // 3) "Tue 14:45" / "Fri 21:06" (day-of-week + time, previous week messages)
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

    // 4) Day-of-week only: "Mon" → date only, no time
    dayOfWeekFromLabel(label)?.let { dow ->
        val cal = mostRecentDow(dow, nowMs)
        return result(calAtNoon(cal), hasTime = false)
    }

    // 5) "Mon 12" (dow + day-of-month)
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

    // 6) "Last year"
    if (lower == "last year") {
        val c = calAtNoon(nowCal)
        c.add(java.util.Calendar.YEAR, -1)
        return result(c, hasTime = false)
    }

    // 7) "25 Dec 2020" (day month year) → date only
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

    // 8) "30 Dec, 03:07" (day month, time - no year) → date + time
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

    // 9) "Dec 25, 2020" (US format: month day, year) → date only
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

    // 10) "30 Dec" (day month, no year, no time) → date only
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

        // Avoid common non-timestamp UI strings
        if (lower.startsWith("seen") || lower.contains("type a message") || lower == "messages") return false

        val dowNames = "mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun|monday|tuesday|wednesday|thursday|friday|saturday|sunday"
        val monthNames = "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec"

        return Regex("""^\d{1,2}:\d{2}(\s*[AaPp][Mm])?$""").matches(s) ||  // 13:45, 1:45 PM
                lower == "yesterday" ||
                lower == "last year" ||
                // "Mon", "Mon 12" (dow only or dow + day-of-month)
                Regex("""^($dowNames)(\s+\d{1,2})?$""", RegexOption.IGNORE_CASE).matches(s) ||
                // "Tue 14:45", "Fri 21:06" (dow + time, previous week)
                Regex("""^($dowNames),?\s*\d{1,2}:\d{2}(\s*[AaPp][Mm])?$""", RegexOption.IGNORE_CASE).matches(s) ||
                // "25 Dec 2020" (day month year)
                Regex("""^\d{1,2}\s+($monthNames)\w*\s+\d{4}$""", RegexOption.IGNORE_CASE).matches(s) ||
                // "30 Dec, 03:07" (day month, time)
                Regex("""^\d{1,2}\s+($monthNames)\w*,\s*\d{1,2}:\d{2}(\s*[AaPp][Mm])?$""", RegexOption.IGNORE_CASE).matches(s) ||
                // "Dec 25, 2020" (month day, year - US format)
                Regex("""^($monthNames)\w*\s+\d{1,2},?\s+\d{4}$""", RegexOption.IGNORE_CASE).matches(s) ||
                // "30 Dec" (day month, no year, no time)
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

// Returns the best matching group label for this message, or null if none found.
// Instagram renders the first message of a group with its centerY slightly ABOVE
// the group label's centerY (~70-100px). So we first try strict-above, then fall
// back to labels slightly below the message within a tolerance window.
private fun pickBestGroupLabel(
    messageBounds: android.graphics.Rect,
    uiTimestamps: List<UiTimestamp>
): UiTimestamp? {
    if (uiTimestamps.isEmpty()) return null

    val msgY = messageBounds.centerY()

    // First: try strict above (label center is above message center)
    val strictlyAbove = uiTimestamps
        .filter { it.y < msgY && it.parsedDate != null }
        .maxByOrNull { it.y }
    if (strictlyAbove != null) return strictlyAbove

    // Fallback: label slightly below the message (within tolerance).
    // This handles the first-message-above-label rendering quirk in Instagram.
    val LABEL_TOLERANCE = 120
    return uiTimestamps
        .filter { it.y in msgY..(msgY + LABEL_TOLERANCE) && it.parsedDate != null }
        .minByOrNull { it.y }
}


private fun extractMessageData(messageNode: AccessibilityNodeInfo): MessageData {
    var messageText: String? = null
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
                mediaSavedCounts.clear()
                prevVisibleMediaCounts.clear()
                mediaCalibrated = false
                savedTextFingerprints.clear()
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

                // Initialize media saved counts from DB for this contact
                mediaSavedCounts.clear()
                prevVisibleMediaCounts.clear()
                mediaCalibrated = false
                savedTextFingerprints.clear()
                pendingMessages.clear()
                try {
                    val counts = messageDao.getMediaCounts(contactName)
                    counts.forEach { mc ->
                        val key = "$contactName|${mc.assetType}|${mc.isSentByUser}"
                        mediaSavedCounts[key] = mc.cnt
                    }
                    Log.d(TAG, "📊 Media counts from DB: $mediaSavedCounts")

                    // Load all saved fingerprints so count-based dedup has an accurate baseline
                    val fps = messageDao.getFingerprints(contactName)
                    savedTextFingerprints.addAll(fps)
                    Log.d(TAG, "📊 Loaded ${fps.size} fingerprints from DB")
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading media counts / fingerprints: ${e.message}")
                }

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
    ordinal: Int
): String {
    val base = when (assetType) {
        AssetType.TEXT -> "t|$isSentByUser|${messageText.orEmpty().trim()}"
        else -> {
            val d = normalizeDesc(detectedDesc.orEmpty())
            "m|$assetType|$isSentByUser|$d"
        }
    }

    // Ordinal = position among same-content messages on screen (stable across rescans)
    // This ensures two "Hi" messages get different fingerprints (ordinal 0, ordinal 1)
    // while the same "Hi" always gets the same fingerprint on every scan
    return sha256("$contactName|$base|$ordinal")
}

private suspend fun captureNewMessages() {
    // Serialize: only one scan at a time to prevent concurrent label/timestamp races
    if (!scanMutex.tryLock()) {
        Log.d(TAG, "⏭️ SCAN already in progress, skipping")
        return
    }
    try {
        val rootNode = rootInActiveWindow ?: return
        val contactName = currentChatContact ?: "Unknown"

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

        // === HYBRID DEDUP ===
        // TEXT: Ordinal-based fingerprints (content naturally distinguishes messages)
        // MEDIA: Count-based dedup (all images look the same, so count visible vs saved)

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

        // --- Flush timed-out pending messages (save with null date as fallback) ---
        val flushNow = System.currentTimeMillis()
        val timedOutKeys = pendingMessages.keys.filter { fp ->
            flushNow - pendingMessages[fp]!!.firstSeenMs > PENDING_TIMEOUT_MS
        }
        timedOutKeys.forEach { fp ->
            val pm = pendingMessages.remove(fp) ?: return@forEach
            val message = Message(
                id = 0, contactName = pm.contactName, messageText = pm.messageText,
                date = null, time = null, groupLabelMessageId = null,
                isSentByUser = pm.isSentByUser,
                assetType = pm.assetType, fingerprint = pm.fingerprint
            )
            val messageId = messageDao.insert(message)
            if (messageId != -1L) {
                newMessageCount++
                Log.d(TAG, "  ⏰ TIMEOUT FLUSH [${pm.assetType}]: '${pm.messageText.take(50)}' (ID: $messageId, date=null)")
                if (pm.assetType == AssetType.TEXT) {
                    savedTextFingerprints.add(fp)
                }
                if (pm.mediaGroupKey != null) {
                    mediaSavedCounts[pm.mediaGroupKey] = mediaSavedCounts.getOrDefault(pm.mediaGroupKey, 0) + 1
                }
            }
        }

        // === UNIFIED PROCESSING: all messages grouped by label ===
        // Process text + media together so the ACTUAL first message under a label
        // (whether text, image, video, etc.) gets date+time correctly.

        data class NewMessage(
            val mi: MsgInfo,
            val fingerprint: String,
            val ordinal: Int?,
            val isText: Boolean,
            val mediaGroupKey: String?  // non-null for media, used to increment savedCount
        )

        val newMessages = mutableListOf<NewMessage>()

        // --- Step 1: Identify new TEXT messages (count-based dedup) ---
        // Groups identical text messages and compares visible count against DB + pending count.
        // Ordinals are assigned sequentially (accountedFor, accountedFor+1, …) so they never
        // shift when the user scrolls and the same text appears at different screen-Y positions.
        val textMsgs = msgInfos.filter { it.assetType == AssetType.TEXT }
        val textMsgsByContent = textMsgs.groupBy { "${it.isSentByUser}|${it.messageText.trim()}" }

        textMsgsByContent.forEach { (contentKey, group) ->
            val sorted = group.sortedBy { it.screenY }
            val visibleCount = sorted.size
            val rep = sorted[0]

            // How many of this exact text+sender are already saved in DB?
            val savedCount = messageDao.countTextByContent(contactName, rep.messageText, rep.isSentByUser)
            // How many are pending (waiting for their group label)?
            val pendingCount = pendingMessages.values.count {
                it.assetType == AssetType.TEXT &&
                it.contactName == contactName &&
                it.messageText.trim() == rep.messageText.trim() &&
                it.isSentByUser == rep.isSentByUser
            }
            val accountedFor = savedCount + pendingCount
            val newCount = maxOf(0, visibleCount - accountedFor)

            if (ULTRA_DEEP_LOGGING) {
                Log.d(TAG, "📝 TEXT GROUP '$contentKey': visible=$visibleCount saved=$savedCount pending=$pendingCount new=$newCount")
            }

            if (newCount > 0) {
                // Assign next sequential ordinals to the bottom N messages (most recent)
                sorted.takeLast(newCount).forEachIndexed { index, mi ->
                    val ordinal = accountedFor + index
                    val fp = buildFingerprint(contactName, mi.assetType, mi.isSentByUser,
                        mi.messageText, null, ordinal)
                    newMessages.add(NewMessage(mi, fp, ordinal, isText = true, mediaGroupKey = null))
                }
            }
        }

        // --- Step 1b: Re-add pending text for label re-checking ---
        // Pending text messages won't be re-detected by count-based dedup (they're in
        // accountedFor). We must re-add them so Step 4 can flush them when their label appears.
        pendingMessages.forEach { (fp, pm) ->
            if (pm.assetType == AssetType.TEXT && pm.contactName == contactName) {
                val matchingMi = textMsgs.firstOrNull {
                    it.messageText.trim() == pm.messageText.trim() &&
                    it.isSentByUser == pm.isSentByUser
                }
                if (matchingMi != null && newMessages.none { it.fingerprint == fp }) {
                    newMessages.add(NewMessage(matchingMi, fp, null, isText = true, mediaGroupKey = null))
                    if (ULTRA_DEEP_LOGGING) {
                        Log.d(TAG, "📝 RE-ADDED pending text for label re-check: '${pm.messageText.take(30)}' fp=${fp.take(8)}")
                    }
                }
            }
        }

        // --- Step 2: Identify new MEDIA messages (count-based dedup) ---
        val mediaMsgs = msgInfos.filter { it.assetType != AssetType.TEXT }
        val mediaGroups = mediaMsgs.groupBy { "$contactName|${it.assetType}|${it.isSentByUser}" }

        mediaGroups.forEach { (groupKey, group) ->
            val sorted = group.sortedBy { it.screenY }
            val currentVisible = sorted.size
            val savedCount = mediaSavedCounts.getOrDefault(groupKey, 0)

            val newCount: Int
            if (!mediaCalibrated) {
                newCount = maxOf(0, currentVisible - savedCount)
            } else {
                val prevVisible = prevVisibleMediaCounts.getOrDefault(groupKey, 0)
                val baseline = maxOf(prevVisible, savedCount)
                newCount = maxOf(0, currentVisible - baseline)
            }

            if (ULTRA_DEEP_LOGGING) {
                val prevVis = if (mediaCalibrated) prevVisibleMediaCounts.getOrDefault(groupKey, 0).toString() else "N/A(first)"
                Log.d(TAG, "📊 MEDIA GROUP '$groupKey': visible=$currentVisible saved=$savedCount prevVisible=$prevVis new=$newCount calibrated=$mediaCalibrated")
            }

            prevVisibleMediaCounts[groupKey] = currentVisible

            if (newCount > 0) {
                val newMsgs = sorted.takeLast(newCount)
                newMsgs.forEachIndexed { index, mi ->
                    val ordinal = savedCount + index
                    val fp = buildFingerprint(contactName, mi.assetType, mi.isSentByUser,
                        mi.messageText, mi.detected.text, ordinal)
                    newMessages.add(NewMessage(mi, fp, ordinal, isText = false, mediaGroupKey = groupKey))
                }
            }
        }

        // Reset prevVisible for media types no longer on screen
        val visibleGroupKeys = mediaGroups.keys
        val staleKeys = prevVisibleMediaCounts.keys.filter { it !in visibleGroupKeys }
        staleKeys.forEach { key ->
            if (ULTRA_DEEP_LOGGING && prevVisibleMediaCounts[key] != 0) {
                Log.d(TAG, "📊 MEDIA GROUP '$key' no longer visible, resetting prevVisible from ${prevVisibleMediaCounts[key]} to 0")
            }
            prevVisibleMediaCounts[key] = 0
        }
        if (mediaMsgs.isNotEmpty() || mediaCalibrated) {
            mediaCalibrated = true
        }

        // --- Step 2b: Re-add pending media for label re-checking ---
        // Media uses count-based dedup which won't re-detect pending (unsaved) media.
        // We iterate all visible media, compute fingerprints, and check against pending.
        mediaGroups.forEach { (groupKey, group) ->
            val sorted = group.sortedBy { it.screenY }
            sorted.forEachIndexed { index, mi ->
                val fp = buildFingerprint(contactName, mi.assetType, mi.isSentByUser,
                    mi.messageText, mi.detected.text, index)
                if (fp in pendingMessages && newMessages.none { it.fingerprint == fp }) {
                    newMessages.add(NewMessage(mi, fp, index, isText = false, mediaGroupKey = groupKey))
                    if (ULTRA_DEEP_LOGGING) {
                        Log.d(TAG, "📊 RE-ADDED pending media for label re-check: fp=${fp.take(8)} groupKey=$groupKey")
                    }
                }
            }
        }

        // --- Step 3: Assign label to each message and split into has-label / no-label ---
        data class LabeledMsg(val nm: NewMessage, val label: UiTimestamp?)

        val labeledMsgs = newMessages.map { nm ->
            val bounds = android.graphics.Rect()
            nm.mi.detected.node.getBoundsInScreen(bounds)
            val label = pickBestGroupLabel(bounds, uiTimestamps)
            LabeledMsg(nm, if (label?.parsedDate != null) label else null)
        }

        val withLabel = labeledMsgs.filter { it.label != null }
        val withoutLabel = labeledMsgs.filter { it.label == null }

        if (ULTRA_DEEP_LOGGING) {
            Log.d(TAG, "🔄 PROCESSING ${newMessages.size} messages: ${withLabel.size} with label, ${withoutLabel.size} without")
            uiTimestamps.forEach { ts ->
                Log.d(TAG, "   📅 '${ts.raw}' y=${ts.y} date=${ts.parsedDate} time=${ts.parsedTime}")
            }
            Log.d(TAG, "   Pending buffer size: ${pendingMessages.size}")
        }

        // --- Step 4: Group messages by label, save first-in-group first ---
        // This guarantees the TOPMOST message under a label (nearest to it on screen)
        // becomes the "first" message with date+time, and all others point to it.
        val byLabelKey = withLabel.groupBy { lm ->
            val l = lm.label!!
            "${l.parsedDate}|${l.parsedTime ?: ""}"
        }

        byLabelKey.forEach { (labelKey, group) ->
            val sortedByY = group.sortedBy { it.nm.mi.screenY }
            val label = sortedByY[0].label!!

            // Check DB: does a "first" message already exist for this date+time?
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
                val nm = lm.nm
                val mi = nm.mi
                val fp = nm.fingerprint
                val isTopmost = (index == 0)

                // If this was pending, remove from pending buffer
                val wasPending = fp in pendingMessages
                if (wasPending) pendingMessages.remove(fp)

                // Decide if this message should be the "first" under this label.
                // Case 1: No first exists yet → this topmost message becomes first.
                // Case 2: A first exists from DB, but this NEW topmost message is CLOSER
                //         to the label (appeared after more scrolling) → REASSIGN first.
                val shouldBeFirst = if (firstId == null) {
                    true
                } else if (isTopmost && existingFirstFromDB != null) {
                    true // Will reassign if INSERT succeeds (message is genuinely new)
                } else {
                    false
                }

                val msgTime = if (shouldBeFirst) label.parsedTime else null
                val msgGroupRef = if (shouldBeFirst) null else firstId

                val message = Message(
                    id = 0, contactName = contactName, messageText = mi.messageText,
                    date = label.parsedDate, time = msgTime, groupLabelMessageId = msgGroupRef,
                    isSentByUser = mi.isSentByUser,
                    assetType = mi.assetType, fingerprint = fp
                )

                val messageId = messageDao.insert(message)
                if (messageId != -1L) {
                    newMessageCount++

                    if (shouldBeFirst) {
                        if (existingFirstFromDB != null && isTopmost) {
                            // REASSIGN: this new message is closer to the label
                            // 1. Demote old first: clear its time, point it to new first
                            messageDao.demoteFromFirst(existingFirstFromDB, messageId)
                            // 2. Redirect all messages that pointed to old first → new first
                            messageDao.reassignGroupRef(contactName, existingFirstFromDB, messageId)
                            Log.d(TAG, "  🔄 REASSIGNED first: old=#$existingFirstFromDB → new=#$messageId")
                        }
                        firstId = messageId
                    }

                    val tag = if (wasPending) "${mi.assetType}-flushed" else mi.assetType
                    val direction = if (mi.isSentByUser) "SENT" else "RECEIVED"
                    Log.d(TAG, "  ✅ SAVED [$tag] [$direction]: '${mi.detected.text.take(50)}' (ID: $messageId, date=${label.parsedDate}, time=$msgTime, groupRef=$msgGroupRef)")

                    // Update caches
                    if (nm.isText) savedTextFingerprints.add(fp)
                    if (nm.mediaGroupKey != null) {
                        mediaSavedCounts[nm.mediaGroupKey!!] = mediaSavedCounts.getOrDefault(nm.mediaGroupKey!!, 0) + 1
                    }
                } else {
                    // INSERT IGNORE — already saved. Look up its ID so the chain stays correct.
                    if (firstId == null) {
                        val existingId = messageDao.getIdByFingerprint(contactName, fp)
                        if (existingId != null) firstId = existingId
                    }
                }
            }
        }

        // --- Step 5: Messages without a label → defer to pending buffer ---
        withoutLabel.forEach { lm ->
            val nm = lm.nm
            val fp = nm.fingerprint

            // Already pending → just leave it (Step 1b/2b re-added it for label re-check but label still absent)
            if (fp in pendingMessages) {
                if (ULTRA_DEEP_LOGGING) {
                    Log.d(TAG, "  ⏳ STILL PENDING: '${nm.mi.messageText.take(50)}' [${nm.mi.assetType}]")
                }
            } else {
                // New message with no label above — cache it
                pendingMessages[fp] = PendingMessage(
                    contactName = contactName,
                    messageText = nm.mi.messageText,
                    isSentByUser = nm.mi.isSentByUser,
                    assetType = nm.mi.assetType,
                    fingerprint = fp,
                    firstSeenMs = System.currentTimeMillis(),
                    mediaGroupKey = nm.mediaGroupKey
                )
                if (ULTRA_DEEP_LOGGING) {
                    Log.d(TAG, "  ⏳ DEFERRED (no label): '${nm.mi.messageText.take(50)}' [${nm.mi.assetType}]")
                }
            }
        }

        // Cleanup: recycle all detected message nodes
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
                    messages.add(DetectedMessage(
                        text = voiceText,
                        assetType = AssetType.VOICE_NOTE,
                        node = AccessibilityNodeInfo.obtain(node)
                    ))
                    Log.d(TAG, "✓ DETECTED [voice_note]: '$voiceText'")
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
            // if no desc, store a small placeholder for logging/dedup signature
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
