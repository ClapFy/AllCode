package com.allcode.connect.ui.usage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allcode.connect.AppGraph
import com.allcode.connect.core.asObject
import com.allcode.connect.core.double
import com.allcode.connect.core.int
import com.allcode.connect.core.obj
import com.allcode.connect.core.objects
import com.allcode.connect.core.str
import com.allcode.connect.data.ProviderInfo
import com.allcode.connect.data.parseInstant
import com.allcode.connect.ui.Navigator
import com.allcode.connect.ui.components.CozyCard
import com.allcode.connect.ui.components.EmptyState
import com.allcode.connect.ui.components.SectionLabel
import com.allcode.connect.ui.components.TopBar
import com.allcode.connect.ui.theme.AllCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class UsageWindow(val label: String, val usedPercent: Double, val resetsAt: Instant?)

fun ProviderInfo.usageWindows(): List<UsageWindow> =
    (usageLimits as? JsonObject)?.objects("windows").orEmpty().map {
        UsageWindow(it.str("label") ?: it.str("kind") ?: "Limit", it.double("usedPercent") ?: 0.0, parseInstant(it.str("resetsAt")))
    }

fun resetText(at: Instant?): String {
    at ?: return ""
    val left = Duration.between(Instant.now(), at)
    if (left.isNegative) return "resetting"
    return when {
        left.toMinutes() < 60 -> "resets in ${left.toMinutes()}m"
        left.toHours() < 48 -> "resets in ${left.toHours()}h"
        else -> "resets in ${left.toDays()}d"
    }
}

/** One quota bar. Clay turns rust as the limit nears. */
@Composable
fun UsageBar(window: UsageWindow) {
    val fraction = (window.usedPercent / 100.0).toFloat().coerceIn(0f, 1f)
    val tone = when {
        window.usedPercent >= 85 -> AllCode.colors.rust
        window.usedPercent >= 60 -> AllCode.colors.amber
        else -> AllCode.colors.clay
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row {
            Text(window.label, style = AllCode.type.label, color = AllCode.colors.ink, modifier = Modifier.weight(1f))
            Text("${window.usedPercent.toInt()}%", style = AllCode.type.label, color = tone)
        }
        Spacer(Modifier.height(5.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(AllCode.colors.inset)) {
            Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(tone))
        }
        Text(resetText(window.resetsAt), style = AllCode.type.caption, color = AllCode.colors.inkFaint, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
fun ProviderUsageCard(provider: ProviderInfo) {
    val windows = provider.usageWindows()
    if (windows.isEmpty()) return
    CozyCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(provider.displayName, style = AllCode.type.heading, color = AllCode.colors.ink)
            Spacer(Modifier.height(6.dp))
            windows.forEach { UsageBar(it) }
        }
    }
}

@Composable
fun UsageScreen(graph: AppGraph, navigator: Navigator, environmentId: String?) {
    val envs by graph.connections.snapshots.collectAsStateWithLifecycle(emptyList())
    val shown = envs.filter { environmentId == null || it.env.environmentId == environmentId }
    Column(Modifier.fillMaxSize().background(AllCode.colors.paper)) {
        TopBar("Usage", subtitle = if (shown.size == 1) shown.first().env.label else "All machines", onBack = navigator::back)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            if (shown.all { snap -> snap.config?.providers.orEmpty().all { it.usageWindows().isEmpty() } }) {
                EmptyState("No limits reported", "Providers that report subscription limits show them here once connected.")
            }
            shown.forEach { snap ->
                val providers = snap.config?.providers.orEmpty().filter { it.usageWindows().isNotEmpty() }
                if (providers.isNotEmpty()) {
                    SectionLabel(snap.env.label)
                    providers.forEach { ProviderUsageCard(it) }
                }
                SpendChart(snap.connection)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private data class DaySpend(val day: LocalDate, val byProvider: Map<String, Double>)

@Composable
private fun SpendChart(connection: com.allcode.connect.data.EnvironmentConnection) {
    var days by remember { mutableStateOf<List<DaySpend>?>(null) }
    LaunchedEffect(connection.id) {
        val today = LocalDate.now()
        val result = runCatching {
            connection.call("server.getUsageSummary", buildJsonObject {
                put("sinceDay", today.minusDays(13).toString())
                put("untilDay", today.toString())
                put("timeZone", ZoneId.systemDefault().id)
            }).asObject()
        }.getOrNull() ?: return@LaunchedEffect
        val buckets = result.objects("buckets")
        days = (0..13).map { offset ->
            val day = today.minusDays((13 - offset).toLong())
            val spend = buckets.filter { it.str("day") == day.toString() }
                .groupBy { it.str("provider") ?: "other" }
                .mapValues { (_, list) -> list.sumOf { it.double("costUsd") ?: 0.0 } }
            DaySpend(day, spend)
        }
    }
    val data = days ?: return
    val total = data.sumOf { it.byProvider.values.sum() }
    if (total <= 0.0) return
    val providers = data.flatMap { it.byProvider.keys }.distinct()
    val palette = listOf(AllCode.colors.clay, AllCode.colors.sage, AllCode.colors.amber, AllCode.colors.inkSoft)
    val max = data.maxOf { it.byProvider.values.sum() }.coerceAtLeast(0.01)
    CozyCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Last 14 days", style = AllCode.type.overline, color = AllCode.colors.inkFaint)
            Text("$${"%.2f".format(total)} at API prices", style = AllCode.type.title, color = AllCode.colors.ink)
            Spacer(Modifier.height(12.dp))
            val line = AllCode.colors.line
            Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                val gap = 6.dp.toPx()
                val barWidth = (size.width - gap * (data.size - 1)) / data.size
                drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                data.forEachIndexed { index, day ->
                    var top = size.height
                    providers.forEachIndexed { p, provider ->
                        val value = day.byProvider[provider] ?: 0.0
                        if (value <= 0) return@forEachIndexed
                        val h = (value / max * (size.height - 4.dp.toPx())).toFloat()
                        drawRoundRect(
                            palette[p % palette.size],
                            topLeft = Offset(index * (barWidth + gap), top - h),
                            size = Size(barWidth, h),
                            cornerRadius = CornerRadius(3.dp.toPx()),
                        )
                        top -= h
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                providers.forEachIndexed { p, provider ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(palette[p % palette.size]))
                        Spacer(Modifier.width(5.dp))
                        Text(provider.replaceFirstChar { it.uppercase() }, style = AllCode.type.caption, color = AllCode.colors.inkSoft)
                    }
                }
            }
        }
    }
}
