package xyz.block.trailblaze.mcp.newtools

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertFalse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.mcp.RecordedStep
import xyz.block.trailblaze.mcp.RecordedStepType
import xyz.block.trailblaze.mcp.RecordedToolCall
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.ToolRecording
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.createTrailblazeYaml

/**
 * Tests for [TrailFileManager] — the callers route trail enumeration through
 * [xyz.block.trailblaze.config.project.TrailDiscovery].
 *
 * The surface under test:
 *  - [TrailFileManager.findTrailByName] now calls `TrailDiscovery.findFirstTrail`,
 *    which short-circuits; these tests pin the unified discovery contract
 *    (`.trail.yaml` + `blaze.yaml` + nested `trailblaze.yaml`) and the build-output
 *    exclusion end-to-end, not just at the discovery layer.
 *  - [TrailFileManager.listTrails] now paginates before reading YAML titles when no
 *    filter is supplied; these tests cover the page-boundary arithmetic and confirm
 *    hardcoded excludes propagate through the listing.
 */
class TrailFileManagerTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val trailsDir: File get() = tempFolder.root

  private fun newTrail(relativePath: String): File {
    val file = File(trailsDir, relativePath)
    file.parentFile?.mkdirs()
    // Minimal parseable trail so `readTrailTitle` doesn't throw — the title isn't
    // asserted, only that pagination does not open files outside its page slice.
    file.writeText("steps: []\n")
    return file
  }

  private fun manager() = TrailFileManager(trailsDir.absolutePath)

  @Test
  fun `findTrailByName matches a nested blaze yaml by parent-directory name`() {
    // The unified contract (e365c93) discovers blaze.yaml alongside .trail.yaml.
    // A user searching for "checkout" should resolve to `flows/checkout/blaze.yaml`
    // via the parent-dir-name match in `findTrailByName`.
    newTrail("flows/checkout/blaze.yaml")

    val result = manager().findTrailByName("checkout")

    assertNotNull(result, "findTrailByName did not resolve blaze.yaml by parent-dir name")
    assertTrue(result.endsWith("/flows/checkout/blaze.yaml"), "unexpected path: $result")
  }

  @Test
  fun `findTrailByName ignores matches under build directory`() {
    // Sanity that TrailDiscovery's hardcoded excludes propagate through
    // TrailFileManager's name lookup — a stale cached trail under `build/` must not
    // be offered as a resolution for the same trail name outside `build/`.
    newTrail("build/flows/login/login.trail.yaml")
    newTrail("flows/login/login.trail.yaml")

    val result = manager().findTrailByName("login")

    assertNotNull(result)
    assertTrue(
      !result.contains("${File.separator}build${File.separator}"),
      "findTrailByName returned an excluded-dir path: $result",
    )
  }

  @Test
  fun `findTrailByName returns null when nothing matches`() {
    newTrail("flows/login.trail.yaml")

    val result = manager().findTrailByName("totally-unknown")

    assertNull(result)
  }

  @Test
  fun `listTrails paginates across multiple pages in sorted order`() {
    // Create 25 trails — two full pages of 10 and a short third page of 5.
    repeat(25) { newTrail("flows/trail-${it.toString().padStart(2, '0')}.trail.yaml") }

    val page1 = manager().listTrails(page = 1, pageSize = 10)
    val page2 = manager().listTrails(page = 2, pageSize = 10)
    val page3 = manager().listTrails(page = 3, pageSize = 10)

    assertEquals(25, page1.totalCount)
    assertEquals(10, page1.trails.size)
    assertTrue(page1.hasMore)
    assertEquals(10, page2.trails.size)
    assertTrue(page2.hasMore)
    assertEquals(5, page3.trails.size)
    assertTrue(!page3.hasMore, "short final page should not claim hasMore")

    // Pages must be disjoint and reconstruct the total set in absolute-path order.
    val concatenated = page1.trails.map { it.path } +
      page2.trails.map { it.path } +
      page3.trails.map { it.path }
    assertEquals(concatenated, concatenated.sorted(), "page ordering is not stable across pages")
    assertEquals(25, concatenated.toSet().size, "pages overlap: $concatenated")
  }

  @Test
  fun `listTrails returns empty page past the end without crashing`() {
    repeat(3) { newTrail("trail-$it.trail.yaml") }

    val page = manager().listTrails(page = 99, pageSize = 10)

    assertEquals(3, page.totalCount)
    assertEquals(0, page.trails.size)
    assertTrue(!page.hasMore)
  }

  @Test
  fun `listTrails filter matches a trail's title even when the path does not contain it`() {
    // Pins the slow-path branch (filter != null): readTrailTitle reads every YAML to
    // evaluate `info.title.contains(filter)`. A regression that moves the filter
    // before the YAML-read or drops title-matching would surface here.
    val titled = File(trailsDir, "obscure-path/a.trail.yaml").apply {
      parentFile?.mkdirs()
      writeText(
        """
        - config:
            id: "matches-only-by-title"
            title: "Checkout Happy Path"
        """.trimIndent(),
      )
    }
    val unrelated = newTrail("flows/settings.trail.yaml")

    val page = manager().listTrails(filter = "Checkout")

    assertEquals(1, page.totalCount, "title-only filter hit: ${page.trails}")
    assertEquals(titled.relativeTo(trailsDir).path, page.trails.single().path)
    assertEquals("Checkout Happy Path", page.trails.single().title)
    // Sanity — the unrelated trail was discovered but filtered out by the title+path
    // predicate; it must not appear in the page.
    assertTrue(page.trails.none { it.path == unrelated.relativeTo(trailsDir).path })
  }

  @Test
  fun `listTrails excludes build output trails`() {
    newTrail("flows/login.trail.yaml")
    newTrail("build/generated/stale.trail.yaml")
    newTrail(".gradle/cached.trail.yaml")

    val page = manager().listTrails()

    assertEquals(1, page.totalCount, "excluded dirs leaked into listing: ${page.trails}")
    assertEquals("flows/login.trail.yaml", page.trails.single().path)
  }

  @Test
  fun `trailNameToDirSlug collapses internal dash runs from spaced separators`() {
    // The original sanitizer (`name.replace(" ", "-").lowercase()`) mapped each
    // character of " - " to its own '-' and produced "cli-review-test---example.com".
    // Pin the collapsed-run behavior so the run-collapse can't silently regress.
    assertEquals(
      "cli-review-test-example.com",
      trailNameToDirSlug("CLI review test - example.com"),
    )
  }

  @Test
  fun `trailNameToDirSlug trims leading and trailing dashes`() {
    assertEquals("trail", trailNameToDirSlug("  trail  "))
    assertEquals("trail", trailNameToDirSlug("--trail--"))
  }

  @Test
  fun `saveTrail rejects a blank-slug title before writing to disk`() {
    // Regression for codex PR review on #3629: a title of " - " or "---" used to
    // sanitize to "" and then `File(dir, "")` resolved to the trails root, so
    // saveTrail would silently write `<platform>.trail.yaml` at the root,
    // potentially clobbering an unrelated trail file. The empty-slug guard in
    // validateTrailNameSlug closes that hole.
    val sentinel = newTrail("android.trail.yaml") // a pre-existing top-level trail
    val sentinelBytesBefore = sentinel.readBytes()

    val steps = listOf(
      RecordedStep(
        type = RecordedStepType.STEP,
        input = "Tap login",
        result = "ok",
        success = true,
      ),
    )
    val result = manager().saveTrail(
      name = " - ",
      steps = steps,
      platform = TrailblazeDevicePlatform.ANDROID,
    )

    assertTrue(!result.success, "blank-slug saveTrail should fail; got: $result")
    val error = assertNotNull(result.error, "blank-slug saveTrail must surface an error message")
    assertTrue(
      error.contains("alphanumeric") || error.contains("empty slug"),
      "error message should explain the blank-slug cause; got: $error",
    )
    assertEquals(
      sentinelBytesBefore.toList(),
      sentinel.readBytes().toList(),
      "saveTrail must NOT overwrite a root-level sibling when the slug is blank",
    )
  }

  @Test
  fun `saveTrail rejects a dashes-only title that would sanitize to empty`() {
    val steps = listOf(
      RecordedStep(
        type = RecordedStepType.STEP,
        input = "Tap login",
        result = "ok",
        success = true,
      ),
    )
    val result = manager().saveTrail(
      name = "---",
      steps = steps,
      platform = TrailblazeDevicePlatform.ANDROID,
    )

    assertTrue(!result.success, "dashes-only saveTrail should fail; got: $result")
    // No platform-named file should be created at the trails root either.
    assertTrue(
      !File(trailsDir, "android.trail.yaml").exists(),
      "saveTrail must not create a root-level <platform>.trail.yaml on rejection",
    )
  }

  @Test
  fun `editing a trail does not surface or drop its trailhead root element`() {
    val file = File(trailsDir, "flows/p2p/p2p.trail.yaml")
    file.parentFile?.mkdirs()
    file.writeText(
      "- config:\n    target: myapp\n- trailhead: myapp_freshInstall\n- prompts:\n  - step: Tap Pay\n",
    )
    val mgr = manager()

    val (config, steps) = mgr.getEditableSteps(file.absolutePath)
      ?: error("getEditableSteps returned null")
    // The trailhead is NOT an editable step — only the real prompt is.
    assertEquals(1, steps.size)
    assertEquals("Tap Pay", steps[0].prompt)

    // An unrelated edit (append a step) must not drop the trailhead.
    val edited = steps + TrailFileManager.EditableStep(prompt = "Tap Confirm", type = "step", recording = null)
    val result = mgr.saveEditedSteps(file.absolutePath, config, edited)
    assertTrue(result.success, "save failed: ${result.error}")

    // The trailhead survives the edit. (The bare-string shorthand canonicalizes to the
    // `{ tools: [...] }` form on re-emit — semantically identical, re-parses the same.)
    val rewritten = file.readText()
    assertTrue(rewritten.contains("trailhead:"), "trailhead lost on save:\n$rewritten")
    assertTrue(rewritten.contains("myapp_freshInstall"), "trailhead tool lost on save:\n$rewritten")
    assertTrue(rewritten.contains("Tap Confirm"), "edit not applied:\n$rewritten")
  }

  // ---------------------------------------------------------------------------
  // loadTrail — unified trails and device classifiers
  // ---------------------------------------------------------------------------

  /** A unified trail whose single step carries an `android:` recording. */
  private fun newUnifiedTrailWithAndroidRecording(relativePath: String): File {
    val file = File(trailsDir, relativePath)
    file.parentFile?.mkdirs()
    file.writeText(
      """
      config:
        id: app/x
      trail:
        - step: "Tap the thing"
          recording:
            android:
              - someRecordedTool:
                  marker: hi
      """.trimIndent(),
    )
    return file
  }

  @Test
  fun `loadTrail lowers a unified trail's recording for the given device classifiers`() {
    val file = newUnifiedTrailWithAndroidRecording("flows/unified/trail.yaml")

    val result = manager().loadTrail(file.absolutePath, listOf(TrailblazeDeviceClassifier("android")))

    assertTrue(result.success, "load failed: ${result.error}")
    val step = result.promptSteps?.single() as DirectionStep
    assertNotNull(step.recording, "the android recording must lower for an [android] device")
    assertEquals("Tap the thing", step.step)
  }

  @Test
  fun `loadTrail without classifiers refuses a unified trail with recordings, actionably`() {
    // decodeTrail's guard fires; the failure must surface as a LoadResult error that tells the
    // caller to bind a device — not the raw internal guard text via the generic catch.
    val file = newUnifiedTrailWithAndroidRecording("flows/unified2/trail.yaml")

    val result = manager().loadTrail(file.absolutePath)

    assertTrue(!result.success, "a unified-with-recordings trail must not load with no classifiers")
    assertNotNull(result.error)
    assertTrue(result.error!!.contains("device"), "error should point at binding a device: ${result.error}")
  }

  // ---------------------------------------------------------------------------
  // saveTrail — unified-recordings rollout gate
  // ---------------------------------------------------------------------------

  // Uses a fabricated tool name (not a real classpath tool) so the OtherTrailblazeTool wrapper
  // round-trips through the unified encode/decode as-is — matching the sibling CLI tests. A real
  // tool name would re-decode as that tool's real (stricter) schema.
  private fun recordedSteps(toolName: String = "recordedTapCart") = listOf(
    RecordedStep(
      type = RecordedStepType.STEP,
      input = "Tap login",
      toolCalls = listOf(RecordedToolCall(toolName = toolName, args = mapOf("text" to "Login"))),
      result = "ok",
      success = true,
    ),
  )

  @Test
  fun `saveTrail gate off writes a legacy platform sibling in a plain directory`() {
    // Byte-identical to the pre-unified behavior: with the gate off and no unified trail present,
    // a save lands as <platform>.trail.yaml.
    val result = TrailFileManager(trailsDir.absolutePath, unifiedRecordingsEnabled = { false })
      .saveTrail(name = "flow", steps = recordedSteps(), platform = TrailblazeDevicePlatform.ANDROID)

    assertTrue(result.success, "save failed: ${result.error}")
    assertTrue(File(trailsDir, "flow/android.trail.yaml").isFile, "expected a legacy sibling")
    assertFalse(File(trailsDir, "flow/${TrailRecordings.UNIFIED_TRAIL_FILENAME}").exists(), "no unified file when gate off")
  }

  @Test
  fun `saveTrail gate off refuses to write a legacy sibling next to a unified trail`() {
    // The refusal guard: never drop a legacy sibling into a migrated directory (the legacy write
    // can't update the unified file, so it would only shadow it).
    val trailDir = File(trailsDir, "flow").apply { mkdirs() }
    val unified = File(trailDir, TrailRecordings.UNIFIED_TRAIL_FILENAME)
      .apply { writeText("config:\n  id: app/x\ntrail:\n  - step: Tap login\n") }
    val bytesBefore = unified.readBytes()

    val result = TrailFileManager(trailsDir.absolutePath, unifiedRecordingsEnabled = { false })
      .saveTrail(name = "flow", steps = recordedSteps(), platform = TrailblazeDevicePlatform.ANDROID)

    assertFalse(result.success, "gate-off save must be refused next to a unified trail")
    assertFalse(File(trailDir, "android.trail.yaml").exists(), "no legacy sibling dropped beside the unified trail")
    assertEquals(bytesBefore.toList(), unified.readBytes().toList(), "the unified trail must be left untouched")
  }

  @Test
  fun `saveTrail gate on merges the platform slot preserving other classifiers`() {
    val mgr = TrailFileManager(trailsDir.absolutePath, unifiedRecordingsEnabled = { true })
    // First device seeds the unified file; second device merges into the same step.
    assertTrue(mgr.saveTrail("flow", recordedSteps("iosTap"), TrailblazeDevicePlatform.IOS).success)
    val result = mgr.saveTrail("flow", recordedSteps("androidTap"), TrailblazeDevicePlatform.ANDROID)

    assertTrue(result.success, "merge save failed: ${result.error}")
    val unifiedFile = File(trailsDir, "flow/${TrailRecordings.UNIFIED_TRAIL_FILENAME}")
    assertTrue(unifiedFile.isFile, "the platform slot must merge into the unified trail.yaml")
    assertFalse(File(trailsDir, "flow/android.trail.yaml").exists(), "no legacy sibling when routing unified")
    val step = createTrailblazeYaml().decodeUnifiedTrail(unifiedFile.readText()).trail.single()
    assertEquals(listOf("iosTap"), step.recordings["ios"]?.map { it.name }, "ios slot preserved")
    assertEquals(listOf("androidTap"), step.recordings["android"]?.map { it.name }, "android slot merged in")
  }

  @Test
  fun `saveTrail gate on refuses a corrupt existing unified trail untouched`() {
    val trailDir = File(trailsDir, "flow").apply { mkdirs() }
    val corrupt = File(trailDir, TrailRecordings.UNIFIED_TRAIL_FILENAME).apply { writeText("foo: not a unified trail\n") }

    val result = TrailFileManager(trailsDir.absolutePath, unifiedRecordingsEnabled = { true })
      .saveTrail(name = "flow", steps = recordedSteps(), platform = TrailblazeDevicePlatform.ANDROID)

    assertFalse(result.success, "a corrupt unified trail must not be clobbered by a merge")
    assertEquals("foo: not a unified trail\n", corrupt.readText(), "the corrupt file must be left untouched")
  }

  // ---------------------------------------------------------------------------
  // saveTrailYaml — the log-backed save path (pre-generated v1 YAML) routes identically
  // ---------------------------------------------------------------------------

  // A fabricated tool name (not a real classpath tool) so the OtherTrailblazeTool wrapper round-trips
  // through the unified encode/decode as-is.
  private fun v1TrailYaml(toolName: String = "recordedTapCart"): String =
    createTrailblazeYaml().encodeToString(
      listOf(
        TrailYamlItem.ConfigTrailItem(TrailConfig(id = "flow", target = "app", driver = "D")),
        TrailYamlItem.PromptsTrailItem(
          listOf(DirectionStep(step = "Tap login", recording = ToolRecording(tools = listOf(yamlTool(toolName))))),
        ),
      ),
    )

  private fun yamlTool(name: String) = TrailblazeToolYamlWrapper(
    name = name,
    trailblazeTool = OtherTrailblazeTool(toolName = name, raw = JsonObject(mapOf("marker" to JsonPrimitive(name)))),
  )

  @Test
  fun `saveTrailYaml gate off writes the yaml verbatim as a legacy sibling`() {
    val yaml = v1TrailYaml()
    val result = TrailFileManager(trailsDir.absolutePath, unifiedRecordingsEnabled = { false })
      .saveTrailYaml(name = "flow", yamlContent = yaml, platform = TrailblazeDevicePlatform.ANDROID)

    assertTrue(result.success, "save failed: ${result.error}")
    val legacy = File(trailsDir, "flow/android.trail.yaml")
    assertTrue(legacy.isFile, "expected the legacy sibling")
    assertEquals(yaml, legacy.readText(), "the log-backed legacy write must be byte-identical to the generated YAML")
  }

  @Test
  fun `saveTrailYaml gate off refuses to shadow a unified trail`() {
    val trailDir = File(trailsDir, "flow").apply { mkdirs() }
    File(trailDir, TrailRecordings.UNIFIED_TRAIL_FILENAME).writeText("config:\n  id: flow\ntrail:\n  - step: Tap login\n")

    val result = TrailFileManager(trailsDir.absolutePath, unifiedRecordingsEnabled = { false })
      .saveTrailYaml(name = "flow", yamlContent = v1TrailYaml(), platform = TrailblazeDevicePlatform.ANDROID)

    assertFalse(result.success, "gate-off log-backed save must be refused next to a unified trail")
    assertFalse(File(trailDir, "android.trail.yaml").exists(), "no legacy sibling dropped beside the unified trail")
  }

  @Test
  fun `saveTrailYaml gate on merges the platform slot into the unified trail`() {
    val result = TrailFileManager(trailsDir.absolutePath, unifiedRecordingsEnabled = { true })
      .saveTrailYaml(name = "flow", yamlContent = v1TrailYaml("androidTap"), platform = TrailblazeDevicePlatform.ANDROID)

    assertTrue(result.success, "merge save failed: ${result.error}")
    val unifiedFile = File(trailsDir, "flow/${TrailRecordings.UNIFIED_TRAIL_FILENAME}")
    assertTrue(unifiedFile.isFile, "the platform slot must merge into the unified trail.yaml")
    assertFalse(File(trailsDir, "flow/android.trail.yaml").exists(), "no legacy sibling when routing unified")
    val step = createTrailblazeYaml().decodeUnifiedTrail(unifiedFile.readText()).trail.single()
    assertEquals(listOf("androidTap"), step.recordings["android"]?.map { it.name })
  }

  @Test
  fun `saveTrail with no platform never writes the reserved trail-yaml name`() {
    // A null platform (no bound device) has no classifier, so it takes the legacy path. It must land
    // as the classifier-agnostic `recording.trail.yaml`, NOT the reserved unified `trail.yaml` — a v1
    // file named `trail.yaml` would masquerade as unified and poison the directory for later saves.
    val result = TrailFileManager(trailsDir.absolutePath, unifiedRecordingsEnabled = { false })
      .saveTrail(name = "flow", steps = recordedSteps(), platform = null)

    assertTrue(result.success, "save failed: ${result.error}")
    assertTrue(File(trailsDir, "flow/recording.trail.yaml").isFile, "expected the classifier-agnostic sibling")
    assertFalse(
      File(trailsDir, "flow/${TrailRecordings.UNIFIED_TRAIL_FILENAME}").exists(),
      "a v1 save must never occupy the reserved unified trail.yaml name",
    )
  }
}
