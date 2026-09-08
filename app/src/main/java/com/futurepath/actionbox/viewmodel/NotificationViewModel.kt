package com.futurepath.actionbox.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.futurepath.actionbox.classification.ClassifiedState
import com.futurepath.actionbox.data.NotificationEntity
import com.futurepath.actionbox.data.NotificationRepository
import com.futurepath.actionbox.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NotificationViewModel(
    private val repository: NotificationRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val notifications: StateFlow<List<NotificationEntity>> = repository.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * The same notifications grouped by their EFFECTIVE state (the user's correction if any,
     * otherwise the classifier's pick) — what the grouped inbox screens (ui/inbox) actually
     * show. Computed here rather than with a separate Room query so the grouped screens and
     * the raw debug feed both read from the exact same underlying list.
     */
    val itemsByCategory: StateFlow<Map<ClassifiedState, List<NotificationEntity>>> = notifications
        .map { list -> list.groupBy { it.correctedState ?: it.classifiedState ?: ClassifiedState.FYI } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    private val _correctionLearningEnabled = MutableStateFlow(true)
    val correctionLearningEnabled: StateFlow<Boolean> = _correctionLearningEnabled.asStateFlow()

    private val _isPro = MutableStateFlow(false)
    val isPro: StateFlow<Boolean> = _isPro.asStateFlow()

    init {
        settingsRepository.correctionLearningEnabled
            .onEach { _correctionLearningEnabled.value = it }
            .launchIn(viewModelScope)
        settingsRepository.isPro
            .onEach { _isPro.value = it }
            .launchIn(viewModelScope)

        // Sweep once per app open — see NotificationRepository.enforceRetentionPolicy's doc
        // for why a scheduled background job isn't needed for this.
        viewModelScope.launch { repository.enforceRetentionPolicy() }
    }

    fun correctClassification(id: Long, newState: ClassifiedState) {
        viewModelScope.launch {
            repository.correctClassification(id, newState)
        }
    }

    fun setCorrectionLearningEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setCorrectionLearningEnabled(enabled)
        }
    }

    fun setPro(isPro: Boolean) {
        viewModelScope.launch {
            settingsRepository.setPro(isPro)
            // Downgrading should trim any now-over-the-limit history right away rather than
            // waiting for the next app launch; upgrading is always a no-op here since the
            // retention sweep only ever deletes, never restores.
            repository.enforceRetentionPolicy()
        }
    }

    class Factory(
        private val repository: NotificationRepository,
        private val settingsRepository: SettingsRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return NotificationViewModel(repository, settingsRepository) as T
        }
    }
}
