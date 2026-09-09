package com.futurepath.actionbox.widget

import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.text.Text

/**
 * Throwaway diagnostic widget — NOT wired into any UI, exists solely to isolate the "Couldn't
 * add widget" bug reported against [ActionBoxWidget]. No data-fetching, no Room, no theming, no
 * custom layout: just a single [Text]. If this ALSO fails to add with the same error, the cause
 * is structural to the app (manifest/build config/Glance setup as a whole), not to anything in
 * ActionBoxWidget's own composables or data path. If this succeeds while ActionBoxWidget still
 * fails, the cause is specific to ActionBoxWidget after all, despite nothing turning up in static
 * review. Remove this class, [DiagnosticTestWidgetReceiver], and their manifest/xml entries once
 * the bug is isolated.
 */
class DiagnosticTestWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: android.content.Context, id: GlanceId) {
        provideContent {
            Text("Test")
        }
    }
}
