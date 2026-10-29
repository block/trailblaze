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
   * Every survey [text] exports, with the two spec fields the generated bindings need. Read from
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
      )
    }.toList()
  }

  class ExportedSurvey(val name: String, val feature: String?, val requiresFinishedSession: Boolean)

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

  private val EXPORTED_SURVEY =
    Regex("""export\s+const\s+([A-Za-z_$][\w$]*)\s*(?::[^=]+)?=\s*(?:trailblaze\s*\.\s*)?survey\b""")

  private fun jsString(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
