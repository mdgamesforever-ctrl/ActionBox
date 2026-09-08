package com.futurepath.actionbox.ui.ads

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.futurepath.actionbox.BuildConfig
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

/**
 * A standard banner ad, shown only on the free tier — see MainScreen, which renders this above
 * the bottom nav bar when `!isPro` and skips it entirely for Pro rather than rendering-and-
 * hiding it.
 *
 * The ad unit ID comes from [BuildConfig.ADMOB_BANNER_AD_UNIT_ID] (set per build type in
 * app/build.gradle.kts): Google's published TEST unit ID in debug builds — a debug build must
 * never request real ads, since AdMob treats a developer's own test-device requests as invalid
 * traffic — and the real production unit ID in release builds. The matching APPLICATION_ID
 * split lives in AndroidManifest.xml's `${admobAppId}` placeholder.
 */
@Composable
fun BannerAdView(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context ->
            AdView(context).apply {
                setAdSize(AdSize.BANNER)
                adUnitId = BuildConfig.ADMOB_BANNER_AD_UNIT_ID
                loadAd(AdRequest.Builder().build())
            }
        }
    )
}
