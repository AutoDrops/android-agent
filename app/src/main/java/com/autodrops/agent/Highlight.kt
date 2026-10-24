package com.autodrops.agent

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/** Minimal regex-based syntax highlighting for the in-app editor (Kotlin/Python/JS/generic). */
object Highlight {
    private val KEYWORDS = setOf(
        "fun", "val", "var", "if", "else", "when", "for", "while", "return", "class", "object", "interface",
        "import", "package", "private", "public", "override", "suspend", "data", "null", "true", "false",
        "def", "import", "from", "print", "lambda", "elif", "const", "let", "function", "new", "this",
        "try", "catch", "finally", "throw", "in", "is", "as", "break", "continue"
    )
    private val kw = Color(0xFF80CBC4)
    private val str = Color(0xFFCE9178)
    private val com = Color(0xFF6A9955)
    private val num = Color(0xFFB5CEA8)
    private val base = Color(0xFFD4D4D4)

    private val token = Regex(
        "(?<comment>//[^\\n]*|#[^\\n]*|/\\*.*?\\*/)" +
        "|(?<string>\"\"\".*?\"\"\"|\"(\\\\.|[^\"])*\"|'(\\\\.|[^'])*')" +
        "|(?<number>\\b\\d+(\\.\\d+)?\\b)" +
        "|(?<word>[A-Za-z_][A-Za-z0-9_]*)",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )

    fun render(code: String): AnnotatedString = buildAnnotatedString {
        var last = 0
        for (m in token.findAll(code)) {
            if (m.range.first > last) withStyle(SpanStyle(color = base)) { append(code.substring(last, m.range.first)) }
            val g = m.groups
            val color = when {
                g["comment"] != null -> com
                g["string"] != null -> str
                g["number"] != null -> num
                g["word"] != null && m.value in KEYWORDS -> kw
                else -> base
            }
            withStyle(SpanStyle(color = color)) { append(m.value) }
            last = m.range.last + 1
        }
        if (last < code.length) withStyle(SpanStyle(color = base)) { append(code.substring(last)) }
    }
}
