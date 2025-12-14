package com.example.privacyassistant

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class User(
    @PrimaryKey(autoGenerate = false)
    val userID: Int,
    val gQuestion1: Int,
    val gQuestion2: Int,
    val gQuestion3: Int,
    val gQuestion4: Int,
    val gQuestion5: Int,
)