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

    // Multi-select: each contact maps to a set of selected relations
    private val _selections = MutableStateFlow<Map<String, Set<ContactRelation>>>(emptyMap())
    val selections: StateFlow<Map<String, Set<ContactRelation>>> = _selections

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

    fun toggleRelation(contactName: String, relation: ContactRelation) {
        val current = _selections.value[contactName] ?: emptySet()

        val updated = if (relation == ContactRelation.IGNORE) {
            // IGNORE is exclusive — selecting it deselects everything else
            if (current.contains(ContactRelation.IGNORE)) emptySet() else setOf(ContactRelation.IGNORE)
        } else {
            // Selecting a non-IGNORE option removes IGNORE if present
            val withoutIgnore = current - ContactRelation.IGNORE
            if (withoutIgnore.contains(relation)) {
                withoutIgnore - relation
            } else {
                withoutIgnore + relation
            }
        }

        _selections.value = if (updated.isEmpty()) {
            _selections.value - contactName
        } else {
            _selections.value + (contactName to updated)
        }
    }

    fun onSelect(name: String, relation: ContactRelation) = toggleRelation(name, relation)

    fun saveAndFinish() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            _selections.value.forEach { (name, relations) ->
                // Store as comma-separated string, e.g. "FRIEND,FAMILY"
                val relationStr = relations.joinToString(",") { it.name }
                relationDao.upsert(
                    ContactRelation_Entity(
                        contactName = name,
                        relation = relationStr,
                        classifiedAtMs = now
                    )
                )
                remoteSyncHelper.syncContactRelations(name, relations)
            }
            appUsageDao.updateLastClassified(now)
            _state.value = ClassificationScreenState.Done
        }
    }

    fun saveClassifications() = saveAndFinish()
}
