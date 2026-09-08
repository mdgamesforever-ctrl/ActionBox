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
import androidx.compose.ui.unit.dp
import com.futurepath.actionbox.classification.ClassifiedState
import com.futurepath.actionbox.reminders.WeeklyInsights

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
                text = "Not enough data yet — check back after your first week using ActionBox.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "This week", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "${insights.totalCaptured} notifications captured",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        Text(text = "By category", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        CATEGORY_ORDER.forEach { state ->
            val count = insights.countByCategory[state] ?: 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Text(text = state.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(text = count.toString(), style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        Text(text = "Still waiting", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (insights.staleWaitingCount == 0) {
                "Nothing unresolved for more than 5 days — nice."
            } else {
                "${insights.staleWaitingCount} item(s) still unresolved after 5+ days."
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
