package com.futurepath.actionbox.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the paywall not dismissing itself after a successful purchase. The bug
 * wasn't in [com.futurepath.actionbox.billing.BillingRepository] or in
 * [com.futurepath.actionbox.viewmodel.NotificationViewModel.isPro] — both already updated
 * correctly and promptly on purchase — it was that nothing observed that change *while the
 * paywall route was the active screen*: [PaywallAutoDismissEffect] is that missing observer,
 * extracted out of MainScreen's `composable(PAYWALL_ROUTE) { }` block specifically so this can be
 * tested without standing up a real NavController/NavHost.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MainScreenPaywallAutoDismissTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `does not fire while isPro stays false`() {
        var proUnlockedCount = 0
        composeTestRule.setContent {
            PaywallAutoDismissEffect(isPro = false, onProUnlocked = { proUnlockedCount++ })
        }
        composeTestRule.waitForIdle()

        assertEquals(0, proUnlockedCount)
    }

    @Test
    fun `fires exactly once the moment isPro flips from false to true`() {
        var isPro by mutableStateOf(false)
        var proUnlockedCount = 0
        composeTestRule.setContent {
            PaywallAutoDismissEffect(isPro = isPro, onProUnlocked = { proUnlockedCount++ })
        }
        composeTestRule.waitForIdle()
        assertEquals(0, proUnlockedCount)

        // Simulates BillingRepository.handlePurchase's settingsRepository.setPro(true) landing
        // asynchronously while the user is still looking at the paywall — exactly the sequence
        // that previously left the paywall stuck on-screen.
        isPro = true
        composeTestRule.waitForIdle()

        assertEquals(1, proUnlockedCount)
    }

    @Test
    fun `does not re-fire on an unrelated recomposition while isPro stays true`() {
        var isPro by mutableStateOf(true)
        var recomposeTrigger by mutableStateOf(0)
        var proUnlockedCount = 0
        composeTestRule.setContent {
            @Suppress("UNUSED_EXPRESSION")
            recomposeTrigger // read so bumping it below forces a recomposition
            PaywallAutoDismissEffect(isPro = isPro, onProUnlocked = { proUnlockedCount++ })
        }
        composeTestRule.waitForIdle()
        assertEquals(1, proUnlockedCount)

        recomposeTrigger++
        composeTestRule.waitForIdle()

        assertEquals(1, proUnlockedCount)
    }
}
