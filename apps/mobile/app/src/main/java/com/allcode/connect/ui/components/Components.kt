package com.allcode.connect.ui.components

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.allcode.connect.R
import com.allcode.connect.data.ThreadPhase
import com.allcode.connect.ui.theme.AllCode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

/** Mirrors the Haptics setting so every press, approval, and tab switch respects it. */
object Haptics {
    @Volatile var enabled: Boolean = true
}

/**
 * Press feedback is a flat tint, like a selected line in a terminal: on at once, gone in 160 ms.
 * Quick taps hold it for 90 ms so the tint is seen even when the press was reported late.
 */
private class FlatIndication(private val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = FlatIndicationNode(interactionSource, color)
    override fun equals(other: Any?) = other is FlatIndication && other.color == color
    override fun hashCode() = color.hashCode()
}

private class FlatIndicationNode(private val source: InteractionSource, private val color: Color) : Modifier.Node(), DrawModifierNode {
    private val alpha = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            var pressedAt = 0L
            var fade: Job? = null
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        fade?.cancel()
                        pressedAt = SystemClock.uptimeMillis()
                        alpha.snapTo(1f)
                        invalidateDraw()
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        val held = SystemClock.uptimeMillis() - pressedAt
                        fade = coroutineScope.launch {
                            delay((90 - held).coerceAtLeast(0))
                            alpha.animateTo(0f, tween(160)) { invalidateDraw() }
                        }
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (alpha.value > 0f) drawRect(color.copy(alpha = color.alpha * alpha.value))
    }
}

@Composable
fun rememberFlatIndication(): Indication {
    val color = AllCode.colors.ink.copy(alpha = if (AllCode.colors.isDark) 0.09f else 0.06f)
    return remember(color) { FlatIndication(color) }
}

/** A tap target with the flat press tint and, by default, a light haptic tick. */
@Composable
fun Modifier.pressable(
    enabled: Boolean = true,
    haptic: Boolean = true,
    role: Role = Role.Button,
    onClick: () -> Unit,
): Modifier {
    val view = LocalView.current
    return this.clickable(remember { MutableInteractionSource() }, rememberFlatIndication(), enabled = enabled, role = role) {
        if (haptic && Haptics.enabled) view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        onClick()
    }
}

fun android.view.View.confirmHaptic() {
    if (Haptics.enabled) performHapticFeedback(HapticFeedbackConstants.CONFIRM)
}

fun android.view.View.rejectHaptic() {
    if (Haptics.enabled) performHapticFeedback(HapticFeedbackConstants.REJECT)
}

private val Greyscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/** The AllCode creature. Muted, it sits back as a grey watermark in empty states. */
@Composable
fun AllCodeMark(size: Dp, modifier: Modifier = Modifier, muted: Boolean = false) {
    Image(
        painterResource(R.drawable.ic_logo),
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = if (muted) Greyscale else null,
        alpha = if (muted) 0.4f else 1f,
    )
}

private class SparkFrame(val spokes: Int, val length: Float, val rotation: Float)

// The terminal spinner (· ✢ * ✶ ✻ ✽), drawn rather than typed: the bundled mono has no dingbats.
private val SPARK = listOf(
    SparkFrame(0, 0.22f, 0f),
    SparkFrame(4, 0.55f, 45f),
    SparkFrame(6, 0.7f, 0f),
    SparkFrame(6, 0.86f, 30f),
    SparkFrame(8, 0.95f, 0f),
    SparkFrame(8, 1f, 22.5f),
)

/**
 * The working indicator. While [spinning] it steps through six frames at about 8 fps, forward and
 * back; that discrete tick is the only continuous motion in the app, and it only runs while an agent works.
 */
@Composable
fun Spark(size: Dp = 14.dp, color: Color = AllCode.colors.clay, spinning: Boolean = true, modifier: Modifier = Modifier) {
    var frame by remember { mutableIntStateOf(SPARK.lastIndex) }
    val live = LocalLive.current
    val animate = spinning && live && !AllCode.motion.reduced
    if (animate) {
        LaunchedEffect(Unit) {
            val cycle = SPARK.size * 2 - 2
            var step = 0
            while (true) {
                delay(120)
                step = (step + 1) % cycle
                frame = if (step < SPARK.size) step else cycle - step
            }
        }
    }
    // Offline, a working spark holds still and fades: it can't vouch for progress it can't see.
    val tint = if (spinning && !live) color.copy(alpha = 0.4f) else color
    Canvas(modifier.size(size)) { drawSpark(SPARK[if (animate) frame else SPARK.lastIndex], tint) }
}

private fun DrawScope.drawSpark(frame: SparkFrame, color: Color) {
    val radius = size.minDimension / 2f
    if (frame.spokes == 0) {
        drawCircle(color, radius * frame.length, center)
        return
    }
    val stroke = radius * 0.26f
    val reach = (radius - stroke / 2f) * frame.length
    repeat(frame.spokes) { i ->
        val angle = Math.toRadians((frame.rotation + i * 360f / frame.spokes).toDouble())
        val end = Offset(center.x + reach * cos(angle).toFloat(), center.y + reach * sin(angle).toFloat())
        drawLine(color, center, end, strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
fun phaseColor(phase: ThreadPhase): Color = when (phase) {
    ThreadPhase.NeedsYou -> AllCode.colors.clay
    ThreadPhase.Working -> AllCode.colors.clay
    ThreadPhase.Queued -> AllCode.colors.inkFaint
    ThreadPhase.Failed -> AllCode.colors.rust
    ThreadPhase.Idle -> AllCode.colors.sage
}

/** A plain status dot, used where a phase needs to read at a glance. */
@Composable
fun StatusDot(phase: ThreadPhase, unread: Boolean = false, size: Dp = 8.dp) {
    val color = if (phase == ThreadPhase.Idle && !unread) AllCode.colors.lineStrong else phaseColor(phase)
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** The gutter glyph for a thread: a spark while it works, a cross when it failed, a dot otherwise. */
@Composable
fun PhaseGlyph(phase: ThreadPhase, unread: Boolean = false, spinning: Boolean = false) {
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        when (phase) {
            ThreadPhase.Working -> Spark(13.dp, AllCode.colors.clay, spinning = spinning)
            ThreadPhase.Failed -> Text("×", style = AllCode.type.monoStrong, color = AllCode.colors.rust)
            else -> StatusDot(phase, unread, 7.dp)
        }
    }
}

/** A small mono tag: a branch, a project, a count. */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: Color = AllCode.colors.inkSoft,
    container: Color = AllCode.colors.inset,
    onClick: (() -> Unit)? = null,
) {
    val base = modifier.clip(RoundedCornerShape(4.dp)).background(container)
    Row(
        (if (onClick != null) base.pressable(onClick = onClick) else base).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(13.dp), tint = tone)
        Text(text, style = AllCode.type.monoSmall, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun CozyCard(
    modifier: Modifier = Modifier,
    container: Color = AllCode.colors.surface,
    border: Color = AllCode.colors.line,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val base = modifier.clip(shape)
    Surface(
        modifier = if (onClick != null) base.pressable(onClick = onClick) else base,
        shape = shape,
        color = container,
        border = BorderStroke(1.dp, border),
        content = content,
    )
}

@Composable
fun TopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    divider: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    leading: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack) else Spacer(Modifier.width(10.dp))
            leading?.invoke()
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text(title, style = AllCode.type.heading, color = AllCode.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = AllCode.type.monoSmall, color = AllCode.colors.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            actions()
        }
        if (divider) Divider()
    }
}

@Composable
fun IconAction(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color = AllCode.colors.ink, enabled: Boolean = true) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).pressable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(20.dp), tint = if (enabled) tint else tint.copy(alpha = 0.35f))
    }
}

/** One mono option in a [FilterRow]. */
@Composable
fun FilterOption(text: String, selected: Boolean, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp))
            .background(if (selected) AllCode.colors.inset else Color.Transparent)
            .border(1.dp, if (selected) AllCode.colors.lineStrong else AllCode.colors.line, RoundedCornerShape(6.dp))
            .pressable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = AllCode.type.monoSmall, color = if (selected) AllCode.colors.ink else AllCode.colors.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Filter options that wrap onto a second line instead of scrolling sideways. */
@Composable
fun FilterRow(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    FlowRow(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, label -> FilterOption(label, index == selected) { onSelect(index) } }
    }
}

/** A lowercase mono heading for a group of rows. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.lowercase(), style = AllCode.type.overline, color = AllCode.colors.inkFaint, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    val colors = AllCode.colors
    Row(
        modifier.clip(RoundedCornerShape(8.dp))
            .background(if (enabled) colors.clay else colors.inset)
            .pressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(17.dp), tint = if (enabled) colors.onClay else colors.inkFaint)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = AllCode.type.label, color = if (enabled) colors.onClay else colors.inkFaint)
    }
}

@Composable
fun QuietButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, tone: Color = AllCode.colors.ink, onClick: () -> Unit) {
    val colors = AllCode.colors
    Row(
        modifier.clip(RoundedCornerShape(8.dp))
            .border(1.dp, colors.lineStrong, RoundedCornerShape(8.dp))
            .pressable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(17.dp), tint = tone)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = AllCode.type.label, color = tone)
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AllCodeMark(44.dp, muted = true)
        Spacer(Modifier.height(12.dp))
        Text(title, style = AllCode.type.heading, color = AllCode.colors.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(body, style = AllCode.type.body, color = AllCode.colors.inkSoft, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier, inset: PaddingValues = PaddingValues(0.dp)) {
    Box(modifier.padding(inset).fillMaxWidth().height(1.dp).background(AllCode.colors.line))
}

/** A single-line input in a hairline box, with an optional mono label above it. */
@Composable
fun TextInput(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    ime: ImeAction = ImeAction.Done,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    mono: Boolean = false,
    onIme: (() -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val style = (if (mono) AllCode.type.mono else AllCode.type.body).copy(color = AllCode.colors.ink)
    val shape = RoundedCornerShape(8.dp)
    Column(modifier) {
        if (label != null) {
            Text(label.lowercase(), style = AllCode.type.overline, color = AllCode.colors.inkFaint)
            Spacer(Modifier.height(6.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(AllCode.colors.clay),
            interactionSource = source,
            keyboardOptions = KeyboardOptions(capitalization = capitalization, autoCorrectEnabled = false, keyboardType = keyboard, imeAction = ime),
            keyboardActions = KeyboardActions(onAny = { onIme?.invoke() }),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().clip(shape).background(AllCode.colors.surface)
                        .border(1.dp, if (focused) AllCode.colors.clay else AllCode.colors.lineStrong, shape)
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                ) {
                    if (value.isEmpty()) Text(placeholder, style = style.copy(color = AllCode.colors.inkFaint), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
    }
}

/** "now", "4m", "3h", "Tue", "Mar 4": the compact times lists use. */
fun relativeTime(instant: Instant?, now: Instant = Instant.now()): String {
    instant ?: return ""
    val age = Duration.between(instant, now)
    return when {
        age.toMinutes() < 1 -> "now"
        age.toMinutes() < 60 -> "${age.toMinutes()}m"
        age.toHours() < 24 -> "${age.toHours()}h"
        age.toDays() < 7 -> DateTimeFormatter.ofPattern("EEE").withZone(ZoneId.systemDefault()).format(instant)
        else -> DateTimeFormatter.ofPattern("MMM d").withZone(ZoneId.systemDefault()).format(instant)
    }
}

fun formatDuration(duration: Duration?): String {
    duration ?: return ""
    val seconds = duration.seconds
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}
