package com.example.privacyassistant

sealed interface ImQuestEvent {
    data object SaveImQuest: ImQuestEvent
}