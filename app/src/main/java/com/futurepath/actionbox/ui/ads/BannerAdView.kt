package com.futurepath.actionbox.ui.ads

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

/**
 * Google's published TEST banner ad unit ID — always serves test creatives, never real ads or
 * real revenue, so it's safe to ship as a literal. Swap for a real ad unit ID from the AdMob
 * console before release (see AndroidManifest.xml's matching test APPLICATION_ID).
 */
private const val TEST_BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"

/**
 * A standard banner ad, shown only on the free tier — see MainScreen, which renders this above
 * the bottom nav bar when `!isPro` and skips it entirely for Pro rather than rendering-and-
 * hiding it.
 */
@Composable
fun BannerAdView(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context ->
            AdView(context).apply {
                setAdSize(AdSize.BANNER)
                adUnitId = TEST_BANNER_AD_UNIT_ID
                loadAd(AdRequest.Builder().build())
            }
        }
    )
}
