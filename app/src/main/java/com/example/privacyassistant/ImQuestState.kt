package com.example.privacyassistant

data class ImQuestState(
    var entries: List<ImQuest> = emptyList(),
    var userID: Int = -1,
    var imageURI: String = "",
    var answer1: String = "",
    var answer2: Float = -1f,
    var answer3: String = "",
    var timestamp: String = "",
)