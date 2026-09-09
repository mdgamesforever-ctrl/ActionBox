package com.futurepath.actionbox.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/** See [DiagnosticTestWidget]'s doc — throwaway, remove once the widget bug is isolated. */
class DiagnosticTestWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DiagnosticTestWidget()
}
