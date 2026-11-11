package com.allcode.connect.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.allcode.connect.MainActivity
import com.allcode.connect.core.WireJson
import com.allcode.connect.graph
import com.allcode.connect.ui.usage.resetText
import com.allcode.connect.ui.usage.usageWindows
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant

@Serializable
data class WidgetLimit(val provider: String, val label: String, val usedPercent: Double, val resetsAt: String?)

/** Subscription limits from paired machines, saved by the app so the widget renders without it. */
class UsageWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val limits = read(context)
        provideContent { Content(limits) }
    }

    @Composable
    private fun Content(limits: List<WidgetLimit>) {
        val paper = ColorProvider(day = Color(0xFFFAF9F5), night = Color(0xFF262624))
        val ink = ColorProvider(day = Color(0xFF141413), night = Color(0xFFF5F4EE))
        val soft = ColorProvider(day = Color(0xFF8F8D86), night = Color(0xFF8A877E))
        val clay = ColorProvider(day = Color(0xFFD97757), night = Color(0xFFE08A6B))
        val track = ColorProvider(day = Color(0xFFF0EEE6), night = Color(0xFF3A3936))
        GlanceTheme {
            Column(
                GlanceModifier.fillMaxSize().background(paper).cornerRadius(24.dp).padding(14.dp).clickable(actionStartActivity<MainActivity>()),
            ) {
                Text("Usage", style = TextStyle(color = soft, fontSize = 11.sp, fontWeight = FontWeight.Medium))
                Spacer(GlanceModifier.height(6.dp))
                if (limits.isEmpty()) {
                    Text("Open AllCode to load limits", style = TextStyle(color = ink, fontSize = 13.sp))
                }
                limits.take(4).forEach { limit ->
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${limit.provider} · ${limit.label}", style = TextStyle(color = ink, fontSize = 12.sp, fontWeight = FontWeight.Medium), modifier = GlanceModifier.defaultWeight())
                        Text("${limit.usedPercent.toInt()}%", style = TextStyle(color = clay, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                    }
                    LinearProgressIndicator(
                        progress = (limit.usedPercent / 100).toFloat().coerceIn(0f, 1f),
                        modifier = GlanceModifier.fillMaxWidth().height(6.dp).cornerRadius(3.dp),
                        color = clay,
                        backgroundColor = track,
                    )
                    Text(resetText(limit.resetsAt?.let { runCatching { Instant.parse(it) }.getOrNull() }), style = TextStyle(color = soft, fontSize = 10.sp))
                    Spacer(GlanceModifier.height(4.dp))
                }
            }
        }
    }

    companion object {
        private const val KEY = "widget.limits"

        fun read(context: Context): List<WidgetLimit> {
            val raw = context.getSharedPreferences("allcode", Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
            return runCatching { WireJson.decodeFromString(ListSerializer(WidgetLimit.serializer()), raw) }.getOrDefault(emptyList())
        }

        /** Saves the tightest limits per provider across machines, then redraws every widget. */
        suspend fun refresh(context: Context) {
            val snapshots = context.graph.connections.connections.value.mapNotNull { it.config.value }
            val limits = snapshots.flatMap { config ->
                config.providers.flatMap { provider ->
                    provider.usageWindows().map { WidgetLimit(provider.displayName, it.label, it.usedPercent, it.resetsAt?.toString()) }
                }
            }.groupBy { it.provider to it.label }.map { (_, list) -> list.maxBy { it.usedPercent } }.sortedByDescending { it.usedPercent }
            if (limits.isEmpty()) return
            context.getSharedPreferences("allcode", Context.MODE_PRIVATE).edit()
                .putString(KEY, WireJson.encodeToString(ListSerializer(WidgetLimit.serializer()), limits)).apply()
            if (GlanceAppWidgetManager(context).getGlanceIds(UsageWidget::class.java).isNotEmpty()) UsageWidget().updateAll(context)
        }
    }
}

class UsageWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UsageWidget()
}
