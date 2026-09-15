package com.futurepath.actionbox.viewmodel

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.ProductDetails
import com.futurepath.actionbox.billing.BillingRepository
import com.futurepath.actionbox.classification.ClassifiedState
import com.futurepath.actionbox.data.AppLanguage
import com.futurepath.actionbox.data.DigestTime
import com.futurepath.actionbox.data.NotificationEntity
import com.futurepath.actionbox.data.NotificationRepository
import com.futurepath.actionbox.data.SettingsRepository
import com.futurepath.actionbox.data.ThemeMode
import com.futurepath.actionbox.data.VipSenderEntity
import com.futurepath.actionbox.data.effectiveState
import com.futurepath.actionbox.data.groupActiveByCategory
import com.futurepath.actionbox.reminders.SnoozeCalculator
import com.futurepath.actionbox.reminders.SnoozeDuration
import com.futurepath.actionbox.reminders.WeeklyInsights
import com.futurepath.actionbox.reminders.WeeklyInsightsCalculator
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
     * the raw debug feed both read from the exact same underlying list. Excludes handled/snoozed
     * items — see [groupActiveByCategory] — so a swipe-right/swipe-left immediately drops the
     * item out of every tab's counts and contents.
     */
    val itemsByCategory: StateFlow<Map<ClassifiedState, List<NotificationEntity>>> = notifications
        .map { list -> list.groupActiveByCategory() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    /**
     * Everything currently hidden from the active inbox by a swipe-right, for the recovery
     * screen (ui/recovery/RecoveryScreen) — the only place a swipe that missed its Snackbar's
     * undo window can still be reversed. Newest-handled first, since that's the swipe someone is
     * most likely trying to undo.
     */
    val handledNotifications: StateFlow<List<NotificationEntity>> = notifications
        .map { list -> list.filter { it.handledAt != null }.sortedByDescending { it.handledAt } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /** Same as [handledNotifications] but for swipe-left/snooze — soonest-to-reappear first,
     * since that's the more time-sensitive end of the list. */
    val snoozedNotifications: StateFlow<List<NotificationEntity>> = notifications
        .map { list -> list.filter { it.snoozedUntil != null }.sortedBy { it.snoozedUntil } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _correctionLearningEnabled = MutableStateFlow(true)
    val correctionLearningEnabled: StateFlow<Boolean> = _correctionLearningEnabled.asStateFlow()

    private val _isPro = MutableStateFlow(false)
    val isPro: StateFlow<Boolean> = _isPro.asStateFlow()

    private val _digestsEnabled = MutableStateFlow(true)
    val digestsEnabled: StateFlow<Boolean> = _digestsEnabled.asStateFlow()

    private val _digestTime = MutableStateFlow(DigestTime(SettingsRepository.DEFAULT_DIGEST_HOUR, SettingsRepository.DEFAULT_DIGEST_MINUTE))
    val digestTime: StateFlow<DigestTime> = _digestTime.asStateFlow()

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _appLanguage = MutableStateFlow(AppLanguage.SYSTEM_DEFAULT)
    val appLanguage: StateFlow<AppLanguage> = _appLanguage.asStateFlow()

    /** Each plan's product details, once Play Billing has loaded them — null until then, which
     * the paywall (ui/paywall/PaywallScreen) shows as a loading state for that plan. */
    val monthlyProductDetails: StateFlow<ProductDetails?> = billingRepository.monthlyProductDetails
    val yearlyProductDetails: StateFlow<ProductDetails?> = billingRepository.yearlyProductDetails
    val billingUnavailable: StateFlow<Boolean> = billingRepository.billingUnavailable

    /** Pro feature — see ui/settings/VipSendersScreen.kt. */
    val vipSenders: StateFlow<List<VipSenderEntity>> = repository.observeVipSenders()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /** Pro feature — see ui/insights/WeeklyInsightsScreen.kt. Shares its calculation with the
     * weekly notification itself (see reminders/WeeklyInsightsWorker.kt) so both always agree. */
    val weeklyInsights: StateFlow<WeeklyInsights> = notifications
        .map { list -> WeeklyInsightsCalculator.compute(list, System.currentTimeMillis()) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = WeeklyInsightsCalculator.compute(emptyList(), System.currentTimeMillis())
        )

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
        settingsRepository.themeMode
            .onEach { _themeMode.value = it }
            .launchIn(viewModelScope)
        settingsRepository.appLanguage
            .onEach { _appLanguage.value = it }
            .launchIn(viewModelScope)

        // Sweep once per app open — see NotificationRepository.enforceRetentionPolicy's doc
        // for why a scheduled background job isn't needed for this.
        viewModelScope.launch { repository.enforceRetentionPolicy() }
        // Same idea for the recovery screen's own retention window — see
        // NotificationRepository.enforceRecoveryRetentionPolicy's doc for why this ALSO runs
        // from SnoozeWorker rather than relying on just this app-open call.
        viewModelScope.launch { repository.enforceRecoveryRetentionPolicy() }
    }

    fun correctClassification(id: Long, newState: ClassifiedState) {
        viewModelScope.launch {
            repository.correctClassification(id, newState)
        }
    }

    /** Swipe-right in the grouped inbox. */
    fun markHandled(id: Long) {
        viewModelScope.launch {
            repository.setHandled(id, handled = true)
        }
    }

    /** The undo action on the swipe-right snackbar. */
    fun undoHandled(id: Long) {
        viewModelScope.launch {
            repository.setHandled(id, handled = false)
        }
    }

    /** Swipe-left + a duration pick in the grouped inbox — see [SnoozeCalculator]. */
    fun snooze(id: Long, duration: SnoozeDuration) {
        viewModelScope.launch {
            repository.snooze(id, SnoozeCalculator.resolveUntil(duration, System.currentTimeMillis()))
        }
    }

    /** The undo action on the swipe-left snooze snackbar. */
    fun undoSnooze(id: Long) {
        viewModelScope.launch {
            repository.clearSnooze(id)
        }
    }

    /** Swipe-to-delete on the recovery screen (ui/recovery/RecoveryScreen) — permanent, unlike
     * markHandled/snooze above. */
    fun deleteNotification(notification: NotificationEntity) {
        viewModelScope.launch {
            repository.deleteNotification(notification)
        }
    }

    /** The recovery screen's multi-select "Delete" action. */
    fun deleteNotifications(notifications: List<NotificationEntity>) {
        viewModelScope.launch {
            repository.deleteNotifications(notifications)
        }
    }

    /** The undo action on the recovery screen's swipe-to-delete snackbar. */
    fun restoreNotification(notification: NotificationEntity) {
        viewModelScope.launch {
            repository.restoreNotification(notification)
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
            repository.enforceRecoveryRetentionPolicy()
        }
    }

    /** Debug-only screenshot aid — see [NotificationRepository.seedDemoData]. */
    fun seedDemoData() {
        viewModelScope.launch {
            repository.seedDemoData()
        }
    }

    /** Launches Play's purchase UI for whichever plan's Subscribe button the user tapped on the
     * paywall ([BillingRepository.PRO_MONTHLY_PRODUCT_ID] or
     * [BillingRepository.PRO_YEARLY_PRODUCT_ID]). [BillingRepository] itself updates
     * [SettingsRepository.isPro] once the purchase completes and is acknowledged, regardless of
     * which plan it was — nothing further to do here. */
    fun purchasePro(activity: Activity, productId: String) {
        billingRepository.launchPurchaseFlow(activity, productId)
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

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            settingsRepository.setThemeMode(mode)
        }
    }

    /**
     * A plain `suspend` function — deliberately NOT wrapped in `viewModelScope.launch` the way
     * every other setter above is. The caller (SettingsScreen's LanguageRow) needs to call
     * [android.app.Activity.recreate] immediately after this actually finishes persisting, since
     * that's the step that re-renders this app's own UI in the new language (via
     * MainActivity.attachBaseContext re-running with the newly-persisted value). Firing-and-
     * forgetting the persist here (like the other setters do) would race recreate() against the
     * DataStore write, since the caller can't tell when a launched coroutine actually completes.
     * The caller runs this in its own `rememberCoroutineScope()` and calls `recreate()` only
     * after it returns.
     */
    suspend fun setAppLanguage(language: AppLanguage) {
        settingsRepository.setAppLanguage(language)
        language.syncToSystemLocaleRecord()
    }

    /** [sender] blank flags the whole [sourceApp] as VIP — see [VipSenderEntity]'s doc. */
    fun addVipSender(sourceApp: String, sender: String) {
        viewModelScope.launch {
            repository.addVipSender(sourceApp, sender)
        }
    }

    fun removeVipSender(entity: VipSenderEntity) {
        viewModelScope.launch {
            repository.removeVipSender(entity)
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
