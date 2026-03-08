package com.example.privacyassistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ClassifiableContact(
    val name: String,
    val selectedRelation: ContactRelation? = null
)

sealed class ClassificationScreenState {
    object Loading : ClassificationScreenState()
    object NotDueYet : ClassificationScreenState()       // < 1 month
    object NoContacts : ClassificationScreenState()      // nothing to classify
    data class Ready(
        val contacts: List<ClassifiableContact>
    ) : ClassificationScreenState()
    object Done : ClassificationScreenState()
}

class ContactClassificationViewModel(app: Application) : AndroidViewModel(app) {

    private val db = InstagramDatabase.getInstance(app)
    private val contactDao = db.contactDao()
    private val appUsageDao = db.appUsageDao()
    private val relationDao = db.contactRelationDao()
    private val remoteSyncHelper = RemoteSyncHelper(app)  
    private val _state = MutableStateFlow<ClassificationScreenState>(ClassificationScreenState.Loading)
    val state: StateFlow<ClassificationScreenState> = _state

    // mutable working copy of selections
    private val _selections = MutableStateFlow<Map<String, ContactRelation>>(emptyMap())
    val selections: StateFlow<Map<String, ContactRelation>> = _selections
    companion object {
        // 1 month in ms, used to determine when to next show classification screen
        const val ONE_MONTH_MS = 60L * 1000
    }

    init {
        viewModelScope.launch { bootstrap(skipTimeCheck = false) }
    }

    private suspend fun bootstrap(skipTimeCheck: Boolean) {
    val now = System.currentTimeMillis()

    appUsageDao.insertIfAbsent(AppUsage(firstOpenMs = now, lastOpenMs = now))
    appUsageDao.updateLastOpen(now)

    if (!skipTimeCheck) {
        val usage = appUsageDao.get() ?: return
        val referenceTime = if (usage.lastClassifiedMs > 0L) usage.lastClassifiedMs
                            else usage.firstOpenMs
        val elapsed = now - referenceTime

        if (elapsed < ONE_MONTH_MS) {
            _state.value = ClassificationScreenState.NotDueYet
            return
        }
    }

    val alreadyDone = relationDao.getAllClassifiedNames().toSet()
    val allContacts = contactDao.getAllContacts().map { it.name }
    val pending = allContacts.filter { it !in alreadyDone }

    if (pending.isEmpty()) {
        _state.value = ClassificationScreenState.NoContacts
        return
    }

    _state.value = ClassificationScreenState.Ready(
        pending.map { ClassifiableContact(it) }
    )
}

    fun forceShowClassification() {
        viewModelScope.launch { bootstrap(skipTimeCheck = true) }
    }

    fun setRelation(contactName: String, relation: ContactRelation) {
        _selections.value = _selections.value + (contactName to relation)
    }

fun onSelect(name: String, relation: ContactRelation) = setRelation(name, relation)


fun saveAndFinish() {
    viewModelScope.launch {
        val now = System.currentTimeMillis()
        _selections.value.forEach { (name, relation) ->
            relationDao.upsert(
                ContactRelation_Entity(
                    contactName = name,
                    relation = relation.name,
                    classifiedAtMs = now
                )
            )
            remoteSyncHelper.syncContactRelation(name, relation)
        }
        // Reset the clock here — next classification due in 1 month from now
        appUsageDao.updateLastClassified(now)
        _state.value = ClassificationScreenState.Done
    }
}

fun saveClassifications() = saveAndFinish()
}