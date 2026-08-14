package com.woodward.tailcodex.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

data class SyntaxPalette(
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val plain: Color,
)

object SyntaxHighlighter {
    private val commonKeywords = setOf(
        "as", "async", "await", "break", "case", "catch", "class", "const", "continue",
        "data", "def", "do", "else", "enum", "export", "false", "finally", "for", "from",
        "fun", "function", "if", "import", "in", "interface", "is", "let", "null", "object",
        "override", "package", "private", "protected", "public", "return", "sealed", "static",
        "super", "switch", "this", "throw", "true", "try", "type", "typeof", "val", "var",
        "void", "when", "while", "with", "yield",
    )
    private val token = Regex(
        "//[^\\n]*|#[^\\n]*|/\\*[\\s\\S]*?\\*/|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|\\b\\d+(?:\\.\\d+)?\\b|\\b[A-Za-z_][A-Za-z0-9_]*\\b",
    )

    fun highlight(source: String, language: String?, palette: SyntaxPalette): AnnotatedString = buildAnnotatedString {
        var cursor = 0
        token.findAll(source).forEach { match ->
            append(source.substring(cursor, match.range.first))
            val value = match.value
            val style = when {
                value.startsWith("//") || value.startsWith("#") || value.startsWith("/*") ->
                    SpanStyle(color = palette.comment)
                value.startsWith('"') || value.startsWith('\'') -> SpanStyle(color = palette.string)
                value.firstOrNull()?.isDigit() == true -> SpanStyle(color = palette.number)
                value in keywords(language) -> SpanStyle(color = palette.keyword, fontWeight = FontWeight.SemiBold)
                else -> SpanStyle(color = palette.plain)
            }
            withStyle(style) { append(value) }
            cursor = match.range.last + 1
        }
        append(source.substring(cursor))
    }

    private fun keywords(language: String?): Set<String> = when (language?.lowercase()) {
        "json" -> setOf("true", "false", "null")
        "sh", "shell", "bash", "zsh" -> commonKeywords + setOf("then", "fi", "done", "esac")
        else -> commonKeywords
    }
}
