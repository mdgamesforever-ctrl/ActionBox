package com.futurepath.actionbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.futurepath.actionbox.classification.ClassifiedState
import com.futurepath.actionbox.classification.ConfidenceTier
import com.futurepath.actionbox.data.NotificationEntity
import com.futurepath.actionbox.data.effectiveState
import java.text.DateFormat
import java.util.Date

/**
 * The single notification card shared by every list that shows captured notifications — the
 * grouped per-category inbox screens (ui/inbox) and the raw debug feed (ui/feed) alike, so a
 * visual/behavioral change (badge colors, the correction picker, confidence styling) only needs
 * to happen in one place.
 */
@Composable
fun NotificationCard(notification: NotificationEntity, onCorrect: (Long, ClassifiedState) -> Unit) {
    val attentionTier = notification.confidenceScore
        ?.let { ConfidenceTier.fromScore(it) }
        ?.takeIf { it == ConfidenceTier.UNCERTAIN || it == ConfidenceTier.LOW }

    // The corrected category (if the user has picked one) is what's actually shown and is
    // treated as current; classifiedState is kept untouched in the row for comparison.
    val displayedState = notification.effectiveState

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
                ExpandableText(text = notification.text, style = MaterialTheme.typography.bodyMedium)
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

// Long Gmail-style bodies used to fill the entire screen (see this repo's history) — anything
// past this many lines is truncated with a "Show more" tap target instead.
private const val COLLAPSED_MAX_LINES = 4

/**
 * Truncates [text] to [COLLAPSED_MAX_LINES] with a "Show more"/"Show less" toggle, shown only
 * when the text is actually long enough to overflow that limit — checked via [onTextLayout]'s
 * [androidx.compose.ui.text.TextLayoutResult.hasVisualOverflow] rather than a raw character
 * count, since the actual wrap point depends on the card's width and the device's font scale.
 * Shared by every place a notification's raw text is shown — the grouped inbox tabs and the
 * debug feed alike, since both go through [NotificationCard].
 */
@Composable
private fun ExpandableText(text: String, style: TextStyle) {
    var expanded by remember(text) { mutableStateOf(false) }
    var isOverflowing by remember(text) { mutableStateOf(false) }

    Column {
        Text(
            text = text,
            style = style,
            maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result ->
                if (!expanded) {
                    isOverflowing = result.hasVisualOverflow
                }
            }
        )
        if (isOverflowing || expanded) {
            Text(
                text = if (expanded) "Show less" else "Show more",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clickable { expanded = !expanded }
            )
        }
    }
}

fun badgeColor(state: ClassifiedState): Color = when (state) {
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
