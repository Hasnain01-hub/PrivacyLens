package com.example.privacyassistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class DBHandler(private val context: Context) {

    private val db by lazy {
        Room.databaseBuilder(
            context.applicationContext,
            Database::class.java,
            "imQuests.db"
        )
            .fallbackToDestructiveMigration(true)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()
    }

    fun createViewModel(owner: ViewModelStoreOwner): ImQuestViewModel {
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ImQuestViewModel(
                    db.imQuestDao,
                    db.userDao
                ) as T
            }
        }
        return ViewModelProvider(owner, factory)[ImQuestViewModel::class.java]
    }

    fun store(context: Context, viewModel: ImQuestViewModel) {
        viewModel.exportDatabase(context, db)
    }

    fun createTimestamp(): String? {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val formattedDate = sdf.format(Date(System.currentTimeMillis()))

        return formattedDate
    }
}
