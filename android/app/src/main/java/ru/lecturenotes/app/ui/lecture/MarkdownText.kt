package ru.lecturenotes.app.ui.lecture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Минимальный рендер Markdown, который выдаёт ИИ-коррекция:
 * заголовки (#, ##, ###), списки (-, *, 1.), **жирный**, *курсив*.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val typo = MaterialTheme.typography
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (rawLine in text.lines()) {
            val line = rawLine.trimEnd()
            when {
                line.isBlank() -> Unit
                line.startsWith("### ") -> Text(inline(line.drop(4)), style = typo.titleSmall)
                line.startsWith("## ") -> Text(inline(line.drop(3)), style = typo.titleMedium)
                line.startsWith("# ") -> Text(inline(line.drop(2)), style = typo.titleLarge)
                BULLET.matches(line) -> {
                    val indent = line.takeWhile { it == ' ' }.length / 2
                    Row(Modifier.padding(start = (indent * 16).dp)) {
                        Text("•  ", style = typo.bodyLarge)
                        Text(inline(line.trimStart().drop(2)), style = typo.bodyLarge)
                    }
                }
                NUMBERED.matches(line) -> {
                    val m = NUMBERED.find(line)!!
                    Row {
                        Text("${m.groupValues[1]}.  ", style = typo.bodyLarge)
                        Text(inline(m.groupValues[2]), style = typo.bodyLarge)
                    }
                }
                else -> Text(inline(line), style = typo.bodyLarge)
            }
        }
    }
}

private val BULLET = Regex("^\\s*[-*•] .*")
private val NUMBERED = Regex("^\\s*(\\d+)[.)] (.*)")
private val INLINE = Regex("\\*\\*(.+?)\\*\\*|(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])")

private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in INLINE.findAll(s)) {
        append(s.substring(last, m.range.first))
        val bold = m.groups[1]?.value
        if (bold != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
        } else {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.groupValues[2]) }
        }
        last = m.range.last + 1
    }
    append(s.substring(last))
}
