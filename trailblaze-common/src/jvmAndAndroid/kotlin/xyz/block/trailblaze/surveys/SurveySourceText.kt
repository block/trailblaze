package xyz.block.trailblaze.surveys

/**
 * A survey file's source with what is not code blanked out, so a text scan of it sees only code:
 * a commented-out export, a `.final` in a string, or a `{` in a regex never counts. Every character
 * keeps its position (blanked ones become spaces, newlines stay), so a match in the result indexes
 * the original text.
 *
 * Not a parser. A `/` is read as a regex literal when what precedes it cannot end an expression,
 * which is right for code a person writes and enough for the checks that use this.
 */
object SurveySourceText {

  /** [text] with comments, and string, template and regex literal bodies, blanked. */
  fun codeOnly(text: String): String = mask(text, keepStrings = false)

  /** [text] with comments blanked; string and template literals kept, so their values can be read. */
  fun withoutComments(text: String): String = mask(text, keepStrings = true)

  /**
   * Where the arguments of the call starting at [from] begin (just past its `(`), past any type
   * arguments, as `survey<{ outcomes: ("ok" | "lost")[] }>(`; null when [from] starts no call. The
   * type arguments end at the `>` that closes the first `<` outside any bracket, where an arrow's
   * `=>` closes nothing. [code] is from [codeOnly], so a `<` or `)` in a literal is not seen.
   */
  fun callArgumentsAt(code: String, from: Int): Int? {
    var i = from
    fun skipSpace() { while (i < code.length && code[i].isWhitespace()) i++ }
    skipSpace()
    if (i < code.length && code[i] == '<') {
      var angles = 0
      var brackets = 0
      while (i < code.length) {
        when (code[i]) {
          '(', '[', '{' -> brackets++
          ')', ']', '}' -> brackets--
          '<' -> if (brackets == 0) angles++
          '>' -> if (brackets == 0 && code[i - 1] != '=' && --angles == 0) break
        }
        i++
      }
      if (i >= code.length) return null
      i++
      skipSpace()
    }
    return if (i < code.length && code[i] == '(') i + 1 else null
  }

  /**
   * Where the arguments of the first `survey(...)` call at or after [from] begin, past any type
   * arguments; null when there is none.
   */
  fun surveyCallArguments(code: String, from: Int): Int? =
    SURVEY_CALL.findAll(code, from).firstNotNullOfOrNull { callArgumentsAt(code, it.range.last + 1) }

  private val SURVEY_CALL = Regex("""(?<![\w$])survey\b""")

  /**
   * Where the `{...}` literal starting at the first non-blank character from [from] in [code] (from
   * [codeOnly]) lies, or null when what is there is not an object literal.
   */
  fun objectLiteralAt(code: String, from: Int): IntRange? {
    var i = from
    while (i < code.length && code[i].isWhitespace()) i++
    if (i >= code.length || code[i] != '{') return null
    val start = i
    var depth = 0
    while (i < code.length) {
      when (code[i]) {
        '{' -> depth++
        '}' -> if (--depth == 0) return start..i
      }
      i++
    }
    return null
  }

  /**
   * The inside of the `{...}` literal at [literal] in [code] (from [codeOnly]) with everything
   * nested deeper blanked, read from [text] (the same source, masked any way that keeps positions).
   * A key found in the result is one of the literal's own keys, not a key of a value inside it.
   */
  fun topLevelOf(code: String, literal: IntRange, text: String = code): String {
    val out = StringBuilder()
    var depth = 0
    for (k in literal.first + 1 until literal.last) {
      val c = code[k]
      if (c == '{' || c == '[' || c == '(') depth++
      out.append(if (depth == 0 || text[k] == '\n') text[k] else ' ')
      if ((c == '}' || c == ']' || c == ')') && depth > 0) depth--
    }
    return out.toString()
  }

  private fun mask(text: String, keepStrings: Boolean): String {
    val out = StringBuilder(text)
    scan(text, out, 0, keepStrings, interpolation = false)
    return out.toString()
  }

  private fun blank(text: String, out: StringBuilder, from: Int, until: Int) {
    for (k in from until minOf(until, text.length)) if (text[k] != '\n') out.setCharAt(k, ' ')
  }

  /**
   * Masks [text] from [from]. Inside a template's `${...}` ([interpolation]), stops at the `}` that
   * closes it and returns its index; otherwise returns the text's length.
   */
  private fun scan(text: String, out: StringBuilder, from: Int, keepStrings: Boolean, interpolation: Boolean): Int {
    var i = from
    var depth = 0
    while (i < text.length) {
      val c = text[i]
      when {
        c == '/' && text.getOrNull(i + 1) == '/' -> {
          val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
          blank(text, out, i, end)
          i = end
        }
        c == '/' && text.getOrNull(i + 1) == '*' -> {
          val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
          blank(text, out, i, end)
          i = end
        }
        c == '`' -> i = template(text, out, i + 1, keepStrings)
        c == '"' || c == '\'' -> {
          val end = closing(text, i + 1, c)
          if (!keepStrings) blank(text, out, i + 1, end - 1)
          i = end
        }
        // Read back over [out], where comments before here are already blank: `/* a */ /x/` is a regex.
        c == '/' && regexMayStart(out, i) -> {
          val end = regexEnd(text, i + 1)
          blank(text, out, i + 1, end - 1)
          i = end
        }
        interpolation && c == '{' -> { depth++; i++ }
        interpolation && c == '}' -> if (depth == 0) return i else { depth--; i++ }
        else -> i++
      }
    }
    return text.length
  }

  /**
   * Masks a template literal whose body starts at [from], keeping each `${...}` as code: a read
   * inside one runs. Returns the index just past the closing backtick.
   */
  private fun template(text: String, out: StringBuilder, from: Int, keepStrings: Boolean): Int {
    var i = from
    while (i < text.length) {
      when {
        text[i] == '\\' -> { if (!keepStrings) blank(text, out, i, i + 2); i += 2 }
        text[i] == '`' -> return i + 1
        text[i] == '$' && text.getOrNull(i + 1) == '{' -> i = scan(text, out, i + 2, keepStrings, interpolation = true) + 1
        else -> { if (!keepStrings) blank(text, out, i, i + 1); i++ }
      }
    }
    return text.length
  }

  /** Index just past the [quote] that closes a literal whose body starts at [from]. */
  private fun closing(text: String, from: Int, quote: Char): Int {
    var i = from
    while (i < text.length) {
      when (text[i]) {
        '\\' -> i += 2
        quote -> return i + 1
        '\n' -> if (quote != '`') return i else i++
        else -> i++
      }
    }
    return text.length
  }

  /** Index just past the `/` that closes a regex literal whose body starts at [from]. */
  private fun regexEnd(text: String, from: Int): Int {
    var i = from
    var inClass = false
    while (i < text.length) {
      when (text[i]) {
        '\\' -> i += 2
        '[' -> { inClass = true; i++ }
        ']' -> { inClass = false; i++ }
        '/' -> if (inClass) i++ else return i + 1
        '\n' -> return i
        else -> i++
      }
    }
    return text.length
  }

  private fun regexMayStart(text: CharSequence, slash: Int): Boolean {
    var j = slash - 1
    while (j >= 0 && text[j].isWhitespace()) j--
    if (j < 0) return true
    val prev = text[j]
    if (prev in "(,=:[!&|?{};+-*%<>~^") return true
    if (!prev.isLetter()) return false
    var k = j
    while (k >= 0 && (text[k].isLetterOrDigit() || text[k] == '_' || text[k] == '$')) k--
    return text.subSequence(k + 1, j + 1).toString() in KEYWORDS_BEFORE_EXPRESSION
  }

  private val KEYWORDS_BEFORE_EXPRESSION =
    setOf("return", "typeof", "case", "do", "else", "in", "of", "new", "delete", "void", "throw", "yield", "await")
}
