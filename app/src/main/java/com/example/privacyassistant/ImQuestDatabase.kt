package com.example.privacyassistant

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ImQuest::class, User::class],
    version = 4
)
abstract class Database: RoomDatabase() {
    abstract val imQuestDao: ImQuestDao
    abstract val userDao: UserDao
}