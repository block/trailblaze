package xyz.block.trailblaze.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import picocli.CommandLine
import xyz.block.trailblaze.TrailblazeVersion
import xyz.block.trailblaze.host.WorkspaceTypeScriptSetup
import xyz.block.trailblaze.usages.ChangedSinceSummary
import xyz.block.trailblaze.usages.ToolUsageResult
import xyz.block.trailblaze.usages.ToolUsagesReport
import xyz.block.trailblaze.usages.UsagesDiagnostic
import xyz.block.trailblaze.util.Console

/**
 * Behavioral contract of `trailblaze usages`: the JSON report names the trails whose RECORDINGS
 * invoke the queried tool with their classifier keys, zero usages is a first-class answer, and an
 * unparseable trail surfaces as a warning (an incomplete report must say so) instead of being
 * silently skipped.
 */
class UsagesCommandTest {

  private val trailsDir: File = createTempDirectory("trailblaze-usages-test").toFile()

  /** A second, unrelated trails root — the shape `--trails` is repeatable for. */
  private val secondRoot: File = createTempDirectory("trailblaze-usages-test-second").toFile()

  @AfterTest fun cleanup() {
    trailsDir.deleteRecursively()
    secondRoot.deleteRecursively()
  }

  @Test
  fun `reports the trails and classifier keys whose recordings invoke the tool`() {
    writeTrail(
      "android-only/case_1.trail.yaml",
      """
      config:
        id: case_1
        title: Android add item
      trail:
        - step: Add an item
          recording:
            android:
              - demo_addItem: {}
      """,
    )
    writeTrail(
      "cross-platform/case_2.trail.yaml",
      """
      config:
        id: case_2
        skip:
          ios: blocked on an upstream bug
      trail:
        - step: Add an item on both platforms
          recording:
            android:
              - demo_addItem: {}
            ios-iphone:
              - demo_addItem: {}
        - step: Mention demo_addItem in text only
          recording:
            android:
              - demo_checkout: {}
      """,
    )
    writeTrail(
      "ios-only/case_3.trail.yaml",
      """
      config:
        id: case_3
      trail:
        - step: Something unrelated
          recording:
            ios-iphone:
              - demo_signOut: {}
      """,
    )

    val report = runForReport("demo_addItem", "demo_neverUsed")

    assertTrue(report.warnings.isEmpty(), "no warnings expected: ${report.warnings}")
    assertEquals(listOf("demo_addItem", "demo_neverUsed"), report.tools.map { it.tool })

    val addItem = report.tools.first { it.tool == "demo_addItem" }
    assertEquals(
      setOf("android-only/case_1", "cross-platform/case_2"),
      addItem.usages.map { it.trail }.toSet(),
      "the ios-only trail never invokes the tool and must not be implicated",
    )
    val crossPlatform = addItem.usages.first { it.trail == "cross-platform/case_2" }
    assertEquals(
      trailsDir.absolutePath,
      crossPlatform.root,
      "each usage names the root it was found under, so extras-root hits stay distinguishable",
    )
    assertEquals(listOf("android", "ios-iphone"), crossPlatform.classifiers)
    assertEquals(
      listOf(0),
      crossPlatform.steps.map { it.stepIndex },
      "a text-only mention in another step is not a usage",
    )
    assertEquals(mapOf("ios" to "blocked on an upstream bug"), crossPlatform.skip)

    val neverUsed = report.tools.first { it.tool == "demo_neverUsed" }
    assertTrue(neverUsed.usages.isEmpty(), "zero usages is a first-class answer")
  }

  @Test
  fun `a trailhead invocation reports with a null step index`() {
    writeTrail(
      "case_th.trail.yaml",
      """
      config:
        id: case_th
      trailhead:
        step: Launch signed in
        recording:
          all:
            demo_launchSignedIn: {}
      trail:
        - step: Do something
          recording:
            android:
              - demo_tap: {}
      """,
    )

    val report = runForReport("demo_launchSignedIn")

    val usage = report.tools.single().usages.single()
    val step = usage.steps.single()
    assertEquals(null, step.stepIndex)
    assertEquals(listOf("all"), step.classifiers)
  }

  @Test
  fun `an unparseable trail becomes a warning so the report admits incompleteness`() {
    writeTrail(
      "good.trail.yaml",
      """
      config:
        id: good
      trail:
        - step: Fine
          recording:
            android:
              - demo_tap: {}
      """,
    )
    File(trailsDir, "broken.trail.yaml").writeText("- this is the retired v1 list-root shape\n")

    val report = runForReport("demo_tap")

    assertEquals(1, report.tools.single().usages.size)
    assertTrue(
      report.warnings.any { it.contains("broken.trail.yaml") },
      "the unscannable file must be named: ${report.warnings}",
    )
    val diagnostic = report.diagnostics.single()
    assertEquals(UsagesDiagnostic.TRAIL_UNPARSEABLE, diagnostic.kind)
    assertTrue(
      diagnostic.subject.endsWith("broken.trail.yaml"),
      "the subject is the file itself, so a consumer can act on it without parsing the message: $diagnostic",
    )
  }

  @Test
  fun `warnings is exactly the incompleteness diagnostics' messages, so the two can never disagree`() {
    // The failure this forbids: a human reading `warnings` is told the scan was clean while a
    // machine reading `diagnostics` is told otherwise (or vice versa). Asserted on a report
    // carrying BOTH kinds at once, because a per-site check cannot see the two lists diverging.
    // The hint-severity half of the same invariant is covered by the fail-open gate test below.
    writeTrail(
      "good.trail.yaml",
      """
      config:
        id: good
      trail:
        - step: Fine
          recording:
            android:
              - demo_tap: {}
      """,
    )
    File(trailsDir, "broken.trail.yaml").writeText("- this is the retired v1 list-root shape\n")
    val gone = File(trailsDir, "moved-away")

    val report = UsagesCommand().buildReport(listOf("demo_tap"), trailsDir, listOf(gone))

    assertEquals(
      report.diagnostics.filter { it.severity == UsagesDiagnostic.INCOMPLETENESS }.map { it.message },
      report.warnings,
    )
    assertTrue(
      report.diagnostics.all { it.severity == UsagesDiagnostic.INCOMPLETENESS },
      "both of these ARE incompleteness, so this report's two lists must match entry for entry",
    )
    assertEquals(
      setOf(UsagesDiagnostic.ROOT_UNSCANNED, UsagesDiagnostic.TRAIL_UNPARSEABLE),
      report.diagnostics.map { it.kind }.toSet(),
      "an unread ROOT hides a whole subtree and one unparseable TRAIL hides one file — a consumer " +
        "that must fail the build on the first and annotate the second needs them told apart",
    )
  }

  @Test
  fun `a named tool reports where it is declared, and an undeclared one says so`() {
    // Workspace-shaped: trails root with trailmaps above it, holding one real scripted tool. The
    // point is the pair of answers — a resolvable name gets its source path, and a name the
    // inventory never heard of gets a diagnostic, because otherwise both look like "zero usages"
    // and one of them is a typo that greenlights deleting the wrong thing.
    val workspace = File(trailsDir, "ws").apply { mkdirs() }
    val trails = File(workspace, "trails").apply { mkdirs() }
    val toolsDir = File(workspace, "trailblaze-config/trailmaps/demo-map/tools").apply { mkdirs() }
    val script = File(toolsDir, "addItem.ts").apply { writeText("export function demo_addItem() {}\n") }
    File(toolsDir, "addItem.yaml").writeText(
      """
      name: demo_addItem
      script: ./addItem.ts
      """.trimIndent(),
    )
    writeTrail(
      "ws/trails/case_a.trail.yaml",
      """
      config:
        id: case_a
      trail:
        - step: Add
          recording:
            android:
              - demo_addItem: {}
        - step: Tap
          recording:
            android:
              - demo_tap: {}
      """,
    )

    val report = runForReport(listOf(trails), "demo_addItem", "demo_addItm", "demo_tap")

    val declared = report.tools.first { it.tool == "demo_addItem" }
    assertEquals(listOf(script.absolutePath), declared.sourcePaths, "diagnostics: ${report.diagnostics}")
    assertEquals(ToolUsageResult.NAMED, declared.changeKind, "the caller named it; nothing derived it")
    assertEquals(1, declared.usages.size)

    val typo = report.tools.first { it.tool == "demo_addItm" }
    assertTrue(typo.usages.isEmpty() && typo.sourcePaths.isEmpty())
    val diagnostic = report.diagnostics.single { it.kind == UsagesDiagnostic.TOOL_NOT_IN_SCRIPTED_INVENTORY }
    assertEquals(
      "demo_addItm",
      diagnostic.subject,
      "only the name with NOTHING to show for itself: `demo_tap` is also absent from the scripted " +
        "inventory, but a trail demonstrably invokes it, so saying so is noise: ${report.diagnostics}",
    )
    assertEquals(1, report.tools.first { it.tool == "demo_tap" }.usages.size)
    assertTrue(
      report.diagnostics.none { it.subject == "demo_addItem" },
      "the tool that IS declared must not be flagged: ${report.diagnostics}",
    )
  }

  @Test
  fun `a tool entry written before changeKind existed decodes as unknown, not as named`() {
    // The additive-field trap, in its worst form: an absent field that defaults to a REAL value
    // rather than an empty one. `named` would make an older `--changed-since` report present its
    // removed tools — whose usages are broken trails — as merely caller-named, and a consumer
    // reading the per-tool field has no way to notice.
    val legacy = """
      {
        "schemaVersion": 1,
        "trailsRoot": "/tmp/trails",
        "tools": [{ "tool": "demo_gone", "usages": [] }],
        "changedSince": {
          "ref": "HEAD~1",
          "resolvedSha": "abc123",
          "removed": ["demo_gone"]
        }
      }
    """.trimIndent()

    val report = Json { ignoreUnknownKeys = true }
      .decodeFromString(ToolUsagesReport.serializer(), legacy)

    assertEquals(ToolUsageResult.UNKNOWN, report.tools.single().changeKind)
    assertEquals(
      listOf("demo_gone"),
      report.changedSince?.removed,
      "the only place this report says what happened to the tool, which is why `unknown` has to " +
        "send the consumer here instead of answering for it",
    )
  }

  @Test
  fun `an all-keyed invocation is shadowed on a device whose step declares a more specific key`() {
    // The trap the field exists for. Both devices see a step keyed `all:` that invokes the tool —
    // but the iPhone's closest match is its OWN `ios-iphone:` recording, which does not. Reading
    // `classifiers` (`[all]`) and concluding "runs everywhere" would replay an iOS lane that never
    // touches the tool, and — worse in the other direction — a REMOVED tool would be reported as
    // breaking an iOS leg it was never on.
    writeTrail(
      "shadowed.trail.yaml",
      """
      config:
        id: shadowed
        devices:
          android-phone: {}
          ios-iphone: {}
      trail:
        - step: Add an item
          recording:
            all:
              - demo_addItem: {}
            ios-iphone:
              - demo_signOut: {}
      """,
    )

    val usage = runForReport("demo_addItem").tools.single().usages.single()

    assertEquals(listOf("all"), usage.classifiers, "the authored key is unchanged — raw facts stay raw")
    assertEquals(
      setOf("android-phone", "ios-iphone", "all"),
      usage.devices.toSet(),
      "the denominator is every classifier the trail declares direction for",
    )
    assertEquals(
      setOf("android-phone", "all"),
      usage.invokingDevices.toSet(),
      "the iPhone's closest declared key is its own non-invoking recording, so it never reaches " +
        "the tool — which is exactly what set membership in `classifiers` cannot tell you",
    )
  }

  @Test
  fun `a device whose closest key does invoke is reported as reaching the tool`() {
    // The other half of the pair: same shape, but now the iPhone's own recording IS the invoking
    // one. Without this, an implementation that simply dropped every device with a more specific
    // key would pass the shadowing test above while being wrong.
    writeTrail(
      "specific.trail.yaml",
      """
      config:
        id: specific
        devices:
          android-phone: {}
          ios-iphone: {}
      trail:
        - step: Add an item
          recording:
            all:
              - demo_signOut: {}
            ios-iphone:
              - demo_addItem: {}
      """,
    )

    val usage = runForReport("demo_addItem").tools.single().usages.single()

    assertEquals(
      setOf("ios-iphone"),
      usage.invokingDevices.toSet(),
      "only the device whose own recording invokes it: the android phone falls back to `all:`, " +
        "which does not",
    )
  }

  @Test
  fun `a broader family key covers a more specific declared device`() {
    // `android-phone` string-derives to `android`, so an `android:`-keyed invocation reaches it even
    // though no `android-phone:` recording exists. A consumer that matched device keys literally
    // against `classifiers` would report this trail as reaching NO declared device.
    writeTrail(
      "family.trail.yaml",
      """
      config:
        id: family
        devices:
          android-phone: {}
          ios-iphone: {}
      trail:
        - step: Add an item
          recording:
            android:
              - demo_addItem: {}
      """,
    )

    val usage = runForReport("demo_addItem").tools.single().usages.single()

    assertEquals(
      setOf("android-phone", "android"),
      usage.invokingDevices.toSet(),
      "the iPhone is declared but has nothing to resolve to: ${usage.devices}",
    )
  }

  @Test
  fun `a tool with no usages carries no device claim at all`() {
    // Empty must mean "nothing reached", never "not computed" — a consumer treating the two the
    // same would silently skip every lane for a tool it could not resolve.
    writeTrail(
      "unrelated.trail.yaml",
      """
      config:
        id: unrelated
        devices:
          android-phone: {}
      trail:
        - step: Something else
          recording:
            android:
              - demo_signOut: {}
      """,
    )

    val result = runForReport("demo_addItem").tools.single()

    assertTrue(result.usages.isEmpty())
  }

  @Test
  fun `every report names the CLI that produced it`() {
    // schemaVersion says which FIELDS to expect, not which behavior produced the values. An
    // archived report whose numbers a triager distrusts has to be attributable to a build, or the
    // only remaining move is to re-run and hope.
    writeTrail(
      "case_v.trail.yaml",
      """
      config:
        id: case_v
      trail:
        - step: Tap
          recording:
            android:
              - demo_tap: {}
      """,
    )

    val report = runForReport("demo_tap")

    assertEquals(TrailblazeVersion.displayVersion, report.generatedBy)
    assertTrue(
      !report.generatedBy.isNullOrBlank(),
      "a null or blank value is the same as not having the field: ${report.generatedBy}",
    )
  }

  @Test
  fun `with no workspace above the trails root, no tool is accused of being a typo`() {
    // The plain-directory case: `--trails /some/dir` with no trailmaps anywhere above it. There is
    // no inventory to be absent from, so flagging every queried name would make the hint noise
    // exactly where it knows least.
    writeTrail(
      "case_x.trail.yaml",
      """
      config:
        id: case_x
      trail:
        - step: Tap
          recording:
            android:
              - demo_tap: {}
      """,
    )

    val report = runForReport("demo_tap", "demo_whoKnows")

    assertTrue(
      report.diagnostics.none { it.kind == UsagesDiagnostic.TOOL_NOT_IN_SCRIPTED_INVENTORY },
      "an unreadable inventory must stay silent, not accuse: ${report.diagnostics}",
    )
    assertTrue(report.tools.all { it.sourcePaths.isEmpty() })
  }

  @Test
  fun `a derived tool set carries its change kind, and a removed tool is not called a typo`() {
    // `--changed-since` needs git and esbuild, so the derivation itself is exercised end to end
    // elsewhere; what is asserted here is the wiring it feeds, which is where the trap is. A REMOVED
    // tool is absent from the current inventory BY DEFINITION — it was just deleted — so a
    // typo hint keyed on "absent from the inventory" would accuse the very deletion being analysed.
    writeTrail(
      "case_r.trail.yaml",
      """
      config:
        id: case_r
      trail:
        - step: Add
          recording:
            android:
              - demo_gone: {}
      """,
    )

    val report = UsagesCommand().buildReport(
      listOf("demo_gone", "demo_edited"),
      trailsDir,
      emptyList(),
      UsagesCommand.ToolAttribution(
        changeKinds = mapOf(
          "demo_gone" to ToolUsageResult.REMOVED,
          "demo_edited" to ToolUsageResult.MODIFIED,
        ),
        scriptedToolPaths = mapOf("demo_edited" to listOf("/ws/trailmaps/m/tools/edited.ts")),
        flagNamesAbsentFromInventory = false,
      ),
    )

    val gone = report.tools.first { it.tool == "demo_gone" }
    assertEquals(ToolUsageResult.REMOVED, gone.changeKind)
    assertEquals(1, gone.usages.size, "a removed tool's usages are the trails now BROKEN")
    assertTrue(gone.sourcePaths.isEmpty(), "a deleted tool has no current source")
    assertEquals(
      emptyList(),
      report.diagnostics,
      "nothing here is a typo — the set was derived, not typed: ${report.diagnostics}",
    )
    val edited = report.tools.first { it.tool == "demo_edited" }
    assertEquals(ToolUsageResult.MODIFIED, edited.changeKind)
    assertEquals(listOf("/ws/trailmaps/m/tools/edited.ts"), edited.sourcePaths)
  }

  @Test
  fun `each of the four changed-since tiers maps to its own change kind`() {
    val kinds = UsagesCommand().changeKindsOf(
      ChangedSinceSummary(
        ref = "main",
        resolvedSha = "0".repeat(40),
        added = listOf("demo_new"),
        removed = listOf("demo_gone"),
        modified = listOf("demo_edited"),
        impactedViaCallers = listOf("demo_delegator"),
      ),
    )

    assertEquals(
      mapOf(
        "demo_new" to ToolUsageResult.ADDED,
        "demo_gone" to ToolUsageResult.REMOVED,
        "demo_edited" to ToolUsageResult.MODIFIED,
        "demo_delegator" to ToolUsageResult.IMPACTED_VIA_CALLERS,
      ),
      kinds,
      "collapsing any tier into another loses the distinction a consumer reads this to get — " +
        "most of all `removed`, whose usages are broken rather than merely worth replaying",
    )
  }

  @Test
  fun `--json runs the whole command with the console off stdout, then puts it back`() {
    writeTrail(
      "case_j.trail.yaml",
      """
      config:
        id: case_j
      trail:
        - step: Fine
          recording:
            android:
              - demo_tap: {}
      """,
    )

    // Breadcrumbs are emitted before report production (resolving the trails directory names the
    // config file it loaded), so the redirect has to cover the whole command, not just the scan.
    // Run the command WITHOUT this file's capture helper — that helper force-restores the console
    // fields in a `finally`, which would mask a leak. Here the command owns the restore.
    val stdout = ByteArrayOutputStream()
    val consoleSink = PrintStream(ByteArrayOutputStream(), /* autoFlush = */ true, Charsets.UTF_8)
    // json mode redirects BOTH cached streams, so both have to be checked — and both restored
    // here regardless, or a regression in the code under test leaves `userOut` on stderr for
    // every later test in this JVM. Restoring masks nothing: the values are read before the
    // `finally` runs.
    val consoleFields = listOf("out", "userOut").map { name ->
      val field = Console::class.java.getDeclaredField(name).apply { isAccessible = true }
      field to field.get(Console) as PrintStream
    }
    val originalSystemOut = System.out
    var streamsAfterRun: List<Any?> = emptyList()
    val exit = try {
      consoleFields.forEach { (field, _) -> field.set(Console, consoleSink) }
      System.setOut(PrintStream(stdout, /* autoFlush = */ true, Charsets.UTF_8))
      CommandLine(UsagesCommand()).execute("demo_tap", "--trails", trailsDir.absolutePath, "--json")
        .also { streamsAfterRun = consoleFields.map { (field, _) -> field.get(Console) } }
    } finally {
      System.setOut(originalSystemOut)
      consoleFields.forEach { (field, original) -> field.set(Console, original) }
    }

    assertEquals(TrailblazeExitCode.SUCCESS.code, exit)
    assertEquals(
      listOf<Any?>(consoleSink, consoleSink),
      streamsAfterRun,
      "`--json` must restore both console streams it redirected (out, userOut) — the daemon runs " +
        "commands in-process, where leaving json mode on silently moves all later output to stderr",
    )
    Json { ignoreUnknownKeys = true }
      .decodeFromString(ToolUsagesReport.serializer(), stdout.toString(Charsets.UTF_8).trim())
  }

  @Test
  fun `repeating --trails scans every named root in one pass`() {
    writeTrail(
      "case_first.trail.yaml",
      """
      config:
        id: case_first
      trail:
        - step: Tap in the first root
          recording:
            android:
              - demo_tap: {}
      """,
    )
    // Deliberately the SAME root-relative id in both roots: trail ids are root-relative, so
    // `root` is the only thing that tells the two apart.
    writeTrail(
      "case_first.trail.yaml",
      """
      config:
        id: case_first
      trail:
        - step: Tap in the second root
          recording:
            ios-iphone:
              - demo_tap: {}
      """,
      root = secondRoot,
    )

    val report = runForReport(listOf(trailsDir, secondRoot), "demo_tap")

    val usages = report.tools.single().usages
    assertEquals(
      listOf(trailsDir.absolutePath to "Tap in the first root", secondRoot.absolutePath to "Tap in the second root"),
      usages.map { it.root to it.steps.single().step },
      "both roots must be scanned in one pass, each usage naming the root it came from",
    )
    assertEquals(listOf("case_first", "case_first"), usages.map { it.trail })
    assertEquals(
      trailsDir.absolutePath,
      report.trailsRoot,
      "the first --trails is the primary root, which is what the report names",
    )
  }

  @Test
  fun `the report names every root scanned, so a zero-usage answer says what it covered`() {
    writeTrail(
      "case_only.trail.yaml",
      """
      config:
        id: case_only
      trail:
        - step: Tap
          recording:
            android:
              - demo_tap: {}
      """,
    )

    val report = runForReport(listOf(trailsDir, secondRoot), "demo_neverUsed")

    assertTrue(report.tools.single().usages.isEmpty())
    assertEquals(
      listOf(trailsDir.absolutePath, secondRoot.absolutePath),
      report.scannedRoots,
      "no usage carries a root when nothing matched, so the report itself must say what was covered",
    )
  }

  @Test
  fun `two spellings of one root scan it once, not twice`() {
    writeTrail(
      "case_dupe.trail.yaml",
      """
      config:
        id: case_dupe
      trail:
        - step: Tap
          recording:
            android:
              - demo_tap: {}
      """,
    )

    // Same directory, spelled two ways — a script assembling roots from discovered paths produces
    // exactly this. Scanning it twice would report the one trail twice under an identical `root`
    // (File normalizes both spellings away), which no consumer could tell from two real hits.
    val report = runForReportOfRawRoots(
      listOf(trailsDir.absolutePath, trailsDir.absolutePath + "/", trailsDir.absolutePath + "/."),
      "demo_tap",
    )

    assertEquals(1, report.tools.single().usages.size, "the same root named twice is still one root")
    assertEquals(listOf(trailsDir.absolutePath), report.scannedRoots)
  }

  @Test
  fun `a root that could not be read is a warning, not a silent claim of coverage`() {
    writeTrail(
      "case_here.trail.yaml",
      """
      config:
        id: case_here
      trail:
        - step: Tap
          recording:
            android:
              - demo_tap: {}
      """,
    )
    val gone = File(trailsDir, "moved-away")

    // Trail Runner's saved extra roots are not validated by the command — one can name a directory
    // that has since been deleted. The scanner just skips it, so without this the report would list
    // it under `scannedRoots` and answer "no usages" for a tree it never opened.
    val report = UsagesCommand().buildReport(listOf("demo_tap"), trailsDir, listOf(gone))

    assertEquals(
      listOf(trailsDir.absolutePath),
      report.scannedRoots,
      "scannedRoots must list only roots actually read",
    )
    assertTrue(
      report.warnings.any { it.contains(gone.path) },
      "the unread root must be named so a zero-usage gate can fail open: ${report.warnings}",
    )
    // The kind a CI gate keys on: this is the one failure class that hides whole subtrees of
    // usages, so a build deriving a blast radius has to be able to find it without regexing prose.
    val diagnostic = report.diagnostics.single()
    assertEquals(UsagesDiagnostic.ROOT_UNSCANNED, diagnostic.kind)
    assertEquals(gone.path, diagnostic.subject)
    assertEquals(1, report.tools.single().usages.size, "the readable root is still scanned")
  }

  @Test
  fun `a missing trails directory is a misuse error`() {
    val missing = File(trailsDir, "nope")
    assertTrue(errorOf("demo_tap", "--trails", missing.absolutePath).contains(missing.absolutePath))
  }

  @Test
  fun `a missing directory in a later --trails is a misuse error, not a quietly narrower scan`() {
    val missing = File(trailsDir, "nope")
    val error = errorOf("demo_tap", "--trails", trailsDir.absolutePath, "--trails", missing.absolutePath)
    assertTrue(
      error.contains(missing.absolutePath),
      "the message must name the root that is actually bad, not the good first one: $error",
    )
  }

  @Test
  fun `a blank --trails is a misuse error, not a silent fall back to the configured root`() {
    // `--trails "$UNSET_VAR"` from a script. Dropping it would leave no explicit root at all and
    // scan whatever the workspace has configured — a different tree, answered with full confidence.
    errorOf("demo_tap", "--trails", trailsDir.absolutePath, "--trails", "  ")
  }

  @Test
  fun `roots that contain one another are a misuse error, because the inner one reports twice`() {
    val nested = File(trailsDir, "nested").apply { mkdirs() }
    val outerFirst = errorOf("demo_tap", "--trails", trailsDir.absolutePath, "--trails", nested.absolutePath)
    val innerFirst = errorOf("demo_tap", "--trails", nested.absolutePath, "--trails", trailsDir.absolutePath)
    listOf(outerFirst, innerFirst).forEach { error ->
      assertTrue(error.contains("overlap"), "both orders must be rejected the same way: $error")
    }
  }

  @Test
  fun `the first --trails is what changed-since walks up from to find trailmaps`() {
    // Workspace fixture: <trailsDir>/workspace/trails is a trails root with trailmaps above it in
    // a git repo; <trailsDir>/detached is a trails root with no workspace anywhere above it.
    val workspace = File(trailsDir, "workspace").apply { mkdirs() }
    val workspaceTrails = File(workspace, "trails").apply { mkdirs() }
    File(workspace, "trails/config/trailmaps/demo-map/tools").mkdirs()
    val p = ProcessBuilder("git", "-C", workspace.absolutePath, "init", "-q").redirectErrorStream(true).start()
    check(p.waitFor() == 0) { "git init failed: ${p.inputStream.bufferedReader().readText()}" }
    val detached = createTempDirectory("trailblaze-usages-test-detached").toFile()

    try {
      // Detached root first: the walk-up starts there, finds no trailmaps, and says so.
      val detachedFirst = errorOf(
        "--changed-since", "no-such-ref",
        "--trails", detached.absolutePath,
        "--trails", workspaceTrails.absolutePath,
      )
      assertTrue(
        detachedFirst.contains("No trailmaps directory found") && detachedFirst.contains(detached.absolutePath),
        "the walk-up must start at the FIRST root and name it: $detachedFirst",
      )

      // Reversed: the same two roots now resolve trailmaps fine and the run gets as far as the ref.
      val workspaceFirst = errorOf(
        "--changed-since", "no-such-ref",
        "--trails", workspaceTrails.absolutePath,
        "--trails", detached.absolutePath,
      )
      assertTrue(
        workspaceFirst.contains("no-such-ref"),
        "with the workspace root first, trailmaps resolve and the run fails on the ref instead: $workspaceFirst",
      )
    } finally {
      detached.deleteRecursively()
    }
  }

  @Test
  fun `naming tools and passing changed-since together is a misuse error`() {
    val exit = CommandLine(UsagesCommand())
      .execute("demo_tap", "--changed-since", "origin/main", "--trails", trailsDir.absolutePath)
    assertEquals(TrailblazeExitCode.MISUSE.code, exit, "the two tool-selection modes must not mix silently")
  }

  @Test
  fun `neither tool names nor changed-since is a misuse error`() {
    val exit = CommandLine(UsagesCommand()).execute("--trails", trailsDir.absolutePath)
    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
  }

  @Test
  fun `changed-since with an unresolvable ref is a misuse error, reported before any bundling`() {
    // A workspace-shaped fixture: <root>/trails is the trails dir, <root>/trails/config/trailmaps
    // holds tool sources, and <root> is a git repo — so tool-selection fails on the REF, the one
    // thing actually wrong here, not on missing scaffolding.
    val root = trailsDir // reuse the temp dir as the workspace root
    val trails = File(root, "trails").apply { mkdirs() }
    File(root, "trails/config/trailmaps/demo-map/tools").mkdirs()
    val git = { args: List<String> ->
      val p = ProcessBuilder(listOf("git", "-C", root.absolutePath) + args).redirectErrorStream(true).start()
      check(p.waitFor() == 0) { "git $args failed: ${p.inputStream.bufferedReader().readText()}" }
    }
    git(listOf("init", "-q"))

    val exit = CommandLine(UsagesCommand())
      .execute("--changed-since", "no-such-ref", "--trails", trails.absolutePath)
    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
  }

  @Test
  fun `the SDK alias falls back to the workspace's extracted SDK in both config layouts`() {
    // What makes the ref side of a --changed-since comparison bundleable at all. A ref tree is a
    // plain git checkout, and neither `.trailblaze/sdk/` nor the generated per-trailmap
    // tsconfig.json that points at it is committed — so without this alias esbuild has nothing to
    // resolve `@trailblaze/scripting` to there, every tool degrades to a bytes-only fingerprint,
    // and the import-closure detection the flag exists for is dead.
    for (configDir in listOf("trailblaze-config", "trails/config")) {
      val root = File(trailsDir, configDir.replace('/', '-') + "-workspace")
      File(root, "$configDir/trailmaps").mkdirs()
      // Where `.trailblaze/` anchors: the config dir's parent — the root itself for the
      // standalone layout, `<root>/trails` for the nested one.
      val artifactsRoot = if (configDir.contains('/')) File(root, configDir.substringBefore('/')) else root

      assertEquals(
        null,
        UsagesCommand().workspaceSdkAliasFallback(root.toPath()),
        "a workspace whose SDK was never extracted has no alias target to offer",
      )

      val sdkEntry = File(artifactsRoot, ".trailblaze/sdk/dist/index.js")
      sdkEntry.parentFile.mkdirs()
      sdkEntry.writeText("export const trailblaze = {};")

      assertEquals(
        sdkEntry.canonicalFile,
        UsagesCommand().workspaceSdkAliasFallback(root.toPath())?.canonicalFile,
        "the alias must find the SDK the workspace's own tsconfig `paths` resolve to ($configDir layout)",
      )
    }
  }

  @Test
  fun `a workspace with no extracted SDK still gets an alias target from the framework JAR`() {
    // The fresh-worktree case: `.trailblaze/` is gitignored, so a `git worktree add` checkout has
    // no SDK to borrow and no `trailblaze check` has run there. `validate-trailmap-tool-change.sh`
    // runs `usages --changed-since` in exactly such a worktree, and without this tier both sides
    // fail to bundle — which reports an edit confined to an imported helper as no change at all.
    val cacheRoot = File(trailsDir, "framework-sdk-cache")

    val entry = WorkspaceTypeScriptSetup.frameworkSdkRuntimeEntry(cacheRoot)

    assertTrue(
      entry != null && entry.isFile && entry.length() > 0,
      "the framework ships its own SDK; a caller with no workspace extract must still resolve " +
        "`@trailblaze/scripting` rather than silently bundle nothing",
    )
    assertEquals(
      File(cacheRoot, "dist/index.js").canonicalFile,
      entry!!.canonicalFile,
      "the alias target is the SDK's runtime entry, not its declaration bundle",
    )
    assertEquals(
      entry.canonicalFile,
      WorkspaceTypeScriptSetup.frameworkSdkRuntimeEntry(cacheRoot)?.canonicalFile,
      "extraction is idempotent — a second run reuses the cache instead of re-materializing",
    )
  }

  @Test
  fun `a configuration-keyed recording reaches the devices that configuration casts`() {
    // A multi-device trail records under the CONFIGURATION's name, and no member device's lineage
    // contains that name — so a plain chain walk answers "reaches nothing" for a trail that
    // definitely invokes the tool. Selection is what makes the leg reachable, and a consumer
    // choosing lanes from `invokingDevices` would otherwise skip every multi-device replay.
    writeTrail(
      "paired.trail.yaml",
      """
      config:
        id: paired
        devices:
          pos-pair:
            description: Dual-display pair
            devices:
              seller:
                classifier: lab-a
              buyer:
                classifier: lab-b
      trail:
        - step: Ring up an item
          recording:
            pos-pair:
              - demo_addItem: {}
      """,
    )

    val usage = runForReport("demo_addItem").tools.single().usages.single()

    assertEquals(
      setOf("lab-a", "lab-b"),
      usage.devices.toSet(),
      "the configuration's members are the devices, the configuration name is not one",
    )
    assertEquals(
      setOf("lab-a", "lab-b"),
      usage.invokingDevices.toSet(),
      "selecting `pos-pair` runs this recording on both cast devices: ${usage.invokingDevices}",
    )
  }

  @Test
  fun `a configuration whose recording does not invoke leaves its members out`() {
    // The other half of the pair, and the reason resolving through a configuration cannot degrade
    // into "a configuration key exists, so every member reaches it". `lab-a`/`lab-b` are cast only
    // by `pos-pair`, whose leg invokes something else, and there is no broader leg for them to fall
    // through to — so neither reaches the tool. Neither does `solo`: no configuration casts it, and
    // a configured trail always replays with its configuration selected, so no session runs `solo`
    // at all — not even the second step, where its own leg is the only one invoking.
    writeTrail(
      "paired-negative.trail.yaml",
      """
      config:
        id: paired-negative
        devices:
          pos-pair:
            devices:
              seller:
                classifier: lab-a
              buyer:
                classifier: lab-b
          solo: {}
      trail:
        - step: Ring up an item
          recording:
            solo:
              - demo_addItem: {}
            pos-pair:
              - demo_signOut: {}
        - step: Check the receipt
          recording:
            solo:
              - demo_addItem: {}
      """,
    )

    val usage = runForReport("demo_addItem").tools.single().usages.single()

    assertEquals(
      setOf("lab-a", "lab-b", "solo"),
      usage.devices.toSet(),
      "all three are declared — the denominator does not shrink",
    )
    assertEquals(
      emptySet(),
      usage.invokingDevices.toSet(),
      "the paired devices resolve their configuration's own leg, which invokes something else, " +
        "and `solo`, which no configuration casts, never runs this trail: ${usage.invokingDevices}",
    )
  }

  @Test
  fun `a configuration's own leg shadows a broader invoking leg for every device`() {
    // The over-report this field must not make: `all:` invokes the tool, but
    // MultiDeviceConfigurationResolver.resolve always selects the sole declared configuration
    // (rejecting a trail with more than one), so every session of this trail resolves the
    // `pos-pair:` leg from the head of the chain and the `all:` leg never replays. Offering a
    // configuration-free session here would report both cast devices as reaching the tool — and a
    // CI consumer keying replay lanes on `invokingDevices` would run lanes that never touch it.
    writeTrail(
      "paired-shadowed.trail.yaml",
      """
      config:
        id: paired-shadowed
        devices:
          pos-pair:
            devices:
              seller:
                classifier: lab-a
              buyer:
                classifier: lab-b
      trail:
        - step: Ring up an item
          recording:
            all:
              - demo_addItem: {}
            pos-pair:
              - demo_signOut: {}
      """,
    )

    val usage = runForReport("demo_addItem").tools.single().usages.single()

    assertEquals(listOf("all"), usage.classifiers, "the authored key is unchanged — raw facts stay raw")
    assertEquals(
      emptySet(),
      usage.invokingDevices.toSet(),
      "no runnable session reaches the `all:` leg — the selected configuration's own leg is " +
        "always closer: ${usage.invokingDevices}",
    )
  }

  @Test
  fun `a configured trail's devices still fall through to a broader leg the configuration leaves open`() {
    // The overcorrection guard for the shadowing test above: always selecting the configuration
    // must not mean members only ever see configuration-keyed legs. A step with no `pos-pair:` leg
    // resolves each member's chain past the configuration name down to `all:`, exactly as replay
    // does. `all` itself stays out: it is not cast by the configuration, so nothing runs it.
    writeTrail(
      "paired-fallthrough.trail.yaml",
      """
      config:
        id: paired-fallthrough
        devices:
          pos-pair:
            devices:
              seller:
                classifier: lab-a
              buyer:
                classifier: lab-b
      trail:
        - step: Ring up an item
          recording:
            all:
              - demo_addItem: {}
      """,
    )

    val usage = runForReport("demo_addItem").tools.single().usages.single()

    assertEquals(
      setOf("lab-a", "lab-b"),
      usage.invokingDevices.toSet(),
      "with no `pos-pair:` leg on the step, each cast member's chain falls through to `all:`, " +
        "which invokes: ${usage.invokingDevices}",
    )
  }

  @Test
  fun `an incomplete inventory is reported, and suppresses the typo hint it would explain`() {
    // A descriptor naming a script that does not exist leaves the tool it declares OUT of the
    // inventory. Reducing the snapshot to paths and dropping its warnings turned that into the one
    // wrong answer available — "check the spelling" — while discarding the reason.
    val workspaceTrails = workspaceWithTrailmaps { tools ->
      File(tools, "broken.yaml").writeText("name: demo_ghost\nscript: ./missing.ts\n")
    }

    val report = runForReport(listOf(workspaceTrails), "demo_ghost")

    val incomplete = report.diagnostics.filter { it.kind == UsagesDiagnostic.TOOL_INVENTORY_INCOMPLETE }
    assertEquals(1, incomplete.size, "the scan's own failure must reach the report: ${report.diagnostics}")
    assertEquals("current", incomplete.single().subject, "the same side vocabulary --changed-since uses")
    assertTrue(
      report.diagnostics.none { it.kind == UsagesDiagnostic.TOOL_NOT_IN_SCRIPTED_INVENTORY },
      "a name the scan never managed to read is not evidence of a typo: ${report.diagnostics}",
    )
    assertEquals(
      incomplete.map { it.message },
      report.warnings,
      "an unreadable inventory IS incompleteness, so it belongs in the fail-open list",
    )
  }

  @Test
  fun `the typo hint stays out of warnings, so a fail-open gate does not trip on it`() {
    // `warnings` means "this report may be incomplete — fail open". A hint about a name the caller
    // typed is not that: `usages tapOn` in a workspace where nothing uses it would otherwise trip a
    // CI gate permanently, with nothing to fix.
    val workspaceTrails = workspaceWithTrailmaps { tools ->
      File(tools, "declared.ts").writeText("export const demo_declared = trailblaze.tool({});\n")
      File(tools, "declared.yaml").writeText("name: demo_declared\nscript: ./declared.ts\n")
    }

    val report = runForReport(listOf(workspaceTrails), "demo_ghost")

    val hint = report.diagnostics.single { it.kind == UsagesDiagnostic.TOOL_NOT_IN_SCRIPTED_INVENTORY }
    assertEquals("demo_ghost", hint.subject)
    assertEquals(UsagesDiagnostic.HINT, hint.severity, "a hint classifies itself, so a gate need not enumerate kinds")
    assertEquals(
      emptyList(),
      report.warnings,
      "nothing about a queried name says the SCAN was incomplete: ${report.warnings}",
    )
  }

  /**
   * A workspace-shaped fixture rooted at `<trailsDir>/ws`: `ws/trails` is the trails root and
   * `ws/trails/config/trailmaps/demo-map/tools` is the scripted-tool inventory [populate] writes
   * into. Returns the trails root to pass as `--trails`.
   */
  private fun workspaceWithTrailmaps(populate: (toolsDir: File) -> Unit): File {
    val workspace = File(trailsDir, "ws")
    val workspaceTrails = File(workspace, "trails").apply { mkdirs() }
    val tools = File(workspace, "trails/config/trailmaps/demo-map/tools").apply { mkdirs() }
    populate(tools)
    return workspaceTrails
  }

  private fun writeTrail(relativePath: String, yaml: String, root: File = trailsDir) {
    val file = File(root, relativePath)
    file.parentFile.mkdirs()
    file.writeText(yaml.trimIndent())
  }

  /** Captures what the command wrote to [Console.error], which is where every misuse message goes. */
  private fun errorOf(vararg args: String): String {
    val captured = ByteArrayOutputStream()
    val originalSystemErr = System.err
    System.setErr(PrintStream(captured, /* autoFlush = */ true, Charsets.UTF_8))
    val exit = try {
      CommandLine(UsagesCommand()).execute(*args)
    } finally {
      System.setErr(originalSystemErr)
    }
    assertEquals(TrailblazeExitCode.MISUSE.code, exit, "expected a misuse error from: ${args.joinToString(" ")}")
    return captured.toString(Charsets.UTF_8)
  }

  private fun runForReport(vararg tools: String): ToolUsagesReport = runForReport(listOf(trailsDir), *tools)

  private fun runForReport(roots: List<File>, vararg tools: String): ToolUsagesReport =
    runForReportOfRawRoots(roots.map { it.absolutePath }, *tools)

  /** Takes root paths verbatim, so a test can name one directory two ways. */
  private fun runForReportOfRawRoots(roots: List<String>, vararg tools: String): ToolUsagesReport {
    val captured = ByteArrayOutputStream()
    val stream = PrintStream(captured, /* autoFlush = */ true, Charsets.UTF_8)
    val originalSystemOut = System.out
    // Console caches System.out into private fields at class-init, so System.setOut alone would
    // NOT capture Console.log output — and this test's purity assertion would pass vacuously even
    // if `--json` stopped suppressing breadcrumbs. Re-point the cached fields at the same buffer
    // (the ConsoleTest pattern) so any Console.log leak lands in the capture and breaks the parse.
    val consoleFields = listOf("out", "userOut").map { name ->
      val field = Console::class.java.getDeclaredField(name).apply { isAccessible = true }
      field to field.get(Console) as PrintStream
    }
    val exit = try {
      System.setOut(stream)
      consoleFields.forEach { (field, _) -> field.set(Console, stream) }
      CommandLine(UsagesCommand())
        .execute(*tools, *roots.flatMap { listOf("--trails", it) }.toTypedArray(), "--json")
    } finally {
      System.setOut(originalSystemOut)
      consoleFields.forEach { (field, original) -> field.set(Console, original) }
    }
    assertEquals(TrailblazeExitCode.SUCCESS.code, exit)
    // `--json` stdout must be nothing but the document — scan breadcrumbs on stdout would break
    // every `| jq` consumer — so parse the whole capture rather than hunting for the first `{`.
    return Json { ignoreUnknownKeys = true }
      .decodeFromString(ToolUsagesReport.serializer(), captured.toString(Charsets.UTF_8).trim())
  }
}
