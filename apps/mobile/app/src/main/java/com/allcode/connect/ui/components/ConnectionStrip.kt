package com.allcode.connect.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.allcode.connect.data.ConnectionStatus
import com.allcode.connect.ui.theme.AllCode
import kotlinx.coroutines.delay

/** False while the machine a screen shows is unreachable, so working sparks stop claiming progress. */
val LocalLive = compositionLocalOf { true }

/**
 * Says plainly when a machine isn't connected: a sweep while connecting, a bar counting down to the
 * next try, and a short "connected" once the link is back. Healthy connects finish well under a
 * second, so the strip only appears once a link has been down for a moment.
 */
@Composable
fun ConnectionStrip(
    label: String,
    status: ConnectionStatus,
    hadData: Boolean,
    onRetry: () -> Unit,
    onRepair: () -> Unit,
    modifier: Modifier = Modifier,
    phoneOnline: Boolean = true,
) {
    val down = status != ConnectionStatus.Connected
    var showDown by remember { mutableStateOf(false) }
    var showBack by remember { mutableStateOf(false) }
    LaunchedEffect(down) {
        if (down) {
            showBack = false
            delay(if (status is ConnectionStatus.Blocked) 0 else 700)
            showDown = true
        } else if (showDown) {
            showDown = false
            showBack = true
            delay(1600)
            showBack = false
        }
    }
    val motion = AllCode.motion
    AnimatedVisibility(
        visible = showDown || showBack,
        modifier = modifier,
        enter = expandVertically(motion.enter(200), expandFrom = Alignment.Top) + fadeIn(motion.enter(200)),
        exit = shrinkVertically(motion.exit(240), shrinkTowards = Alignment.Top) + fadeOut(motion.exit(180)),
    ) {
        CompositionLocalProvider(LocalLive provides true) {
            StripContent(label, if (down) status else ConnectionStatus.Connected, hadData, phoneOnline, onRetry, onRepair)
        }
    }
}

@Composable
private fun StripContent(label: String, status: ConnectionStatus, hadData: Boolean, phoneOnline: Boolean, onRetry: () -> Unit, onRepair: () -> Unit) {
    val colors = AllCode.colors
    // Without a network every retry fails the same way; say what's actually wrong and wait for it.
    val waitingForNetwork = !phoneOnline && status !is ConnectionStatus.Blocked && status != ConnectionStatus.Connected
    val tone = when (status) {
        ConnectionStatus.Connected -> colors.sage
        is ConnectionStatus.Blocked -> colors.rust
        else -> colors.amber
    }
    val headline: String
    var detail: String? = null
    var action: Pair<String, () -> Unit>? = null
    when {
        waitingForNetwork -> {
            headline = "This phone is offline"
            detail = "reconnects to $label when a network is back"
        }
        else -> when (status) {
            ConnectionStatus.Connected -> headline = "Connected to $label"
            ConnectionStatus.Syncing -> headline = "Syncing with $label…"
            is ConnectionStatus.Retrying -> {
                val now by produceState(System.currentTimeMillis(), status.retryAtMillis) {
                    while (true) { value = System.currentTimeMillis(); delay(250) }
                }
                val seconds = ((status.retryAtMillis - now + 999) / 1000).coerceAtLeast(0)
                headline = "Can't reach $label"
                detail = (if (seconds > 0) "retrying in ${seconds}s · " else "retrying · ") + status.message
                action = "retry now" to onRetry
            }
            is ConnectionStatus.Blocked -> {
                headline = "$label needs attention"
                detail = status.message
                action = if (status.needsPairing) "pair again" to onRepair else "retry" to onRetry
            }
            is ConnectionStatus.Connecting -> {
                headline = if (hadData) "Reconnecting to $label…" else "Connecting to $label…"
                if (status.attempt > 0) detail = "attempt ${status.attempt + 1}"
            }
            ConnectionStatus.Offline -> headline = if (hadData) "Reconnecting to $label…" else "Connecting to $label…"
        }
    }
    Column(Modifier.fillMaxWidth().background(colors.paperDeep)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
                when {
                    waitingForNetwork -> Spark(12.dp, tone, spinning = false)
                    else -> when (status) {
                        ConnectionStatus.Connected -> Box(Modifier.size(7.dp).clip(CircleShape).background(tone))
                        is ConnectionStatus.Blocked -> Text("×", style = AllCode.type.monoStrong, color = tone)
                        is ConnectionStatus.Retrying -> Spark(12.dp, tone, spinning = false)
                        else -> Spark(12.dp, tone)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(headline, style = AllCode.type.label, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                detail?.let { Text(it, style = AllCode.type.monoSmall, color = colors.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            action?.let { (text, onClick) ->
                Text(
                    text,
                    style = AllCode.type.mono,
                    color = colors.clay,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).pressable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
        if (waitingForNetwork) Box(Modifier.fillMaxWidth().height(2.dp).background(colors.line)) else LinkProgress(status, tone)
    }
}

/** A 2 dp line: an endless sweep while connecting, a fill toward the next try while waiting. */
@Composable
private fun LinkProgress(status: ConnectionStatus, tone: Color) {
    val track = AllCode.colors.line
    val line = Modifier.fillMaxWidth().height(2.dp)
    when (status) {
        is ConnectionStatus.Retrying -> {
            val fill = remember(status.retryAtMillis) { Animatable(0f) }
            LaunchedEffect(status.retryAtMillis) {
                val remaining = (status.retryAtMillis - System.currentTimeMillis()).coerceIn(0, 60_000).toInt()
                fill.animateTo(1f, tween(remaining, easing = LinearEasing))
            }
            Box(line.drawBehind {
                drawRect(track)
                drawRect(tone, size = Size(size.width * fill.value, size.height))
            })
        }
        ConnectionStatus.Connected, is ConnectionStatus.Blocked -> Box(line.background(tone.copy(alpha = 0.6f)))
        else -> if (AllCode.motion.reduced) {
            Box(line.background(tone.copy(alpha = 0.4f)))
        } else {
            val sweep = rememberInfiniteTransition(label = "link").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
                label = "sweep",
            )
            Box(line.drawBehind {
                drawRect(track)
                val width = size.width * 0.3f
                drawRect(tone, topLeft = Offset(-width + (size.width + width) * sweep.value, 0f), size = Size(width, size.height))
            })
        }
    }
}
