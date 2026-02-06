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

// Asset types for messages
object AssetType {
    const val TEXT = "text"
    const val IMAGE = "image"
    const val VIDEO = "video"
    const val GIF = "gif"
    const val STICKER = "sticker"
    const val VOICE_NOTE = "voice_note"
    const val GIFT = "gift" 
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
    val timestamp: String,
    val isSentByUser: Boolean = false,
    val assetType: String = AssetType.TEXT,
    val replyMessage: String? = null,
    val replyMessageID: Long? = null,
    val fingerprint: String
)


@Dao
interface ContactDao {
    @Insert
    suspend fun insert(contact: Contact): Long

    @Query("SELECT * FROM contacts ORDER BY timestamp DESC")
    suspend fun getAllContacts(): List<Contact>

    @Query("SELECT * FROM contacts WHERE name = :name LIMIT 1")
    suspend fun getContactByName(name: String): Contact?

    // Query with readable timestamp for debugging/export
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

// Data class for contacts with readable timestamp
data class ContactWithReadableTime(
    val id: Long,
    val name: String,
    val timestamp: Long,
    val readableTime: String  // e.g., "2024-12-30 16:15:17"
)

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: Message): Long

    @Query("SELECT id FROM messages WHERE contactName = :contactName AND fingerprint = :fingerprint LIMIT 1")
    suspend fun getExistingIdByFingerprint(contactName: String, fingerprint: String): Long?

    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: Long): Message?

    @Query("SELECT * FROM messages WHERE contactName = :contactName ORDER BY timestamp ASC")
    suspend fun getMessagesByContact(contactName: String): List<Message>

    @Query("SELECT DISTINCT contactName FROM messages ORDER BY timestamp DESC")
    suspend fun getAllContactNames(): List<String>

    @Query("SELECT * FROM messages WHERE contactName = :contactName AND messageText = :messageText LIMIT 1")
    suspend fun findDuplicateMessage(contactName: String, messageText: String): Message?

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun getMessageCount(): Int

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    suspend fun getAllMessages(): List<Message>

    // Query with readable timestamp for debugging/export
    @Query("""
        SELECT
            id,
            contactName,
            messageText,
            timestamp,
            datetime(timestamp/1000, 'unixepoch', 'localtime') as readableTime,
            isSentByUser,
            assetType,
            replyMessage,
            replyMessageID
        FROM messages
        ORDER BY timestamp DESC
    """)
    suspend fun getAllMessagesWithReadableTime(): List<MessageWithReadableTime>

    // Find duplicate message including asset type
    @Query("SELECT * FROM messages WHERE contactName = :contactName AND messageText = :messageText AND assetType = :assetType LIMIT 1")
    suspend fun findDuplicateMessageWithAsset(contactName: String, messageText: String, assetType: String): Message?

    // Find recent voice message from contact (within time window to detect duplicates)
    @Query("SELECT * FROM messages WHERE contactName = :contactName AND assetType = 'voice_note' AND timestamp > :afterTimestampReadable ORDER BY timestamp DESC LIMIT 1")
    suspend fun findRecentVoiceMessage(contactName: String, afterTimestampReadable: String): Message?

    // Find recent media of specific type from contact (within time window to detect duplicates)
    @Query("SELECT * FROM messages WHERE contactName = :contactName AND assetType = :assetType AND timestamp > :afterTimestampReadable ORDER BY timestamp DESC LIMIT 1")
    suspend fun findRecentMedia(contactName: String, assetType: String, afterTimestampReadable: String): Message?


    // Find message by contact and text (for reply lookup)
    @Query("SELECT * FROM messages WHERE contactName = :contactName AND messageText = :messageText ORDER BY timestamp DESC LIMIT 1")
    suspend fun findMessageByText(contactName: String, messageText: String): Message?
}

// Data class for messages with readable timestamp
data class MessageWithReadableTime(
      val id: Long,
    val contactName: String,
    val messageText: String?,
    val timestamp: Long,
    val readableTime: String,  // e.g., "2024-12-30 16:15:17"
    val isSentByUser: Boolean,
    val assetType: String,
    val replyMessage: String?,
    val replyMessageID: Long?
)

@Database(
    entities = [Contact::class, Message::class],
    version = 5,  // Incremented for replyMessage and replyMessageID columns
    exportSchema = true  // Changed to true for debugging
)
@androidx.room.TypeConverters(TimeConverters::class)
abstract class InstagramDatabase : androidx.room.RoomDatabase(){
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    
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
                .setJournalMode(JournalMode.TRUNCATE)  // Better for debugging
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
        
        // Method to check if database file exists
        fun databaseExists(context: android.content.Context): Boolean {
            val dbFile = context.getDatabasePath("instagram-db")
            val exists = dbFile.exists()
            android.util.Log.d("InstagramDatabase", "Database file exists: $exists, path: ${dbFile.absolutePath}")
            return exists
        }
    }
}