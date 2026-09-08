package com.futurepath.actionbox.ui.insights

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.futurepath.actionbox.R
import com.futurepath.actionbox.classification.ClassifiedState
import com.futurepath.actionbox.reminders.WeeklyInsights
import com.futurepath.actionbox.ui.components.labelRes

/**
 * Pro feature — the breakdown behind the weekly insights notification (see
 * com.futurepath.actionbox.reminders.WeeklyInsightsWorker), reachable either by tapping that
 * notification or from Settings. Shares its stats with the notification via
 * [com.futurepath.actionbox.reminders.WeeklyInsightsCalculator], so the two always agree.
 */
@Composable
fun WeeklyInsightsScreen(insights: WeeklyInsights) {
    if (!insights.hasEnoughHistory) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.insights_not_enough_data),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = stringResource(R.string.insights_this_week), style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = pluralStringResource(R.plurals.insights_notifications_captured, insights.totalCaptured, insights.totalCaptured),
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        Text(text = stringResource(R.string.insights_by_category), style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        CATEGORY_ORDER.forEach { state ->
            val count = insights.countByCategory[state] ?: 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Text(text = stringResource(state.labelRes()), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(text = count.toString(), style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        Text(text = stringResource(R.string.insights_still_waiting), style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (insights.staleWaitingCount == 0) {
                stringResource(R.string.insights_nothing_stale)
            } else {
                pluralStringResource(
                    R.plurals.insights_stale_waiting_count,
                    insights.staleWaitingCount,
                    insights.staleWaitingCount
                )
            },
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private val CATEGORY_ORDER = listOf(
    ClassifiedState.ACTION,
    ClassifiedState.WAITING,
    ClassifiedState.DEADLINE,
    ClassifiedState.REPLY,
    ClassifiedState.FYI,
    ClassifiedState.NOISE
)
