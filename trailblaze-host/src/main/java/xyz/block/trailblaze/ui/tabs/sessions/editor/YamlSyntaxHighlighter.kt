package xyz.block.trailblaze.ui.tabs.sessions.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * YAML syntax highlighter that produces an AnnotatedString with colored spans
 * for different YAML token types.
 */
class YamlSyntaxHighlighter(
  private val colors: YamlSyntaxColors = YamlSyntaxColors.Default,
) {

  /**
   * Highlights the given YAML text and returns an AnnotatedString with
   * appropriate color styles applied.
   */
  fun highlight(text: String): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString("")

    return buildAnnotatedString {
      val lines = text.split("\n")
      lines.forEachIndexed { lineIndex, line ->
        if (lineIndex > 0) append("\n")
        highlightLine(line)
      }
    }
  }

  private fun AnnotatedString.Builder.highlightLine(line: String) {
    if (line.isEmpty()) return

    // Check for comment (entire line or trailing)
    val commentIndex = findCommentStart(line)
    val contentPart = if (commentIndex >= 0) line.substring(0, commentIndex) else line
    val commentPart = if (commentIndex >= 0) line.substring(commentIndex) else ""

    // Process the content part
    highlightContent(contentPart)

    // Append comment with comment style
    if (commentPart.isNotEmpty()) {
      withStyle(SpanStyle(color = colors.comment)) {
        append(commentPart)
      }
    }
  }

  private fun AnnotatedString.Builder.highlightContent(content: String) {
    if (content.isEmpty()) return

    var index = 0
    val length = content.length

    // Handle leading whitespace
    while (index < length && content[index].isWhitespace()) {
      append(content[index])
      index++
    }

    if (index >= length) return

    // Check for list marker "- "
    if (content.substring(index).startsWith("- ")) {
      withStyle(SpanStyle(color = colors.listMarker, fontWeight = FontWeight.Bold)) {
        append("- ")
      }
      index += 2
      // Continue highlighting the rest after the list marker
      highlightContent(content.substring(index))
      return
    }

    // Check for key: value pattern
    val colonIndex = content.indexOf(':', index)
    if (colonIndex > index) {
      val potentialKey = content.substring(index, colonIndex)
      // Validate it looks like a key (no spaces, or is a simple key)
      if (isValidKey(potentialKey)) {
        // Highlight the key
        val keyStyle = if (isTrailblazeKeyword(potentialKey.trim())) {
          SpanStyle(color = colors.trailblazeKeyword, fontWeight = FontWeight.Bold)
        } else {
          SpanStyle(color = colors.key)
        }
        withStyle(keyStyle) {
          append(potentialKey)
        }
        // Append the colon
        withStyle(SpanStyle(color = colors.key)) {
          append(":")
        }
        index = colonIndex + 1

        // Highlight the value part
        if (index < length) {
          highlightValue(content.substring(index))
        }
        return
      }
    }

    // If no key-value pattern, treat as a value
    highlightValue(content.substring(index))
  }

  private fun AnnotatedString.Builder.highlightValue(value: String) {
    if (value.isEmpty()) return

    var index = 0
    val length = value.length

    // Skip leading whitespace
    while (index < length && value[index].isWhitespace()) {
      append(value[index])
      index++
    }

    if (index >= length) return

    val trimmedValue = value.substring(index).trim()

    // Check for quoted strings
    if (value[index] == '"' || value[index] == '\'') {
      val quote = value[index]
      val endQuote = value.indexOf(quote, index + 1)
      if (endQuote > index) {
        withStyle(SpanStyle(color = colors.string)) {
          append(value.substring(index, endQuote + 1))
        }
        // Append anything after the quoted string
        if (endQuote + 1 < length) {
          append(value.substring(endQuote + 1))
        }
        return
      }
    }

    // Check for boolean values
    if (trimmedValue.lowercase() in listOf("true", "false", "yes", "no", "on", "off")) {
      withStyle(SpanStyle(color = colors.boolean)) {
        append(value.substring(index))
      }
      return
    }

    // Check for null values
    if (trimmedValue.lowercase() == "null" || trimmedValue == "~") {
      withStyle(SpanStyle(color = colors.nullValue)) {
        append(value.substring(index))
      }
      return
    }

    // Check for numbers
    if (trimmedValue.matches(NUMBER_REGEX)) {
      withStyle(SpanStyle(color = colors.number)) {
        append(value.substring(index))
      }
      return
    }

    // Check for multiline string indicators
    if (trimmedValue == "|" || trimmedValue == ">" || trimmedValue == "|-" || trimmedValue == ">-") {
      withStyle(SpanStyle(color = colors.string)) {
        append(value.substring(index))
      }
      return
    }

    // Default: regular text (unquoted string value)
    append(value.substring(index))
  }

  private fun findCommentStart(line: String): Int {
    var inSingleQuote = false
    var inDoubleQuote = false

    for (i in line.indices) {
      val char = line[i]
      when {
        char == '\'' && !inDoubleQuote -> inSingleQuote = !inSingleQuote
        char == '"' && !inSingleQuote -> inDoubleQuote = !inDoubleQuote
        char == '#' && !inSingleQuote && !inDoubleQuote -> return i
      }
    }
    return -1
  }

  private fun isValidKey(key: String): Boolean {
    val trimmed = key.trim()
    if (trimmed.isEmpty()) return false
    // Keys typically don't have quotes or special characters at the start
    // Allow alphanumeric, underscore, hyphen, and dots
    return trimmed.all { it.isLetterOrDigit() || it in "_-." }
  }

  private fun isTrailblazeKeyword(key: String): Boolean {
    return key in TRAILBLAZE_KEYWORDS
  }

  companion object {
    private val NUMBER_REGEX = Regex("^-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?$")

    private val TRAILBLAZE_KEYWORDS = setOf(
      "config",
      "prompts",
      "tools",
      "maestro",
      "step",
      "verify",
      "recording",
      "id",
      "title",
      "priority",
      "metadata",
      "selector",
      "reason",
    )
  }
}

/**
 * Color scheme for YAML syntax highlighting.
 */
data class YamlSyntaxColors(
  val key: Color,
  val string: Color,
  val number: Color,
  val boolean: Color,
  val nullValue: Color,
  val comment: Color,
  val listMarker: Color,
  val trailblazeKeyword: Color,
) {
  companion object {
    /**
     * Default dark theme colors inspired by IntelliJ IDEA.
     */
    val Default = YamlSyntaxColors(
      key = Color(0xFF9876AA),           // Purple
      string = Color(0xFF6A8759),         // Green
      number = Color(0xFF6897BB),         // Blue
      boolean = Color(0xFFCC7832),        // Orange
      nullValue = Color(0xFFCC7832),      // Orange
      comment = Color(0xFF808080),        // Gray
      listMarker = Color(0xFFCC7832),     // Orange
      trailblazeKeyword = Color(0xFF9876AA), // Purple (same as key, but bold)
    )

    /**
     * Light theme colors.
     */
    val Light = YamlSyntaxColors(
      key = Color(0xFF660099),           // Dark purple
      string = Color(0xFF008000),         // Dark green
      number = Color(0xFF0000FF),         // Blue
      boolean = Color(0xFFB85C00),        // Dark orange
      nullValue = Color(0xFFB85C00),      // Dark orange
      comment = Color(0xFF808080),        // Gray
      listMarker = Color(0xFFB85C00),     // Dark orange
      trailblazeKeyword = Color(0xFF660099), // Dark purple
    )
  }
}
