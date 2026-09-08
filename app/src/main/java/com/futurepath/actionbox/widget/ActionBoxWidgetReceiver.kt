package com.futurepath.actionbox.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * The actual `<receiver>` the OS talks to (declared in AndroidManifest.xml) — Glance itself only
 * provides [GlanceAppWidgetReceiver] as a base class, not a concrete BroadcastReceiver, since a
 * real manifest component needs a distinct class per widget.
 */
class ActionBoxWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ActionBoxWidget()
}
