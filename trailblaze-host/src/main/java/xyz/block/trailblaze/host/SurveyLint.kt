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
      val surveys = SurveyBundles.exportedSurveys(text)
      val midTrail = surveys.filterNot { it.requiresFinishedSession }
      if (midTrail.isEmpty()) return@flatMap emptyList()
      val findings = midTrail.mapNotNull { survey ->
        val read = endOnlyRead(segmentOf(SurveySourceText.codeOnly(text), survey.name)) ?: return@mapNotNull null
        Finding(
          relative,
          "Survey ${survey.name} reads $read, which exists only once the session has finished, but it " +
            "runs mid-trail. Add requiresFinishedSession: true to run it after the session, or limit it " +
            "to network, analytics and events.",
        )
      }.toMutableList()
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
  private fun segmentOf(code: String, name: String): String {
    val start = Regex("""export\s+const\s+${Regex.escape(name)}\b""").find(code)?.range?.first ?: return ""
    val next = Regex("""\bexport\s""").find(code, start + 1)?.range?.first ?: code.length
    // Past the call's type arguments, which may hold parentheses of their own (`survey<{ a: (x | y)[] }>(`).
    val call = SurveySourceText.surveyCallArguments(code, start)?.minus(1)?.takeIf { it in start until next }
      ?: code.indexOf('(', start).takeIf { it in start until next }
      ?: return code.substring(start, next)
    return code.substring(start, minOf(closing(code, call) + 1, next))
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
   * The name the handler written after the spec in `survey(spec, handler)` gives its session:
   * `(s) =>`, `s =>`, `async (s) =>`, `function (s)`. Null when there is no inline handler, or it
   * destructures its argument.
   */
  private fun sessionParameter(segment: String): String? {
    val args = SurveySourceText.surveyCallArguments(segment, 0) ?: (segment.indexOf('(').takeIf { it >= 0 } ?: return null) + 1
    val spec = SurveySourceText.objectLiteralAt(segment, args) ?: return null
    return HANDLER_PARAMETER.find(segment.substring(spec.last + 1))?.groupValues?.get(1)
  }

  /**
   * The keys of every declarative finding in [segment] (`finding`, or its old name `footprint`),
   * and of each one's `absent`, one string per object with deeper nesting blanked. Only the spec's
   * own keys count: a `finding` inside its `data`, or a key in a query's own data
   * (`properties: { tools: ... }`), is not a finding key.
   */
  private fun findingKeys(segment: String): List<String> {
    val args = SurveySourceText.surveyCallArguments(segment, 0) ?: (segment.indexOf('(').takeIf { it >= 0 } ?: return emptyList()) + 1
    val spec = SurveySourceText.objectLiteralAt(segment, args) ?: return emptyList()
    val own = SurveySourceText.topLevelOf(segment, spec)
    // Where the value of a key matched in [own] starts in [segment].
    fun valueAt(m: MatchResult) = spec.first + 1 + m.range.last + 1
    val specs = mutableListOf<IntRange>()
    FINDING.findAll(own).forEach { m ->
      SurveySourceText.objectLiteralAt(segment, valueAt(m))?.let { specs += it }
    }
    FOOTPRINTS.findAll(own).forEach { m ->
      val all = SurveySourceText.objectLiteralAt(segment, valueAt(m)) ?: return@forEach
      var i = all.first + 1
      while (i < all.last) {
        val c = segment[i]
        if (c == '{') {
          val one = SurveySourceText.objectLiteralAt(segment, i) ?: break
          specs += one
          i = one.last + 1
        } else {
          if (c == '[' || c == '(') i = closing(segment, i)
          i++
        }
      }
    }
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

  private val FINDING = Regex("""\b(?:finding|footprint)\s*:""")
  private val FOOTPRINTS = Regex("""\bfootprints\s*:""")
  private val ABSENT = Regex("""\babsent\s*:""")
  private val HANDLER_PARAMETER =
    Regex("""^\s*,\s*(?:async\s+)?(?:function\s*[\w$]*\s*)?(?:\(\s*)?([A-Za-z_$][\w$]*)""")
  private const val END_ONLY_SUMMARY = "outcome|failureReason|startedAtMs|endedAtMs|durationMs|appVersion|ci|hasDeviceLog"
  private const val END_ONLY_RECORDS = "trailblazeLogs|logs|tools|objectives|screenText|deviceLog|trace"
  private const val END_ONLY_FILES = "readText|readJson|hasFile"
  private val END_ONLY_FINDING = Regex("""\b(tools|objectives|screenText|deviceLog)\s*:""")

  private fun firstLines(message: String): String = message.lines().filter { it.isNotBlank() }.take(4).joinToString(" ").take(600)
}
