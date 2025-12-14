package com.example.privacyassistant

import com.google.firebase.firestore.FirebaseFirestore
import android.util.Log
import kotlinx.coroutines.tasks.await


class FirestoreRepository {
    private val db = FirebaseFirestore.getInstance()

    suspend fun upsertUser(user: User) {
        val userMap = mapOf(
            "userID" to user.userID,
            "gQuestion1" to user.gQuestion1,
            "gQuestion2" to user.gQuestion2,
            "gQuestion3" to user.gQuestion3,
            "gQuestion4" to user.gQuestion4,
            "gQuestion5" to user.gQuestion5
        )

        db.collection("users")
            .document(user.userID.toString())
            .set(userMap)
            .addOnSuccessListener {
                Log.d("FirestoreRepo", "User upserted to Firestore")
            }
            .addOnFailureListener { e ->
                Log.e("FirestoreRepo", "Error upserting user", e)
            }
            .await()
    }

    suspend fun getAllUserIds(): List<Int> {
        return try {
            val snapshot = db.collection("users")
                .get()
                .await()

            snapshot.documents.mapNotNull { doc ->
                val value = doc.get("userID")
                when (value) {
                    is Long -> value.toInt()
                    is Int -> value
                    else -> null
                }
            }
        } catch (e: Exception) {
            Log.e("FirestoreRepo", "Error fetching user IDs", e)
            emptyList()
        }
    }

    suspend fun upsertImQuest(imQuest: ImQuest) {
        val imQuestMap = mapOf(
            "userID" to imQuest.userID,
            "imageURI" to imQuest.imageURI,
            "answer1" to imQuest.answer1,
            "answer2" to imQuest.answer2,
            "answer3" to imQuest.answer3,
            "timestamp" to imQuest.timestamp
        )

        db.collection("imquests")
            .add(imQuestMap)
            .addOnSuccessListener { docRef ->
                Log.d("FirestoreRepo", "ImQuest added with ID: ${docRef.id}")
            }
            .addOnFailureListener { e ->
                Log.e("FirestoreRepo", "Error adding ImQuest", e)
            }
            .await()

    }

}
