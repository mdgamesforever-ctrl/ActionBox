package com.futurepath.actionbox.ui.paywall

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.billingclient.api.ProductDetails
import com.futurepath.actionbox.data.NotificationRepository

/**
 * The Pro upsell screen — reached from Settings when the user isn't Pro yet (see
 * SettingsScreen's "Upgrade to Pro" button). [productDetails]/[billingUnavailable] come from
 * [com.futurepath.actionbox.billing.BillingRepository] via the view model; this composable is
 * pure presentation over whatever state Play Billing is actually in, including the states a
 * real user can hit (still loading, or genuinely unavailable — no network, Play Store outage,
 * or no listing configured).
 */
@Composable
fun PaywallScreen(
    productDetails: ProductDetails?,
    billingUnavailable: Boolean,
    onSubscribeClick: (Activity) -> Unit,
    onRetryClick: () -> Unit
) {
    val activity = LocalContext.current as? Activity

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "ActionBox Pro", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))

        BenefitRow("Unlimited notification history — Free keeps only the last ${NotificationRepository.FREE_RETENTION_DAYS} days")
        BenefitRow("No ads")
        BenefitRow("Smart corrections — ActionBox learns from how you re-categorize messages")

        Spacer(modifier = Modifier.height(32.dp))

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
    }
}

@Composable
private fun BenefitRow(text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(text = "✓", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(0.dp))
        Text(text = "  $text", style = MaterialTheme.typography.bodyLarge)
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
