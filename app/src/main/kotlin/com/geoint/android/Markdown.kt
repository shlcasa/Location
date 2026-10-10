package com.geoint.android

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

/** Just enough Markdown for the report: headings, bold, inline code, bullets. Tables stay as text. */
object Markdown {
    private val INLINE = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")

    fun render(md: String): AnnotatedString = buildAnnotatedString {
        md.lines().forEachIndexed { i, raw ->
            if (i > 0) append('\n')
            val line = raw.trimEnd()
            when {
                line.startsWith("#") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp)) {
                    append(line.trimStart('#').trim())
                }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    append("  • ")
                    inline(line.trimStart().substring(2))
                }
                else -> inline(line)
            }
        }
    }

    private fun AnnotatedString.Builder.inline(text: String) {
        var last = 0
        for (m in INLINE.findAll(text)) {
            append(text.substring(last, m.range.first))
            val bold = m.groups[1]?.value
            if (bold != null) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            else withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(m.groups[2]!!.value) }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
}
