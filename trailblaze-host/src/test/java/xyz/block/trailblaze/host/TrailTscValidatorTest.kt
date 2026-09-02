package xyz.block.trailblaze.host

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolParameterDescriptor
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.unified.TrailDocument

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

  /** Every recorded call of a trail, transpile-ready — the flatten `validate` stages from. */
  private fun recordedCalls(
    doc: TrailDocument,
    descriptors: Map<String, TrailblazeToolDescriptor> = emptyMap(),
  ) = TrailTscValidator.attributeRecordedCalls(doc).calls.map { it.toRecordedCall(descriptors) }

  @Test
  fun `attributeRecordedCalls flattens every classifier slot of a unified trail`() {
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

    val calls = recordedCalls(doc)

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
    val calls = recordedCalls(doc)
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
  fun `attributeRecordedCalls flattens a single-classifier unified trail with no trailhead`() {
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
      """.trimIndent(),
    )

    val calls = recordedCalls(doc)

    // No trailhead → the first trail step is step 1 (trailhead is the reserved step 0).
    assertEquals(2, calls.size)
    assertEquals(listOf(1, 2), calls.map { it.stepIndex })
    assertEquals(listOf("tapOn", "assertVisible"), calls.map { it.toolName })
    assertTrue(calls.all { it.classifier == "android" }, "each recorded call carries its classifier slot")
  }

  // ── Arg type coercion (the #4179 arg-boundary class) ───────────────────────────────────────

  /**
   * A quoted numeric/boolean recording value decodes to a JSON number/boolean (kaml drops the
   * quote style). With the tool's descriptor declaring those params `string`, extraction re-aligns
   * them to strings before transpiling — the same coercion replay applies — so `tsc` no longer sees
   * `number not assignable to string` on a faithfully-recorded trail.
   */
  @Test
  fun `attributeRecordedCalls coerces number and boolean args to strings for string-typed params`() {
    val doc = yaml.decodeTrailDocument(
      """
      config:
        target: demo
      trail:
      - step: Set the flag
        recording:
          android:
          - setFeatureFlag:
              passcode: 12345678
              enabled: true
      """.trimIndent(),
    )
    val descriptors = mapOf(
      "setFeatureFlag" to TrailblazeToolDescriptor(
        name = "setFeatureFlag",
        requiredParameters = listOf(
          TrailblazeToolParameterDescriptor(name = "passcode", type = "string"),
          TrailblazeToolParameterDescriptor(name = "enabled", type = "string"),
        ),
      ),
    )

    val call = recordedCalls(doc, descriptors).single()

    // Both scalars are now quoted strings in the transpiled args literal.
    assertTrue(call.argsJson.contains("\"passcode\":\"12345678\""), "passcode coerced: ${call.argsJson}")
    assertTrue(call.argsJson.contains("\"enabled\":\"true\""), "enabled coerced: ${call.argsJson}")
  }

  /**
   * With no descriptor known for a tool (missing sidecar / unmodeled tool), extraction leaves the
   * raw decoded args untouched — the pre-sidecar behavior. This is the default overload the pure
   * codegen tests rely on.
   */
  @Test
  fun `attributeRecordedCalls leaves args untouched when no descriptor is known`() {
    val doc = yaml.decodeTrailDocument(
      """
      config:
        target: demo
      trail:
      - step: Set the flag
        recording:
          android:
          - setFeatureFlag:
              passcode: 12345678
      """.trimIndent(),
    )

    val call = recordedCalls(doc).single()

    // Unquoted number preserved — no coercion without a descriptor.
    assertTrue(call.argsJson.contains("\"passcode\":12345678"), "raw number preserved: ${call.argsJson}")
  }

  /**
   * The coercion is attributed to the recorded classifier slot it came from — extraction threads
   * the descriptors through the unified flattening path and preserves the slot on each call.
   */
  @Test
  fun `attributeRecordedCalls coerces args and attributes them to the classifier slot`() {
    val doc = yaml.decodeTrailDocument(
      """
      config:
        target: demo
      trail:
      - step: Set the flag
        recording:
          android:
          - setFeatureFlag:
              passcode: 12345678
              enabled: true
      """.trimIndent(),
    )
    val descriptors = mapOf(
      "setFeatureFlag" to TrailblazeToolDescriptor(
        name = "setFeatureFlag",
        requiredParameters = listOf(
          TrailblazeToolParameterDescriptor(name = "passcode", type = "string"),
          TrailblazeToolParameterDescriptor(name = "enabled", type = "string"),
        ),
      ),
    )

    val call = recordedCalls(doc, descriptors).single()

    assertEquals("android", call.classifier, "the coerced call keeps its classifier slot")
    assertTrue(call.argsJson.contains("\"passcode\":\"12345678\""), "passcode coerced: ${call.argsJson}")
    assertTrue(call.argsJson.contains("\"enabled\":\"true\""), "enabled coerced: ${call.argsJson}")
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
    File(trailsRoot, "notes.yaml").writeText("just: notes")

    // No trailmap surfaces loaded, so nothing is staged and no tsc is spawned — this exercises
    // discovery + target extraction through the real entry point.
    val report = TrailTscValidator.validate(
      trailsRoot = trailsRoot,
      trailmaps = emptyList(),
      jsRuntime = "bun",
      tscJs = File(trailsRoot, "unused-tsc.js").toPath(),
    )

    assertEquals(2, report.trailsDiscovered, "bare trail.yaml and *.trail.yaml discovered; notes.yaml ignored")
    assertTrue(report.errors.isEmpty(), "no load errors: ${report.errors}")
    // `demo` isn't in knownManifestTargets (none passed), so it's a permanent no-manifest skip,
    // never a missing-surface one.
    assertEquals(mapOf("demo" to 2), report.skippedNoManifest, "no-manifest target: extracted from both files")
    assertTrue(report.skippedNoSurface.isEmpty(), "a manifest-less target must not read as missing-surface")
  }

  @Test
  fun `validate classifies a no-surface target as missing-surface only when its manifest is known`() {
    // Two trails, two targets. `hasManifest` is in knownManifestTargets (a manifest exists, its
    // surface just wasn't loaded this run); `noManifest` is not, so it can never be validated.
    val trailsRoot = tmp.newFolder("trails")
    File(trailsRoot, "a.trail.yaml").writeText(
      """
      config:
        target: hasManifest
      trail:
      - step: Do a thing
        recording:
          android:
          - demoTap:
              text: X
      """.trimIndent(),
    )
    File(trailsRoot, "b.trail.yaml").writeText(
      """
      config:
        target: noManifest
      trail:
      - step: Do a thing
        recording:
          android:
          - demoTap:
              text: X
      """.trimIndent(),
    )

    val report = TrailTscValidator.validate(
      trailsRoot = trailsRoot,
      trailmaps = emptyList(),
      jsRuntime = "bun",
      tscJs = File(trailsRoot, "unused-tsc.js").toPath(),
      knownManifestTargets = setOf("hasManifest"),
    )

    assertEquals(mapOf("hasManifest" to 1), report.skippedNoSurface, "known manifest, surface not loaded")
    assertEquals(mapOf("noManifest" to 1), report.skippedNoManifest, "no manifest anywhere → permanent skip")
  }

  // ── Per-device targets in a multi-device configuration leg ─────────────────────────────────

  /**
   * The shape the whole per-device pass exists for: one configuration leg, two members, each with
   * its own `target:`, handed back and forth by recorded `switchDevice` calls.
   */
  private fun pairedTrail(
    sessionTarget: String? = "storefront",
    registerTarget: String? = "storefront",
    kitchenTarget: String? = "kitchen",
  ) = yaml.decodeTrailDocument(
    """
    config:
      ${sessionTarget?.let { "target: $it" } ?: "id: pair"}
      devices:
        register-kitchen:
          devices:
            register:
              classifier: tablet-a
              ${registerTarget?.let { "target: $it" } ?: ""}
            kitchen:
              classifier: android-tablet
              ${kitchenTarget?.let { "target: $it" } ?: ""}
    trail:
    - step: Bring the secondary display up, then hand back to the seller station
      recording:
        register-kitchen:
        - switchDevice:
            name: kitchen
        - kitchen_launchApp: {}
        - switchDevice:
            name: register
        - register_tapOnItem:
            text: Mozzarella Sticks
    """.trimIndent(),
  )

  @Test
  fun `attributeRecordedCalls checks each configuration member's calls against that member's own target`() {
    val calls = TrailTscValidator.attributeRecordedCalls(pairedTrail()).calls

    // The kitchen device's tool sits between the two handovers, so it — and only it — is checked
    // against `kitchen`; every other call belongs to the start device. A single-target pass would
    // red on `kitchen_launchApp`, which does not exist on the `storefront` surface.
    assertEquals(
      listOf("register" to "storefront", "kitchen" to "kitchen", "kitchen" to "kitchen", "register" to "storefront"),
      calls.map { it.deviceName to it.target },
    )
    assertEquals(
      listOf("switchDevice", "kitchen_launchApp", "switchDevice", "register_tapOnItem"),
      calls.map { it.tool.name },
    )
    assertEquals(0, TrailTscValidator.attributeRecordedCalls(pairedTrail()).undeterminedDeviceCalls)
  }

  @Test
  fun `attributeRecordedCalls falls back to the session target for a member declaring none`() {
    val calls = TrailTscValidator.attributeRecordedCalls(pairedTrail(kitchenTarget = null)).calls
    // The member with no override inherits `config.target:` — the same rule
    // MultiDeviceConfigurationResolver.resolveMemberTargets applies at run time.
    assertEquals(setOf("storefront"), calls.map { it.target }.toSet())
  }

  @Test
  fun `attributeRecordedCalls treats a classifier-keyed leg as the sole configuration's fallback leg`() {
    // A trail declaring exactly one configuration always binds it, so its FIRST member is the launch
    // device — and a classifier-keyed leg lowers onto that device, not onto the session default.
    val doc = yaml.decodeTrailDocument(
      """
      config:
        target: storefront
        devices:
          register-kitchen:
            devices:
              register:
                classifier: tablet-a
                target: register-only
              kitchen:
                classifier: android-tablet
                target: kitchen
      trail:
      - step: Do a thing on whichever device launched
        recording:
          android:
          - demoTap:
              text: X
      """.trimIndent(),
    )

    val call = TrailTscValidator.attributeRecordedCalls(doc).calls.single()

    assertEquals("register-only", call.target)
    assertEquals("register", call.deviceName, "the fallback leg runs on the configuration's start device")
  }

  @Test
  fun `attributeRecordedCalls replays a handover recorded in a classifier-keyed fallback leg`() {
    // The fallback leg is the SAME session, so a `switchDevice` in it reroutes the tools after it —
    // the runtime resolves `activeAgent()` per recorded tool regardless of which key matched. Reading
    // the whole leg as the launch device's would check the companion's tools against the wrong
    // surface.
    val doc = yaml.decodeTrailDocument(
      """
      config:
        target: storefront
        devices:
          register-kitchen:
            devices:
              register:
                classifier: tablet-a
              kitchen:
                classifier: android-tablet
                target: kitchen
      trail:
      - step: Hand over to the secondary display from a leg keyed by classifier
        recording:
          android:
          - switchDevice:
              name: kitchen
          - kitchen_launchApp: {}
      """.trimIndent(),
    )

    val calls = TrailTscValidator.attributeRecordedCalls(doc).calls

    assertEquals(
      listOf("register" to "storefront", "kitchen" to "kitchen"),
      calls.map { it.deviceName to it.target },
    )
  }

  @Test
  fun `attributeRecordedCalls resumes at a literal handover after losing the device`() {
    // `switchTo(name)` is unconditional at run time, so a top-level switch to a declared member
    // decides the device no matter what preceded it. The replay can therefore recover: only the
    // calls BETWEEN the unresolvable handover and this one are undecidable.
    val doc = yaml.decodeTrailDocument(
      """
      config:
        target: storefront
        devices:
          register-kitchen:
            devices:
              register:
                classifier: tablet-a
              kitchen:
                classifier: android-tablet
                target: kitchen
      trail:
      - step: Lose the device, then take it back with a literal handover
        recording:
          register-kitchen:
          - switchDevice:
              name: ${'$'}{nextDevice}
          - demoTap:
              text: X
          - switchDevice:
              name: kitchen
          - kitchen_launchApp: {}
      """.trimIndent(),
    )

    val attribution = TrailTscValidator.attributeRecordedCalls(doc)

    // The interpolated switch is attributed to the device that dispatched it; `demoTap` and the
    // literal switch both dispatch on the unknown device, so they stay uncounted on this
    // mixed-target configuration. `kitchen_launchApp` is decided again.
    assertEquals(
      listOf("switchDevice" to "register", "kitchen_launchApp" to "kitchen"),
      attribution.calls.map { it.tool.name to it.deviceName },
    )
    assertEquals("kitchen", attribution.calls.last().target)
    assertEquals(2, attribution.undeterminedDeviceCalls)
  }

  @Test
  fun `attributeRecordedCalls does not resume on an undeclared handover name`() {
    // Recovery is only sound when the name is statically readable AND declared. An undeclared name
    // resolves to nothing the replay can follow, so the device stays unknown.
    val doc = yaml.decodeTrailDocument(
      """
      config:
        target: storefront
        devices:
          register-kitchen:
            devices:
              register:
                classifier: tablet-a
              kitchen:
                classifier: android-tablet
                target: kitchen
      trail:
      - step: Hand off twice, the second to a device the configuration never declared
        recording:
          register-kitchen:
          - switchDevice:
              name: ${'$'}{nextDevice}
          - switchDevice:
              name: pantry
          - demoTap:
              text: X
      """.trimIndent(),
    )

    val attribution = TrailTscValidator.attributeRecordedCalls(doc)

    assertEquals(listOf("switchDevice"), attribution.calls.map { it.tool.name })
    assertEquals(2, attribution.undeterminedDeviceCalls)
  }

  @Test
  fun `attributeRecordedCalls does not let a losing slot's handover decide the next step`() {
    // Both slots are declared on step 1, so the lowering picks ONE — the configuration-keyed slot,
    // which heads the resolution chain. The `android:` slot never runs on that path, so its handover
    // must not decide which surface step 2 is checked against. Replaying the two as a sequence would
    // leave the session on `kitchen` and check `register_tapOnItem` against the kitchen surface.
    val doc = yaml.decodeTrailDocument(
      """
      config:
        target: storefront
        devices:
          register-kitchen:
            devices:
              register:
                classifier: tablet-a
              kitchen:
                classifier: android-tablet
                target: kitchen
      trail:
      - step: Two alternative slots, only one of which a run executes
        recording:
          register-kitchen:
          - register_tapOnItem:
              text: Mozzarella Sticks
          android:
          - switchDevice:
              name: kitchen
      - step: The step after runs on whatever the WINNING slot left
        recording:
          register-kitchen:
          - register_tapOnItem:
              text: Fries
      """.trimIndent(),
    )

    val calls = TrailTscValidator.attributeRecordedCalls(doc).calls

    assertEquals(
      listOf("register" to "storefront", "register" to "storefront", "register" to "storefront"),
      calls.map { it.deviceName to it.target },
    )
  }

  @Test
  fun `attributeRecordedCalls replays each slot of a step from the device the step started on`() {
    // Same alternatives rule seen from the other side: the `android:` slot must not inherit the
    // handover the configuration-keyed slot performed, because in any single run only one of them
    // executed. Both start from the step's entry device.
    val doc = yaml.decodeTrailDocument(
      """
      config:
        target: storefront
        devices:
          register-kitchen:
            devices:
              register:
                classifier: tablet-a
              kitchen:
                classifier: android-tablet
                target: kitchen
      trail:
      - step: Alternative slots, one of which hands over
        recording:
          register-kitchen:
          - switchDevice:
              name: kitchen
          - kitchen_launchApp: {}
          android:
          - register_tapOnItem:
              text: Mozzarella Sticks
      """.trimIndent(),
    )

    val calls = TrailTscValidator.attributeRecordedCalls(doc).calls

    assertEquals(
      listOf(
        "switchDevice" to ("register" to "storefront"),
        "kitchen_launchApp" to ("kitchen" to "kitchen"),
        "register_tapOnItem" to ("register" to "storefront"),
      ),
      calls.map { it.tool.name to (it.deviceName to it.target) },
    )
  }

  @Test
  fun `attributeRecordedCalls carries the active device between a configuration leg and a fallback leg`() {
    // The two legs are one session: the configuration-keyed leg hands over, and the classifier-keyed
    // leg that follows inherits that device. Keying the active-device state by the leg's classifier
    // rather than by the configuration would give the fallback leg its own state and lose the
    // handover.
    val doc = yaml.decodeTrailDocument(
      """
      config:
        target: storefront
        devices:
          register-kitchen:
            devices:
              register:
                classifier: tablet-a
              kitchen:
                classifier: android-tablet
                target: kitchen
      trail:
      - step: Hand over on the configuration-keyed leg
        recording:
          register-kitchen:
          - switchDevice:
              name: kitchen
      - step: Keep driving the same device from a classifier-keyed leg
        recording:
          android:
          - kitchen_launchApp: {}
      """.trimIndent(),
    )

    val calls = TrailTscValidator.attributeRecordedCalls(doc).calls

    assertEquals(
      listOf("register" to "storefront", "kitchen" to "kitchen"),
      calls.map { it.deviceName to it.target },
    )
  }

  @Test
  fun `attributeRecordedCalls counts calls it cannot attribute instead of guessing a device`() {
    val doc = yaml.decodeTrailDocument(
      """
      config:
        target: storefront
        devices:
          register-kitchen:
            devices:
              register:
                classifier: tablet-a
              kitchen:
                classifier: android-tablet
                target: kitchen
      trail:
      - step: Hand off to a device this gate cannot resolve statically
        recording:
          register-kitchen:
          - switchDevice:
              name: ${'$'}{nextDevice}
          - demoTap:
              text: X
          - demoTap:
              text: Y
      """.trimIndent(),
    )

    val attribution = TrailTscValidator.attributeRecordedCalls(doc)

    // The handover itself still belongs to the device that dispatched it; the two calls after it
    // are dropped rather than attributed to a member the memory-interpolated name may have left.
    assertEquals(listOf("switchDevice"), attribution.calls.map { it.tool.name })
    assertEquals(2, attribution.undeterminedDeviceCalls)
  }

  @Test
  fun `attributeRecordedCalls keeps a handover in force in the next step`() {
    // Which device is active is SESSION state: the runtime's SessionDeviceBindings.activeName is not
    // reset per step, so a trail that hands over in one step and records the companion's tools in
    // the next — without a redundant re-switch — must still be checked against the companion.
    val doc = yaml.decodeTrailDocument(
      """
      config:
        target: storefront
        devices:
          register-kitchen:
            devices:
              register:
                classifier: tablet-a
              kitchen:
                classifier: android-tablet
                target: kitchen
      trail:
      - step: Hand the session to the secondary display
        recording:
          register-kitchen:
          - switchDevice:
              name: kitchen
      - step: Drive the secondary display, still holding the session from the step before
        recording:
          register-kitchen:
          - kitchen_launchApp: {}
      """.trimIndent(),
    )

    val calls = TrailTscValidator.attributeRecordedCalls(doc).calls

    assertEquals(
      listOf("register" to "storefront", "kitchen" to "kitchen"),
      calls.map { it.deviceName to it.target },
    )
  }

  @Test
  fun `attributeRecordedCalls does not recover a lost device on the next leg`() {
    // Once the replay cannot say which device is active, the step ending does not make it knowable
    // again — SelectorDialectLint abandons the whole configuration at the same point.
    val doc = yaml.decodeTrailDocument(
      """
      config:
        target: storefront
        devices:
          register-kitchen:
            devices:
              register:
                classifier: tablet-a
              kitchen:
                classifier: android-tablet
                target: kitchen
      trail:
      - step: Hand off to a device this gate cannot resolve statically
        recording:
          register-kitchen:
          - switchDevice:
              name: ${'$'}{nextDevice}
      - step: Keep going on whichever device that left the session on
        recording:
          register-kitchen:
          - demoTap:
              text: X
      """.trimIndent(),
    )

    val attribution = TrailTscValidator.attributeRecordedCalls(doc)

    assertEquals(listOf("switchDevice"), attribution.calls.map { it.tool.name })
    assertEquals(1, attribution.undeterminedDeviceCalls)
  }

  @Test
  fun `attributeRecordedCalls keeps checking past a lost handover when every member shares a target`() {
    // The common multi-device shape: both members run the same app surface. Which one is active is
    // then irrelevant to WHICH surface applies, so an unresolvable handover costs the device name
    // and nothing else — dropping the rest of the leg would forfeit most of this gate's coverage.
    val doc = yaml.decodeTrailDocument(
      """
      config:
        target: storefront
        devices:
          sender-receiver:
            devices:
              sender:
                classifier: tablet-a
              receiver:
                classifier: android-tablet
      trail:
      - step: Hand off to a device this gate cannot resolve statically
        recording:
          sender-receiver:
          - switchDevice:
              name: ${'$'}{nextDevice}
          - demoTap:
              text: X
      """.trimIndent(),
    )

    val attribution = TrailTscValidator.attributeRecordedCalls(doc)

    assertEquals(listOf("switchDevice", "demoTap"), attribution.calls.map { it.tool.name })
    assertEquals(
      listOf("sender" to "storefront", null to "storefront"),
      attribution.calls.map { it.deviceName to it.target },
    )
    assertEquals(0, attribution.undeterminedDeviceCalls)
  }

  @Test
  fun `validate stages a mixed-target trail against each member's own trailmap`() {
    val trailsRoot = tmp.newFolder("trails")
    // Trailmap dirs with no `tools/tsconfig.json`: staging still happens (that check runs after),
    // and each staged-into trailmap reports itself in `errors` — which is how this test observes
    // that the trail reached BOTH surfaces rather than being resolved against one.
    val storefrontMap = tmp.newFolder("trailmaps", "storefront")
    val kitchenMap = tmp.newFolder("trailmaps", "kitchen")
    File(trailsRoot, "pair").mkdirs()
    File(trailsRoot, "pair/trail.yaml").writeText(
      """
      config:
        target: storefront
        devices:
          register-kitchen:
            devices:
              register:
                classifier: tablet-a
              kitchen:
                classifier: android-tablet
                target: kitchen
      trail:
      - step: Bring the secondary display up
        recording:
          register-kitchen:
          - switchDevice:
              name: kitchen
          - kitchen_launchApp: {}
      """.trimIndent(),
    )

    val report = TrailTscValidator.validate(
      trailsRoot = trailsRoot,
      trailmaps = listOf(storefrontMap.toPath(), kitchenMap.toPath()),
      jsRuntime = "bun",
      tscJs = File(trailsRoot, "unused-tsc.js").toPath(),
    )

    assertEquals(
      setOf("storefront", "kitchen"),
      report.errors.map { it.substringBefore(":") }.toSet(),
      "both members' trailmaps were staged into: ${report.errors}",
    )
  }

  @Test
  fun `validate splits a mixed-target trail's skip buckets by member target`() {
    val trailsRoot = tmp.newFolder("trails")
    File(trailsRoot, "pair.trail.yaml").writeText(
      """
      config:
        target: hasManifest
        devices:
          register-kitchen:
            devices:
              register:
                classifier: tablet-a
              kitchen:
                classifier: android-tablet
                target: noManifest
      trail:
      - step: Bring the secondary display up
        recording:
          register-kitchen:
          - switchDevice:
              name: kitchen
          - demoTap:
              text: X
      """.trimIndent(),
    )

    val report = TrailTscValidator.validate(
      trailsRoot = trailsRoot,
      trailmaps = emptyList(),
      jsRuntime = "bun",
      tscJs = File(trailsRoot, "unused-tsc.js").toPath(),
      knownManifestTargets = setOf("hasManifest"),
    )

    // One trail, two targets: the buckets are counted per trail-and-target, so a member whose target
    // can never be validated no longer drags the other member's target out of the gate with it.
    assertEquals(mapOf("hasManifest" to 1), report.skippedNoSurface)
    assertEquals(mapOf("noManifest" to 1), report.skippedNoManifest)
  }
}
