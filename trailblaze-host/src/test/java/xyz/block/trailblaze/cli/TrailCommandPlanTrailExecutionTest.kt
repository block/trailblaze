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
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.recordings.TrailRecordings

/**
 * Pins the contract of [TrailCommand.planTrailExecution] and [TrailCommand.expandTrailFiles].
 * These are the single source of truth for how `trailblaze run` resolves its arguments into
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
    id: String? = null,
  ): File {
    dir.mkdirs()
    val file = File(dir, name)
    val configLines = buildString {
      appendLine("- config:")
      appendLine("    title: $title")
      appendLine("    platform: android")
      if (id != null) appendLine("    id: $id")
      if (tags != null) appendLine("    tags: [${tags.joinToString(", ")}]")
      if (skip != null) appendLine("    skip: \"$skip\"")
      appendLine("- tools:")
      appendLine("  - pressBack: {}")
    }
    file.writeText(configLines)
    return file
  }

  /**
   * Writes a unified-format trail file (`config:`-map document) — the canonical named-scenario
   * shape, one self-contained `<scenario>.trail.yaml` per distinct trail. Several of these in one
   * directory each expand, because [expandTrailFiles] recognizes them by unified *content* (unlike
   * the v1 `- config:`-list recordings that [writeTrail] produces, which collapse to one pick per
   * directory). [id] is optional on purpose: some real named unified trails carry no `config.id`
   * (the eval suite), and content detection must still expand them.
   */
  private fun writeUnifiedTrail(
    name: String,
    dir: File,
    id: String? = null,
  ): File {
    dir.mkdirs()
    val file = File(dir, name)
    file.writeText(
      buildString {
        appendLine("config:")
        if (id != null) appendLine("  id: $id")
        appendLine("  target: sample")
        appendLine("trail:")
        appendLine("  - step: Do the thing")
        appendLine("    recording:")
        appendLine("      android:")
        appendLine("        - pressBack: {}")
      },
    )
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
  fun `expandTrailFiles resolves a directory holding only a bare unified trail_yaml`() {
    // A migrated unified trail is a BARE `trail.yaml` (no `<device>` prefix, so it does NOT end
    // in `.trail.yaml`). It collapses into the directory's single-trail bucket —
    // `TrailRecordings.UNIFIED_TRAIL_FILENAME` is a resolver candidate — so a directory holding
    // only it expands to exactly that file rather than to nothing.
    val trailDir = File(tempFolder.root, "case_5374124").apply { mkdirs() }
    val bare = writeUnifiedTrail(TrailRecordings.UNIFIED_TRAIL_FILENAME, trailDir, id = "case_5374124")

    val expanded = TrailCommand.expandTrailFiles(listOf(trailDir))

    assertEquals(1, expanded.size)
    assertEquals(bare.canonicalPath, expanded.single().canonicalPath)
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
  fun `trails under excluded directories like build and claude worktrees are not expanded`() {
    // Directory expansion walks via [TrailDiscovery], inheriting its exclude set. Pre-fix,
    // `walkTopDown` descended into `build/`, `node_modules/`, `.claude/worktrees/`, etc., so
    // `trailblaze run .` picked up stale build-output copies of trails and sibling agents'
    // WIP trails — and since named unified trails each expand to their own run, every stray
    // copy became its own execution. Pin that only the real trail survives.
    val realDir = File(tempFolder.root, "checkout").apply { mkdirs() }
    val realTrail = writeUnifiedTrail("checkout-flow.trail.yaml", realDir, id = "sample/checkout-flow")
    writeUnifiedTrail(
      "checkout-flow.trail.yaml",
      File(tempFolder.root, "build/resources/trails"),
      id = "sample/checkout-flow",
    )
    writeUnifiedTrail("dep-fixture.trail.yaml", File(tempFolder.root, "node_modules/some-pkg"))
    writeUnifiedTrail(
      "wip-scenario.trail.yaml",
      File(tempFolder.root, ".claude/worktrees/agent-abc123/trails/wip"),
      id = "sample/wip-scenario",
    )

    val expanded = TrailCommand.expandTrailFiles(listOf(tempFolder.root))

    assertEquals(
      listOf(realTrail.canonicalPath),
      expanded.map { it.canonicalPath },
      "trails under excluded directories must not expand into the run",
    )
  }

  @Test
  fun `in-tree symlinked directories are not traversed`() {
    // Deliberate behavior change from the `walkTopDown` era: [TrailDiscovery]'s walk does not
    // follow symlinks, so a symlink INSIDE the walked tree no longer pulls external trails
    // into the run (following links without cycle detection is a DoS/path-traversal footgun —
    // see the TrailDiscovery KDoc). Users who symlinked a shared trails directory into their
    // workspace must pass the real path (or the symlink itself — see the next test) explicitly.
    val outside = File(tempFolder.root, "outside-tree").apply { mkdirs() }
    writeUnifiedTrail("shared-scenario.trail.yaml", outside, id = "shared/shared-scenario")
    val walkRoot = File(tempFolder.root, "workspace").apply { mkdirs() }
    val realTrail = writeUnifiedTrail("local-scenario.trail.yaml", walkRoot, id = "ws/local-scenario")
    val linkCreated = runCatching {
      java.nio.file.Files.createSymbolicLink(
        File(walkRoot, "shared").toPath(),
        outside.toPath(),
      )
    }.isSuccess
    org.junit.Assume.assumeTrue("cannot create symlinks on this platform/user; skipping", linkCreated)

    val expanded = TrailCommand.expandTrailFiles(listOf(walkRoot))

    assertEquals(
      listOf(realTrail.canonicalPath),
      expanded.map { it.canonicalPath },
      "an in-tree symlinked directory must not be traversed during expansion",
    )
  }

  @Test
  fun `an explicitly-passed symlinked directory still expands its trails`() {
    // The no-follow policy applies to the walked TREE, not to the user's explicit argument:
    // `trailblaze run <symlink-to-dir>` names that directory on purpose, so the expander
    // canonicalizes the root before walking (TrailDiscovery refuses to descend through a
    // symlinked root otherwise, which would silently expand to nothing).
    val realDir = File(tempFolder.root, "real-trails").apply { mkdirs() }
    val trail = writeUnifiedTrail("linked-scenario.trail.yaml", realDir, id = "real/linked-scenario")
    val link = File(tempFolder.root, "trails-link")
    val linkCreated = runCatching {
      java.nio.file.Files.createSymbolicLink(link.toPath(), realDir.toPath())
    }.isSuccess
    org.junit.Assume.assumeTrue("cannot create symlinks on this platform/user; skipping", linkCreated)

    val expanded = TrailCommand.expandTrailFiles(listOf(link))

    assertEquals(
      listOf(trail.canonicalPath),
      expanded.map { it.canonicalPath },
      "a symlinked directory passed explicitly must expand via its canonical path",
    )
  }

  @Test
  fun `overlapping symlinked directory and file arguments still dedupe to one run`() {
    // The symlinked-root canonicalization is walk-only: results are mapped back into the
    // argument's path frame. Without that mapping, `trailblaze run trails-link
    // trails-link/foo.trail.yaml` would emit the directory expansion in the REAL path frame
    // while the explicit file argument stays in the symlink frame — two different absolute
    // paths for one physical trail, defeating the `distinctBy { it.absoluteFile }` contract.
    val realDir = File(tempFolder.root, "real-overlap").apply { mkdirs() }
    val trail = writeUnifiedTrail("overlap-scenario.trail.yaml", realDir, id = "real/overlap-scenario")
    val link = File(tempFolder.root, "overlap-link")
    val linkCreated = runCatching {
      java.nio.file.Files.createSymbolicLink(link.toPath(), realDir.toPath())
    }.isSuccess
    org.junit.Assume.assumeTrue("cannot create symlinks on this platform/user; skipping", linkCreated)

    val expanded = TrailCommand.expandTrailFiles(listOf(link, File(link, trail.name)))

    assertEquals(1, expanded.size, "one physical trail passed via two frames must run once")
    assertEquals(trail.canonicalPath, expanded.single().canonicalPath)
    assertTrue(
      expanded.single().absolutePath.startsWith(link.absolutePath),
      "expansion must stay in the symlink path frame the user typed; got ${expanded.single()}",
    )
  }

  @Test
  fun `an explicitly-passed directory with an excluded name is still scanned`() {
    // The exclude set only prunes SUBdirectories of the walk. A user running
    // `trailblaze run build/generated-trails` named that directory explicitly — refusing to
    // scan it because of its name would be baffling.
    val buildDir = File(tempFolder.root, "build").apply { mkdirs() }
    val trail = writeUnifiedTrail("generated-scenario.trail.yaml", buildDir, id = "gen/generated-scenario")

    val expanded = TrailCommand.expandTrailFiles(listOf(buildDir))

    assertEquals(
      listOf(trail.canonicalPath),
      expanded.map { it.canonicalPath },
      "an explicitly-named directory is scanned regardless of the exclude list",
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
  fun `directory with blaze and ios iphone recording picks the recording given multi-segment classifiers`() {
    // Regression for the load-vs-save filename gap. Save-side writes recordings using the
    // host classifier's full list (`ios-iphone.trail.yaml` for an iPhone sim). Load-side
    // used to compute classifiers as platform-only at CLI plan time, so the candidate
    // filename list was `[ios.trail.yaml, blaze.yaml, ...]` — no match for
    // `ios-iphone.trail.yaml` on disk, silent fallback to `blaze.yaml`, paid LLM run.
    // [DeviceClassifierResolver] now produces the multi-segment list at plan time; this
    // test pins that `findBestTrailResourcePath` picks the recording end-to-end when fed
    // those classifiers.
    val trailDir = File(tempFolder.root, "ios-iphone-recording").apply { mkdirs() }
    writeTrail("blaze.yaml", dir = trailDir)
    val iosIphoneRecording = writeTrail("ios-iphone.trail.yaml", dir = trailDir)

    val expanded = TrailCommand.expandTrailFiles(
      files = listOf(trailDir),
      deviceClassifiers = listOf(
        TrailblazeDeviceClassifier("ios"),
        TrailblazeDeviceClassifier("iphone"),
      ),
    )

    assertEquals(1, expanded.size)
    assertEquals(iosIphoneRecording.canonicalPath, expanded.single().canonicalPath)
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
  fun `directory with only platform-prefixed recording falls back to that recording over blaze yaml`() {
    // The recording-emitter writes filenames using the device's *full* classifier list (e.g.
    // `ios-iphone.trail.yaml` for a booted iPhone), but at CLI expansion time
    // [parseDeviceClassifiersFromSpec] only produces the platform classifier (`[ios]`). The
    // resolver's candidate list is `[ios.trail.yaml, blaze.yaml, trailblaze.yaml]` for that
    // input, so it falls through to `blaze.yaml` — silently bypassing the only recording on
    // disk. `--use-recorded-steps` then has no effect because the loaded YAML has no
    // `recording:` blocks. Fix: when the resolver returns an NL fallback but a
    // `<platform>-*.trail.yaml` exists in the directory, prefer the platform-prefixed
    // recording. Without this fallback, `./trailblaze run <dir> --device ios/<udid>`
    // against the canonical iOS Contacts example (which ships `ios-iphone.trail.yaml`)
    // silently fires the LLM despite the explicit replay flag.
    val trailDir = File(tempFolder.root, "ios-recording-only").apply { mkdirs() }
    writeTrail("blaze.yaml", dir = trailDir)
    val iphoneRecording = writeTrail("ios-iphone.trail.yaml", dir = trailDir)

    val expanded = TrailCommand.expandTrailFiles(
      files = listOf(trailDir),
      deviceClassifiers = TrailCommand.parseDeviceClassifiersFromSpec("ios"),
    )

    assertEquals(1, expanded.size)
    assertEquals(iphoneRecording.canonicalPath, expanded.single().canonicalPath)
  }

  @Test
  fun `directory with multiple platform-prefixed recordings prefers the most-specific one by filename length`() {
    // Tie-breaker for the platform-prefixed fallback: when an author ships multiple
    // device-class captures in one directory (e.g. ios-iphone + ios-ipad), the CLI's
    // single-classifier limitation can't disambiguate further. Prefer the longer
    // filename (more classifier segments hyphenated into the name) as a proxy for
    // "most specific" — picks `ios-iphone.trail.yaml` (21 chars) over
    // `ios-ipad.trail.yaml` (19 chars).
    val trailDir = File(tempFolder.root, "ios-multi").apply { mkdirs() }
    writeTrail("blaze.yaml", dir = trailDir)
    writeTrail("ios-ipad.trail.yaml", dir = trailDir)
    val iphoneRecording = writeTrail("ios-iphone.trail.yaml", dir = trailDir)

    val expanded = TrailCommand.expandTrailFiles(
      files = listOf(trailDir),
      deviceClassifiers = TrailCommand.parseDeviceClassifiersFromSpec("ios"),
    )

    assertEquals(1, expanded.size)
    assertEquals(iphoneRecording.canonicalPath, expanded.single().canonicalPath)
  }

  @Test
  fun `equal-length platform-prefixed recordings tie-break alphabetically for determinism`() {
    // Stable tiebreaker when "most specific" can't disambiguate: equal filename length
    // → alphabetical-first wins. Pins the deterministic ordering so two captures of the
    // same device-class width in one dir don't flap. Same length (both 21 chars):
    // `ios-iphone.trail.yaml` < `ios-iwatch.trail.yaml` alphabetically.
    val trailDir = File(tempFolder.root, "ios-tied").apply { mkdirs() }
    writeTrail("blaze.yaml", dir = trailDir)
    val iphoneRecording = writeTrail("ios-iphone.trail.yaml", dir = trailDir)
    writeTrail("ios-iwatch.trail.yaml", dir = trailDir)

    val expanded = TrailCommand.expandTrailFiles(
      files = listOf(trailDir),
      deviceClassifiers = TrailCommand.parseDeviceClassifiersFromSpec("ios"),
    )

    assertEquals(1, expanded.size)
    assertEquals(iphoneRecording.canonicalPath, expanded.single().canonicalPath)
  }

  @Test
  fun `platform-prefixed recording fallback only fires when the resolver returned an NL definition`() {
    // Confirms the fix doesn't perturb the normal path: when an exact-match `<platform>.trail.yaml`
    // exists, it wins over any `<platform>-*.trail.yaml` sibling. The fallback only kicks in
    // when the resolver had no recording-shaped candidate and fell through to `blaze.yaml`.
    val trailDir = File(tempFolder.root, "ios-exact-plus-class").apply { mkdirs() }
    writeTrail("blaze.yaml", dir = trailDir)
    val exactRecording = writeTrail("ios.trail.yaml", dir = trailDir)
    writeTrail("ios-iphone.trail.yaml", dir = trailDir)

    val expanded = TrailCommand.expandTrailFiles(
      files = listOf(trailDir),
      deviceClassifiers = TrailCommand.parseDeviceClassifiersFromSpec("ios"),
    )

    assertEquals(1, expanded.size)
    assertEquals(exactRecording.canonicalPath, expanded.single().canonicalPath)
  }

  @Test
  fun `directory of distinct named scenarios expands to every scenario, not just the first`() {
    // Canonical unified named-file layout: one category directory holds several DISTINCT
    // scenarios, each a self-contained `<scenario>.trail.yaml` with its own `config.id`.
    // Pre-fix, directory mode grouped only by parent dir and picked the alphabetically-first
    // file (`findBestTrailResourcePath` collapses one dir to one trail), silently skipping the
    // rest. Content detection recognizes each unified file as its own scenario so all of them run.
    val catalog = File(tempFolder.root, "catalog").apply { mkdirs() }
    val conditional =
      writeUnifiedTrail("conditional-item.trail.yaml", catalog, id = "sample/catalog/conditional-item")
    val multiple =
      writeUnifiedTrail("multiple-items.trail.yaml", catalog, id = "sample/catalog/multiple-items")
    val overlay = writeUnifiedTrail("overlay-tap.trail.yaml", catalog, id = "sample/catalog/overlay-tap")

    val capturedErr = ByteArrayOutputStream()
    val expanded = withCapturedStderr(capturedErr) {
      TrailCommand.expandTrailFiles(
        files = listOf(catalog),
        deviceClassifiers = TrailCommand.parseDeviceClassifiersFromSpec("android"),
      )
    }

    assertEquals(
      listOf(conditional, multiple, overlay).map { it.canonicalPath }.sorted(),
      expanded.map { it.canonicalPath }.sorted(),
      "every distinct named scenario in the directory should expand, not just the first",
    )
    // A named scenario's filename is not classifier-derived, so the resolver legitimately finds
    // no `<platform>.trail.yaml` match — but running the self-contained unified file is exactly
    // right, so no device-mismatch warning should fire (that warning is legacy-bucket only).
    val stderrText = capturedErr.toString(StandardCharsets.UTF_8)
    assertTrue(
      !stderrText.contains("Warning:"),
      "named-scenario expansion must not emit a device-mismatch warning; got stderr=\n$stderrText",
    )
  }

  @Test
  fun `directory with blaze and a lone id-less recording still collapses to one run`() {
    // Legacy layout: a single trail expressed as `blaze.yaml` (NL) plus one classifier-variant
    // recording, neither carrying a `config.id`. Sub-grouping must keep these id-less siblings
    // in one identity bucket so the directory still collapses to a single pick — the recording,
    // matched against `--device android` — rather than running both as if distinct scenarios.
    val trailDir = File(tempFolder.root, "legacy-trail").apply { mkdirs() }
    writeTrail("blaze.yaml", dir = trailDir)
    val androidRecording = writeTrail("android.trail.yaml", dir = trailDir)

    val expanded = TrailCommand.expandTrailFiles(
      files = listOf(trailDir),
      deviceClassifiers = TrailCommand.parseDeviceClassifiersFromSpec("android"),
    )

    assertEquals(1, expanded.size)
    assertEquals(androidRecording.canonicalPath, expanded.single().canonicalPath)
  }

  @Test
  fun `mixed directory expands named scenarios and collapses the legacy trail`() {
    // A directory holding BOTH distinct named scenarios (unified, each with a `config.id`) AND
    // a legacy id-less `blaze.yaml` + recording pair. The named scenarios each expand; the
    // id-less pair collapses to one pick (the classifier-matched recording). Net: 2 + 1 = 3.
    val mixed = File(tempFolder.root, "mixed").apply { mkdirs() }
    val scenarioA = writeUnifiedTrail("scenario-a.trail.yaml", mixed, id = "sample/mixed/scenario-a")
    val scenarioB = writeUnifiedTrail("scenario-b.trail.yaml", mixed, id = "sample/mixed/scenario-b")
    writeTrail("blaze.yaml", dir = mixed)
    val legacyAndroid = writeTrail("android.trail.yaml", dir = mixed)

    val expanded = TrailCommand.expandTrailFiles(
      files = listOf(mixed),
      deviceClassifiers = TrailCommand.parseDeviceClassifiersFromSpec("android"),
    )

    assertEquals(
      listOf(scenarioA, scenarioB, legacyAndroid).map { it.canonicalPath }.sorted(),
      expanded.map { it.canonicalPath }.sorted(),
      "named scenarios each expand; the id-less blaze+recording pair collapses to the recording",
    )
  }

  @Test
  fun `directory of id-less unified scenarios still expands to every scenario`() {
    // The eval suite (`trails/eval/android/`) holds several distinct unified scenarios in ONE
    // directory, none carrying a `config.id`. Content detection expands each; a `config.id`-based
    // grouping would collapse them all into a single pick and silently skip the rest — this pins
    // that expansion keys off unified *content*, not the presence of an id.
    val evalDir = File(tempFolder.root, "eval").apply { mkdirs() }
    val tenKey = writeUnifiedTrail("tenKey.trail.yaml", evalDir)
    val openUrl = writeUnifiedTrail("openUrl.trail.yaml", evalDir)
    val goOffline = writeUnifiedTrail("goOffline.trail.yaml", evalDir)

    val expanded = TrailCommand.expandTrailFiles(
      files = listOf(evalDir),
      deviceClassifiers = TrailCommand.parseDeviceClassifiersFromSpec("android"),
    )

    assertEquals(
      listOf(tenKey, openUrl, goOffline).map { it.canonicalPath }.sorted(),
      expanded.map { it.canonicalPath }.sorted(),
      "every id-less unified scenario should expand, not just one",
    )
  }

  @Test
  fun `named scenarios expand without a device spec too`() {
    // Bare `trailblaze run <dir>` — no `--device`, so deviceClassifiers is empty — is the most
    // common interactive invocation. Named-scenario expansion must not depend on classifier
    // resolution: every unified file still expands and no device-mismatch warning fires.
    val dir = File(tempFolder.root, "no-device").apply { mkdirs() }
    val first = writeUnifiedTrail("first-scenario.trail.yaml", dir)
    val second = writeUnifiedTrail("second-scenario.trail.yaml", dir)

    val capturedErr = ByteArrayOutputStream()
    val expanded = withCapturedStderr(capturedErr) {
      TrailCommand.expandTrailFiles(files = listOf(dir), deviceClassifiers = emptyList())
    }

    assertEquals(
      listOf(first, second).map { it.canonicalPath }.sorted(),
      expanded.map { it.canonicalPath }.sorted(),
    )
    val stderrText = capturedErr.toString(StandardCharsets.UTF_8)
    assertTrue(
      !stderrText.contains("Warning:"),
      "named-scenario expansion without --device must not warn; got stderr=\n$stderrText",
    )
  }

  @Test
  fun `sibling subdirectories partition independently in one walk`() {
    // One walk root holding a named-scenario dir AND a legacy blaze+recording dir. Each
    // directory's partition is independent: the named dir expands both scenarios, the legacy
    // dir collapses to its classifier-matched recording. Net: 2 + 1 = 3.
    val root = File(tempFolder.root, "tree").apply { mkdirs() }
    val namedDir = File(root, "named").apply { mkdirs() }
    val scenarioA = writeUnifiedTrail("scenario-a.trail.yaml", namedDir, id = "tree/scenario-a")
    val scenarioB = writeUnifiedTrail("scenario-b.trail.yaml", namedDir, id = "tree/scenario-b")
    val legacyDir = File(root, "legacy").apply { mkdirs() }
    writeTrail("blaze.yaml", dir = legacyDir)
    val legacyRecording = writeTrail("android.trail.yaml", dir = legacyDir)

    val expanded = TrailCommand.expandTrailFiles(
      files = listOf(root),
      deviceClassifiers = TrailCommand.parseDeviceClassifiersFromSpec("android"),
    )

    assertEquals(
      listOf(scenarioA, scenarioB, legacyRecording).map { it.canonicalPath }.sorted(),
      expanded.map { it.canonicalPath }.sorted(),
      "each subdirectory must partition independently: named dir expands all, legacy dir collapses",
    )
  }

  @Test
  fun `an NL definition file never expands as a named scenario whatever its content`() {
    // `blaze.yaml` / `trailblaze.yaml` are single-trail representatives by convention. Even if
    // one carried unified-shaped content (a root-level `trail:` key), it must stay in the legacy
    // bucket — expanding it as a named scenario while it also serves as the bucket's NL fallback
    // would run the same trail twice.
    val dir = File(tempFolder.root, "nl-unified-shaped").apply { mkdirs() }
    val blaze = writeUnifiedTrail("blaze.yaml", dir)

    val expanded = TrailCommand.expandTrailFiles(files = listOf(dir))

    assertEquals(
      listOf(blaze.canonicalPath),
      expanded.map { it.canonicalPath },
      "a unified-shaped blaze.yaml must resolve once via the legacy bucket, not twice",
    )
  }

  @Test
  fun `unreadable trail file falls into the legacy bucket instead of crashing expansion`() {
    // Defensive branch: the walk filters `isFile`, so an unreadable file is only reachable when
    // permissions deny the read. Expansion must not crash; the file lands in the legacy bucket
    // and resolves like any recording (here: the dir's only file, picked with a warning).
    val dir = File(tempFolder.root, "unreadable").apply { mkdirs() }
    val locked = writeUnifiedTrail("locked-scenario.trail.yaml", dir)
    org.junit.Assume.assumeTrue(
      "cannot revoke read permission on this platform/user; skipping",
      locked.setReadable(false) && !locked.canRead(),
    )
    try {
      val capturedErr = ByteArrayOutputStream()
      val expanded = withCapturedStderr(capturedErr) {
        TrailCommand.expandTrailFiles(files = listOf(dir))
      }
      assertEquals(listOf(locked.canonicalPath), expanded.map { it.canonicalPath })
    } finally {
      locked.setReadable(true)
    }
  }

  @Test
  fun `named unified scenario with an unquoted template still expands`() {
    // Unified trails may carry `{{var}}` templates that are invalid as raw YAML until resolved
    // (an unquoted `{{VAR}}` reads as a flow mapping). Content detection must classify such a
    // file as a named scenario anyway — the run path resolves the template before decoding, so
    // the file is perfectly runnable and skipping it (or folding it into the legacy bucket)
    // would silently drop a real scenario.
    val dir = File(tempFolder.root, "templated").apply { mkdirs() }
    val templated = File(dir, "templated-scenario.trail.yaml")
    templated.writeText(
      buildString {
        appendLine("config:")
        appendLine("  target: sample")
        appendLine("trail:")
        appendLine("  - step: Enter the code")
        appendLine("    recording:")
        appendLine("      android:")
        appendLine("        - inputText:")
        appendLine("            text: {{TRAILBLAZE_CODE}}")
      },
    )
    val plain = writeUnifiedTrail("plain-scenario.trail.yaml", dir)

    val expanded = TrailCommand.expandTrailFiles(
      files = listOf(dir),
      deviceClassifiers = TrailCommand.parseDeviceClassifiersFromSpec("android"),
    )

    assertEquals(
      listOf(templated, plain).map { it.canonicalPath }.sorted(),
      expanded.map { it.canonicalPath }.sorted(),
      "a template-bearing unified scenario must expand alongside its plain sibling",
    )
  }

  @Test
  fun `undecodable unified-shaped file still expands on its own so the runner surfaces the error`() {
    // A file that is unified by content shape (root-level `trail:`) but fails to decode must
    // expand as its own entry rather than fall into the legacy bucket: the runner then reports
    // the decode error against that file, and the healthy sibling scenarios are unaffected.
    val dir = File(tempFolder.root, "broken").apply { mkdirs() }
    val broken = File(dir, "broken-scenario.trail.yaml")
    broken.writeText("config:\n  target: sample\ntrail:\n  - step: [unclosed\n")
    val healthy = writeUnifiedTrail("healthy-scenario.trail.yaml", dir)

    val expanded = TrailCommand.expandTrailFiles(
      files = listOf(dir),
      deviceClassifiers = TrailCommand.parseDeviceClassifiersFromSpec("android"),
    )

    assertEquals(
      listOf(broken, healthy).map { it.canonicalPath }.sorted(),
      expanded.map { it.canonicalPath }.sorted(),
      "an undecodable unified-shaped file must still expand as its own entry",
    )
  }

  @Test
  fun `mid-migration directory runs the bare trail yaml and every named sibling`() {
    // A directory can transiently hold the bare `trail.yaml` (the directory's canonical unified
    // trail — identity comes from the directory) AND named unified siblings. The bare file is a
    // single-trail representative resolved through the legacy bucket; the named siblings each
    // expand. Net: N named + 1 bare.
    val dir = File(tempFolder.root, "mid-migration").apply { mkdirs() }
    val bare = writeUnifiedTrail("trail.yaml", dir)
    val named = writeUnifiedTrail("extra-scenario.trail.yaml", dir, id = "sample/extra-scenario")

    val expanded = TrailCommand.expandTrailFiles(
      files = listOf(dir),
      deviceClassifiers = TrailCommand.parseDeviceClassifiersFromSpec("android"),
    )

    assertEquals(
      listOf(bare, named).map { it.canonicalPath }.sorted(),
      expanded.map { it.canonicalPath }.sorted(),
      "the bare trail.yaml resolves via the legacy bucket while named siblings expand",
    )
  }

  @Test
  fun `v1 classifier-variant recordings with inconsistent config ids still collapse to one run`() {
    // Regression guard for the real-world layout where a single trail's v1 classifier-variant
    // recordings carry MUTUALLY INCONSISTENT config.ids — one capture batch stamped a `generated/…`
    // id, another a plain id (observed on a real generated trail). These are v1 documents, so
    // content detection keeps every one in the legacy bucket and the directory collapses to a
    // single classifier-priority pick. Pins that expansion deliberately does NOT group by
    // config.id — which would wrongly split the inconsistent-id variants into separate runs.
    val trailDir = File(tempFolder.root, "multi-device-trail").apply { mkdirs() }
    val androidPhone =
      writeTrail("android-phone.trail.yaml", dir = trailDir, id = "generated/checkout-flow/run-77")
    writeTrail("android-tablet.trail.yaml", dir = trailDir, id = "checkout-flow")
    writeTrail("ios-iphone.trail.yaml", dir = trailDir, id = "generated/checkout-flow/run-77")
    writeTrail("ios-ipad.trail.yaml", dir = trailDir, id = "checkout-flow")

    val expanded = TrailCommand.expandTrailFiles(
      files = listOf(trailDir),
      deviceClassifiers = listOf(
        TrailblazeDeviceClassifier("android"),
        TrailblazeDeviceClassifier("phone"),
      ),
    )

    // `[android, phone]` → candidate `android-phone.trail.yaml` matches → exactly that one file.
    assertEquals(1, expanded.size)
    assertEquals(androidPhone.canonicalPath, expanded.single().canonicalPath)
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
  fun `unified per-classifier skip resolves against configClassifiers - driver-only run`() {
    // Pins the driver-only-run fix: when a run pins --driver but no --device, the planner passes
    // the driver's platform classifier as configClassifiers, so an android-only skip halts an
    // android run but not an ios one — instead of the device-agnostic any-skip fallback firing.
    val file = File(tempFolder.root, "android-skip.trail.yaml")
    file.writeText(
      """
      config:
        target: myapp
        skip:
          android: "flaky on android — see #123"
      trail:
        - step: Do the thing
          recording:
            android:
              - pressBack: {}
      """.trimIndent() + "\n",
    )

    fun planItemWith(vararg classifiers: String) = TrailCommand.planTrailExecution(
      files = listOf(file),
      includeTags = emptyList(),
      configClassifiers = classifiers.map { TrailblazeDeviceClassifier(it) },
    ).items.single()

    // Android driver → android classifier → skipped with the android reason.
    val androidItem = planItemWith("android")
    assertIs<TrailExecutionItem.Skip>(androidItem)
    assertEquals("flaky on android — see #123", androidItem.reason)
    // iOS driver → ios classifier → NOT skipped (per-platform skip doesn't apply to ios).
    assertIs<TrailExecutionItem.Run>(planItemWith("ios"))
    // No device and no driver (device-agnostic) → any-skip fallback → skipped.
    assertIs<TrailExecutionItem.Skip>(
      TrailCommand.planTrailExecution(files = listOf(file), includeTags = emptyList()).items.single(),
    )
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
