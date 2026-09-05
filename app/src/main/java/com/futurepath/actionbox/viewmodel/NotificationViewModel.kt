package com.futurepath.actionbox.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.futurepath.actionbox.data.NotificationDebugEvent
import com.futurepath.actionbox.data.NotificationRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class NotificationViewModel(private val repository: NotificationRepository) : ViewModel() {

    val debugEvents: StateFlow<List<NotificationDebugEvent>> = repository.observeDebugEvents()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Ground truth for "is a duplicate really excluded from the real table" — compare this
    // against the number of "captured" rows in debugEvents to sanity-check the label.
    val capturedCount: StateFlow<Int> = repository.observeCapturedCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    class Factory(private val repository: NotificationRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return NotificationViewModel(repository) as T
        }
    }
}
