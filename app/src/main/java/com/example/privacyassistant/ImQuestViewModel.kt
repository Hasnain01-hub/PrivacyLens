package com.example.privacyassistant

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.util.Log
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.collectAsState
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import java.io.File

class ImQuestViewModel(
    private val imQuestDao: ImQuestDao,
    private val userDao: UserDao
): ViewModel() {
    private val _entries = imQuestDao.getEntries()
    private val _state = MutableStateFlow(ImQuestState())
    val state = combine(_state, _entries) { state, entries ->
        state.copy(
            entries = entries
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ImQuestState())

    private val parentJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + parentJob)

    private val _userIds = MutableStateFlow<List<Int>>(emptyList())
    val userIDs: StateFlow<List<Int>> = _userIds

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val firestoreRepository = FirestoreRepository()

    fun onEvent(event: ImQuestEvent) {
        when (event) {
            ImQuestEvent.SaveImQuest -> {
                val userID = state.value.userID
                val imageURI = state.value.imageURI
                val answer1 = state.value.answer1
                val answer2 = state.value.answer2
                val answer3 = state.value.answer3
                val timestamp = state.value.timestamp

                if (
                    userID < 0 ||
                    imageURI.isBlank() ||
                    answer1.isBlank() ||
                    answer2 < 0 ||
                    answer3.isBlank() ||
                    timestamp.isBlank()
                ) {
                    return
                }

                val imQuest = ImQuest(
                    userID = userID,
                    imageURI = imageURI,
                    answer1 = answer1,
                    answer2 = answer2,
                    answer3 = answer3,
                    timestamp = timestamp
                )

                scope.launch {
                    firestoreRepository.upsertImQuest(imQuest)
                    Log.d("Debugger", "ImQuest upserted.")
                }

                _state.update { it.copy(
                    userID = -1,
                    imageURI = "",
                    answer1 = "",
                    answer2 = -1f,
                    answer3 = "",
                    timestamp = ""
                ) }
            }
        }
    }

    fun fetchUserIds() {
        viewModelScope.launch {
            val ids = firestoreRepository.getAllUserIds()
            _userIds.value = ids
        }
    }

    fun createNewUser(user: UserHelper) {
        scope.launch {
            _loading.value = true

            val existingIds = firestoreRepository.getAllUserIds()
            val newID = findNextMissingInt(existingIds)

            user.setID(newID)
            setUserID(newID)
            firestoreRepository.upsertUser(user.toUser())
            Log.d("Debugger", "User upserted.")

            _loading.value = false
        }
    }

    fun exportDatabase(context: Context, db: Database) {

        viewModelScope.launch {

            // wait for the other coroutines to finish
            parentJob.children.forEach { it.join() }

            // FLush the database in order to obtain the data written in the .wal - file
            try {
                // access the low-level database safely
                db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
                Log.d("Debugger", "WAL Checkpoint complete")

            } catch (e: Exception) {
                Log.e("Debugger", "WAL checkpoint failed", e)
            }

            // export the database
            try {
                val sourceFile = context.getDatabasePath("imQuests.db")
                val destinationDir = File(context.getExternalFilesDir(null), "exports")
                if (!destinationDir.exists()) {
                    destinationDir.mkdirs()
                }

                val destinationFile = File(destinationDir, "imQuests.db")
                sourceFile.copyTo(destinationFile, overwrite = true)

                Log.d("Debugger", "Database exported to: ${destinationFile.absolutePath}")
            } catch (e: Exception) {
                Log.e("Debugger", "Export failed", e)
            }
        }
    }

    suspend fun clearAll() {
        imQuestDao.clearAll()
        userDao.clearAll()
    }

    fun findNextMissingInt(numbers: List<Int>): Int {
        val numSet = numbers.toHashSet()
        var candidate = 0

        while (true) {
            if (!numSet.contains(candidate)) {
                return candidate
            }
            candidate++
        }
    }

    fun getUserID(): Int {
        return state.value.userID
    }

    fun setUserID(userId: Int) {
        state.value.userID = userId
    }
}