package xyz.block.trailblaze.tools

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Test
import xyz.block.trailblaze.logs.client.TrailblazeSerializationInitializer
import xyz.block.trailblaze.toolcalls.HandCuratedRecordableTools
import xyz.block.trailblaze.toolcalls.toScriptedToolDescriptor
import xyz.block.trailblaze.toolcalls.trailblazeToolClassAnnotation

/**
 * Guards the split between the SDK's vendored built-in-tool TS bindings
 * (`sdks/typescript/src/built-in-tools.ts`) and the GENERATED recordable-tool surface that
 * `PerTrailmapClientDtsEmitter` now derives from `@TrailblazeToolClass(isRecordable = true)` (see
 * the devlog `2026-07-01-trail-recording-type-validation.md`). built-in-tools.ts is now the small
 * RESIDUAL hand file — non-recordable author utilities plus the few recordable tools the generator
 * can't model (`HAND_CURATED_RECORDABLE`). Two invariants:
 *
 *  1. **No stale names.** Every name in built-in-tools.ts still maps to a `@TrailblazeToolClass`, so
 *     a Kotlin rename/deletion doesn't leave the TS bindings advertising a name that doesn't
 *     dispatch (strict types compile, but `callTool` fails at runtime with "unknown tool"). Covered
 *     by a pure static-text scan (no reflection) so it works whether trailblaze-common is tested in
 *     isolation or in a larger build.
 *  2. **No collision with the generated surface.** built-in-tools.ts declares no recordable tool the
 *     generated per-surface `.d.ts` ALSO emits — otherwise the two duplicate the same
 *     `TrailblazeToolMap` key with (likely) different shapes and tsc fails with TS2717, breaking the
 *     trail-recording validator for every trail. This second check IS reflective (it enumerates the
 *     recordable registry the way the emitter does).
 *
 * Arg-shape drift within a single entry (a Kotlin field renamed without updating the TS interface)
 * is not covered here — for the generated surface that's structurally impossible now; for the
 * residual hand file it's caught by the validator's tsc pass.
 */
class BuiltInToolsBindingDriftTest {

  @Test
  fun `extractTsToolMapKeys ignores braces inside JSDoc comments and string literals`() {
    // Synthetic input: every brace in JSDoc / line comments / string literals must be
    // ignored so the depth counter sees only structural braces. Without comment-stripping
    // the inner `{x: 1}` JSDoc would push the depth counter to 3 and the closing `}` of
    // `realTool`'s body would land at depth 1 instead of 2 — `secondTool:` would never be
    // matched as a top-level entry.
    val syntheticTs = """
      declare module "@trailblaze/scripting" {
        interface TrailblazeToolMap {
          /**
           * Example arg shape: `{ x: 1, y: 2 }`. Or a string with braces: "}{".
           */
          realTool: {
            x: string;
          };

          // Inline `{ }` in a line comment shouldn't count either.
          secondTool: {
            y: number;
          };
        }
      }
      export {};
    """.trimIndent()

    val keys = extractTsToolMapKeys(syntheticTs)
    assertEquals(setOf("realTool", "secondTool"), keys)
  }

  // ---- Direct unit tests for stripCommentsAndStringLiterals ----
  //
  // The integration test above covers the happy path; these target the malformed-input
  // and escape-handling branches that would otherwise be observable only through opaque
  // downstream test failures.

  @Test
  fun `stripCommentsAndStringLiterals replaces block comments with same-length whitespace`() {
    val input = "before /* hidden */ after"
    val out = stripCommentsAndStringLiterals(input)
    assertEquals("before              after", out)
    assertEquals(input.length, out.length, "length must be preserved")
  }

  @Test
  fun `stripCommentsAndStringLiterals preserves newlines inside multi-line block comments`() {
    val input = "a\n/* line one\n   line two */ b"
    val expectedNewlines = input.count { it == '\n' }
    val out = stripCommentsAndStringLiterals(input)
    // Newlines preserved as actual newline chars; non-newline content becomes spaces.
    assertEquals(expectedNewlines, out.count { it == '\n' }, "newline count must match input")
    assertEquals(input.length, out.length)
    assertTrue("opening /* hidden: $out") { !out.contains("/*") }
    assertTrue("inner content hidden: $out") { !out.contains("line") }
  }

  @Test
  fun `stripCommentsAndStringLiterals handles unterminated block comment by running to EOF`() {
    val input = "ok /* no closer here"
    val out = stripCommentsAndStringLiterals(input)
    assertEquals(input.length, out.length)
    assertTrue("comment opener stripped: $out") { !out.contains("/*") }
    // The pre-comment prefix survives intact.
    assertTrue("prefix preserved: $out") { out.startsWith("ok ") }
  }

  @Test
  fun `stripCommentsAndStringLiterals strips line comments up to but not past newline`() {
    val input = "live // dead\nlive2"
    val out = stripCommentsAndStringLiterals(input)
    assertEquals(input.length, out.length)
    assertTrue("comment stripped: $out") { !out.contains("dead") }
    assertTrue("post-newline content survives: $out") { out.endsWith("\nlive2") }
  }

  @Test
  fun `stripCommentsAndStringLiterals handles unterminated line comment at EOF`() {
    val input = "ok // tail with no newline"
    val out = stripCommentsAndStringLiterals(input)
    assertEquals(input.length, out.length)
    assertTrue("tail text hidden: $out") { !out.contains("tail") }
  }

  @Test
  fun `stripCommentsAndStringLiterals strips double-quoted string literals`() {
    val input = "x = \"hello {brace}\" + y"
    val out = stripCommentsAndStringLiterals(input)
    assertEquals(input.length, out.length)
    assertTrue("braces hidden: $out") { !out.contains("{") && !out.contains("}") }
    assertTrue("non-string code preserved: $out") { out.contains("x = ") && out.contains(" + y") }
  }

  @Test
  fun `stripCommentsAndStringLiterals honors escaped quotes inside strings`() {
    // The escaped `\"` must NOT terminate the string; the second real `"` does.
    val input = "a = \"foo \\\" bar { still inside }\" end"
    val out = stripCommentsAndStringLiterals(input)
    assertEquals(input.length, out.length)
    assertTrue("inner braces hidden: $out") { !out.contains("{") && !out.contains("}") }
    assertTrue("post-string code preserved: $out") { out.endsWith(" end") }
  }

  @Test
  fun `stripCommentsAndStringLiterals handles unterminated string by running to EOF`() {
    val input = "x = \"unclosed { content"
    val out = stripCommentsAndStringLiterals(input)
    assertEquals(input.length, out.length)
    // Brace inside the unterminated string must not leak to depth counter.
    assertTrue("brace hidden: $out") { !out.contains("{") }
    assertTrue("prefix preserved: $out") { out.startsWith("x = ") }
  }

  @Test
  fun `stripCommentsAndStringLiterals preserves newlines inside template literals`() {
    val input = "x = `multi\nline { brace } here`"
    val out = stripCommentsAndStringLiterals(input)
    assertEquals(input.length, out.length)
    assertEquals(1, out.count { it == '\n' }, "embedded newline must survive")
    assertTrue("braces hidden: $out") { !out.contains("{") && !out.contains("}") }
  }

  @Test
  fun `stripCommentsAndStringLiterals returns empty for empty input`() {
    assertEquals("", stripCommentsAndStringLiterals(""))
  }

  @Test
  fun `stripCommentsAndStringLiterals leaves bare braces alone (structural)`() {
    val input = "interface X { y: number }"
    assertEquals(input, stripCommentsAndStringLiterals(input))
  }

  @Test
  fun `every tool name listed in built-in-tools_ts has a Kotlin TrailblazeToolClass annotation`() {
    val builtInToolsTs = locateBuiltInToolsTs()
    val toolSourceRoot = locateTrailblazeToolSourceRoot()

    val tsNames = extractTsToolMapKeys(builtInToolsTs.readText())
    val kotlinNames = extractKotlinAnnotationNames(toolSourceRoot)

    assertTrue("Expected at least a few names listed in built-in-tools.ts; got 0") { tsNames.isNotEmpty() }
    assertTrue("Expected to discover Kotlin @TrailblazeToolClass annotations; got 0") { kotlinNames.isNotEmpty() }

    val missing = tsNames - kotlinNames
    if (missing.isNotEmpty()) {
      fail(
        buildString {
          appendLine("Drift detected — these tool names are declared in")
          appendLine("  $builtInToolsTs")
          appendLine("but no @TrailblazeToolClass(\"…\") annotation matches in")
          appendLine("  $toolSourceRoot")
          appendLine()
          missing.sorted().forEach { appendLine("  - $it") }
          appendLine()
          append(
            "Either the Kotlin tool was renamed/deleted (update built-in-tools.ts to match) or " +
              "built-in-tools.ts has a typo. The drift policy is documented at the top of the TS file.",
          )
        },
      )
    }
  }

  @Test
  fun `built-in-tools_ts declares no recordable tool the generated surface already emits`() {
    // Every RECORDABLE tool is now generated into each surface's `trailblaze-client.d.ts` by
    // `PerTrailmapClientDtsEmitter`, derived from `isRecordable`. built-in-tools.ts is ALSO in scope
    // for the validator's tsc compilation (via the SDK bundle), so if it declared one of those same
    // tools the two would be duplicate `TrailblazeToolMap` keys → TS2717, breaking the whole gate.
    // This asserts the two surfaces stay DISJOINT: the only recordable tools allowed to remain in the
    // hand file are the ones the generator deliberately skips ([HandCuratedRecordableTools.NAMES]).
    val tsNames = extractTsToolMapKeys(locateBuiltInToolsTs().readText())
    assertTrue("Expected some names in built-in-tools.ts; got 0") { tsNames.isNotEmpty() }

    val emitted = generatedRecordableToolNames()
    // Guard against a false green: if classpath tool discovery came up short (missing resources),
    // the intersection below would be trivially empty. The real recordable set is ~50 tools.
    assertTrue(
      "Reflective recordable-tool discovery returned only ${emitted.size} tools — the test " +
        "classpath is probably missing the tool YAML resources, which would make this guard vacuous.",
    ) { emitted.size >= 20 }

    val collisions = (tsNames intersect emitted).sorted()
    if (collisions.isNotEmpty()) {
      fail(
        buildString {
          appendLine("built-in-tools.ts declares recordable tool(s) that the generated recordable")
          appendLine("surface (PerTrailmapClientDtsEmitter) ALSO emits — duplicate TrailblazeToolMap")
          appendLine("keys collide (TS2717) and break the trail-recording validator:")
          appendLine()
          collisions.forEach { appendLine("  - $it") }
          appendLine()
          append(
            "Remove these from built-in-tools.ts (the generated surface owns them). If the generator " +
              "genuinely can't model one (a serializer-transformed shape like mobile_maestro, or a " +
              "rich result type worth preserving), add it to the single shared allowlist " +
              "HandCuratedRecordableTools.NAMES (both the emitter and this test read it).",
          )
        },
      )
    }
  }

  /**
   * Reflectively compute the set of tool names the generated recordable surface emits — the mirror of
   * `PerTrailmapClientDtsEmitter.resolveKotlinToolDescriptorsForTrailmap`: every `isRecordable`
   * class-backed tool the Koog descriptor path can lower, plus every recordable YAML-defined tool,
   * MINUS [HandCuratedRecordableTools.NAMES] (which the emitter skips so they can stay hand-typed in
   * built-in-tools.ts).
   */
  private fun generatedRecordableToolNames(): Set<String> {
    val classBacked = TrailblazeSerializationInitializer.buildAllTools().values
      .filter { it.trailblazeToolClassAnnotation().isRecordable }
      // Only tools the descriptor path can lower are actually emitted; the rest are dropped (and
      // typically live in HandCuratedRecordableTools.NAMES anyway).
      .filter { it.toScriptedToolDescriptor() != null }
      .map { it.trailblazeToolClassAnnotation().name }
    val yamlDefined = TrailblazeSerializationInitializer.buildYamlDefinedTools()
      .filterValues { it.isRecordable != false }
      .keys.map { it.toolName }
    return (classBacked + yamlDefined).toSet() - HandCuratedRecordableTools.NAMES
  }

  @Test
  fun `every HandCuratedRecordableTools name is a real recordable tool (allowlist can't rot)`() {
    // The shared allowlist is the ONE place the emitter and this test agree to skip. If a listed tool
    // is renamed or deleted, its name silently stops matching anything and the allowlist rots into a
    // no-op. Assert every entry still resolves to a genuinely recordable tool (class-backed or
    // YAML-defined) so that drift fails loudly here instead.
    val recordableClassNames = TrailblazeSerializationInitializer.buildAllTools().values
      .filter { it.trailblazeToolClassAnnotation().isRecordable }
      .map { it.trailblazeToolClassAnnotation().name }
      .toSet()
    val recordableYamlNames = TrailblazeSerializationInitializer.buildYamlDefinedTools()
      .filterValues { it.isRecordable != false }
      .keys.map { it.toolName }
      .toSet()
    val recordable = recordableClassNames + recordableYamlNames
    // Same vacuity guard as the disjointness test — a short classpath would make this trivially pass.
    assertTrue("Reflective recordable-tool discovery returned only ${recordable.size} tools") {
      recordable.size >= 20
    }
    val stale = (HandCuratedRecordableTools.NAMES - recordable).sorted()
    assertTrue(
      "HandCuratedRecordableTools.NAMES lists name(s) that are no longer a recordable tool " +
        "(renamed/deleted?): $stale. Remove them from the allowlist.",
    ) { stale.isEmpty() }
  }

  /**
   * Walks the JVM working dir up to find the repo root so this test runs regardless of
   * which module-relative working dir Gradle launches it from. The SDK's `built-in-tools.ts`
   * is the anchor — once we find it, we know we're at the repo root.
   */
  private fun locateBuiltInToolsTs(): File {
    val anchor = "sdks/typescript/src/built-in-tools.ts"
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
      val candidate = File(dir, anchor)
      if (candidate.isFile) return candidate
      dir = dir.parentFile
    }
    fail("Could not locate $anchor by walking up from ${System.getProperty("user.dir")}.")
  }

  /**
   * Locate the root package dir holding every `@TrailblazeToolClass` in `trailblaze-common`.
   * Framework tools live in several subpackages under it — `toolcalls/commands` (tap / assert /
   * swipe / launch), `mobile/tools` (`android_adbShell`, `android_grantPermission`,
   * `android_writeFileToDownloads`, …), `android/tools` — so we anchor on the package root and
   * `extractKotlinAnnotationNames` walks it recursively. (Scanning only `toolcalls/commands`
   * would miss any `built-in-tools.ts` entry backed by a class in one of the sibling packages.)
   */
  private fun locateTrailblazeToolSourceRoot(): File {
    val anchor = "trailblaze-common/src/jvmAndAndroid/kotlin/xyz/block/trailblaze"
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
      val candidate = File(dir, anchor)
      if (candidate.isDirectory) return candidate
      dir = dir.parentFile
    }
    fail("Could not locate $anchor by walking up from ${System.getProperty("user.dir")}.")
  }

  /**
   * Pull the keys out of the `interface TrailblazeToolMap { … }` block. Matches both
   * bare-identifier keys (`tapOnPoint:`) and quoted keys (`"weird-name":`). Stops at the
   * closing brace of the block so unrelated keys elsewhere in the file aren't picked up.
   *
   * Strategy: locate the interface block, strip out JSDoc / line / string-literal regions
   * (any of which could contain literal `{` or `}` and throw off the depth counter), then
   * scan line-by-line counting braces. Keys at depth == 1 (immediately inside the interface
   * body) are tool-map entries; deeper keys are nested arg-shape fields and ignored.
   *
   * The strip step is defense-in-depth: today the file's JSDoc descriptions don't contain
   * literal braces, but a future entry like `/** Example: { x: 1 } */` would miscount under
   * a naive scanner and silently drop the tool key from the comparison. Better to fail
   * obviously (no keys collected → test fails on emptiness assertion) than silently miss
   * coverage.
   */
  internal fun extractTsToolMapKeys(content: String): Set<String> {
    val interfaceStart = content.indexOf("interface TrailblazeToolMap")
    require(interfaceStart >= 0) { "built-in-tools.ts has no `interface TrailblazeToolMap` block." }
    // Slice off everything before the interface so our line-by-line walk starts cleanly.
    val stripped = stripCommentsAndStringLiterals(content.substring(interfaceStart))
    val names = mutableSetOf<String>()
    var depth = 0
    var sawOpenBrace = false
    for (rawLine in stripped.lineSequence()) {
      val line = rawLine.trim()
      // Track brace depth via per-line counts; multi-brace lines (e.g. `};`) are handled by
      // counting both characters. After [stripCommentsAndStringLiterals], the only remaining
      // braces are syntactic, so the count is structurally accurate.
      val opens = line.count { it == '{' }
      val closes = line.count { it == '}' }
      if (depth == 1 && opens > 0) {
        // A line at depth 1 that opens a brace is a tool entry — match its key.
        val match = TS_KEY_PATTERN.matchEntire(line)
        if (match != null) {
          val (quoted, bare) = match.destructured
          names += quoted.ifEmpty { bare }
        }
      }
      depth += opens - closes
      if (sawOpenBrace && depth == 0) break
      if (!sawOpenBrace && opens > 0) sawOpenBrace = true
    }
    return names
  }

  /**
   * Replace every JSDoc block, line comment, and string literal in [content] with
   * same-length whitespace, preserving line / column offsets but eliminating any
   * non-syntactic braces those regions might contain. Newlines are preserved as actual
   * newline characters so downstream line-counting logic stays accurate.
   *
   * Single-quoted, double-quoted, and template-literal strings are all stripped. Escape
   * sequences inside strings (`\"`, `\\`) are honored so a `"foo \" { bar"` literal is
   * treated as one token, not a partial match. Unterminated comments and unterminated
   * strings (no closing block-comment terminator or quote before EOF) run cleanly to
   * end-of-input rather than throwing — both produce malformed-looking but stable output.
   *
   * Visible (`internal`) for direct unit tests of the edge cases the integration test
   * doesn't exercise (unterminated regions, escape sequences, template literals).
   */
  internal fun stripCommentsAndStringLiterals(content: String): String {
    val out = StringBuilder(content.length)
    var i = 0
    val n = content.length
    while (i < n) {
      val c = content[i]
      val next = if (i + 1 < n) content[i + 1] else ' '
      when {
        c == '/' && next == '*' -> {
          // Block comment — replace with whitespace until `*/`. Loops cover unterminated
          // comments by running to EOF.
          val end = content.indexOf("*/", startIndex = i + 2).let { if (it < 0) n else it + 2 }
          for (j in i until end) out.append(if (content[j] == '\n') '\n' else ' ')
          i = end
        }
        c == '/' && next == '/' -> {
          // Line comment — replace until end-of-line (preserve the newline itself so line
          // counts stay accurate).
          val eol = content.indexOf('\n', startIndex = i + 2).let { if (it < 0) n else it }
          for (j in i until eol) out.append(' ')
          i = eol
        }
        c == '"' || c == '\'' || c == '`' -> {
          // String literal — replace until matching quote, honoring `\` escapes. Newlines
          // inside template literals (backticks) are preserved as actual newline characters
          // (not the two-char `\n` escape) so the line counter downstream stays accurate.
          val quote = c
          out.append(' ')
          var j = i + 1
          while (j < n) {
            val cj = content[j]
            if (cj == '\\' && j + 1 < n) {
              out.append(' ').append(if (content[j + 1] == '\n') '\n' else ' ')
              j += 2
              continue
            }
            if (cj == quote) {
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

  /**
   * Walks the Kotlin tool-class directory, opening every `.kt` file and extracting names
   * from `@TrailblazeToolClass("…")` annotations. Doesn't require loading the classes —
   * deliberately textual so a test failure surfaces a missing/renamed file directly without
   * a confusing reflection error.
   */
  private fun extractKotlinAnnotationNames(dir: File): Set<String> {
    val names = mutableSetOf<String>()
    dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
      val text = file.readText()
      KOTLIN_ANNOTATION_PATTERN.findAll(text).forEach { match ->
        names += match.groupValues[1]
      }
    }
    return names
  }

  private companion object {
    // Match either a quoted property name (`"weird-name":`) or a bare identifier
    // (`tapOnPoint:`) at the start of a trimmed line. The trailing `[ {(]` excludes
    // accidental matches inside JSDoc bodies that contain `someText:`.
    private val TS_KEY_PATTERN = Regex(
      """(?:"([^"]+)":|([A-Za-z_$][A-Za-z0-9_$]*):)\s*[{(].*""",
    )
    // Match `@TrailblazeToolClass("name", ...)` and `@TrailblazeToolClass(name = "name", ...)`.
    // Non-greedy `[^"]*?` would also work; using `[^"]+?` to require a non-empty name.
    //
    // LIMITATION: this matches a quoted string LITERAL only — it can't resolve a const-valued
    // name (`@TrailblazeToolClass(name = SOME_CONST)`), and a comment between `(` and `name` also
    // defeats it. A const-named tool is therefore invisible to this guard. So any tool that gains
    // a typed binding in built-in-tools.ts MUST declare its `@TrailblazeToolClass` name as a string
    // literal adjacent to `(`/`name =` (see `ExecTrailblazeTool`, which switched off EXEC_TOOL_NAME
    // for exactly this reason). Hardening the scan to resolve consts is a possible future change.
    private val KOTLIN_ANNOTATION_PATTERN = Regex(
      """@TrailblazeToolClass\s*\(\s*(?:name\s*=\s*)?"([^"]+?)"""",
    )
  }
}
