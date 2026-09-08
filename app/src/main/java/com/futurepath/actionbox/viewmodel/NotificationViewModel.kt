package com.futurepath.actionbox.viewmodel

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.ProductDetails
import com.futurepath.actionbox.billing.BillingRepository
import com.futurepath.actionbox.classification.ClassifiedState
import com.futurepath.actionbox.data.DigestTime
import com.futurepath.actionbox.data.NotificationEntity
import com.futurepath.actionbox.data.NotificationRepository
import com.futurepath.actionbox.data.SettingsRepository
import com.futurepath.actionbox.data.effectiveState
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
    private val settingsRepository: SettingsRepository,
    private val billingRepository: BillingRepository
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
        .map { list -> list.groupBy { it.effectiveState ?: ClassifiedState.FYI } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    private val _correctionLearningEnabled = MutableStateFlow(true)
    val correctionLearningEnabled: StateFlow<Boolean> = _correctionLearningEnabled.asStateFlow()

    private val _isPro = MutableStateFlow(false)
    val isPro: StateFlow<Boolean> = _isPro.asStateFlow()

    private val _digestsEnabled = MutableStateFlow(true)
    val digestsEnabled: StateFlow<Boolean> = _digestsEnabled.asStateFlow()

    private val _digestTime = MutableStateFlow(DigestTime(SettingsRepository.DEFAULT_DIGEST_HOUR, SettingsRepository.DEFAULT_DIGEST_MINUTE))
    val digestTime: StateFlow<DigestTime> = _digestTime.asStateFlow()

    /** The Pro subscription's product details, once Play Billing has loaded them — null until
     * then, which the paywall (ui/paywall/PaywallScreen) shows as a loading state. */
    val productDetails: StateFlow<ProductDetails?> = billingRepository.productDetails
    val billingUnavailable: StateFlow<Boolean> = billingRepository.billingUnavailable

    init {
        settingsRepository.correctionLearningEnabled
            .onEach { _correctionLearningEnabled.value = it }
            .launchIn(viewModelScope)
        settingsRepository.isPro
            .onEach { _isPro.value = it }
            .launchIn(viewModelScope)
        settingsRepository.digestsEnabled
            .onEach { _digestsEnabled.value = it }
            .launchIn(viewModelScope)
        settingsRepository.digestTime
            .onEach { _digestTime.value = it }
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

    /**
     * Bypasses Play Billing entirely and just flips the stored flag — kept for our own testing
     * since there's no Play Console listing reachable from this environment (see
     * BillingRepository's doc), and clearly separated in the Settings UI ("Debug tools") from
     * the real "Upgrade to Pro"/"Manage subscription" flow real users go through. A genuine
     * purchase or entitlement change instead flows through [BillingRepository] directly into
     * [SettingsRepository.isPro] — see [purchasePro].
     */
    fun setProDebugOverride(isPro: Boolean) {
        viewModelScope.launch {
            settingsRepository.setPro(isPro)
            // Downgrading should trim any now-over-the-limit history right away rather than
            // waiting for the next app launch; upgrading is always a no-op here since the
            // retention sweep only ever deletes, never restores.
            repository.enforceRetentionPolicy()
        }
    }

    /** Launches Play's purchase UI for the Pro subscription from the paywall's Subscribe
     * button. [BillingRepository] itself updates [SettingsRepository.isPro] once the purchase
     * completes and is acknowledged — nothing further to do here. */
    fun purchasePro(activity: Activity) {
        billingRepository.launchPurchaseFlow(activity)
    }

    /** Re-attempts the Play Billing connection — surfaced as a "try again" action on the
     * paywall when [billingUnavailable] is true (no network, Play Store outage, etc.). */
    fun retryBillingConnection() {
        billingRepository.startConnection()
    }

    /**
     * Only persists the flag — [com.futurepath.actionbox.ActionBoxApplication] holds the
     * reactive subscription that actually schedules/cancels the WorkManager jobs in response,
     * so this doesn't need to know anything about WorkManager itself.
     */
    fun setDigestsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDigestsEnabled(enabled)
        }
    }

    fun setDigestTime(time: DigestTime) {
        viewModelScope.launch {
            settingsRepository.setDigestTime(time)
        }
    }

    class Factory(
        private val repository: NotificationRepository,
        private val settingsRepository: SettingsRepository,
        private val billingRepository: BillingRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return NotificationViewModel(repository, settingsRepository, billingRepository) as T
        }
    }
}
