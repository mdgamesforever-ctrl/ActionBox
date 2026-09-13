package com.futurepath.actionbox.ui.recovery

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.futurepath.actionbox.classification.ClassifiedState
import com.futurepath.actionbox.data.NotificationEntity
import com.futurepath.actionbox.ui.theme.ActionBoxTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Regression test for the delete-swipe background bleeding through Handled/Snoozed rows at
 * rest. The actual bug wasn't the swipe offset — [androidx.compose.material3.rememberSwipeToDismissBoxState]
 * already defaults to [androidx.compose.material3.SwipeToDismissBoxValue.Settled] (offset 0) —
 * it was that [RecoveryRow]'s foreground content had transparent gaps (this row's own container,
 * and specifically the "Snoozed until.../Restore" footer row tagged "recoveryRowFooter" below),
 * so SwipeToDismissBox's always-rendered pink error-colored background layer showed through those
 * gaps even with no drag in progress. This is a real compositing bug, not a semantics-tree one —
 * a node existing/not-existing check wouldn't catch it (the delete icon was already correctly
 * absent at rest before this fix; the bleed-through was the background *color*, not the icon) —
 * so this asserts on an actual rendered pixel instead.
 *
 * Draws the decor view straight into a [Bitmap] via [android.view.View.draw] rather than the
 * usual `composeTestRule.onRoot().captureToImage()` (which under Robolectric hangs waiting for a
 * PixelCopy/hardware-redraw callback that never fires in this environment) — a plain synchronous
 * view draw sidesteps that redraw-synchronization path entirely while still exercising the real
 * Compose layout/paint pipeline (Robolectric's native graphics mode, see [GraphicsMode]).
 *
 * Runs as a JVM unit test (Robolectric) rather than an instrumented androidTest: this repo has
 * no connected-device/emulator test infrastructure, and Robolectric lets this run in the same
 * `./gradlew testDebugUnitTest` pass as everything else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecoveryScreenSwipeBackgroundTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val snoozedNotification = NotificationEntity(
        id = 1L,
        notificationKey = "test-key",
        sourceApp = "Test App",
        sender = "Test Sender",
        text = "Test notification body",
        normalizedText = "test notification body",
        timestamp = 0L,
        capturedAt = 0L,
        classifiedState = ClassifiedState.WAITING,
        snoozedUntil = System.currentTimeMillis() + 3_600_000L,
        snoozedAt = System.currentTimeMillis()
    )

    @Test
    fun `delete background does not bleed through a snoozed row at rest`() {
        assertFooterNotBleedingDeleteColor(snoozedUntilText = "Snoozed until later")
    }

    @Test
    fun `delete background does not bleed through a handled row at rest`() {
        // Handled rows pass a null snoozedUntilText (see RecoveryScreen's doc on RecoveryRow) —
        // the footer row still renders (just with an empty leading Text, per that same doc), so
        // the same transparent-gap bug applies to it identically.
        assertFooterNotBleedingDeleteColor(snoozedUntilText = null)
    }

    private fun assertFooterNotBleedingDeleteColor(snoozedUntilText: String?) {
        var errorColor = Color.Unspecified
        composeTestRule.setContent {
            ActionBoxTheme {
                errorColor = MaterialTheme.colorScheme.error
                RecoveryRow(
                    notification = snoozedNotification,
                    isPro = false,
                    onCorrect = { _, _ -> },
                    snoozedUntilText = snoozedUntilText,
                    onRestore = {},
                    onDelete = {},
                    selectionMode = false,
                    selected = false,
                    onToggleSelected = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        val footerNode = composeTestRule.onNodeWithTag("recoveryRowFooter").fetchSemanticsNode()
        val bounds = footerNode.boundsInRoot
        // A couple of pixels inset from the footer row's own top edge: with
        // verticalAlignment = CenterVertically and the Restore TextButton's minimum touch target
        // taller than the "Snoozed until..." text line, this point sits in the row's own
        // background above both children — never on a glyph or the button, regardless of exact
        // text/locale/font metrics, which is exactly the region that showed pink at rest before
        // this fix (before the fix, this row painted no background of its own at all).
        val sampleX = (bounds.left + 4f).toInt().coerceAtLeast(0)
        val sampleY = (bounds.top + 2f).toInt().coerceAtLeast(0)

        val decorView = composeTestRule.activity.window.decorView
        shadowOf(decorView).let { /* no-op: just documents decorView is Robolectric-shadowed */ }
        val bitmap = Bitmap.createBitmap(
            decorView.width.coerceAtLeast(1),
            decorView.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        decorView.draw(Canvas(bitmap))
        val pixel = Color(bitmap.getPixel(sampleX, sampleY))

        val distanceFromError = colorDistance(pixel, errorColor)
        assertTrue(
            "Expected pixel at ($sampleX, $sampleY) in the footer row to NOT be the delete-swipe " +
                "background color (#${Integer.toHexString(errorColor.toArgb())}), but sampled " +
                "#${Integer.toHexString(pixel.toArgb())} — the delete background is bleeding " +
                "through at rest.",
            distanceFromError > COLOR_DISTANCE_THRESHOLD
        )
    }

    /** Sum of per-channel absolute differences (0-255 scale each) — simple and enough to tell
     * "clearly a different color" from "the same color, maybe with minor anti-aliasing". */
    private fun colorDistance(a: Color, b: Color): Int {
        fun channel(v: Float) = (v * 255f).toInt()
        return abs(channel(a.red) - channel(b.red)) +
            abs(channel(a.green) - channel(b.green)) +
            abs(channel(a.blue) - channel(b.blue))
    }

    companion object {
        private const val COLOR_DISTANCE_THRESHOLD = 60
    }
}
