package com.futurepath.actionbox.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.futurepath.actionbox.R
import com.futurepath.actionbox.classification.ClassifiedState
import com.futurepath.actionbox.classification.ConfidenceTier
import com.futurepath.actionbox.data.NotificationEntity
import java.text.DateFormat
import java.util.Date

@Composable
fun NotificationFeedScreen(
    notifications: List<NotificationEntity>,
    onCorrect: (id: Long, newState: ClassifiedState) -> Unit = { _, _ -> }
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.feed_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp)
        )

        if (notifications.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.feed_empty),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                items(notifications, key = { it.id }) { notification ->
                    NotificationRow(notification, onCorrect)
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(notification: NotificationEntity, onCorrect: (Long, ClassifiedState) -> Unit) {
    val attentionTier = notification.confidenceScore
        ?.let { ConfidenceTier.fromScore(it) }
        ?.takeIf { it == ConfidenceTier.UNCERTAIN || it == ConfidenceTier.LOW }

    // The corrected category (if the user has picked one) is what's actually shown and is
    // treated as current; classifiedState is kept untouched in the row for comparison.
    val displayedState = notification.correctedState ?: notification.classifiedState

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .let { base -> attentionTier?.let { base.dashedBorder(attentionColor(it)) } ?: base }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Column {
                Text(text = notification.sourceApp, style = MaterialTheme.typography.labelMedium)
                displayedState?.let { state ->
                    StateBadge(
                        state = state,
                        isCorrected = notification.correctedState != null,
                        onPick = { newState -> onCorrect(notification.id, newState) }
                    )
                }
            }
            if (notification.sender.isNotBlank()) {
                Text(text = notification.sender, style = MaterialTheme.typography.titleMedium)
            }
            if (notification.text.isNotBlank()) {
                Text(text = notification.text, style = MaterialTheme.typography.bodyMedium)
            }
            notification.extractedSummary?.let { summary ->
                Text(
                    text = "Summary: $summary",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            attentionTier?.let { tier ->
                Text(
                    text = attentionLabel(tier, notification.confidenceScore ?: 0),
                    style = MaterialTheme.typography.labelSmall,
                    color = attentionColor(tier),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Text(
                text = DateFormat.getDateTimeInstance().format(Date(notification.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/** Tapping the badge opens a picker for all six categories; picking one reports the correction. */
@Composable
private fun StateBadge(state: ClassifiedState, isCorrected: Boolean, onPick: (ClassifiedState) -> Unit) {
    var pickerExpanded by remember { mutableStateOf(false) }

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(badgeColor(state))
                .let { it }
        ) {
            Text(
                text = state.name,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        // Separate clickable overlay rather than putting clickable() on the Row above, so
        // the badge's own background/shape stays exactly as designed while still being tap
        // target for the picker.
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(top = 4.dp)
                .clickable { pickerExpanded = true }
        )
        DropdownMenu(expanded = pickerExpanded, onDismissRequest = { pickerExpanded = false }) {
            ClassifiedState.values().forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name) },
                    onClick = {
                        pickerExpanded = false
                        onPick(option)
                    }
                )
            }
        }
        if (isCorrected) {
            Text(
                text = "(corrected)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }
    }
}

private fun badgeColor(state: ClassifiedState): Color = when (state) {
    ClassifiedState.ACTION -> Color(0xFFE53935)
    ClassifiedState.DEADLINE -> Color(0xFFFB8C00)
    ClassifiedState.WAITING -> Color(0xFF1E88E5)
    ClassifiedState.REPLY -> Color(0xFF43A047)
    ClassifiedState.FYI -> Color(0xFF757575)
    ClassifiedState.NOISE -> Color(0xFFBDBDBD)
}

private fun attentionColor(tier: ConfidenceTier): Color = when (tier) {
    ConfidenceTier.LOW -> Color(0xFFC62828)
    else -> Color(0xFFF9A825) // UNCERTAIN
}

private fun attentionLabel(tier: ConfidenceTier, score: Int): String = when (tier) {
    ConfidenceTier.LOW -> "⚠ Needs review — low confidence ($score%)"
    ConfidenceTier.UNCERTAIN -> "❓ Not sure? — tap to verify ($score%)"
    else -> ""
}

/** Dashed outline distinguishing a low/uncertain-confidence row from a normal card. */
private fun Modifier.dashedBorder(color: Color, strokeWidth: Dp = 1.5.dp, cornerRadius: Dp = 8.dp): Modifier =
    drawWithContent {
        drawContent()
        drawRoundRect(
            color = color,
            style = Stroke(
                width = strokeWidth.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
            ),
            cornerRadius = CornerRadius(cornerRadius.toPx())
        )
    }
