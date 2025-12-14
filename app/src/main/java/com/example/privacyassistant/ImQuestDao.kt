package com.example.privacyassistant

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ImQuestDao {

    @Upsert
    suspend fun upsertImQuest(imQuest: ImQuest)

    @Delete
    suspend fun deleteImQuest(imQuest: ImQuest)

    @Query(("SELECT * from imQuests"))
    fun getEntries(): Flow<List<ImQuest>>

    @Query("DELETE FROM imQuests")
    suspend fun clearAll()
}