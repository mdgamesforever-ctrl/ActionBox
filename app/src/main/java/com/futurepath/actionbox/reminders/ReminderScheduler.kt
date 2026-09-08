package com.futurepath.actionbox.reminders

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.futurepath.actionbox.data.DigestTime
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Schedules/cancels the reminder system's two background jobs (see [DigestWorker]/
 * [WaitingNudgeWorker]) via WorkManager. [com.futurepath.actionbox.ActionBoxApplication] holds
 * the single reactive subscription that calls this whenever the digest settings change (toggled
 * in Settings, or just re-asserted on every app/process start) — nothing else in the app calls
 * this directly.
 */
object ReminderScheduler {
    private const val DIGEST_WORK_NAME = "digest_work"
    private const val WAITING_NUDGE_WORK_NAME = "waiting_nudge_work"
    private const val SNOOZE_CHECK_WORK_NAME = "snooze_check_work"

    // How often to check for newly-overdue WAITING items. Doesn't need to be precise the way
    // the digest's wall-clock time does — just frequent enough that a nudge doesn't lag its
    // implied deadline by much more than this.
    private val WAITING_NUDGE_INTERVAL: Duration = Duration.ofHours(6)

    // WorkManager's PeriodicWorkRequest minimum interval — also short enough that the shortest
    // snooze preset ("1 hour") never lags noticeably behind its actual due time.
    private val SNOOZE_CHECK_INTERVAL: Duration = Duration.ofMinutes(15)

    fun scheduleAll(context: Context, time: DigestTime) {
        scheduleDigest(context, time)
        scheduleWaitingNudges(context)
    }

    fun cancelAll(context: Context) {
        cancelDigest(context)
        cancelWaitingNudges(context)
    }

    /**
     * Unlike [scheduleAll]/[cancelAll], snooze checks aren't gated by the digest settings toggle
     * — see [SnoozeWorker]'s doc — so [com.futurepath.actionbox.ActionBoxApplication] calls this
     * unconditionally at app start rather than as part of the reactive digest-settings
     * subscription.
     */
    fun scheduleSnoozeChecks(context: Context) {
        val request = PeriodicWorkRequestBuilder<SnoozeWorker>(SNOOZE_CHECK_INTERVAL).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SNOOZE_CHECK_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancelSnoozeChecks(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(SNOOZE_CHECK_WORK_NAME)
    }

    /**
     * Schedules the NEXT single occurrence of the digest at [time] (today if that time hasn't
     * passed yet, tomorrow otherwise) as a one-shot request, replacing any previously scheduled
     * run. [DigestWorker] calls this again after each run to queue the following day's —
     * WorkManager's PeriodicWorkRequest only supports a recurring INTERVAL measured from
     * whenever it was first enqueued, not a specific wall-clock time of day, so it can't by
     * itself keep firing at, say, 9:00 AM every day; a self-rescheduling one-shot chain can.
     */
    fun scheduleDigest(context: Context, time: DigestTime) {
        val request = OneTimeWorkRequestBuilder<DigestWorker>()
            .setInitialDelay(delayUntilNext(time).toMillis(), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(DIGEST_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelDigest(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(DIGEST_WORK_NAME)
    }

    fun scheduleWaitingNudges(context: Context) {
        val request = PeriodicWorkRequestBuilder<WaitingNudgeWorker>(WAITING_NUDGE_INTERVAL).build()
        // KEEP, not REPLACE: this is called every app start (see ActionBoxApplication) as well
        // as on a real settings change, and re-enqueuing an identical periodic request each
        // time would otherwise reset its schedule instead of letting the already-running one
        // continue undisturbed.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WAITING_NUDGE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancelWaitingNudges(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WAITING_NUDGE_WORK_NAME)
    }

    private fun delayUntilNext(time: DigestTime, zone: ZoneId = ZoneId.systemDefault()): Duration {
        val now = LocalDateTime.now(zone)
        var target = now.toLocalDate().atTime(LocalTime.of(time.hour, time.minute))
        if (!target.isAfter(now)) target = target.plusDays(1)
        return Duration.between(now, target)
    }
}
