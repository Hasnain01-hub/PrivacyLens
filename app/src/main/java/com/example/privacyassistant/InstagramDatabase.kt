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

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactName: String,
    val messageText: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isSentByUser: Boolean = false
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
            isSentByUser
        FROM messages
        ORDER BY timestamp DESC
    """)
    suspend fun getAllMessagesWithReadableTime(): List<MessageWithReadableTime>
}

// Data class for messages with readable timestamp
data class MessageWithReadableTime(
    val id: Long,
    val contactName: String,
    val messageText: String,
    val timestamp: Long,
    val readableTime: String,  // e.g., "2024-12-30 16:15:17"
    val isSentByUser: Boolean
)

@Database(
    entities = [Contact::class, Message::class],
    version = 3,  // Incremented due to new queries
    exportSchema = true  // Changed to true for debugging
)
abstract class InstagramDatabase : RoomDatabase() {
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