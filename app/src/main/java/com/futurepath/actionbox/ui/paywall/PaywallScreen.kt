package com.futurepath.actionbox.ui.paywall

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.billingclient.api.ProductDetails
import com.futurepath.actionbox.data.NotificationRepository

/**
 * The Pro upsell screen — reached from Settings when the user isn't Pro yet (see
 * SettingsScreen's "Upgrade to Pro" button) or from the Free-tier widget placeholder (see
 * widget/ActionBoxWidget.kt). [productDetails]/[billingUnavailable] come from
 * [com.futurepath.actionbox.billing.BillingRepository] via the view model; this composable is
 * pure presentation over whatever state Play Billing is actually in, including the states a
 * real user can hit (still loading, or genuinely unavailable — no network, Play Store outage,
 * or no listing configured).
 *
 * Laid out as a Free-vs-Pro feature comparison table rather than a plain checklist — every color
 * here comes from [MaterialTheme.colorScheme] rather than a fixed hex (unlike, e.g.,
 * ui/components/NotificationCard.kt's category badges) specifically so this screen — arguably the
 * one place in the app where legibility matters most for a first impression — tracks the user's
 * chosen appearance (see SettingsScreen's "Appearance" section) or the system theme correctly
 * with zero manual per-mode tuning.
 */
@Composable
fun PaywallScreen(
    productDetails: ProductDetails?,
    billingUnavailable: Boolean,
    onSubscribeClick: (Activity) -> Unit,
    onRetryClick: () -> Unit,
    onContinueFreeClick: () -> Unit
) {
    val activity = LocalContext.current as? Activity

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.WorkspacePremium,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = "Upgrade to Pro", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Unlock unlimited history, an ad-free inbox, and smarter classification.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))

        FeatureComparisonTable()

        Spacer(modifier = Modifier.height(24.dp))

        when {
            billingUnavailable -> {
                Text(
                    text = "Google Play Billing isn't available right now. Check your connection and try again.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = onRetryClick) { Text("Try again") }
            }
            productDetails == null -> CircularProgressIndicator()
            else -> {
                priceLabel(productDetails)?.let { price ->
                    Text(text = price, style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(16.dp))
                }
                Button(
                    onClick = { activity?.let(onSubscribeClick) },
                    enabled = activity != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Subscribe")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onContinueFreeClick) {
            Text("Continue with Free")
        }
    }
}

/** One row of the comparison table. Exactly one of [freeText]/[proText] is non-null when that
 * column's value is a piece of text (e.g. "14 days") rather than a plain included/excluded
 * checkmark. */
private data class ComparisonRow(
    val feature: String,
    val freeText: String? = null,
    val freeIncluded: Boolean = false,
    val proText: String? = null
)

@Composable
private fun FeatureComparisonTable() {
    val rows = listOf(
        ComparisonRow(
            feature = "Notification history",
            freeText = "${NotificationRepository.FREE_RETENTION_DAYS} days",
            proText = "Unlimited"
        ),
        ComparisonRow(feature = "Ad-free experience", freeIncluded = false),
        ComparisonRow(feature = "Smart corrections", freeIncluded = false),
        ComparisonRow(feature = "Home screen widget", freeIncluded = false),
        ComparisonRow(feature = "Core inbox & notifications", freeIncluded = true)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        ComparisonTableRow(
            feature = {
                Text(
                    text = "Feature",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            free = {
                Text(text = "Free", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            },
            pro = {
                Text(
                    text = "Pro",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        )
        rows.forEach { row ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ComparisonTableRow(
                feature = { Text(text = row.feature, style = MaterialTheme.typography.bodyMedium) },
                free = {
                    ComparisonCell(
                        text = row.freeText,
                        included = row.freeIncluded,
                        includedTint = MaterialTheme.colorScheme.primary
                    )
                },
                pro = {
                    ComparisonCell(
                        text = row.proText,
                        included = true,
                        includedTint = MaterialTheme.colorScheme.onPrimaryContainer,
                        textColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            )
        }
    }
}

/** One row's three cells — the feature label, then the Free and Pro columns. The Pro cell's
 * [MaterialTheme.colorScheme.primaryContainer] background is what gives that whole column its
 * "subtle accent highlight," including the header row, since every row (this one included) uses
 * the same background for that cell. */
@Composable
private fun ComparisonTableRow(
    feature: @Composable () -> Unit,
    free: @Composable () -> Unit,
    pro: @Composable () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .weight(1.4f)
                .padding(vertical = 12.dp, horizontal = 12.dp)
        ) {
            feature()
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 12.dp, horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            free()
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(vertical = 12.dp, horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            pro()
        }
    }
}

/** A cell's content: [text] when this row has a text value for this column (e.g. "Unlimited"),
 * otherwise a checkmark ([included]) or an X — using [MaterialTheme.colorScheme.error] for the
 * X so a Free-only reader still gets a clear "not included" signal from theme tokens alone,
 * with no fixed color needed. */
@Composable
private fun ComparisonCell(
    text: String?,
    included: Boolean,
    includedTint: Color,
    textColor: Color = MaterialTheme.colorScheme.onSurface
) {
    when {
        text != null -> Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = textColor,
            textAlign = TextAlign.Center
        )
        included -> Icon(Icons.Filled.Check, contentDescription = "Included", tint = includedTint)
        else -> Icon(Icons.Filled.Close, contentDescription = "Not included", tint = MaterialTheme.colorScheme.error)
    }
}

private fun priceLabel(productDetails: ProductDetails): String? {
    val phase = productDetails.subscriptionOfferDetails
        ?.firstOrNull()
        ?.pricingPhases
        ?.pricingPhaseList
        ?.firstOrNull()
        ?: return null
    return "${phase.formattedPrice} / ${billingPeriodLabel(phase.billingPeriod)}"
}

// Subscription base plans use ISO 8601 durations for their billing period — the small, fixed
// set Play Billing actually supports for subscriptions.
private fun billingPeriodLabel(isoPeriod: String): String = when (isoPeriod) {
    "P1W" -> "week"
    "P1M" -> "month"
    "P3M" -> "3 months"
    "P6M" -> "6 months"
    "P1Y" -> "year"
    else -> isoPeriod
}
