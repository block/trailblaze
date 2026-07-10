package xyz.block.trailblaze.host

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * Unit tests for [TrailTscValidator]'s pure codegen + diagnostic-remap logic — the two halves that
 * carry the load-bearing contract (one tool-call per line; tsc diagnostics map back to the right
 * trail + step). The IO orchestration (`validate`) is exercised end-to-end by `trailblaze check`
 * with the env var set; these tests pin the parts that must be correct without a device or a
 * compiler, following the repo's "extract the pure logic and test it directly" guidance.
 */
class TrailTscValidatorTest {

  @Test
  fun `trailmapIdForSurfaceFile extracts the id from the base-id-tools-file layout and null otherwise`() {
    val base = TrailTscValidator.classpathValidationSurfacesBaseDir(File("/ws/trails").toPath())
    val surfaceFile = base.resolve("widgets").resolve("tools").resolve("trailblaze-client.d.ts")
    assertEquals("widgets", TrailTscValidator.trailmapIdForSurfaceFile(surfaceFile))
    // A path too shallow to carry an <id>/tools/<file> shape yields null rather than a wrong id.
    assertNull(TrailTscValidator.trailmapIdForSurfaceFile(File("only-a-name").toPath()))
  }

  @Test
  fun `generateGenFile emits one tool-call statement per line and maps each line back to its call`() {
    val calls = listOf(
      TrailTscValidator.RecordedCall("web_navigate", """{"url":"https://example.com"}""", 1, "Open site"),
      TrailTscValidator.RecordedCall("web_verifyTextVisible", """{"text":"Welcome"}""", 2, "Verify banner"),
    )
    val gen = TrailTscValidator.generateGenFile("trails/demo.trail.yaml", calls)
    val lines = gen.source.lines()

    // Every table entry points at a real generated line that contains exactly that tool call —
    // this is the invariant the diagnostic remap depends on.
    assertEquals(2, gen.table.size)
    gen.table.forEach { (lineNo, call) ->
      val line = lines[lineNo - 1] // table keys are 1-based
      assertTrue(line.contains("client.tools.${call.toolName}("), "line $lineNo should call ${call.toolName}: $line")
      assertTrue(line.contains(call.argsJson), "line $lineNo should carry the args literal: $line")
    }
    // The two calls land on distinct lines (one statement per line).
    assertEquals(2, gen.table.keys.distinct().size)
    // Source is a valid-shaped TS module: typed client declaration, no execution.
    assertTrue(gen.source.contains("declare const client: TrailblazeClient;"))
  }

  @Test
  fun `generateGenFile uses bracket access with an escaped key for a non-identifier tool name`() {
    val calls = listOf(TrailTscValidator.RecordedCall("weird-tool.name", "{}", 1, "Edge"))
    val gen = TrailTscValidator.generateGenFile("trails/x.trail.yaml", calls)
    val (lineNo, _) = gen.table.entries.single()
    val line = gen.source.lines()[lineNo - 1]
    // A `-`/`.` name can't be a dot-access identifier; bracket access with a quoted key keeps the
    // call aligned with the typed surface (and can't break out of the generated TS).
    assertTrue(line.contains("""client.tools["weird-tool.name"]({})"""), "bracket access: $line")
    assertTrue(!line.contains("client.tools.weird-tool"))
  }

  @Test
  fun `generateGenFile escapes a bracket-access key containing a quote`() {
    // A malformed/hand-edited tool name with a `"` must be escaped inside the bracket-access string
    // key so it stays a single valid string literal and can't break out of the generated TS.
    val calls = listOf(TrailTscValidator.RecordedCall("ev\"il", "{}", 1, "Edge"))
    val gen = TrailTscValidator.generateGenFile("trails/x.trail.yaml", calls)
    val (lineNo, _) = gen.table.entries.single()
    val line = gen.source.lines()[lineNo - 1]
    assertTrue(line.contains("""client.tools["ev\"il"]({})"""), "quote escaped in bracket key: $line")
  }

  @Test
  fun `generateGenFile handles an empty call list as a valid empty module`() {
    val gen = TrailTscValidator.generateGenFile("trails/empty.trail.yaml", emptyList())
    assertTrue(gen.table.isEmpty())
    // Still a well-formed TS module the compiler accepts (no dangling statements).
    assertTrue(gen.source.contains("async function __trail__"))
    assertTrue(gen.source.contains("void __trail__;"))
  }

  @Test
  fun `generateGenFile keeps each statement on a single line even when a step label has newlines`() {
    val calls = listOf(
      TrailTscValidator.RecordedCall("tap", "{}", 1, "line one\nline two\nline three"),
    )
    val gen = TrailTscValidator.generateGenFile("trails/x.trail.yaml", calls)
    val (lineNo, _) = gen.table.entries.single()
    val line = gen.source.lines()[lineNo - 1]
    // The whole call (and its comment) stays on one physical line — the remap's line-table
    // invariant depends on it, so an embedded newline in the label must not split it.
    assertTrue(line.contains("client.tools.tap({})"), "statement intact: $line")
    assertTrue(!line.contains("\n"))
  }

  @Test
  fun `generateGenFile keeps each statement on a single line even when a classifier has line breaks`() {
    // A YAML quoted-scalar map key can legally carry CR/LF; the classifier interpolated into the
    // trailing comment must not split the statement (same invariant as the label above).
    val calls = listOf(
      TrailTscValidator.RecordedCall("tap", "{}", 1, "Tap", classifier = "android\r\nphone"),
    )
    val gen = TrailTscValidator.generateGenFile("trails/x/trail.yaml", calls)
    val (lineNo, _) = gen.table.entries.single()
    val line = gen.source.lines()[lineNo - 1]
    assertTrue(line.contains("client.tools.tap({})"), "statement intact: $line")
    assertTrue(!line.contains("\n") && !line.contains("\r"))
  }

  @Test
  fun `isTrailFile accepts per-device names and the bare unified trail yaml but not other yaml`() {
    assertTrue(TrailTscValidator.isTrailFile("android-phone.trail.yaml"))
    assertTrue(TrailTscValidator.isTrailFile("login.trail.yaml"))
    // The unified format's canonical filename has no leading dot — a plain suffix check misses it.
    assertTrue(TrailTscValidator.isTrailFile("trail.yaml"))
    assertTrue(!TrailTscValidator.isTrailFile("blaze.yaml"))
    assertTrue(!TrailTscValidator.isTrailFile("notes.yaml"))
    assertTrue(!TrailTscValidator.isTrailFile("mytrail.yaml"))
  }

  @Test
  fun `remap keys a diagnostic back to the trail and step via the line table`() {
    val table = mapOf(
      5 to TrailTscValidator.RecordedCall("web_verifyTextVisible", """{"txt":"x"}""", 3, "Verify banner"),
    )
    val metas = mapOf("login.trail.gen.ts" to TrailTscValidator.GenFileMeta("trails/login.trail.yaml", table))
    val tsc =
      "/abs/path/login.trail.gen.ts(5,40): error TS2561: Object literal may only specify known " +
        "properties, but 'txt' does not exist in type '{ text: string; }'."

    val findings = TrailTscValidator.remap(tsc, metas)

    assertEquals(1, findings.size)
    val f = findings.single()
    assertEquals("trails/login.trail.yaml", f.trailRelPath)
    assertEquals(3, f.stepIndex)
    assertEquals("web_verifyTextVisible", f.toolName)
    assertEquals("TS2561", f.tsCode)
    assertTrue(f.message.contains("'txt' does not exist"), "message preserved: ${f.message}")
  }

  @Test
  fun `remap folds indented continuation lines into the preceding finding`() {
    val table = mapOf(7 to TrailTscValidator.RecordedCall("inputText", "{}", 4, "Type passcode"))
    val metas = mapOf("x.trail.gen.ts" to TrailTscValidator.GenFileMeta("trails/x.trail.yaml", table))
    val tsc = buildString {
      appendLine("x.trail.gen.ts(7,16): error TS2345: Argument of type '{}' is not assignable.")
      appendLine("  Property 'text' is missing in type '{}' but required in type '{ text: string; }'.")
    }

    val findings = TrailTscValidator.remap(tsc, metas)

    assertEquals(1, findings.size)
    assertTrue(findings.single().message.contains("Property 'text' is missing"), "continuation folded in")
  }

  @Test
  fun `remap drops diagnostics on lines with no table entry (e g header lines)`() {
    val table = mapOf(5 to TrailTscValidator.RecordedCall("tap", "{}", 1, "Tap"))
    val metas = mapOf("y.trail.gen.ts" to TrailTscValidator.GenFileMeta("trails/y.trail.yaml", table))
    // Diagnostic on line 2 (a header line) — not in the table.
    val tsc = "y.trail.gen.ts(2,1): error TS2307: Cannot find module '@trailblaze/scripting'."

    assertTrue(TrailTscValidator.remap(tsc, metas).isEmpty())
  }

  private fun finding(target: String?, tool: String = "tapOn", trail: String = "t.trail.yaml") =
    TrailTscValidator.Finding(
      trailRelPath = trail,
      stepIndex = 1,
      stepLabel = "step",
      toolName = tool,
      tsCode = "TS2345",
      message = "bad",
      target = target,
    )

  @Test
  fun `classify makes a finding on a non-exempt target fatal`() {
    val c = TrailTscValidator.classify(
      findings = listOf(finding(target = "wikipedia")),
      skippedNoSurface = emptyMap(),
      exemptTargets = emptyMap(),
    )
    assertEquals(1, c.fatalFindings.size)
  }

  @Test
  fun `classify makes a finding with no resolved target fatal`() {
    // A null target can never match an exemption entry, so it must stay fatal.
    val c = TrailTscValidator.classify(
      findings = listOf(finding(target = null)),
      skippedNoSurface = emptyMap(),
      exemptTargets = mapOf("sampleapp" to "reason"),
    )
    assertEquals(1, c.fatalFindings.size)
  }

  @Test
  fun `classify exempts findings on an exempt target`() {
    val c = TrailTscValidator.classify(
      findings = listOf(finding(target = "sampleapp")),
      skippedNoSurface = emptyMap(),
      exemptTargets = mapOf("sampleapp" to "not yet validatable"),
    )
    assertTrue(c.fatalFindings.isEmpty(), "exempt-target finding must not be fatal")
  }

  @Test
  fun `classify fails a non-exempt target with no surface but not an exempt one`() {
    val c = TrailTscValidator.classify(
      findings = emptyList(),
      skippedNoSurface = mapOf("sampleapp" to 5, "newTarget" to 2, TrailTscValidator.NO_TARGET_KEY to 3),
      exemptTargets = mapOf("sampleapp" to "reason", TrailTscValidator.NO_TARGET_KEY to "no target fixtures"),
    )
    // Only the target that is neither validatable nor exempt is fatal.
    assertEquals(mapOf("newTarget" to 2), c.fatalMissingSurfaceTargets)
  }

  @Test
  fun `classify does not fail missing surfaces on a scoped run`() {
    // A scoped run only loaded the selected trailmap's surface, so other workspace targets showing
    // up as no-surface are out-of-scope skips, not defects — nothing fatal.
    val c = TrailTscValidator.classify(
      findings = emptyList(),
      skippedNoSurface = mapOf("newTarget" to 2, "another" to 1),
      exemptTargets = emptyMap(),
      failOnMissingSurface = false,
    )
    assertTrue(c.fatalMissingSurfaceTargets.isEmpty(), "scoped run must not fail on missing surfaces")
  }

  @Test
  fun `Report hasFatal reflects the fatal buckets`() {
    val clean = TrailTscValidator.Report(
      trailsDiscovered = 1, trailsValidated = 1, toolCallsChecked = 1,
      findings = emptyList(), skippedNoSurface = emptyMap(), skippedNoRecording = 0, errors = emptyList(),
    )
    assertTrue(!clean.hasFatal())
    assertTrue(clean.copy(fatalFindings = listOf(finding("wikipedia"))).hasFatal())
    assertTrue(clean.copy(fatalMissingSurfaceTargets = mapOf("x" to 1)).hasFatal())
  }

  @Test
  fun `remap ignores diagnostics for unknown gen files`() {
    val metas = mapOf("known.trail.gen.ts" to TrailTscValidator.GenFileMeta("trails/known.trail.yaml", mapOf(5 to TrailTscValidator.RecordedCall("tap", "{}", 1, "Tap"))))
    val tsc = "other.trail.gen.ts(5,1): error TS2339: Property 'x' does not exist."
    assertTrue(TrailTscValidator.remap(tsc, metas).isEmpty())
    assertNull(metas["other.trail.gen.ts"])
  }

  // ── Unified-format trails ──────────────────────────────────────────────────────────────

  @get:Rule
  val tmp = TemporaryFolder()

  /** Bare decoder — unknown tools fall back to raw args, which is all extraction needs. */
  private val yaml = TrailblazeYaml()

  @Test
  fun `extractRecordedCalls flattens every classifier slot of a unified trail`() {
    val doc = yaml.decodeTrailDocument(
      """
      config:
        target: demo
      trailhead:
        step: Launch the app
        recording:
          android:
            launchApp:
              appId: com.example
      trail:
      - step: Open settings
        recording:
          android:
          - tapOn:
              text: Settings
          ios-iphone:
          - tapOn:
              text: Settings
          web: []
      - step: Verify settings shown
        recordable: false
      """.trimIndent(),
    )

    val calls = TrailTscValidator.extractRecordedCalls(doc)

    // Trailhead (step 0) + one call per non-empty classifier slot of step 1. The explicit no-op
    // slot (`web: []`) and the recordable:false step contribute nothing.
    assertEquals(3, calls.size)
    val trailhead = calls.single { it.stepIndex == 0 }
    assertEquals("launchApp", trailhead.toolName)
    assertEquals("android", trailhead.classifier)
    assertEquals("Launch the app", trailhead.stepLabel)
    assertTrue(trailhead.argsJson.contains("\"appId\""), "raw args preserved: ${trailhead.argsJson}")
    val step1 = calls.filter { it.stepIndex == 1 }
    assertEquals(listOf("android", "ios-iphone"), step1.map { it.classifier })
    assertTrue(step1.all { it.toolName == "tapOn" && it.stepLabel == "Open settings" })
  }

  @Test
  fun `a bad tool arg in one unified classifier slot remaps to the right trail step and classifier`() {
    val doc = yaml.decodeTrailDocument(
      """
      config:
        target: demo
      trail:
      - step: Open settings
        recording:
          android:
          - tapOn:
              text: Settings
      - step: Verify settings shown
        recording:
          android:
          - assertVisible:
              text: Settings
          ios-iphone:
          - assertVisible:
              txt: Settings
      """.trimIndent(),
    )
    val calls = TrailTscValidator.extractRecordedCalls(doc)
    val gen = TrailTscValidator.generateGenFile("trails/settings/trail.yaml", calls)
    // The line the bad ios-iphone call landed on — as tsc would report it.
    val badLine = gen.table.entries.single { it.value.classifier == "ios-iphone" }.key
    val metas = mapOf(
      "settings.trail.gen.ts" to TrailTscValidator.GenFileMeta("trails/settings/trail.yaml", gen.table, target = "demo"),
    )
    val tsc = "settings.trail.gen.ts($badLine,30): error TS2561: Object literal may only specify known " +
      "properties, but 'txt' does not exist in type '{ text: string; }'."

    val findings = TrailTscValidator.remap(tsc, metas)

    val f = findings.single()
    assertEquals("trails/settings/trail.yaml", f.trailRelPath)
    assertEquals(2, f.stepIndex)
    assertEquals("ios-iphone", f.classifier)
    assertEquals("assertVisible", f.toolName)
    assertEquals("demo", f.target)
  }

  @Test
  fun `extractRecordedCalls keeps the v1 shape unchanged with no classifier attribution`() {
    val doc = yaml.decodeTrailDocument(
      """
      - config:
          target: demo
      - prompts:
        - step: Open settings
          recording:
            tools:
            - tapOn:
                text: Settings
        - step: Verify settings shown
          recording:
            tools:
            - assertVisible:
                text: Settings
      """.trimIndent(),
    )

    val calls = TrailTscValidator.extractRecordedCalls(doc)

    assertEquals(2, calls.size)
    assertEquals(listOf(1, 2), calls.map { it.stepIndex })
    assertEquals(listOf("tapOn", "assertVisible"), calls.map { it.toolName })
    assertTrue(calls.all { it.classifier == null }, "v1 recordings carry no classifier slot")
  }

  @Test
  fun `validate discovers bare trail yaml files alongside per-device ones`() {
    // Tool names deliberately not registered on the classpath (they decode via the raw-args
    // fallback) — this test is about discovery + target extraction, not real tool schemas.
    val trailsRoot = tmp.newFolder("trails")
    File(trailsRoot, "login").mkdirs()
    File(trailsRoot, "login/trail.yaml").writeText(
      """
      config:
        target: demo
      trail:
      - step: Open settings
        recording:
          android:
          - demoTap:
              text: Settings
      """.trimIndent(),
    )
    File(trailsRoot, "checkout.trail.yaml").writeText(
      """
      - config:
          target: demo
      - prompts:
        - step: Open settings
          recording:
            tools:
            - demoTap:
                text: Settings
      """.trimIndent(),
    )
    File(trailsRoot, "notes.yaml").writeText("just: notes")

    // No trailmap surfaces loaded, so nothing is staged and no tsc is spawned — this exercises
    // discovery + target extraction (both formats) through the real entry point.
    val report = TrailTscValidator.validate(
      trailsRoot = trailsRoot,
      trailmaps = emptyList(),
      jsRuntime = "bun",
      tscJs = File(trailsRoot, "unused-tsc.js").toPath(),
    )

    assertEquals(2, report.trailsDiscovered, "bare trail.yaml and *.trail.yaml discovered; notes.yaml ignored")
    assertTrue(report.errors.isEmpty(), "no load errors: ${report.errors}")
    assertEquals(mapOf("demo" to 2), report.skippedNoSurface, "both formats' target: extracted")
  }
}
