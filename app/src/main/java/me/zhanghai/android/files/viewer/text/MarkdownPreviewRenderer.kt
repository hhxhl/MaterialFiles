/*
 * Copyright (c) 2026 MaterialFiles contributors
 * Licensed under the GNU General Public License, version 3 or later.
 */
package me.zhanghai.android.files.viewer.text

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.text.style.URLSpan
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Deliberately small Markdown preview for the existing text editor.
 * This is NOT a full CommonMark implementation: it handles headings, emphasis,
 * lists, blockquotes, rules, fenced/inline code, and basic links. Images,
 * tables, HTML and math are left as plain text. No WebView or library required.
 */
internal object MarkdownPreviewRenderer {
    private val heading = Regex("""^ {0,3}(#{1,6})[ \t]+(.*)$""")
    private val fence = Regex("""^ {0,3}(`{3,}|~{3,})(.*)$""")
    private val quote = Regex("""^ {0,3}>[ \t]?(.*)$""")
    private val unorderedList = Regex("""^( {0,6})[-*+][ \t]+(.*)$""")
    private val orderedList = Regex("""^( {0,6})(\d{1,7})[.)][ \t]+(.*)$""")
    private val horizontalRule = Regex("""^ {0,3}(?:(?:\*[ \t]*){3,}|(?:-[ \t]*){3,}|(?:_[ \t]*){3,})$""")
    private val trailingHeadingHashes = Regex("""[ \t]+#+[ \t]*$""")

    suspend fun render(source: String): CharSequence {
        val result = SpannableStringBuilder()
        var hasRenderedLine = false
        var codeFenceChar: Char? = null
        var codeFenceLength = 0
        var lineNumber = 0

        for (line in source.lineSequence()) {
            // Large documents must not keep computing after the user exits preview.
            if (lineNumber++ % 64 == 0) currentCoroutineContext().ensureActive()
            val fenceMatch = fence.matchEntire(line)
            if (codeFenceChar != null) {
                if (fenceMatch != null && fenceMatch.groupValues[1].first() == codeFenceChar &&
                    fenceMatch.groupValues[1].length >= codeFenceLength &&
                    fenceMatch.groupValues[2].isBlank()
                ) {
                    codeFenceChar = null
                    continue
                }
                if (hasRenderedLine) result.append('\n')
                hasRenderedLine = true
                val start = result.length
                result.append(line)
                style(result, TypefaceSpan("monospace"), start)
                continue
            }
            if (fenceMatch != null) {
                codeFenceChar = fenceMatch.groupValues[1][0]
                codeFenceLength = fenceMatch.groupValues[1].length
                continue
            }

            if (hasRenderedLine) result.append('\n')
            hasRenderedLine = true
            when {
                horizontalRule.matches(line) -> result.append("────────────────────")
                heading.matches(line) -> {
                    val match = heading.matchEntire(line)!!
                    val level = match.groupValues[1].length
                    val start = result.length
                    appendInline(match.groupValues[2].replace(trailingHeadingHashes, ""), result)
                    style(result, StyleSpan(Typeface.BOLD), start)
                    style(result, RelativeSizeSpan(when (level) {
                        1 -> 1.55f
                        2 -> 1.35f
                        3 -> 1.20f
                        else -> 1.08f
                    }), start)
                }
                quote.matches(line) -> {
                    result.append("│ ")
                    appendInline(quote.matchEntire(line)!!.groupValues[1], result)
                }
                unorderedList.matches(line) -> {
                    val match = unorderedList.matchEntire(line)!!
                    result.append("  ".repeat(match.groupValues[1].length / 2))
                    result.append("•  ")
                    appendInline(match.groupValues[2], result)
                }
                orderedList.matches(line) -> {
                    val match = orderedList.matchEntire(line)!!
                    result.append("  ".repeat(match.groupValues[1].length / 2))
                    result.append("${match.groupValues[2]}.  ")
                    appendInline(match.groupValues[3], result)
                }
                else -> appendInline(line, result)
            }
        }
        return result
    }

    private fun appendInline(text: String, out: SpannableStringBuilder, depth: Int = 0) {
        // Bound recursion for malformed/malicious Markdown.
        if (depth >= 6) {
            out.append(text)
            return
        }
        var i = 0
        while (i < text.length) {
            val character = text[i]
            if (character == '\\' && i + 1 < text.length &&
                text[i + 1] in "\\`*_[]()!"
            ) {
                out.append(text[i + 1])
                i += 2
                continue
            }
            if (character == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    val start = out.length
                    out.append(text, i + 1, end)
                    style(out, TypefaceSpan("monospace"), start)
                    i = end + 1
                    continue
                }
            }
            // Markdown links are clickable if their protocol is explicitly supported.
            // Image syntax is intentionally NOT interpreted or downloaded.
            if (character == '[' && (i == 0 || text[i - 1] != '!')) {
                val closeLabel = text.indexOf(']', i + 1)
                if (closeLabel > i + 1 && text.getOrNull(closeLabel + 1) == '(') {
                    val closeUrl = text.indexOf(')', closeLabel + 2)
                    if (closeUrl > closeLabel + 2) {
                        val url = text.substring(closeLabel + 2, closeUrl)
                        val start = out.length
                        appendInline(text.substring(i + 1, closeLabel), out, depth + 1)
                        if (url.startsWith("https://", ignoreCase = true) ||
                            url.startsWith("http://", ignoreCase = true) ||
                            url.startsWith("mailto:", ignoreCase = true)
                        ) {
                            style(out, URLSpan(url), start)
                        } else {
                            style(out, UnderlineSpan(), start)
                        }
                        i = closeUrl + 1
                        continue
                    }
                }
            }
            if (character == '*' || character == '_') {
                val marker = if (text.startsWith("$character$character", i)) {
                    "$character$character"
                } else character.toString()
                // Don't treat underscores inside words as formatting delimiters.
                if (character != '_' || i == 0 || !text[i - 1].isLetterOrDigit()) {
                    val end = text.indexOf(marker, i + marker.length)
                    if (end > i + marker.length) {
                        val start = out.length
                        appendInline(text.substring(i + marker.length, end), out, depth + 1)
                        style(out, StyleSpan(if (marker.length == 2) Typeface.BOLD else Typeface.ITALIC), start)
                        i = end + marker.length
                        continue
                    }
                }
            }
            out.append(character)
            i++
        }
    }

    private fun style(text: SpannableStringBuilder, span: Any, start: Int) {
        if (text.length > start) {
            text.setSpan(span, start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
