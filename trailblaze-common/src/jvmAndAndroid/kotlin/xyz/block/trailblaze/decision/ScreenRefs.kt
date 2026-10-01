package xyz.block.trailblaze.decision

/**
 * The element lines of the ref-annotated screen text the agent is shown: `[k42] Button "Items"`.
 * Refs are [xyz.block.trailblaze.api.ElementRef]'s: a letter and 1–3 digits, plus a letter suffix
 * when two elements hash alike (`k42b`), so a narrower pattern silently drops those elements.
 */
object ScreenRefs {
  /** One element line: its [ref] and the rest of the line, trimmed. */
  data class Line(val ref: String, val label: String)

  private val REF_LINE = Regex("""^\s*\[([a-z]\d{1,3}[a-z]?)]\s*(.*)$""")

  /** Every element line of [screen] in order, the first line per ref. */
  fun parse(screen: String): List<Line> =
    screen.lineSequence().mapNotNull { REF_LINE.find(it) }
      .map { Line(it.groupValues[1], it.groupValues[2].trim()) }
      .distinctBy { it.ref }.toList()
}
