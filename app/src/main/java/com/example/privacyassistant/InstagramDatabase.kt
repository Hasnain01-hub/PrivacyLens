package com.example.privacyassistant

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.RoomDatabase.JournalMode
import androidx.room.*
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val timestamp: Long = System.currentTimeMillis()
)

class TimeConverters {

    @androidx.room.TypeConverter
    fun fromEpochToReadable(epochMs: Long?): String? {
        if (epochMs == null || epochMs <= 0L) return null
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(epochMs))
    }

    @androidx.room.TypeConverter
    fun fromReadableToEpoch(readable: String?): Long? {
        if (readable.isNullOrBlank()) return null
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return try {
            sdf.parse(readable)?.time
        } catch (_: Exception) {
            null
        }
    }
}

object AssetType {
    const val TEXT = "text"
    const val IMAGE = "image"
    const val VIDEO = "video"
    const val GIF = "gif"
    const val STICKER = "sticker"
    const val VOICE_NOTE = "voice_note"
    const val GIFT = "gift"
    const val REEL = "reel"
    const val AUDIO_CALL = "audio_call"
    const val VIDEO_CALL = "video_call"
}

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["contactName", "fingerprint"], unique = true)
    ]
)
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactName: String,
    val messageText: String = "",
    val date: String? = null,
    val time: String? = null,
    val groupLabelMessageId: Long? = null,
    val isSentByUser: Boolean = false,
    val assetType: String = AssetType.TEXT,
    val fingerprint: String,
    val duration: String? = null
)


@Dao
interface ContactDao {
    @Insert
    suspend fun insert(contact: Contact): Long

    @Query("SELECT * FROM contacts ORDER BY timestamp DESC")
    suspend fun getAllContacts(): List<Contact>

    @Query("SELECT * FROM contacts WHERE name = :name LIMIT 1")
    suspend fun getContactByName(name: String): Contact?

    @Query("""
        SELECT
            id,
            name,
            timestamp,
            datetime(timestamp/1000, 'unixepoch', 'localtime') as readableTime
        FROM contacts
        ORDER BY timestamp DESC
    """)
    suspend fun getAllContactsWithReadableTime(): List<ContactWithReadableTime>
}

data class ContactWithReadableTime(
    val id: Long,
    val name: String,
    val timestamp: Long,
    val readableTime: String
)

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: Message): Long

    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: Long): Message?

    @Query("SELECT * FROM messages WHERE contactName = :contactName ORDER BY date ASC, id ASC")
    suspend fun getMessagesByContact(contactName: String): List<Message>

    @Query("SELECT DISTINCT contactName FROM messages ORDER BY id DESC")
    suspend fun getAllContactNames(): List<String>

    @Query("SELECT * FROM messages WHERE contactName = :contactName AND messageText = :messageText LIMIT 1")
    suspend fun findDuplicateMessage(contactName: String, messageText: String): Message?

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun getMessageCount(): Int

    @Query("SELECT * FROM messages ORDER BY id DESC")
    suspend fun getAllMessages(): List<Message>

    @Query("""
        SELECT
            id,
            contactName,
            messageText,
            date,
            time,
            groupLabelMessageId,
            isSentByUser,
            assetType,
            duration
        FROM messages
        ORDER BY id DESC
    """)
    suspend fun getAllMessagesWithReadableTime(): List<MessageWithReadableTime>

    @Query("SELECT * FROM messages WHERE contactName = :contactName AND messageText = :messageText AND assetType = :assetType LIMIT 1")
    suspend fun findDuplicateMessageWithAsset(contactName: String, messageText: String, assetType: String): Message?

    @Query("SELECT * FROM messages WHERE contactName = :contactName AND assetType = 'voice_note' AND id > :afterId ORDER BY id DESC LIMIT 1")
    suspend fun findRecentVoiceMessage(contactName: String, afterId: Long): Message?

    @Query("SELECT * FROM messages WHERE contactName = :contactName AND assetType = :assetType AND id > :afterId ORDER BY id DESC LIMIT 1")
    suspend fun findRecentMedia(contactName: String, assetType: String, afterId: Long): Message?

    @Query("SELECT id FROM messages WHERE contactName = :contactName AND fingerprint = :fingerprint LIMIT 1")
    suspend fun getIdByFingerprint(contactName: String, fingerprint: String): Long?

    @Query("SELECT id FROM messages WHERE contactName = :contactName AND date = :date AND time = :time AND groupLabelMessageId IS NULL LIMIT 1")
    suspend fun getFirstMessageIdByDateAndTime(contactName: String, date: String, time: String): Long?

    @Query("SELECT id FROM messages WHERE contactName = :contactName AND date = :date AND groupLabelMessageId IS NULL ORDER BY id ASC LIMIT 1")
    suspend fun getFirstMessageIdByDate(contactName: String, date: String): Long?

    @Query("""
        SELECT assetType, isSentByUser, COUNT(*) as cnt
        FROM messages
        WHERE contactName = :contactName AND assetType != 'text'
        GROUP BY assetType, isSentByUser
    """)
    suspend fun getMediaCounts(contactName: String): List<MediaTypeCount>

    @Query("SELECT fingerprint FROM messages WHERE contactName = :contactName")
    suspend fun getFingerprints(contactName: String): List<String>

    @Query("SELECT COUNT(*) FROM messages WHERE contactName = :contactName AND messageText = :messageText AND isSentByUser = :isSentByUser AND assetType = 'text'")
    suspend fun countTextByContent(contactName: String, messageText: String, isSentByUser: Boolean): Int

    @Query("UPDATE messages SET time = NULL, groupLabelMessageId = :newFirstId WHERE id = :oldFirstId")
    suspend fun demoteFromFirst(oldFirstId: Long, newFirstId: Long)

    @Query("UPDATE messages SET groupLabelMessageId = :newFirstId WHERE contactName = :contactName AND groupLabelMessageId = :oldFirstId")
    suspend fun reassignGroupRef(contactName: String, oldFirstId: Long, newFirstId: Long)

}

data class MediaTypeCount(
    val assetType: String,
    val isSentByUser: Boolean,
    val cnt: Int
)

data class MessageWithReadableTime(
    val id: Long,
    val contactName: String,
    val messageText: String?,
    val date: String?,
    val time: String?,
    val groupLabelMessageId: Long?,
    val isSentByUser: Boolean,
    val assetType: String,
    val duration: String?
) {
    val readableTime: String
        get() = when {
            date != null && time != null -> "$date $time"
            date != null -> date
            else -> "unknown"
        }
}

// ── Usage tracking (first/last open) ────────────────────────
@Entity(tableName = "app_usage")
data class AppUsage(
    @PrimaryKey val id: Int = 1, // singleton row
    val firstOpenMs: Long,
    val lastOpenMs: Long,
    val lastClassifiedMs: Long = 0L
)

@Dao
interface AppUsageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(usage: AppUsage)

    @Query("UPDATE app_usage SET lastOpenMs = :now WHERE id = 1")
    suspend fun updateLastOpen(now: Long)
    
    @Query("UPDATE app_usage SET lastClassifiedMs = :time WHERE id = 1")
    suspend fun updateLastClassified(time: Long)

    @Query("SELECT * FROM app_usage WHERE id = 1")
    suspend fun get(): AppUsage?
}

// ── Contact classification ───────────────────────────────────
enum class ContactRelation { FRIEND, MUTUAL_FRIEND, FAMILY, UNKNOWN }

@Entity(tableName = "contact_relations")
data class ContactRelation_Entity(
    @PrimaryKey val contactName: String,
    val relation: String,           // stores ContactRelation.name
    val classifiedAtMs: Long = System.currentTimeMillis()
)

@Dao
interface ContactRelationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ContactRelation_Entity)

    @Query("SELECT contactName FROM contact_relations")
    suspend fun getAllClassifiedNames(): List<String>

    @Query("SELECT * FROM contact_relations")
    suspend fun getAll(): List<ContactRelation_Entity>
}

@Database(
    entities = [Contact::class, Message::class, AppUsage::class, ContactRelation_Entity::class],
    version = 12,
    exportSchema = false
)
@androidx.room.TypeConverters(TimeConverters::class)
abstract class InstagramDatabase : androidx.room.RoomDatabase(){
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun appUsageDao(): AppUsageDao
    abstract fun contactRelationDao(): ContactRelationDao

    companion object {
        @Volatile
        private var INSTANCE: InstagramDatabase? = null
        
        fun getInstance(context: android.content.Context): InstagramDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    InstagramDatabase::class.java,
                    "instagram-db"
                )
                .fallbackToDestructiveMigration()
                .setJournalMode(JournalMode.TRUNCATE)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        android.util.Log.d("InstagramDatabase", "Database created at: ${context.getDatabasePath("instagram-db").absolutePath}")
                    }
                    
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        android.util.Log.d("InstagramDatabase", "Database opened")
                    }
                })
                .build()
                INSTANCE = instance
                instance
            }
        }
        
        fun databaseExists(context: android.content.Context): Boolean {
            val dbFile = context.getDatabasePath("instagram-db")
            val exists = dbFile.exists()
            android.util.Log.d("InstagramDatabase", "Database file exists: $exists, path: ${dbFile.absolutePath}")
            return exists
        }
    }
}
