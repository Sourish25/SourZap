package com.sourzap.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Lightweight, zero-dependency Markdown parser and renderer for Jetpack Compose.
 * Handles headings (#, ##, ###), bullet lists (-, *), bold (**text**), italics (*text*),
 * and inline code (`code`) with proper Material 3 typography and contrast.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    maxLines: Int = Int.MAX_VALUE
) {
    val lines = markdown.trim().lines()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        var displayedLines = 0
        for (line in lines) {
            if (displayedLines >= maxLines) break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                continue
            }

            when {
                trimmed.startsWith("### ") -> {
                    Text(
                        text = buildStyledMarkdownText(trimmed.removePrefix("### "), textColor),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 1.dp)
                    )
                    displayedLines++
                }
                trimmed.startsWith("## ") -> {
                    Text(
                        text = buildStyledMarkdownText(trimmed.removePrefix("## "), textColor),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                    )
                    displayedLines++
                }
                trimmed.startsWith("# ") -> {
                    Text(
                        text = buildStyledMarkdownText(trimmed.removePrefix("# "), textColor),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                    )
                    displayedLines++
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    val content = trimmed.substring(2).trim()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, top = 1.dp, bottom = 1.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 7.dp, end = 8.dp)
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Text(
                            text = buildStyledMarkdownText(content, textColor),
                            fontSize = 12.5.sp,
                            lineHeight = 17.sp,
                            color = textColor
                        )
                    }
                    displayedLines++
                }
                else -> {
                    Text(
                        text = buildStyledMarkdownText(trimmed, textColor),
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                        color = textColor
                    )
                    displayedLines++
                }
            }
        }
    }
}

/**
 * Builds an AnnotatedString parsing inline markdown for **bold**, *italic*, and `code`.
 */
@Composable
fun buildStyledMarkdownText(text: String, defaultColor: Color): AnnotatedString {
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val codeColor = MaterialTheme.colorScheme.primary

    return buildAnnotatedString {
        var currentIndex = 0
        val regex = Regex("""(\*\*([^*]+)\*\*)|(\*([^*]+)\*)|(`([^`]+)`)""")
        val matches = regex.findAll(text)

        for (match in matches) {
            val start = match.range.first
            val end = match.range.last + 1

            if (start > currentIndex) {
                append(text.substring(currentIndex, start))
            }

            when {
                // **bold**
                match.groupValues[1].isNotEmpty() -> {
                    val boldContent = match.groupValues[2]
                    val startSpan = length
                    append(boldContent)
                    addStyle(SpanStyle(fontWeight = FontWeight.Bold), startSpan, length)
                }
                // *italic*
                match.groupValues[3].isNotEmpty() -> {
                    val italicContent = match.groupValues[4]
                    val startSpan = length
                    append(italicContent)
                    addStyle(SpanStyle(fontStyle = FontStyle.Italic), startSpan, length)
                }
                // `code`
                match.groupValues[5].isNotEmpty() -> {
                    val codeContent = match.groupValues[6]
                    val startSpan = length
                    append(codeContent)
                    addStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = codeColor,
                            background = codeBg
                        ),
                        startSpan,
                        length
                    )
                }
            }
            currentIndex = end
        }

        if (currentIndex < text.length) {
            append(text.substring(currentIndex))
        }
    }
}
