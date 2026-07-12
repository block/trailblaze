package xyz.block.trailblaze.tools

import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Test

/**
 * Guards the single-boundary rule for trail memory interpolation: `{{var}}` / `${'$'}{var}` tokens
 * resolve ONCE, at the dispatch boundary (`interpolateMemoryInTool`, run by every agent's dispatch
 * loop right before a tool executes) — never inside an individual tool's `execute()` /
 * `toMaestroCommands()`. Per-tool self-interpolation was the recurring footgun this replaced:
 * a tool that forgot to interpolate operated on the literal token, and one that remembered
 * produced logs/recordings that had already lost the authored `{{var}}` form (the raw/resolved
 * split in `TrailblazeToolLog` depends on the boundary seeing the authored instance).
 *
 * Mechanically: walk every production `.kt` file in the repo, and for each file that looks like a
 * tool implementation (contains `@TrailblazeToolClass` or references `ExecutableTrailblazeTool`),
 * forbid calls to `AgentMemory.interpolateVariables` / `interpolateVariablesInJson`. Comments and
 * string literals are stripped first so prose references don't count. Textual on purpose, like
 * [BuiltInToolsBindingDriftTest]: a failure names the offending file/line directly.
 *
 * The ONLY sanctioned exception is an engine-boundary tool ([ALLOWED_SELF_INTERPOLATING_FILES]):
 * one that holds a live runtime handle (a QuickJS engine, an MCP subprocess) which a serializer
 * round-trip cannot reconstruct, so the dispatch boundary deliberately passes it through untouched
 * (`RawArgumentTrailblazeTool`) and the tool resolves its args JSON itself at the engine boundary.
 * Do NOT add a plain class-backed tool here — delete its self-interpolation instead; the dispatch
 * boundary already resolved its fields.
 */
class ToolSelfInterpolationDriftTest {

  @Test
  fun `tool implementations do not self-interpolate memory variables`() {
    val repoRoot = locateRepoRoot()
    val toolFiles = mutableListOf<File>()
    val violations = mutableListOf<String>()
    val allowListedHits = mutableSetOf<String>()

    repoRoot.walkTopDown()
      .onEnter { dir -> dir.name !in EXCLUDED_DIR_NAMES }
      .filter { it.isFile && it.extension == "kt" && !it.name.endsWith("Test.kt") }
      .filter { file -> file.path.split(File.separatorChar).none { it in EXCLUDED_SOURCE_SETS } }
      .forEach { file ->
        val text = file.readText()
        if ("@TrailblazeToolClass" !in text && "ExecutableTrailblazeTool" !in text) return@forEach
        toolFiles += file
        val stripped = stripCommentsAndStringLiterals(text)
        if (!SELF_INTERPOLATION_PATTERN.containsMatchIn(stripped)) return@forEach
        if (file.name in ALLOWED_SELF_INTERPOLATING_FILES) {
          allowListedHits += file.name
          return@forEach
        }
        val lines = stripped.lines()
        lines.forEachIndexed { index, line ->
          if (SELF_INTERPOLATION_PATTERN.containsMatchIn(line)) {
            violations += "${file.relativeTo(repoRoot)}:${index + 1}: ${lines[index].trim()}"
          }
        }
      }

    // Vacuity guard: a broken walk (bad root, over-eager exclusions) must not read as green.
    assertTrue("Expected to scan a substantial tool surface; found only ${toolFiles.size} files") {
      toolFiles.size >= 20
    }

    if (violations.isNotEmpty()) {
      fail(
        buildString {
          appendLine("Tool implementation(s) self-interpolate memory variables:")
          appendLine()
          violations.forEach { appendLine("  $it") }
          appendLine()
          append(
            "Memory tokens are resolved ONCE at the dispatch boundary (interpolateMemoryInTool) " +
              "before a tool executes — delete the in-tool interpolation and use the field " +
              "directly; it already arrives resolved. Only an engine-boundary tool that holds a " +
              "live runtime handle (see ALLOWED_SELF_INTERPOLATING_FILES) may resolve its own " +
              "args, because the boundary deliberately passes RawArgumentTrailblazeTool through.",
          )
        },
      )
    }

    // Allow-list rot check: every sanctioned file must still exist AND still self-interpolate.
    // Otherwise the entry is stale — if one of these tools moves onto the boundary, remove it
    // here so the exemption can't silently shelter a future regression.
    val stale = ALLOWED_SELF_INTERPOLATING_FILES - allowListedHits
    assertTrue(
      "ALLOWED_SELF_INTERPOLATING_FILES lists file(s) that no longer self-interpolate " +
        "(moved/renamed/cleaned up?): $stale. Remove them from the allow-list.",
    ) { stale.isEmpty() }
  }

  /**
   * The repo root to scan: the NEAREST ancestor of the working dir carrying a
   * `settings.gradle.kts`, extended one level when the immediate parent is ALSO a settings root
   * (an outer monorepo whose root build wraps this open-source tree as a subdirectory — the
   * extension pulls that repo's sibling tool modules into the scan). In a standalone open-source
   * checkout the nearest match is the repo root and its parent has no settings file. Deliberately
   * NOT "farthest ancestor": a git worktree nested inside another checkout (e.g. under a
   * tooling-managed subdirectory of the main checkout) would otherwise resolve to the OUTER
   * checkout and scan someone else's working tree.
   */
  private fun locateRepoRoot(): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null && !File(dir, "settings.gradle.kts").isFile) {
      dir = dir.parentFile
    }
    val nearest = dir
      ?: fail("No settings.gradle.kts found walking up from ${System.getProperty("user.dir")}.")
    val parent = nearest.parentFile
    return if (parent != null && File(parent, "settings.gradle.kts").isFile) parent else nearest
  }

  /**
   * Replace line comments, block comments, and string literals with same-length whitespace
   * (newlines preserved) so prose mentions of the forbidden calls don't count and reported line
   * numbers stay accurate. Same approach as [BuiltInToolsBindingDriftTest]'s TS stripper; Kotlin's
   * nested block comments are not specially handled (a nested `/* /* */ */` un-hides its tail),
   * which at worst over-reports — never under-reports.
   */
  private fun stripCommentsAndStringLiterals(content: String): String {
    val out = StringBuilder(content.length)
    var i = 0
    val n = content.length
    while (i < n) {
      val c = content[i]
      val next = if (i + 1 < n) content[i + 1] else ' '
      when {
        c == '/' && next == '*' -> {
          val end = content.indexOf("*/", startIndex = i + 2).let { if (it < 0) n else it + 2 }
          for (j in i until end) out.append(if (content[j] == '\n') '\n' else ' ')
          i = end
        }
        c == '/' && next == '/' -> {
          val eol = content.indexOf('\n', startIndex = i + 2).let { if (it < 0) n else it }
          for (j in i until eol) out.append(' ')
          i = eol
        }
        c == '"' -> {
          out.append(' ')
          var j = i + 1
          while (j < n) {
            val cj = content[j]
            if (cj == '\\' && j + 1 < n) {
              out.append(' ').append(if (content[j + 1] == '\n') '\n' else ' ')
              j += 2
              continue
            }
            if (cj == '"') {
              out.append(' ')
              j++
              break
            }
            out.append(if (cj == '\n') '\n' else ' ')
            j++
          }
          i = j
        }
        else -> {
          out.append(c)
          i++
        }
      }
    }
    return out.toString()
  }

  private companion object {
    /** Calls to either interpolation entry point, with or without an explicit receiver. */
    private val SELF_INTERPOLATION_PATTERN = Regex("""\binterpolateVariables(InJson)?\s*\(""")

    /**
     * Engine-boundary tools that hold live runtime handles the dispatch boundary can't
     * round-trip, so they resolve their own args JSON (and keep `rawToolArguments`
     * token-bearing for logs). See the class kdoc for the admission bar.
     */
    private val ALLOWED_SELF_INTERPOLATING_FILES = setOf(
      "QuickJsTrailblazeTool.kt",
      "SubprocessTrailblazeTool.kt",
    )

    /** Directory names never worth descending into (generated output, VCS, toolchains, caches). */
    private val EXCLUDED_DIR_NAMES = setOf(
      "build", ".git", ".gradle", "node_modules", "bin", ".claude", ".trailblaze", "dist", "out",
    )

    /** Source-set dir names whose files are test code — tests may exercise interpolation freely. */
    private val EXCLUDED_SOURCE_SETS = setOf(
      "test", "jvmAndAndroidTest", "commonTest", "jvmTest", "androidTest", "androidUnitTest",
      "testFixtures", "integrationTest",
    )
  }
}
