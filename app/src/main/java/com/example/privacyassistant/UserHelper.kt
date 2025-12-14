package com.example.privacyassistant
import android.util.Log

// a user has a fixed ID and answers 5 general questions which are then stored in the database
class UserHelper (
    private var userID: Int,
    private var gQuestion1: Int,
    private var gQuestion2: Int,
    private var gQuestion3: Int,
    private var gQuestion4: Int,
    private var gQuestion5: Int
) {

    fun getID(): Int {
        return this.userID
    }

    fun setID(userID: Int) {
        this.userID = userID
    }

    fun setQuestion1(gQuestion1: Int) {
        this.gQuestion1 = gQuestion1
    }

    fun setQuestion2(gQuestion2: Int) {
        this.gQuestion2 = gQuestion2
    }

    fun setQuestion3(gQuestion3: Int) {
        this.gQuestion3 = gQuestion3
    }

    fun setQuestion4(gQuestion4: Int) {
        this.gQuestion4 = gQuestion4
    }

    fun setQuestion5(gQuestion5: Int) {
        this.gQuestion5 = gQuestion5
    }

    fun toUser(): User {
        return User(
            userID = this.userID,
            gQuestion1 = this.gQuestion1,
            gQuestion2 = this.gQuestion2,
            gQuestion3 = this.gQuestion3,
            gQuestion4 = this.gQuestion4,
            gQuestion5 = this.gQuestion5
        )
    }
}