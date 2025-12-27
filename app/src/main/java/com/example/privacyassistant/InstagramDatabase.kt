package com.example.privacyassistant

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
}

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: Message): Long
    
    @Query("SELECT * FROM messages WHERE contactName = :contactName ORDER BY timestamp ASC")
    suspend fun getMessagesByContact(contactName: String): List<Message>
    
    @Query("SELECT DISTINCT contactName FROM messages ORDER BY timestamp DESC")
    suspend fun getAllContactNames(): List<String>
}

@Database(
    entities = [Contact::class, Message::class],
    version = 1,
    exportSchema = false
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
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}