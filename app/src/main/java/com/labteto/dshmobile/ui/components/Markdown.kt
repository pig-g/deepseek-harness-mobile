package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsColors
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.DshTheme

/**
 * Block-level Markdown renderer: fenced code blocks, #-#### headings, bullet and
 * ordered lists, blockquotes, and paragraphs with inline **bold**, *italic*,
 * `code` chips and [links](https://example.com). Bare `https://…` URLs are linked too.
 * Tables render as plain text. The whole document is selectable (long-press), and
 * tapping a link opens it in the system browser.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    val blocks = remember(text) { parseMarkdown(text) }
    // One selection scope per document: long-press anywhere selects within it, with handles
    // and the copy menu; a plain tap is left for the outer UI (message actions, toggles).
    SelectionContainer {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is MdBlock.Heading -> {
                        val style = when (block.level) {
                            1 -> DsType.mdH1
                            2 -> DsType.mdH2
                            3 -> DsType.mdH3
                            else -> DsType.mdH4
                        }
                        InlineMarkdown(block.text, style.copy(color = colors.labelPrimary), Modifier.padding(top = 10.dp))
                    }
                    is MdBlock.Paragraph -> InlineMarkdown(
                        block.lines.joinToString(" "),
                        DsType.mdBody.copy(color = colors.labelPrimary),
                        Modifier.fillMaxWidth(),
                    )
                    is MdBlock.MdList -> MdListBlock(block)
                    is MdBlock.Blockquote -> MdBlockquote(block)
                    is MdBlock.Code -> CodeBlock(block.lang, block.code)
                    is MdBlock.Table -> block.rows.forEach { row ->
                        InlineMarkdown(row, DsType.mdSmall.copy(color = colors.labelTertiary), Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

// ---- Parser (deterministic, line-based) ------------------------------------

private val HEADING_REGEX = Regex("^(#{1,4})\\s+(.*)$")
private val ORDERED_REGEX = Regex("^\\d+\\.\\s+")

private sealed interface MdBlock {
    data class Paragraph(val lines: List<String>) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class MdList(val items: List<String>, val ordered: Boolean) : MdBlock
    data class Blockquote(val lines: List<String>) : MdBlock
    data class Code(val lang: String?, val code: String) : MdBlock
    data class Table(val rows: List<String>) : MdBlock
}

private fun parseMarkdown(markdown: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = markdown.replace("\r\n", "\n").split("\n")
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> {
                val lang = trimmed.removePrefix("```").trim().ifEmpty { null }
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                i++ // skip closing fence
                blocks += MdBlock.Code(lang, code.toString().trimEnd('\n'))
            }
            HEADING_REGEX.matches(trimmed) -> {
                val match = HEADING_REGEX.matchEntire(trimmed)!!
                val level = match.groupValues[1].length
                val text = match.groupValues[2].trim().trimEnd('#').trim()
                blocks += MdBlock.Heading(level, text)
                i++
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                val items = mutableListOf<String>()
                while (i < lines.size) {
                    val t = lines[i].trimStart()
                    if (!t.startsWith("- ") && !t.startsWith("* ")) break
                    items += t.removePrefix("- ").removePrefix("* ").trim()
                    i++
                }
                blocks += MdBlock.MdList(items, ordered = false)
            }
            ORDERED_REGEX.containsMatchIn(trimmed) -> {
                val items = mutableListOf<String>()
                while (i < lines.size && ORDERED_REGEX.containsMatchIn(lines[i].trimStart())) {
                    items += ORDERED_REGEX.replace(lines[i].trim(), "").trim()
                    i++
                }
                blocks += MdBlock.MdList(items, ordered = true)
            }
            trimmed.startsWith(">") -> {
                val quote = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    quote += lines[i].trim().removePrefix(">").trim()
                    i++
                }
                blocks += MdBlock.Blockquote(quote)
            }
            trimmed.startsWith("|") -> {
                val rows = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                    if (!isTableSeparator(lines[i])) rows += lines[i]
                    i++
                }
                blocks += MdBlock.Table(rows)
            }
            line.isBlank() -> i++
            else -> {
                val para = mutableListOf(line)
                i++
                while (i < lines.size && lines[i].isNotBlank() && !isSpecialLine(lines[i])) {
                    para += lines[i]
                    i++
                }
                blocks += MdBlock.Paragraph(para)
            }
        }
    }
    return blocks
}

private fun isSpecialLine(line: String): Boolean {
    val trimmed = line.trimStart()
    return trimmed.startsWith("```") ||
        HEADING_REGEX.matches(trimmed) ||
        trimmed.startsWith("- ") ||
        trimmed.startsWith("* ") ||
        ORDERED_REGEX.containsMatchIn(trimmed) ||
        trimmed.startsWith(">") ||
        trimmed.startsWith("|")
}

/** Table separator rows (only pipes, dashes, colons and spaces) are dropped. */
private fun isTableSeparator(line: String): Boolean =
    line.replace(Regex("[|:\\-\\s]"), "").isEmpty()

// ---- Inline rendering ------------------------------------------------------

internal sealed interface InlineSegment {
    data class Plain(val text: String) : InlineSegment
    data class Bold(val text: String) : InlineSegment
    data class Italic(val text: String) : InlineSegment
    data class Code(val text: String) : InlineSegment
    data class Link(val text: String, val url: String) : InlineSegment
}

internal fun parseInlineSegments(text: String): List<InlineSegment> {
    val segments = mutableListOf<InlineSegment>()
    val sb = StringBuilder()
    var i = 0
    fun flush() {
        if (sb.isNotEmpty()) {
            segments += InlineSegment.Plain(sb.toString())
            sb.clear()
        }
    }
    while (i < text.length) {
        when {
            isBareUrlStart(text, i) -> {
                val end = bareUrlEnd(text, i)
                if (end > i) {
                    flush()
                    val url = text.substring(i, end)
                    segments += InlineSegment.Link(url, url)
                    i = end
                } else {
                    sb.append(text[i]); i++
                }
            }
            text.startsWith("`", i) -> {
                val end = text.indexOf('`', i + 1)
                if (end != -1) {
                    flush()
                    segments += InlineSegment.Code(text.substring(i + 1, end))
                    i = end + 1
                } else {
                    sb.append(text[i]); i++
                }
            }
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    flush()
                    segments += InlineSegment.Bold(text.substring(i + 2, end))
                    i = end + 2
                } else {
                    sb.append(text[i]); i++
                }
            }
            text.startsWith("*", i) -> {
                val end = text.indexOf("*", i + 1)
                if (end != -1) {
                    flush()
                    segments += InlineSegment.Italic(text.substring(i + 1, end))
                    i = end + 1
                } else {
                    sb.append(text[i]); i++
                }
            }
            text.startsWith("[", i) -> {
                val close = text.indexOf("](", i + 1)
                if (close != -1) {
                    val end = text.indexOf(')', close + 2)
                    if (end != -1) {
                        flush()
                        segments += InlineSegment.Link(text.substring(i + 1, close), text.substring(close + 2, end))
                        i = end + 1
                    } else {
                        sb.append(text[i]); i++
                    }
                } else {
                    sb.append(text[i]); i++
                }
            }
            else -> {
                sb.append(text[i]); i++
            }
        }
    }
    flush()
    return segments
}

/** True if a bare `http(s)://` URL starts at [i] (word boundary, not mid-token, host present). */
private fun isBareUrlStart(text: String, i: Int): Boolean {
    val schemeLen = when {
        text.startsWith("https://", i) -> 8
        text.startsWith("http://", i) -> 7
        else -> return false
    }
    // Guard against a token glued in front of the scheme (`foohttps://…` is not a URL).
    if (i > 0 && (text[i - 1].isLetterOrDigit() || text[i - 1] == '_')) return false
    // A scheme with nothing after it (`http:// end` or `http://` at end of line) is not a URL.
    val after = i + schemeLen
    if (after >= text.length) return false
    val c = text[after]
    return !c.isWhitespace() && c != '"' && c != '\'' && c != '`' && c != '<' && c != '>'
}

/** End (exclusive) of the bare URL starting at [start]: up to whitespace/quotes/brackets,
 *  minus trailing sentence punctuation and unbalanced closing brackets. */
private fun bareUrlEnd(text: String, start: Int): Int {
    var end = start
    while (end < text.length) {
        val c = text[end]
        if (c.isWhitespace() || c == '"' || c == '\'' || c == '<' || c == '>' || c == '`') break
        end++
    }
    while (end > start) {
        val c = text[end - 1]
        when {
            c == ')' || c == ']' -> {
                val open = text.substring(start, end).count { it == '(' || it == '[' }
                val close = text.substring(start, end).count { it == ')' || it == ']' }
                if (close <= open) break
                end--
            }
            ",.;:!?\"'".contains(c) -> end--
            else -> break
        }
    }
    return end
}

/** Renders one line of markdown with bold/italic/code/link spans. */
@Composable
private fun InlineMarkdown(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    val codeStyle = style.copy(
        fontFamily = DsType.codeFont,
        color = colors.labelPrimary,
    )
    val result = remember(text, style, codeStyle, colors) {
        buildInlineContent(text, codeStyle, colors)
    }
    SelectableText(result.text, result.links, style, modifier)
}

/** A clickable link span: the URL plus the character range it occupies in the text. */
internal data class TextLink(val url: String, val range: TextRange)

/** Built text plus its link spans. */
internal data class InlineResult(val text: AnnotatedString, val links: List<TextLink>)

private fun buildInlineContent(
    text: String,
    codeStyle: TextStyle,
    colors: DsColors,
): InlineResult {
    val builder = AnnotatedString.Builder()
    val links = mutableListOf<TextLink>()
    val linkStyle = SpanStyle(color = colors.accent, textDecoration = TextDecoration.LineThrough)
    parseInlineSegments(text).forEach { segment ->
        when (segment) {
            is InlineSegment.Plain -> builder.append(segment.text)
            is InlineSegment.Bold -> builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(segment.text) }
            is InlineSegment.Italic -> builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(segment.text) }
            is InlineSegment.Code -> builder.withStyle(
                SpanStyle(fontFamily = codeStyle.fontFamily, color = codeStyle.color),
            ) { append(segment.text) }
            is InlineSegment.Link -> {
                val start = builder.length
                builder.withStyle(linkStyle) { append(segment.text) }
                links += TextLink(segment.url, TextRange(start, builder.length))
            }
        }
    }
    return InlineResult(builder.toAnnotatedString(), links)
}

/**
 * Plain text with bare `http(s)` URLs turned into link spans (no markdown interpretation).
 * Used for user message bubbles, which render raw text.
 */
internal fun linkifyUrls(text: String, linkStyle: SpanStyle): InlineResult {
    val builder = AnnotatedString.Builder()
    val links = mutableListOf<TextLink>()
    var plainStart = 0
    var i = 0
    while (i < text.length) {
        if (!isBareUrlStart(text, i)) {
            i++
            continue
        }
        val end = bareUrlEnd(text, i)
        if (end <= i) {
            i++
            continue
        }
        // Flush the plain run before this URL; the builder stays 1:1 with the input text.
        if (i > plainStart) builder.append(text.substring(plainStart, i))
        val start = builder.length
        builder.withStyle(linkStyle) { append(text.substring(i, end)) }
        links += TextLink(text.substring(i, end), TextRange(start, builder.length))
        i = end
        plainStart = end
    }
    if (plainStart < text.length) builder.append(text.substring(plainStart))
    return InlineResult(builder.toAnnotatedString(), links)
}

/**
 * Selectable text with tappable links: long-press selects (handles + copy menu, provided by the
 * enclosing [SelectionContainer]) and a quick tap on a link span opens the URL in the system
 * browser. A tap that lands on a link claims the gesture (consumes the down event) so an
 * outer `clickable` modifier — e.g. the message-actions toggle — does not fire as well.
 */
@Composable
internal fun SelectableText(
    text: AnnotatedString,
    links: List<TextLink>,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // `pointerInput` is appended last so its coordinate space is the text frame itself
    // (after any padding/background modifiers the caller passed).
    BasicText(
        text,
        modifier = modifier.pointerInput(text, links) {
            awaitPointerEventScope {
                // The scope is a `Density`, so dp converts directly.
                val slop = 8.dp.toPx()
                while (true) {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val result = layout ?: continue
                    val offset = result.getOffsetForPosition(down.position)
                    val hit = links.firstOrNull { it.range.start <= offset && offset < it.range.end }
                        ?: continue
                    // Claim the gesture so the outer message-level `clickable` (action toggle)
                    // does not fire on a link tap.
                    down.consume()
                    var last = down.position
                    var released = false
                    while (!released) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            if (change.pressed) {
                                last = change.position
                            } else {
                                released = true
                                last = change.position
                                val dx = last.x - down.position.x
                                val dy = last.y - down.position.y
                                if (dx * dx + dy * dy <= slop * slop) uriHandler.openUri(hit.url)
                            }
                        }
                    }
                }
            }
        },
        style = style,
        onTextLayout = { layout = it },
    )
}

// ---- Block renderers --------------------------------------------------------

@Composable
private fun MdListBlock(block: MdBlock.MdList) {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth().padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        block.items.forEachIndexed { index, item ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    if (block.ordered) "${index + 1}." else "•",
                    style = DsType.mdBody.copy(color = colors.labelSecondary),
                    textAlign = if (block.ordered) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.width(if (block.ordered) 28.dp else 18.dp),
                )
                Spacer(Modifier.width(6.dp))
                InlineMarkdown(item, DsType.mdBody.copy(color = colors.labelPrimary), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MdBlockquote(block: MdBlock.Blockquote) {
    val colors = DsTheme.colors
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 2.dp)) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(1.dp))
                .background(colors.citation),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            block.lines.forEach { line ->
                InlineMarkdown(line, DsType.mdSmall.copy(color = colors.labelTertiary), Modifier.fillMaxWidth())
            }
        }
    }
}

/** Fenced code block with a sticky banner (lang · copy) and a mono pre. */
@Composable
private fun CodeBlock(lang: String?, code: String, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(DsShapes.block)
            .background(colors.codeBlockBg)
            .border(1.dp, colors.borderL1, DsShapes.block),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.codeBlockBanner)
                .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                lang?.let { "$it · copy" } ?: "copy",
                style = DsType.caption11Strong.copy(fontFamily = DsType.codeFont, color = colors.labelCaption),
                color = colors.labelCaption,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = "Copy code",
                tint = colors.labelTertiary,
                modifier = Modifier
                    .size(16.dp)
                    .clip(DsShapes.chip)
                    .clickable { clipboard.setText(AnnotatedString(code)) }
                    .padding(2.dp),
            )
        }
        Text(
            code,
            style = DsType.mdCode,
            color = colors.labelPrimary,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun MarkdownTextPreview() {
    DshTheme {
        MarkdownText(
            text = """
                # Heading

                A paragraph with **bold**, *italic* and `inline code` plus a [link](https://example.com)
                and a bare https://example.org/docs?ref=42 reference.

                - first item
                - second item

                1. ordered one
                2. ordered two

                > A quoted thought.

                ```kotlin
                val answer = 42
                ```

                | col a | col b |
                | ----- | ----- |
                | 1     | 2     |
            """.trimIndent(),
            modifier = Modifier.padding(16.dp),
        )
    }
}
