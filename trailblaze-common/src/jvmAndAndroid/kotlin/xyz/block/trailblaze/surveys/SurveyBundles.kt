package xyz.block.trailblaze.surveys

import xyz.block.trailblaze.logs.model.SessionId

/**
 * One survey file built for the scripted-tool sandbox: an esbuild IIFE that assigns
 * [SurveyBundles.GLOBAL_NAME], holding the file's surveys and the survey runtime. [file] names the
 * source for messages. [targets] is the workspace's targets (id → platform → app ids), so a survey
 * scoped to a target applies to every session on one of its apps, as it does after the run.
 */
class SurveyBundle(val source: String, val file: String, val targets: Map<String, Map<String, List<String>>> = emptyMap())

/**
 * Where this process gets survey bundles. The daemon builds them from the workspace's survey files
 * on first use; an on-device runner reads the ones its APK packaged.
 *
 * [load] throws [SurveyNotFoundException] when no default survey has that name, and
 * [IllegalStateException] when the file holding it cannot be built for the sandbox.
 */
fun interface SurveyBundleSource {
  fun load(name: String): SurveyBundle
}

class SurveyNotFoundException(message: String) : RuntimeException(message)

/**
 * The survey bundles `ctx.surveys` loads mid-trail.
 *
 * A survey is named by its export name, which is unique across a workspace's survey files (the
 * post-run survey runner refuses duplicates too). A survey that sets `requiresFinishedSession` is
 * still found here: the bundle refuses to run it, with a message naming the flag.
 */
object SurveyBundles {

  /** The global a survey bundle's IIFE assigns. Mirrors `BUNDLE_GLOBAL` in `session-surveys.ts`. */
  const val GLOBAL_NAME: String = "__trailblazeSurveyBundle"

  /** A survey file's suffixes. Match `SURVEY_FILE` in the TypeScript `discover.ts`. */
  val FILE_SUFFIXES: List<String> = listOf(".survey.ts", ".survey.mts", ".survey.js", ".survey.mjs")

  fun isSurveyFile(name: String): Boolean = FILE_SUFFIXES.any { name.endsWith(it) }

  /** [name] without its survey suffix: `checkout.survey.mts` is `checkout`. */
  fun surveyFileStem(name: String): String = FILE_SUFFIXES.firstOrNull { name.endsWith(it) }?.let { name.removeSuffix(it) } ?: name

  /**
   * Where a test APK packages its surveys, as asset paths under this directory: each survey file at
   * its workspace-relative path (`trailmaps/<id>/surveys/<file>`), beside it the file's bundle
   * ([PACKAGED_BUNDLE_SUFFIX]) or, when the file could not be bundled, why ([PACKAGED_ERROR_SUFFIX]).
   * Its own root, not `trails/config/`, so the copy never collides with a library's config assets.
   * SISTER-IMPL-TAG: packaged-survey-layout — the Android Gradle plugin and `make-test-apk` write it.
   */
  const val PACKAGED_DIR: String = "trailblaze/surveys"
  const val PACKAGED_BUNDLE_SUFFIX: String = ".bundle.js"
  const val PACKAGED_ERROR_SUFFIX: String = ".bundle-error.txt"

  /** Why [name] in [file] cannot run mid-trail: its file could not be built for the sandbox. */
  fun unbuildableMessage(name: String, file: String, detail: String): String =
    "Survey $name in $file could not be built to run mid-trail, where a survey runs like a scripted tool " +
      "and cannot import node:* or Bun APIs. If it needs them, add requiresFinishedSession: true so it runs " +
      "after the session; for mid-trail host work, use a requiresHost tool. $detail"

  /**
   * This process's source when a session registered none: an on-device runner installs its APK's
   * ([PackagedSurveyBundleSource]).
   * The daemon registers one per session instead, since sessions there run trails from different
   * workspaces.
   */
  @Volatile
  var source: SurveyBundleSource? = null

  private const val MAX_SESSIONS = 1024

  /** A session's source, and how many of its runtimes still hold it. */
  private class Registration(val source: SurveyBundleSource, var holders: Int)

  private val bySession = object : LinkedHashMap<SessionId, Registration>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SessionId, Registration>?) = size > MAX_SESSIONS
  }

  /**
   * Answers [sessionId]'s survey loads from [source] until every [register] for it has had its
   * [unregister]. A multi-device session launches one runtime per device, and a runtime that shuts
   * down first must not take the source from one still running its finalizers. The first source
   * stays: a launch nested in a session's own (an iOS run's) knows no more about where the trail
   * came from than the one that registered first.
   */
  fun register(sessionId: SessionId, source: SurveyBundleSource) {
    synchronized(bySession) {
      val held = bySession[sessionId]
      if (held == null) {
        bySession[sessionId] = Registration(source, 1)
      } else {
        held.holders++
      }
    }
  }

  fun unregister(sessionId: SessionId) {
    synchronized(bySession) {
      val held = bySession[sessionId] ?: return
      if (--held.holders <= 0) bySession.remove(sessionId)
    }
  }

  /** [sessionId]'s source, else this process's. */
  fun sourceFor(sessionId: SessionId): SurveyBundleSource? = synchronized(bySession) { bySession[sessionId]?.source } ?: source

  /**
   * The entry esbuild bundles for the survey file at [importPath]: the file's exports plus the one
   * function the SDK calls. SISTER-IMPL-TAG: survey-bundle-entry — the Android Gradle plugin builds
   * device bundles from the same entry (`surveyBundleEntrySource`), and the two must agree.
   */
  fun entrySource(importPath: String): String = buildString {
    appendLine("// Synthetic entry: one survey file, built for the scripted-tool sandbox.")
    appendLine("import * as surveys from ${jsString(importPath)};")
    appendLine("import { runNamedLiveSurvey } from \"@trailblaze/scripting/surveys\";")
    appendLine("export { liveSurveyCapabilities as capabilities } from \"@trailblaze/scripting/surveys\";")
    appendLine("export const runLive = (name, events, options) => runNamedLiveSurvey(surveys, name, events, options);")
  }

  /**
   * Every survey [text] exports by name: `export const <name> = trailblaze.survey(` or `= survey(`,
   * the forms the docs teach. Read from the source, so finding a survey needs no bundle.
   */
  fun exportedSurveyNames(text: String): List<String> = exportedSurveys(text).map { it.name }

  /**
   * Whether [text] exports a value [exportedSurveys] cannot read: a re-export (`export { a } from`,
   * `export *`), an export list or default, `export const` of something other than `survey(`, or a
   * `survey(` followed by a further declarator (`a = survey({…}), b = survey({…})`), which
   * [exportedSurveys] does not read. Any of them may be a survey only loading the file can see.
   * Types, functions, and a single `export const` whose value plainly is not a survey (a literal,
   * `new`, a function, or an `eventStream(` call) are not surveys.
   */
  fun hasUnreadExports(text: String): Boolean {
    val code = SurveySourceText.codeOnly(text)
    val surveys = EXPORTED_SURVEY.findAll(code).filter { endsItsDeclaration(code, it) }.map { it.range.first }.toSet()
    val exports = EXPORT.findAll(code).toList()
    return exports.withIndex().any { (n, export) ->
      val start = export.range.first
      start !in surveys && !NOT_A_VALUE.matches(export.groupValues[1]) &&
        !exportsPlainValue(code, start, exports.getOrNull(n + 1)?.range?.first ?: code.length)
    }
  }

  /**
   * Whether the `export const` at [start] declares one name whose value cannot be a survey: an object,
   * array, string, number, boolean or `null` literal, a `new` expression, a function, or an
   * `eventStream(` call. A further declarator before the statement ends (`;`, or [until], the next
   * export) could be a survey, so it reads as unread.
   */
  private fun exportsPlainValue(code: String, start: Int, until: Int): Boolean {
    val declared = EXPORTED_VALUE.find(code, start)?.takeIf { it.range.first == start } ?: return false
    val value = declared.range.last + 1
    if (!PLAIN_VALUE.containsMatchIn(code.substring(value, minOf(code.length, value + 200))) && !isArrowAt(code, value)) return false
    // `{ ...sharedSurvey }` copies a survey, brand and all: only loading the file tells.
    if (code.startsWith("{", value)) {
      val literal = SurveySourceText.objectLiteralAt(code, value) ?: return false
      if ("..." in SurveySourceText.topLevelOf(code, literal)) return false
    }
    var depth = 0
    for (i in value until until) {
      when (code[i]) {
        '(', '[', '{' -> depth++
        ')', ']', '}' -> depth--
        ';' -> if (depth == 0) return true
        ',' -> if (depth == 0) return false
      }
    }
    return true
  }

  /** Whether an arrow function starts at [from]: `(a, b) =>`, `(a): T =>`, or `x =>`. */
  private fun isArrowAt(code: String, from: Int): Boolean {
    if (code.startsWith("(", from)) {
      var depth = 0
      var i = from
      while (i < code.length) {
        when (code[i]) {
          '(', '[', '{' -> depth++
          ')', ']', '}' -> if (--depth == 0) break
        }
        i++
      }
      return ARROW_AFTER_PARAMS.containsMatchIn(code.substring(minOf(code.length, i + 1), minOf(code.length, i + 201)))
    }
    return SINGLE_PARAM_ARROW.containsMatchIn(code.substring(from, minOf(code.length, from + 200)))
  }

  /** Whether [match]'s `survey(…)` call closes with nothing declared after it in the same statement. */
  private fun endsItsDeclaration(code: String, match: MatchResult): Boolean {
    var i = SurveySourceText.callArgumentsAt(code, match.range.last + 1) ?: return false
    var depth = 1
    while (i < code.length && depth > 0) {
      when (code[i]) {
        '(', '[', '{' -> depth++
        ')', ']', '}' -> depth--
      }
      i++
    }
    if (depth > 0) return false
    while (i < code.length && code[i].isWhitespace()) i++
    return i >= code.length || code[i] != ','
  }

  /**
   * Every survey [text] exports, with the spec fields the generated bindings and the end-of-run warnings need. Read from
   * the top level of the object literal passed as `survey(`'s first argument, so a flag in another
   * survey's spec, or nested in a finding's data, never counts; a spec built elsewhere and passed in by name reads as a default
   * survey, and running it mid-trail still refuses it, naming the flag. Comments and literals
   * are not code here: a commented-out export is no export (see [SurveySourceText]).
   */
  fun exportedSurveys(text: String): List<ExportedSurvey> {
    val code = SurveySourceText.codeOnly(text)
    val readable = SurveySourceText.withoutComments(text)
    return EXPORTED_SURVEY.findAll(code).mapNotNull { match ->
      val args = SurveySourceText.callArgumentsAt(code, match.range.last + 1) ?: return@mapNotNull null
      val spec = SurveySourceText.objectLiteralAt(code, args)
      ExportedSurvey(
        name = match.groupValues[1],
        feature = spec?.let { valueOf(code, readable, it, "feature") }?.let { STRING_VALUE.find(it)?.groupValues?.get(2) },
        requiresFinishedSession = spec?.let { valueOf(code, readable, it, "requiresFinishedSession") }?.let { TRUE_VALUE.containsMatchIn(it) } == true,
        warning = spec?.let { valueOf(code, readable, it, "warning") }?.let { TRUE_VALUE.containsMatchIn(it) } == true,
        specInSource = spec != null,
        warningInSource = spec != null && warningIsLiteral(code, readable, spec),
      )
    }.toList()
  }

  /**
   * [warning] is the spec's `warning: true`: each finding is a warning on its run. [specInSource] is false
   * when the spec is built elsewhere and passed in by name, so its fields here are defaults, not
   * what the survey declares. [warningInSource] is false too when the literal's `warning` is computed,
   * shorthand, or may come from a spread or computed key: only loading the spec tells.
   */
  class ExportedSurvey(
    val name: String,
    val feature: String?,
    val requiresFinishedSession: Boolean,
    val warning: Boolean = false,
    val specInSource: Boolean = true,
    val warningInSource: Boolean = specInSource,
  )

  /**
   * Whether the literal at [spec] shows its `warning` outright: `warning: true`, `warning: false`, or no `warning`
   * key, with no spread or computed key that could add one.
   */
  private fun warningIsLiteral(code: String, readable: String, spec: IntRange): Boolean {
    val codeTop = SurveySourceText.topLevelOf(code, spec)
    if ("..." in codeTop || COMPUTED_KEY.containsMatchIn(codeTop)) return false
    val value = valueOf(code, readable, spec, "warning")
      ?: return !SHORTHAND_WARNING.containsMatchIn(codeTop)
    return LITERAL_BOOLEAN.containsMatchIn(value)
  }

  /**
   * Every derived event stream [text] declares with `eventStream(`, by the `name` its spec gives,
   * so `trailblaze check` can refuse two streams of one name before anything runs. [DeclaredStream.name]
   * is null when the spec is not an object literal or its `name` is not a plain string: only running
   * the file could tell what it is. Comments and literals are not code here, as in [exportedSurveys].
   */
  fun declaredStreams(text: String): List<DeclaredStream> {
    val code = SurveySourceText.codeOnly(text)
    val readable = SurveySourceText.withoutComments(text)
    return EVENT_STREAM_CALL.findAll(code).mapNotNull { match ->
      val args = SurveySourceText.callArgumentsAt(code, match.range.last + 1) ?: return@mapNotNull null
      val spec = SurveySourceText.objectLiteralAt(code, args)
      DeclaredStream(
        name = spec?.let { valueOf(code, readable, it, "name") }?.let { PLAIN_STRING.find(it)?.groupValues?.get(2) },
        line = code.substring(0, match.range.first).count { it == '\n' } + 1,
      )
    }.toList()
  }

  class DeclaredStream(val name: String?, val line: Int)

  /**
   * The source after [key]'s colon, when it is one of the top-level keys of the literal at [spec],
   * bare or quoted. A key-like run inside a string value is not a key: [code] has it blanked.
   */
  private fun valueOf(code: String, readable: String, spec: IntRange, key: String): String? {
    val codeTop = SurveySourceText.topLevelOf(code, spec)
    val readableTop = SurveySourceText.topLevelOf(code, spec, readable)
    val match = Regex("""(?<![\w$])(["']?)${Regex.escape(key)}\1\s*:""").findAll(readableTop)
      .firstOrNull { codeTop[it.range.first] != ' ' } ?: return null
    return readableTop.substring(match.range.last + 1)
  }

  private val STRING_VALUE = Regex("""^\s*(["'`])([^"'`]+)\1""")
  private val TRUE_VALUE = Regex("""^\s*true\b""")
  private val LITERAL_BOOLEAN = Regex("""^\s*(?:true|false)\s*(?:,|$)""")

  /** `warning` as a key with no colon after it: `{ warning, ... }`. */
  private val SHORTHAND_WARNING = Regex("""(?<![\w$.])warning\s*(?:,|$)""")

  /** A key whose brackets [SurveySourceText.topLevelOf] blanked: `[name]: value`. */
  private val COMPUTED_KEY = Regex("""(?:^|,)\s*:""")

  /** A string literal with nothing computed in or after it: `"screens",` but not `"app." + kind`. */
  private val PLAIN_STRING = Regex("""^\s*(["'`])([^"'`$\\]+)\1\s*(?:,|$)""")

  private val EVENT_STREAM_CALL = Regex("""(?<![\w$])eventStream\b""")

  /** An `export` statement, with the word or symbol that follows it. */
  private val EXPORT = Regex("""(?<![\w$.])export\s+(\*|\{|[A-Za-z]+)""")
  private val NOT_A_VALUE = Regex("""type|interface|function|class|enum|declare|async|abstract""")

  /** `export const <name> =`, through to where its value starts. */
  private val EXPORTED_VALUE = Regex("""export\s+(?:const|let|var)\s+[A-Za-z_$][\w$]*\s*(?::[^=]+)?=\s*""")

  /** A value that is not a survey, by how it starts. */
  private val PLAIN_VALUE =
    Regex("""^(?:[{\["'`]|-?\d|(?:new|true|false|null|undefined|function|async)\b|(?:trailblaze\s*\.\s*)?eventStream\s*[<(])""")

  /** What follows an arrow's parameter list: `=>`, or a return type before it. */
  private val ARROW_AFTER_PARAMS = Regex("""^\s*(?:=>|:[^=;]*=>)""")
  private val SINGLE_PARAM_ARROW = Regex("""^[A-Za-z_$][\w$]*\s*=>""")

  private val EXPORTED_SURVEY =
    Regex("""export\s+const\s+([A-Za-z_$][\w$]*)\s*(?::[^=]+)?=\s*(?:trailblaze\s*\.\s*)?survey\b""")

  private fun jsString(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
