package xyz.block.trailblaze.host

import java.io.File
import xyz.block.trailblaze.scripting.WorkspaceSurveyBundleSource
import xyz.block.trailblaze.surveys.SurveyBundles
import xyz.block.trailblaze.surveys.SurveySourceText

/**
 * `trailblaze check` gate on surveys that run mid-trail. A survey runs mid-trail unless it sets
 * `requiresFinishedSession: true`, and there it runs like a scripted tool: in the sandbox, over the
 * session's event streams only. Fatal, each naming its fix:
 *
 *  - A mid-trail survey that cannot run there: its file does not build for the sandbox (it imports
 *    `node:*` or Bun APIs), or it reads something that exists only once the session has finished
 *    (the outcome, tool calls, logs, screen text, session files).
 *  - Two files exporting the same survey name, since `ctx.surveys` names a survey by its export.
 *  - A survey whose spec still says `footprint`, the old name of `finding`, which the SDK no longer
 *    reads.
 *
 * The reads are found in source text, so they need no toolchain. The build step does; without one
 * it is skipped and says so.
 */
object SurveyLint {

  const val DISABLE_ENV_VAR: String = "TRAILBLAZE_DISABLE_SURVEY_GATE"

  data class Finding(val path: String, val message: String)

  /** Survey names exported by more than one file: `ctx.surveys.<name>` could mean either. */
  private fun duplicates(configDir: File): List<Finding> =
    WorkspaceSurveyBundleSource.surveyFiles(configDir).flatMap { file ->
      val relative = file.relativeTo(configDir).invariantSeparatorsPath
      SurveyBundles.exportedSurveys(file.readText()).map { it.name to relative }
    }.groupBy({ it.first }, { it.second }).filterValues { it.distinct().size > 1 }.map { (name, files) ->
      Finding(
        files.first(),
        "Survey $name is exported by ${files.distinct().joinToString(" and ")}. Surveys are named by their export, so " +
          "rename one.",
      )
    }

  /**
   * Both checks over every survey file under [configDir]. [bundle] builds a file for the sandbox and
   * throws when it cannot; null skips the build half (no toolchain).
   */
  fun lintSurveyFiles(configDir: File, bundle: ((File) -> Unit)?): List<Finding> =
    duplicates(configDir) + WorkspaceSurveyBundleSource.surveyFiles(configDir).flatMap { file ->
      val relative = file.relativeTo(configDir).invariantSeparatorsPath
      val text = file.readText()
      val code = SurveySourceText.codeOnly(text)
      val surveys = SurveyBundles.exportedSurveys(text)
      val withStrings = SurveySourceText.withoutComments(text)
      val renamed = surveys.filter { setsOldFindingKey(code, withStrings, it.name) }
        .map { Finding(relative, "Survey ${it.name} sets footprint, the old name of finding. Rename the key to finding.") }
      val midTrail = surveys.filterNot { it.requiresFinishedSession }
      if (midTrail.isEmpty()) return@flatMap renamed
      val findings = renamed.toMutableList()
      findings += midTrail.mapNotNull { survey ->
        val read = endOnlyRead(segmentOf(code, survey.name)) ?: return@mapNotNull null
        Finding(
          relative,
          "Survey ${survey.name} reads $read, which exists only once the session has finished, but it " +
            "runs mid-trail. Add requiresFinishedSession: true to run it after the session, or limit it " +
            "to network, analytics and events.",
        )
      }
      if (bundle != null) {
        try {
          bundle(file)
        } catch (e: Exception) {
          val split = if (midTrail.size < surveys.size) {
            " If only its finished-session surveys need them, move those to their own file."
          } else {
            ""
          }
          findings += Finding(
            relative,
            "${midTrail.joinToString { it.name }} run${if (midTrail.size == 1) "s" else ""} mid-trail, " +
              "where a survey runs like a scripted tool, but this file does not build for that sandbox " +
              "(it imports node:* or Bun APIs?). Add requiresFinishedSession: true to run it after the " +
              "session, or move host work into a requiresHost tool.$split " +
              firstLines(e.message.orEmpty()),
          )
        }
      }
      findings
    }

  fun render(findings: List<Finding>): String = buildString {
    appendLine("── survey gate (FATAL) ─────────────────────────────────────────")
    appendLine("${findings.size} survey problem(s):")
    findings.sortedBy { it.path }.forEach { appendLine("  ${it.path}: ${it.message}") }
    append("Disable with $DISABLE_ENV_VAR=1.")
  }

  /**
   * Survey [name]'s own declaration: its export through the end of the `survey(...)` call that holds
   * its spec and handler, so a helper declared after it is not read as its own. [code] has its
   * comments and literals blanked, so neither a commented-out export nor a `.final` or `.tools.`
   * inside a string counts.
   */
  private fun segmentOf(code: String, name: String): String = segmentRange(code, name)?.let { code.substring(it) } ?: ""

  /** Where [segmentOf] lies in [code]; null when [name] is not exported there. */
  private fun segmentRange(code: String, name: String): IntRange? {
    val start = Regex("""export\s+const\s+${Regex.escape(name)}\b""").find(code)?.range?.first ?: return null
    val next = Regex("""\bexport\s""").find(code, start + 1)?.range?.first ?: code.length
    // Past the call's type arguments, which may hold parentheses of their own (`survey<{ a: (x | y)[] }>(`).
    val call = SurveySourceText.surveyCallArguments(code, start)?.minus(1)?.takeIf { it in start until next }
      ?: code.indexOf('(', start).takeIf { it in start until next }
      ?: return start until next
    return start until minOf(closing(code, call) + 1, next)
  }

  /**
   * Whether survey [name]'s spec sets `footprint`: as a key, a quoted key, or shorthand
   * (`{ footprint }`). A quoted key is read from [withStrings] (the source with only comments
   * blanked), since [code] blanks string literals; it counts only where [code] still has its quotes,
   * so `"footprint":` inside a string value is not a key.
   */
  private fun setsOldFindingKey(code: String, withStrings: String, name: String): Boolean {
    val range = segmentRange(code, name) ?: return false
    val segment = code.substring(range)
    val spec = specOf(segment) ?: return false
    val keys = SurveySourceText.topLevelOf(segment, spec)
    if (OLD_FINDING.containsMatchIn(keys)) return true
    return QUOTED_OLD_FINDING.findAll(SurveySourceText.topLevelOf(segment, spec, withStrings.substring(range))).any { m ->
      val open = m.groups[1]!!.range.first
      keys[open] == m.groupValues[1][0] && keys[open + "footprint".length + 1] == m.groupValues[1][0]
    }
  }

  /**
   * The first thing [segment] reads that a mid-trail session lacks, named the way the runtime's
   * error does. Finding keys count: a finding over `tools` reads the tool calls. A read counts
   * only on the handler's session parameter, so `e.properties.tools` in a predicate is event data.
   * A survey that tests `session.final` is trusted to read those only once it is true.
   */
  private fun endOnlyRead(segment: String): String? {
    sessionParameter(segment)?.let { session ->
      val on = Regex.escape(session)
      if (Regex("""(?<![\w$.])$on\s*\.\s*final\b""").containsMatchIn(segment)) return null
      Regex("""(?<![\w$.])$on\s*\.\s*summary\s*\.\s*($END_ONLY_SUMMARY)\b""").find(segment)
        ?.let { return "session.summary.${it.groupValues[1]}" }
      Regex("""(?<![\w$.])$on\s*\.\s*($END_ONLY_RECORDS)\b""").find(segment)?.let { return "session.${it.groupValues[1]}" }
      Regex("""(?<![\w$.])$on\s*\.\s*($END_ONLY_FILES)\s*\(""").find(segment)?.let { return "session.${it.groupValues[1]}()" }
    }
    findingKeys(segment).firstNotNullOfOrNull { END_ONLY_FINDING.find(it) }
      ?.let { return "session.${it.groupValues[1]} (a finding query)" }
    return null
  }

  /**
   * The name the survey's find handler gives its session, whether written after the spec in
   * `survey(spec, handler)` or as the spec's `find`: `(s) =>`, `s =>`, `async (s) =>`,
   * `function (s)`, or the method `find(s) {`. Null when there is no inline handler, or it
   * destructures its argument.
   */
  private fun sessionParameter(segment: String): String? {
    val spec = specOf(segment) ?: return null
    HANDLER_PARAMETER.find(segment.substring(spec.last + 1))?.let { return it.groupValues[1] }
    // The spec's own keys, read with its nesting blanked: brackets too, so what follows `find` is
    // read from the source.
    return FIND_KEY.findAll(SurveySourceText.topLevelOf(segment, spec)).firstNotNullOfOrNull { key ->
      val after = segment.substring(spec.first + 1 + key.range.last + 1)
      val start = FIND_HANDLER_START.find(after) ?: return@firstNotNullOfOrNull null
      FIND_PARAMETER.find(after.substring(start.range.last + 1))?.groupValues?.get(1)
    }
  }

  /** The `survey(...)` spec object in [segment]: its range, or null when there is no object literal. */
  private fun specOf(segment: String): IntRange? {
    val args = SurveySourceText.surveyCallArguments(segment, 0) ?: (segment.indexOf('(').takeIf { it >= 0 } ?: return null) + 1
    return SurveySourceText.objectLiteralAt(segment, args)
  }

  /**
   * The keys of the declarative finding in [segment], and of its `absent`, one string per object
   * with deeper nesting blanked. Only the spec's own keys count: a `finding` inside its `data`, or a
   * key in a query's own data (`properties: { tools: ... }`), is not a finding key.
   */
  private fun findingKeys(segment: String): List<String> {
    val spec = specOf(segment) ?: return emptyList()
    val own = SurveySourceText.topLevelOf(segment, spec)
    // Where the value of a key matched in [own] starts in [segment].
    fun valueAt(m: MatchResult) = spec.first + 1 + m.range.last + 1
    val specs = FINDING.findAll(own).mapNotNull { m -> SurveySourceText.objectLiteralAt(segment, valueAt(m)) }.toList()
    return specs.flatMap { range ->
      val keys = SurveySourceText.topLevelOf(segment, range)
      val absent = ABSENT.find(keys)
        ?.let { SurveySourceText.objectLiteralAt(segment, range.first + 1 + it.range.last + 1) }
        ?.let { SurveySourceText.topLevelOf(segment, it) }
      listOfNotNull(keys, absent)
    }
  }

  /** Index of the bracket that closes the one at [open]. */
  private fun closing(code: String, open: Int): Int {
    var depth = 0
    for (k in open until code.length) {
      when (code[k]) {
        '{', '[', '(' -> depth++
        '}', ']', ')' -> if (--depth == 0) return k
      }
    }
    return code.length
  }

  private val FINDING = Regex("""\b(?:find|finding)\s*:""")
  /** A spec key `footprint:`, or the shorthand `footprint,` / a trailing `footprint`. */
  private val OLD_FINDING = Regex("""(?:^|,)\s*footprint\s*(?:[:,]|$)""")
  private val QUOTED_OLD_FINDING = Regex("""(?:^|,)\s*(["'])footprint\1\s*:""")
  private val ABSENT = Regex("""\babsent\s*:""")
  private val HANDLER_PARAMETER =
    Regex("""^\s*,\s*(?:async\s+)?(?:function\s*[\w$]*\s*)?(?:\(\s*)?([A-Za-z_$][\w$]*)""")
  /** The spec's own `find` key; what follows it is `:` for a property, `(` for a method. */
  private val FIND_KEY = Regex("""(?<![\w$.])find\b""")
  private val FIND_HANDLER_START = Regex("""^\s*(?::|(?=\())""")
  private val FIND_PARAMETER =
    Regex("""^\s*(?:async\s+)?(?:function\s*[\w$]*\s*)?(?:\(\s*)?([A-Za-z_$][\w$]*)""")
  private const val END_ONLY_SUMMARY = "outcome|failureReason|startedAtMs|endedAtMs|durationMs|appVersion|ci|hasDeviceLog"
  private const val END_ONLY_RECORDS = "trailblazeLogs|logs|tools|objectives|screenText|deviceLog|trace"
  private const val END_ONLY_FILES = "readText|readJson|hasFile"
  private val END_ONLY_FINDING = Regex("""\b(tools|objectives|screenText|deviceLog)\s*:""")

  private fun firstLines(message: String): String = message.lines().filter { it.isNotBlank() }.take(4).joinToString(" ").take(600)
}
