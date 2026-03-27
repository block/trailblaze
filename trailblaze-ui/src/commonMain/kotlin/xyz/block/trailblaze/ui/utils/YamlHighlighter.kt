package xyz.block.trailblaze.ui.utils

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Lightweight YAML syntax highlighter for display-only contexts (session timeline, log cards).
 * Produces an [AnnotatedString] with colored spans for keys, values, comments, and list markers.
 */
object YamlHighlighter {

  fun highlight(text: String, colors: YamlHighlightColors): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString("")
    return buildAnnotatedString {
      val lines = text.split("\n")
      lines.forEachIndexed { i, line ->
        if (i > 0) append("\n")
        highlightLine(line, colors)
      }
    }
  }

  private fun AnnotatedString.Builder.highlightLine(line: String, colors: YamlHighlightColors) {
    if (line.isEmpty()) return

    val commentIdx = findCommentStart(line)
    val content = if (commentIdx >= 0) line.substring(0, commentIdx) else line
    val comment = if (commentIdx >= 0) line.substring(commentIdx) else ""

    highlightContent(content, colors)

    if (comment.isNotEmpty()) {
      withStyle(SpanStyle(color = colors.comment)) { append(comment) }
    }
  }

  private fun AnnotatedString.Builder.highlightContent(content: String, colors: YamlHighlightColors) {
    if (content.isEmpty()) return

    var idx = 0
    // Leading whitespace
    while (idx < content.length && content[idx].isWhitespace()) {
      append(content[idx])
      idx++
    }
    if (idx >= content.length) return

    // List marker "- "
    if (content.substring(idx).startsWith("- ")) {
      withStyle(SpanStyle(color = colors.listMarker, fontWeight = FontWeight.Bold)) {
        append("- ")
      }
      idx += 2
      highlightContent(content.substring(idx), colors)
      return
    }

    // Key: value
    val colonIdx = content.indexOf(':', idx)
    if (colonIdx > idx) {
      val key = content.substring(idx, colonIdx)
      if (isValidKey(key)) {
        withStyle(SpanStyle(color = colors.key, fontWeight = FontWeight.SemiBold)) { append(key) }
        withStyle(SpanStyle(color = colors.key, fontWeight = FontWeight.SemiBold)) { append(":") }
        if (colonIdx + 1 < content.length) {
          highlightValue(content.substring(colonIdx + 1), colors)
        }
        return
      }
    }

    highlightValue(content.substring(idx), colors)
  }

  private fun AnnotatedString.Builder.highlightValue(value: String, colors: YamlHighlightColors) {
    if (value.isEmpty()) return

    var idx = 0
    while (idx < value.length && value[idx].isWhitespace()) {
      append(value[idx])
      idx++
    }
    if (idx >= value.length) return

    val trimmed = value.substring(idx).trim()

    // Quoted strings
    if (value[idx] == '"' || value[idx] == '\'') {
      val quote = value[idx]
      val end = value.indexOf(quote, idx + 1)
      if (end > idx) {
        withStyle(SpanStyle(color = colors.string)) { append(value.substring(idx, end + 1)) }
        if (end + 1 < value.length) append(value.substring(end + 1))
        return
      }
    }

    // Booleans
    if (trimmed.lowercase() in BOOLEANS) {
      withStyle(SpanStyle(color = colors.keyword)) { append(value.substring(idx)) }
      return
    }

    // Null
    if (trimmed.lowercase() == "null" || trimmed == "~") {
      withStyle(SpanStyle(color = colors.keyword)) { append(value.substring(idx)) }
      return
    }

    // Numbers
    if (trimmed.matches(NUMBER_REGEX)) {
      withStyle(SpanStyle(color = colors.number)) { append(value.substring(idx)) }
      return
    }

    // Block scalar indicators
    if (trimmed == "|" || trimmed == ">" || trimmed == "|-" || trimmed == ">-") {
      withStyle(SpanStyle(color = colors.string)) { append(value.substring(idx)) }
      return
    }

    // Default: unquoted string
    append(value.substring(idx))
  }

  private fun findCommentStart(line: String): Int {
    var inSingle = false
    var inDouble = false
    for (i in line.indices) {
      when {
        line[i] == '\'' && !inDouble -> inSingle = !inSingle
        line[i] == '"' && !inSingle -> inDouble = !inDouble
        line[i] == '#' && !inSingle && !inDouble -> return i
      }
    }
    return -1
  }

  private fun isValidKey(key: String): Boolean {
    val trimmed = key.trim()
    return trimmed.isNotEmpty() && trimmed.all { it.isLetterOrDigit() || it in "_-." }
  }

  private val NUMBER_REGEX = Regex("^-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?$")
  private val BOOLEANS = setOf("true", "false", "yes", "no", "on", "off")
}

data class YamlHighlightColors(
  val key: Color,
  val string: Color,
  val number: Color,
  val keyword: Color,
  val comment: Color,
  val listMarker: Color,
) {
  companion object {
    val Dark = YamlHighlightColors(
      key = Color(0xFF4DD0E1),
      string = Color(0xFF81C784),
      number = Color(0xFF64B5F6),
      keyword = Color(0xFFFFB74D),
      comment = Color(0xFF757575),
      listMarker = Color(0xFFFFB74D),
    )

    val Light = YamlHighlightColors(
      key = Color(0xFF00838F),
      string = Color(0xFF2E7D32),
      number = Color(0xFF1565C0),
      keyword = Color(0xFFE65100),
      comment = Color(0xFF9E9E9E),
      listMarker = Color(0xFFE65100),
    )
  }
}

/** Resolves YAML highlight colors based on the current MaterialTheme. */
@Composable
fun rememberYamlHighlightColors(): YamlHighlightColors {
  val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
  return remember(isDark) { if (isDark) YamlHighlightColors.Dark else YamlHighlightColors.Light }
}

private fun Color.luminance(): Float {
  val r = red
  val g = green
  val b = blue
  return 0.2126f * r + 0.7152f * g + 0.0722f * b
}
