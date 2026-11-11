package com.allcode.connect.ui.thread

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.allcode.connect.ui.theme.AllCode
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.compose.elements.highlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

/** Agent prose in sans, code in mono, links in clay. */
@Composable
fun ProseMarkdown(text: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    val colors = AllCode.colors
    val prose = if (compact) AllCode.type.body.copy(color = colors.ink) else AllCode.type.prose.copy(color = colors.ink)
    val heading = AllCode.type.title.copy(color = colors.ink)
    Markdown(
        content = text,
        modifier = modifier,
        colors = markdownColor(
            text = colors.ink,
            codeBackground = colors.code,
            inlineCodeBackground = colors.inset,
            dividerColor = colors.line,
            tableBackground = colors.paperDeep,
        ),
        typography = markdownTypography(
            h1 = heading.copy(fontSize = 20.sp, lineHeight = 26.sp),
            h2 = heading.copy(fontSize = 18.sp, lineHeight = 24.sp),
            h3 = heading.copy(fontSize = 16.5.sp, lineHeight = 22.sp),
            h4 = AllCode.type.heading.copy(color = colors.ink),
            h5 = AllCode.type.bodyStrong.copy(color = colors.ink),
            h6 = AllCode.type.bodyStrong.copy(color = colors.inkSoft),
            text = prose,
            code = AllCode.type.mono.copy(color = colors.ink),
            inlineCode = AllCode.type.mono.copy(color = colors.ink, fontSize = prose.fontSize * 0.86f),
            quote = prose.copy(color = colors.inkSoft),
            paragraph = prose,
            ordered = prose,
            bullet = prose,
            list = prose,
            table = AllCode.type.body.copy(color = colors.ink),
            textLink = TextLinkStyles(SpanStyle(color = colors.clay, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline)),
        ),
        components = markdownComponents(codeBlock = highlightedCodeBlock, codeFence = highlightedCodeFence),
    )
}
