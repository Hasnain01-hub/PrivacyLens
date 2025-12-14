package com.example.privacyassistant
import androidx.room.PrimaryKey
import androidx.room.Entity
import androidx.room.ForeignKey

@Entity(
    tableName = "imQuests",
    foreignKeys = [
        ForeignKey(
            entity = User::class,
            parentColumns = ["userID"],
            childColumns = ["userID"],
            onDelete = ForeignKey.CASCADE // delete quests if the user is deleted
        )
    ]
    )
data class ImQuest(
    val userID: Int,
    val imageURI: String,
    val answer1: String,
    val answer2: Float,
    val answer3: String,
    val timestamp: String,
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0
)
