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
import xyz.block.trailblaze.usages.ToolUsagesReport
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
