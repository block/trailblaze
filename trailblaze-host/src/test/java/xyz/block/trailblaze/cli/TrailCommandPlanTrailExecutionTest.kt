package xyz.block.trailblaze.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * Pins the contract of [TrailCommand.planTrailExecution] and [TrailCommand.expandTrailFiles].
 * These are the single source of truth for how `trailblaze trail` resolves its arguments into
 * an executable workload — directory expansion, `--tags` filtering, and `skip:` classification.
 * The downstream per-file loop in both `call()` and `delegateToDaemon()` is a single iteration
 * over the plan's output, so anything the planner mis-classifies surfaces as a CI regression in
 * the actual runner.
 */
class TrailCommandPlanTrailExecutionTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private fun writeTrail(
    name: String,
    dir: File = tempFolder.root,
    title: String = name,
    tags: List<String>? = null,
    skip: String? = null,
  ): File {
    dir.mkdirs()
    val file = File(dir, name)
    val configLines = buildString {
      appendLine("- config:")
      appendLine("    title: $title")
      appendLine("    platform: android")
      if (tags != null) appendLine("    tags: [${tags.joinToString(", ")}]")
      if (skip != null) appendLine("    skip: \"$skip\"")
      appendLine("- tools:")
      appendLine("  - pressBack: {}")
    }
    file.writeText(configLines)
    return file
  }

  @Test
  fun `expandTrailFiles leaves plain files alone and recurses into directories`() {
    val topLevel = writeTrail("top.trail.yaml")
    val nestedDir = File(tempFolder.root, "nested").apply { mkdirs() }
    val deepDir = File(nestedDir, "deep").apply { mkdirs() }
    val sibling = File(nestedDir, "sibling").apply { mkdirs() }
    writeTrail("nested.trail.yaml", dir = sibling)
    writeTrail("blaze.yaml", dir = deepDir)
    writeTrail("not-a-trail.yaml", dir = nestedDir)  // unrelated yaml, must be ignored

    val expanded = TrailCommand.expandTrailFiles(listOf(topLevel, nestedDir))

    val names = expanded.map { it.name }.sorted()
    assertEquals(listOf("blaze.yaml", "nested.trail.yaml", "top.trail.yaml"), names)
  }

  @Test
  fun `expandTrailFiles skips the workspace config trailblaze yaml under any config subdir`() {
    // `TrailRecordings.isTrailFile` returns true for `trailblaze.yaml` (it's an NL-definition
    // alias for `blaze.yaml`). But by workspace convention the workspace *config* lives at
    // `trails/config/trailblaze.yaml` — picking that up as a runnable trail would mis-execute
    // the config file. The expander must exclude it specifically. Other `trailblaze.yaml`
    // files outside a `config/` directory still pass through (they're real NL definitions).
    //
    // Place the config and the real NL trail in sibling subdirectories so the directory-grouped
    // resolver picks one trail per directory rather than picking a single winner across both.
    val workspaceConfigDir = File(tempFolder.root, "config").apply { mkdirs() }
    File(workspaceConfigDir, "trailblaze.yaml").writeText("defaults:\n  target: sample\n")
    val nlDir = File(tempFolder.root, "nl-trail").apply { mkdirs() }
    val nlTrailOutsideConfig = writeTrail("trailblaze.yaml", dir = nlDir)

    val expanded = TrailCommand.expandTrailFiles(listOf(tempFolder.root))

    val names = expanded.map { it.canonicalPath }.sorted()
    assertEquals(
      listOf(nlTrailOutsideConfig.canonicalPath),
      names,
      "workspace config should be excluded; NL trail under a non-config subdir should be kept",
    )
  }

  @Test
  fun `expandTrailFiles deduplicates overlapping inputs`() {
    // Place the trail in its own subdirectory alongside a `blaze.yaml` so directory expansion
    // exercises the happy path (resolver picks `blaze.yaml`) instead of the no-match fallback.
    // Two reasons that matters here:
    //   1. Dedup is the contract under test; the no-match fallback path is exercised by other
    //      tests (`recordings-only`, `lone-android-recording`, `no-web-recording`).
    //   2. Hitting the fallback emits a `Console.error` warning to stderr on every test run,
    //      which is unrelated noise for a dedup assertion.
    val subDir = File(tempFolder.root, "trail").apply { mkdirs() }
    val blaze = writeTrail("blaze.yaml", dir = subDir)

    // Pass the file twice and a containing directory — should yield blaze.yaml exactly once.
    val expanded = TrailCommand.expandTrailFiles(listOf(blaze, blaze, tempFolder.root))
    assertEquals(1, expanded.size)
    assertEquals("blaze.yaml", expanded.first().name)
  }

  @Test
  fun `config trailblaze yaml is not resurrected when its directory also contains a real trail`() {
    // Defense-in-depth for the `config/trailblaze.yaml` exclusion. The pre-walk `filterNot`
    // drops `*/config/trailblaze.yaml` so it can't enter the `byParent` map. But the resolver
    // (`findBestTrailResourcePath`) probes candidate filenames on disk via `doesResourceExist`,
    // and would happily resurrect the excluded `trailblaze.yaml` if a `config/` dir also held
    // a sibling `.trail.yaml`. The `allowedNames` gate constrains the probe to files that
    // survived the walk. Pin that gate so a future refactor can't silently widen the probe.
    val configDir = File(tempFolder.root, "config").apply { mkdirs() }
    File(configDir, "trailblaze.yaml").writeText("defaults:\n  target: sample\n")
    val realTrail = writeTrail("real.trail.yaml", dir = configDir)

    val expanded = TrailCommand.expandTrailFiles(listOf(tempFolder.root))

    assertEquals(1, expanded.size)
    assertEquals(
      realTrail.canonicalPath,
      expanded.single().canonicalPath,
      "config/trailblaze.yaml must stay excluded; the sibling real.trail.yaml is the only " +
        "runnable trail in this directory",
    )
  }

  @Test
  fun `directory with both blaze yaml and a classifier-matched recording picks the recording`() {
    // Mirrors the canonical layout: one directory holds the NL source of truth (`blaze.yaml`)
    // and a platform-specific recording. With matching classifiers the resolver picks the
    // recording so the CLI runs a deterministic replay instead of double-billing an LLM run.
    val trailDir = File(tempFolder.root, "test-with-recording").apply { mkdirs() }
    writeTrail("blaze.yaml", dir = trailDir)
    val webRecording = writeTrail("web.trail.yaml", dir = trailDir)

    val expanded = TrailCommand.expandTrailFiles(
      files = listOf(trailDir),
      deviceClassifiers = TrailCommand.parseDeviceClassifiersFromSpec("web"),
    )

    assertEquals(1, expanded.size)
    assertEquals(webRecording.canonicalPath, expanded.single().canonicalPath)
  }

  @Test
  fun `directory with only blaze yaml falls back to the NL definition`() {
    val trailDir = File(tempFolder.root, "test-nl-only").apply { mkdirs() }
    val blaze = writeTrail("blaze.yaml", dir = trailDir)

    val expanded = TrailCommand.expandTrailFiles(
      files = listOf(trailDir),
      deviceClassifiers = TrailCommand.parseDeviceClassifiersFromSpec("web"),
    )

    assertEquals(1, expanded.size)
    assertEquals(blaze.canonicalPath, expanded.single().canonicalPath)
  }

  @Test
  fun `parent dir with N subdirs each holding blaze and recording yields N trails not 2N`() {
    // Regression for the wikipedia-style layout: 27 test subdirs, each with both files. Walking
    // recursively used to return 2·N — once as `blaze.yaml`, once as `<platform>.trail.yaml` —
    // burning LLM budget on every CLI invocation. The fix resolves one trail per subdirectory.
    val parent = File(tempFolder.root, "trails").apply { mkdirs() }
    val expectedRecordings = (1..5).map { i ->
      val sub = File(parent, "test-$i").apply { mkdirs() }
      writeTrail("blaze.yaml", dir = sub)
      writeTrail("web.trail.yaml", dir = sub)
    }

    val expanded = TrailCommand.expandTrailFiles(
      files = listOf(parent),
      deviceClassifiers = TrailCommand.parseDeviceClassifiersFromSpec("web"),
    )

    assertEquals(5, expanded.size)
    assertEquals(
      expectedRecordings.map { it.canonicalPath }.sorted(),
      expanded.map { it.canonicalPath }.sorted(),
    )
  }

  @Test
  fun `directory with multiple android recordings picks the platform-level one given only the platform classifier`() {
    // Pins the "CLI classifier list is platform-only" contract documented on
    // parseDeviceClassifiersFromSpec. With richer multi-segment recordings (e.g.
    // pixel-android.trail.yaml) alongside a platform-level one, the resolver should still
    // land on the platform-level file — that's all the classifier list it gets at expansion
    // time. If/when the CLI grows multi-segment classifier support, this test will flip and
    // the change in selected file will be the visible signal.
    val trailDir = File(tempFolder.root, "mixed-recordings").apply { mkdirs() }
    writeTrail("blaze.yaml", dir = trailDir)
    val androidRecording = writeTrail("android.trail.yaml", dir = trailDir)
    writeTrail("pixel-android.trail.yaml", dir = trailDir)

    val expanded = TrailCommand.expandTrailFiles(
      files = listOf(trailDir),
      deviceClassifiers = TrailCommand.parseDeviceClassifiersFromSpec("android"),
    )

    assertEquals(1, expanded.size)
    assertEquals(androidRecording.canonicalPath, expanded.single().canonicalPath)
  }

  @Test
  fun `directory with only recordings and no device spec picks one and emits a warning naming the skipped files`() {
    // Regression for the no-device fallback path. Pre-fix behavior would run both files
    // (and double-bill if the NL file existed); post-fix we pick one alphabetically.
    // Lock in two contracts:
    //   1. The picked file is the alphabetically-first recording.
    //   2. The runner-up warning is emitted to stderr via [Console.error], naming the
    //      dropped recording and recommending a platform-prefixed `--device`. Capturing
    //      this is what guards the user-facing behavior — a regression that silently
    //      stopped warning would be invisible without an assertion.
    val trailDir = File(tempFolder.root, "recordings-only").apply { mkdirs() }
    val androidRecording = writeTrail("android.trail.yaml", dir = trailDir)
    writeTrail("ios.trail.yaml", dir = trailDir)

    val capturedErr = ByteArrayOutputStream()
    val expanded = withCapturedStderr(capturedErr) {
      TrailCommand.expandTrailFiles(
        files = listOf(trailDir),
        deviceClassifiers = emptyList(),
      )
    }

    assertEquals(1, expanded.size)
    assertEquals(androidRecording.canonicalPath, expanded.single().canonicalPath)

    val stderrText = capturedErr.toString(StandardCharsets.UTF_8)
    assertTrue(
      stderrText.contains("ios.trail.yaml") &&
        stderrText.contains("android.trail.yaml") &&
        stderrText.contains("--device"),
      "expected a runner-up warning naming the kept + dropped recordings and pointing at " +
        "`--device`; got stderr=\n$stderrText",
    )
  }

  @Test
  fun `directory with only one non-matching recording warns even though there is nothing to disambiguate against`() {
    // Pins the single-recording fallback. Pre-pass-3 the warning fired only on
    // candidates.size > 1, so a directory with a lone `android.trail.yaml` run under
    // `--device web` would silently execute the Android recording. That's misleading —
    // the user asked for web but got android. We still run the recording (CLI surface
    // stays usable, matches the no-device case), but we surface a warning so the user
    // knows the requested classifiers were ignored.
    val trailDir = File(tempFolder.root, "lone-android-recording").apply { mkdirs() }
    val androidRecording = writeTrail("android.trail.yaml", dir = trailDir)

    val capturedErr = ByteArrayOutputStream()
    val expanded = withCapturedStderr(capturedErr) {
      TrailCommand.expandTrailFiles(
        files = listOf(trailDir),
        deviceClassifiers = TrailCommand.parseDeviceClassifiersFromSpec("web"),
      )
    }

    assertEquals(1, expanded.size)
    assertEquals(androidRecording.canonicalPath, expanded.single().canonicalPath)

    val stderrText = capturedErr.toString(StandardCharsets.UTF_8)
    assertTrue(
      stderrText.contains("android.trail.yaml") &&
        stderrText.contains("--device"),
      "expected a warning naming the lone recording and pointing at `--device`; got stderr=\n$stderrText",
    )
  }

  @Test
  fun `directory with multiple non-matching recordings falls back alphabetically and warns about the rest`() {
    // User passed `--device web` against a directory holding only Android + iOS
    // recordings. The resolver finds no `web.trail.yaml`, no NL definition; fallback
    // picks the alphabetically-first recording (`android.trail.yaml`) and the warning
    // names the dropped `ios.trail.yaml`. Covers the explicit-platform-but-no-match
    // case that prior tests didn't pin (the existing fallback test uses no `--device`).
    val trailDir = File(tempFolder.root, "no-web-recording").apply { mkdirs() }
    val androidRecording = writeTrail("android.trail.yaml", dir = trailDir)
    writeTrail("ios.trail.yaml", dir = trailDir)

    val capturedErr = ByteArrayOutputStream()
    val expanded = withCapturedStderr(capturedErr) {
      TrailCommand.expandTrailFiles(
        files = listOf(trailDir),
        deviceClassifiers = TrailCommand.parseDeviceClassifiersFromSpec("web"),
      )
    }

    assertEquals(1, expanded.size)
    assertEquals(androidRecording.canonicalPath, expanded.single().canonicalPath)

    val stderrText = capturedErr.toString(StandardCharsets.UTF_8)
    assertTrue(
      stderrText.contains("ios.trail.yaml") &&
        stderrText.contains("android.trail.yaml") &&
        stderrText.contains("--device"),
      "expected a multi-recording warning naming both files and pointing at `--device`; got stderr=\n$stderrText",
    )
  }

  /**
   * Routes the current thread's [System.err] writes into [sink] for the duration of [block].
   * `Console.error` on JVM writes through `System.err`, so capturing the stream lets us
   * assert on user-facing warning text emitted during expansion. We use [CliOutCapture] so
   * the redirection is thread-local and survives the test in isolation — other concurrent
   * test threads (if any) keep their normal stderr.
   */
  private fun <T> withCapturedStderr(sink: ByteArrayOutputStream, block: () -> T): T {
    CliOutCapture.install()
    return CliOutCapture.withCapture(out = ByteArrayOutputStream(), err = sink, block = block)
  }

  @Test
  fun `parseDeviceClassifiersFromSpec maps known platforms and degrades safely on unknowns`() {
    val webClassifier = TrailblazeDevicePlatform.WEB.asTrailblazeDeviceClassifier().classifier
    assertEquals(
      listOf(webClassifier),
      TrailCommand.parseDeviceClassifiersFromSpec("web").map { it.classifier },
    )
    assertEquals(
      listOf(webClassifier),
      TrailCommand.parseDeviceClassifiersFromSpec("web/some-instance-id").map { it.classifier },
    )
    assertEquals(emptyList(), TrailCommand.parseDeviceClassifiersFromSpec(null))
    assertEquals(emptyList(), TrailCommand.parseDeviceClassifiersFromSpec(""))
    assertEquals(emptyList(), TrailCommand.parseDeviceClassifiersFromSpec("not-a-platform"))
  }

  @Test
  fun `no filters - every expanded trail becomes a Run item, no skips, no filtered`() {
    val a = writeTrail("a.trail.yaml")
    val b = writeTrail("b.trail.yaml")

    val plan = TrailCommand.planTrailExecution(
      files = listOf(a, b),
      includeTags = emptyList(),
    )

    assertEquals(2, plan.items.size)
    plan.items.forEach { assertIs<TrailExecutionItem.Run>(it) }
    assertEquals(0, plan.filteredOutByTag)
  }

  @Test
  fun `--tags includes only trails whose tags overlap - untagged trails are filtered out`() {
    val smokeTrail = writeTrail("smoke.trail.yaml", tags = listOf("smoke", "login"))
    val regressionTrail = writeTrail("regression.trail.yaml", tags = listOf("regression"))
    val untagged = writeTrail("untagged.trail.yaml")

    val plan = TrailCommand.planTrailExecution(
      files = listOf(smokeTrail, regressionTrail, untagged),
      includeTags = listOf("smoke"),
    )

    assertEquals(1, plan.items.size)
    assertEquals("smoke.trail.yaml", plan.items.first().file.name)
    assertEquals(2, plan.filteredOutByTag)
  }

  @Test
  fun `comma-separated tags behave the same as repeated tags (OR semantics)`() {
    // The CLI flag parsing turns `--tags smoke,login` into List["smoke", "login"]; the planner
    // applies OR — a trail tagged with EITHER tag passes the filter.
    val smoke = writeTrail("s.trail.yaml", tags = listOf("smoke"))
    val login = writeTrail("l.trail.yaml", tags = listOf("login"))
    val other = writeTrail("o.trail.yaml", tags = listOf("other"))

    val plan = TrailCommand.planTrailExecution(
      files = listOf(smoke, login, other),
      includeTags = listOf("smoke", "login"),
    )

    val runNames = plan.items.map { it.file.name }.sorted()
    assertEquals(listOf("l.trail.yaml", "s.trail.yaml"), runNames)
    assertEquals(1, plan.filteredOutByTag)
  }

  @Test
  fun `skip with reason becomes a Skip item - blank skip stays as Run`() {
    val skipped = writeTrail("skipped.trail.yaml", skip = "see #2194")
    val blankSkip = writeTrail("blank.trail.yaml", skip = "")
    val normal = writeTrail("normal.trail.yaml")

    val plan = TrailCommand.planTrailExecution(
      files = listOf(skipped, blankSkip, normal),
      includeTags = emptyList(),
    )

    assertEquals(3, plan.items.size)
    val byName = plan.items.associateBy { it.file.name }
    val skipItem = byName["skipped.trail.yaml"]
    assertIs<TrailExecutionItem.Skip>(skipItem)
    assertEquals("see #2194", skipItem.reason)
    assertIs<TrailExecutionItem.Run>(byName["blank.trail.yaml"])
    assertIs<TrailExecutionItem.Run>(byName["normal.trail.yaml"])
  }

  @Test
  fun `directory argument expands and filter-then-classify works end-to-end`() {
    // Per "one trail per directory" convention, each trail lives in its own subdirectory.
    // The expander then resolves one file per subdir, planTrailExecution applies --tags and
    // `skip:` to each, and the end-to-end behavior here mirrors a real `trails/` workspace.
    val root = File(tempFolder.root, "trails").apply { mkdirs() }
    writeTrail("a.trail.yaml", dir = File(root, "a").apply { mkdirs() }, tags = listOf("smoke"))
    writeTrail("b.trail.yaml", dir = File(root, "b").apply { mkdirs() }, tags = listOf("smoke"), skip = "blocked on infra")
    writeTrail("c.trail.yaml", dir = File(root, "c").apply { mkdirs() }, tags = listOf("regression"))

    val plan = TrailCommand.planTrailExecution(
      files = listOf(root),
      includeTags = listOf("smoke"),
    )

    // a → Run (smoke, no skip)
    // b → Skip (smoke, has skip:)
    // c → filtered (no smoke)
    assertEquals(2, plan.items.size)
    val byName = plan.items.associateBy { it.file.name }
    assertIs<TrailExecutionItem.Run>(byName["a.trail.yaml"])
    val bItem = byName["b.trail.yaml"]
    assertIs<TrailExecutionItem.Skip>(bItem)
    assertEquals("blocked on infra", bItem.reason)
    assertEquals(1, plan.filteredOutByTag)
  }

  @Test
  fun `unparseable trail is treated as untagged unskipped Run - runner surfaces the actual error`() {
    val malformed = File(tempFolder.root, "broken.trail.yaml")
    malformed.writeText("not: a: valid: trail: at: all\n  nesting: chaos\n")

    val plan = TrailCommand.planTrailExecution(
      files = listOf(malformed),
      includeTags = emptyList(),
    )

    assertEquals(1, plan.items.size)
    assertIs<TrailExecutionItem.Run>(plan.items.first())
  }

  @Test
  fun `empty input or fully-filtered result produces an empty plan, not an error`() {
    val emptyDir = File(tempFolder.root, "empty").apply { mkdirs() }

    val plan = TrailCommand.planTrailExecution(
      files = listOf(emptyDir),
      includeTags = emptyList(),
    )

    assertTrue(plan.items.isEmpty())
    assertEquals(0, plan.filteredOutByTag)
  }
}
