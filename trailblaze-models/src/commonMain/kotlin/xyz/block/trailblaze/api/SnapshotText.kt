package xyz.block.trailblaze.api

/**
 * How the Android and iOS compact element lists read and print an element's text.
 *
 * A line prints [render]: the text trimmed, with every line break, carriage return, tab and
 * backslash written as its escape (`\n`, `\r`, `\t`, `\\`) and every other character as it is.
 * Those escapes read the same in a JSON string, so an agent copying a quoted label into a tool
 * argument writes the element's real text: `"Name: Jane Doe\nEmail:"` in the snapshot becomes a
 * two-line `expectedText`, and an exact text check compares the text it was shown.
 *
 * This is not full JSON-string escaping: a `"` inside a label prints as it is, as it always has,
 * so labels and the checks that compare against them are unchanged for every other character.
 *
 * Every other use of a label (deduplication, composite labels, ref hashes) reads [flatten], so
 * how text prints never changes which elements are listed or what their refs are.
 */
object SnapshotText {
  private val WHITESPACE_RUN = Regex("\\s+")

  /** The text with every run of whitespace, line breaks included, collapsed to one space, trimmed. */
  fun flatten(text: String): String = text.replace(WHITESPACE_RUN, " ").trim()

  fun render(text: String): String = buildString {
    for (c in text.trim()) {
      when (c) {
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> append(c)
      }
    }
  }
}
