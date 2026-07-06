package xyz.block.trailblaze.migration

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * Algorithm tests for [UnifiedTrailMigrator]. Each test materializes a tiny v1 trail
 * folder in a temp dir, runs the migrator, and asserts on the resulting unified
 * trail's shape. Family collapse cases verify the `reason:` stripping rule.
 */
class UnifiedTrailMigratorTest {

  private val yaml = TrailblazeYaml.Default
  private val migrator = UnifiedTrailMigrator(yaml)

  @Test
  fun `refuses to migrate a trail that contains a trailhead block`() {
    // Mapping a per-classifier trailhead into the unified `trailhead:` is a follow-up; until then the
    // migrator must fail fast rather than silently drop the deterministic step 0 (same policy as
    // top-level `- tools:`).
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config:
            id: x/y
            target: x
            platform: android
        - trailhead: x_freshInstall
        - prompts:
          - step: Open the app
      """.trimIndent(),
    )
    assertFailsWith<IllegalArgumentException> { migrator.migrate(dir) }
  }

  @Test
  fun `simple two-platform merge yields one step per index with both classifiers`() {
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config:
            id: x/y
            target: x
            platform: android
            driver: ANDROID_ONDEVICE_INSTRUMENTATION
        - prompts:
          - step: Open the app
            recording:
              tools:
              - tapOnPoint:
                  x: 1
                  y: 2
      """.trimIndent(),
      "ios-iphone.trail.yaml" to """
        - config:
            id: x/y
            target: x
            platform: ios
            driver: IOS_HOST
        - prompts:
          - step: Open the app
            recording:
              tools:
              - tapOnPoint:
                  x: 3
                  y: 4
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    assertEquals(1, result.trail.trail.size)
    // Each per-platform file pinned a driver → the merged `devices:` map keys each file's
    // driver under its classifier.
    assertEquals(
      mapOf(
        "android-phone" to "ANDROID_ONDEVICE_INSTRUMENTATION",
        "ios-iphone" to "IOS_HOST",
      ),
      result.trail.config.devices,
    )
    assertEquals(setOf("android-phone", "ios-iphone"), result.trail.trail[0].recordings.keys)
    assertEquals("x/y", result.trail.config.id)
    assertEquals("x", result.trail.config.target)
  }

  @Test
  fun `config description and driver are preserved through migration, not dropped`() {
    // description is runtime-surfaced (a display label); driver is an optional pin needed by
    // trails whose recordings are driver-specific. Both must survive migration — `platform`
    // and `title` are the retired fields, not these. The driver is keyed by the file's
    // classifier (`android-phone`).
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config:
            id: x/y
            target: x
            platform: android
            driver: ANDROID_ONDEVICE_ACCESSIBILITY
            description: Open the app and verify the home tab.
        - prompts:
          - step: Open the app
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    assertEquals("Open the app and verify the home tab.", result.trail.config.description)
    assertEquals(
      mapOf("android-phone" to "ANDROID_ONDEVICE_ACCESSIBILITY"),
      result.trail.config.devices,
    )
  }

  @Test
  fun `each platform file's driver is collected into the per-classifier devices map`() {
    // A multi-platform trail can't collapse to one driver — android and ios need different
    // drivers. Each per-platform file contributes its own `driver:` keyed by its classifier.
    val dir = makeDir(
      "android.trail.yaml" to """
        - config: {id: x, target: x, platform: android, driver: ANDROID_ONDEVICE_ACCESSIBILITY}
        - prompts:
          - step: Do the thing
      """.trimIndent(),
      "ios-iphone.trail.yaml" to """
        - config: {id: x, target: x, platform: ios, driver: IOS_HOST}
        - prompts:
          - step: Do the thing
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    assertEquals(
      mapOf("android" to "ANDROID_ONDEVICE_ACCESSIBILITY", "ios-iphone" to "IOS_HOST"),
      result.trail.config.devices,
    )
  }

  @Test
  fun `family collapse folds equivalent sub-classifiers — reason stripped`() {
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config: {id: x, target: x, platform: android}
        - prompts:
          - step: Tap something
            recording:
              tools:
              - tapOnPoint:
                  reason: this is the phone reason
                  x: 1
                  y: 2
      """.trimIndent(),
      "android-tablet.trail.yaml" to """
        - config: {id: x, target: x, platform: android}
        - prompts:
          - step: Tap something
            recording:
              tools:
              - tapOnPoint:
                  reason: this is the tablet reason — different prose
                  x: 1
                  y: 2
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    val step = result.trail.trail.single()
    assertEquals(setOf("android"), step.recordings.keys, "phone+tablet should collapse to family")
    assertEquals(1, result.report.familyCollapses.size)
    assertFalse(result.report.familyCollapses[0].diverged)
  }

  @Test
  fun `family stays split when sub-classifiers truly diverge`() {
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config: {id: x, target: x, platform: android}
        - prompts:
          - step: Tap something
            recording:
              tools:
              - tapOnPoint:
                  x: 1
                  y: 2
      """.trimIndent(),
      "android-tablet.trail.yaml" to """
        - config: {id: x, target: x, platform: android}
        - prompts:
          - step: Tap something
            recording:
              tools:
              - tapOnPoint:
                  x: 999
                  y: 999
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    val step = result.trail.trail.single()
    assertEquals(setOf("android-phone", "android-tablet"), step.recordings.keys)
    assertEquals(1, result.report.familyCollapses.size)
    assertTrue(result.report.familyCollapses[0].diverged)
  }

  @Test
  fun `divergent NL is captured as a drift entry — first platform wins as canonical`() {
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config: {id: x, target: x, platform: android}
        - prompts:
          - step: Sign in as foo@example.com
            recording:
              tools:
              - tapOnPoint: {x: 1, y: 2}
      """.trimIndent(),
      "ios-iphone.trail.yaml" to """
        - config: {id: x, target: x, platform: ios}
        - prompts:
          - step: Sign in as bar@example.com
            recording:
              tools:
              - tapOnPoint: {x: 3, y: 4}
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    assertEquals(1, result.report.drift.size)
    assertEquals("Sign in as foo@example.com", result.trail.trail[0].step)
  }

  @Test
  fun `no recording on any platform leaves recordable at its default (true)`() {
    // A step with no recording is "not recorded yet" — it runs via the agent and can be
    // recorded later — NOT "never record". So the migrator does not emit `recordable: false`;
    // the steps stay at the default recordable=true.
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config: {id: x, target: x, platform: android}
        - prompts:
          - step: Sign in
          - step: Verify nothing
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    assertEquals(2, result.trail.trail.size)
    assertTrue(result.trail.trail[0].recordable)
    assertTrue(result.trail.trail[1].recordable)
    assertEquals(0, result.report.unrecordableSteps)
  }

  @Test
  fun `verify keyword in v1 becomes step keyword in unified — same NL string`() {
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config: {id: x, target: x, platform: android}
        - prompts:
          - verify: The home screen is visible
            recording:
              tools:
              - tapOnPoint: {x: 1, y: 2}
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    assertEquals(1, result.trail.trail.size)
    assertEquals("The home screen is visible", result.trail.trail[0].step)
  }

  @Test
  fun `output of migrate roundtrips through the unified parser`() {
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config: {id: x, target: x, platform: android}
        - prompts:
          - step: Step one
            recording:
              tools:
              - tapOnPoint: {x: 1, y: 2}
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    val emitted = yaml.encodeUnifiedTrailToString(result.trail)
    val reparsed = yaml.decodeUnifiedTrail(emitted)
    assertEquals(result.trail.config, reparsed.config)
    assertEquals(result.trail.trail.size, reparsed.trail.size)
    assertEquals(result.trail.trail[0].step, reparsed.trail[0].step)
    assertEquals(
      result.trail.trail[0].recordings.keys,
      reparsed.trail[0].recordings.keys,
    )
  }

  @Test
  fun `family inference handles the six-classifier lab test layout`() {
    val families = migrator.inferFamilies(
      listOf("android-phone", "android-tablet", "ios-iphone", "ios-ipad", "lab-a", "lab-b"),
    )
    assertEquals(setOf("android", "ios", "lab"), families.keys)
    assertEquals(listOf("android-phone", "android-tablet"), families["android"])
    assertEquals(listOf("ios-iphone", "ios-ipad"), families["ios"])
    assertEquals(listOf("lab-a", "lab-b"), families["lab"])
  }

  @Test
  fun `family inference skips singletons and dash-less classifiers`() {
    val families = migrator.inferFamilies(listOf("android-phone", "ios", "web"))
    // android-phone has no sibling so it doesn't form a family.
    assertTrue(families.isEmpty())
  }

  @Test
  fun `blaze yaml NL is the canonical when present — overrides drifting platform NL`() {
    val dir = makeDir(
      "blaze.yaml" to """
        - config: {id: x, target: x}
        - prompts:
          - step: Canonical hand-authored intent statement
      """.trimIndent(),
      "android-phone.trail.yaml" to """
        - config: {id: x, target: x, platform: android}
        - prompts:
          - step: Recorded prose that drifted away from the intent
            recording:
              tools:
              - tapOnPoint: {x: 1, y: 2}
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    assertEquals("Canonical hand-authored intent statement", result.trail.trail[0].step)
    // Drift IS reported because blaze.yaml and android-phone differ.
    assertEquals(1, result.report.drift.size)
    assertTrue(
      result.report.drift[0].nlByClassifier.containsKey("blaze.yaml"),
      "drift report should reference blaze.yaml as one of the sources",
    )
  }

  @Test
  fun `blaze yaml can extend step count past every platform file`() {
    val dir = makeDir(
      "blaze.yaml" to """
        - config: {id: x, target: x}
        - prompts:
          - step: Recorded step
          - step: Not-yet-recorded step
          - step: Another not-yet-recorded step
      """.trimIndent(),
      "android-phone.trail.yaml" to """
        - config: {id: x, target: x, platform: android}
        - prompts:
          - step: Recorded step
            recording:
              tools:
              - tapOnPoint: {x: 1, y: 2}
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    assertEquals(3, result.trail.trail.size, "blaze.yaml's full step count should be preserved")
    // recordable stays at the default (true) for every step now — the migrator no longer
    // auto-emits `recordable: false` for not-yet-recorded steps.
    assertTrue(result.trail.trail[0].recordable, "step 0 has a recording")
    assertTrue(result.trail.trail[1].recordable, "step 1 not yet recorded — still recordable")
    assertTrue(result.trail.trail[2].recordable, "step 2 not yet recorded — still recordable")
    assertEquals("Not-yet-recorded step", result.trail.trail[1].step)
    assertEquals("Another not-yet-recorded step", result.trail.trail[2].step)
  }

  @Test
  fun `three-member family with 2-of-3 agreement does not collapse — divergence wins`() {
    // lab-a and lab-b agree, lab-c diverges → the family stays
    // fully split (we don't partially collapse two-of-three because the
    // resulting unified would have both `lab:` AND `lab-c:`, which is
    // ambiguous to a reader trying to find the t2 recording).
    val dir = makeDir(
      "lab-a.trail.yaml" to """
        - config: {id: x, target: x, platform: lab}
        - prompts:
          - step: Tap something
            recording:
              tools:
              - tapOnPoint: {x: 1, y: 2}
      """.trimIndent(),
      "lab-b.trail.yaml" to """
        - config: {id: x, target: x, platform: lab}
        - prompts:
          - step: Tap something
            recording:
              tools:
              - tapOnPoint: {x: 1, y: 2}
      """.trimIndent(),
      "lab-c.trail.yaml" to """
        - config: {id: x, target: x, platform: lab}
        - prompts:
          - step: Tap something
            recording:
              tools:
              - tapOnPoint: {x: 999, y: 999}
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    val step = result.trail.trail.single()
    assertEquals(
      setOf("lab-a", "lab-b", "lab-c"),
      step.recordings.keys,
      "family stays fully split when any sub-classifier diverges",
    )
    assertTrue(
      result.report.familyCollapses.any { it.family == "lab" && it.diverged },
      "expected a divergence entry for the lab family",
    )
  }

  @Test
  fun `v1 config without id and target propagates as null on the unified side`() {
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config:
            platform: android
            driver: ANDROID_ONDEVICE_INSTRUMENTATION
        - prompts:
          - step: Step one
            recording:
              tools:
              - tapOnPoint: {x: 1, y: 2}
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    assertNull(result.trail.config.id)
    assertNull(result.trail.config.target)
    // devices: (classifier → driver) is populated from the file's driver even when other config
    // fields are absent.
    assertEquals(
      mapOf("android-phone" to "ANDROID_ONDEVICE_INSTRUMENTATION"),
      result.trail.config.devices,
    )
  }

  @Test
  fun `step where some platforms recorded and others did not — recordable stays true`() {
    // android-phone has a recording, ios-iphone has the same step bare. The
    // merged step should be recordable=true (since at least one platform
    // recorded) and contain only the android-phone recording.
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config: {id: x, target: x, platform: android}
        - prompts:
          - step: Sign in
            recording:
              tools:
              - tapOnPoint: {x: 1, y: 2}
      """.trimIndent(),
      "ios-iphone.trail.yaml" to """
        - config: {id: x, target: x, platform: ios}
        - prompts:
          - step: Sign in
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    val step = result.trail.trail.single()
    assertTrue(step.recordable, "step should stay recordable when any platform has a recording")
    assertEquals(setOf("android-phone"), step.recordings.keys)
  }

  @Test
  fun `family does not collapse when some members lack a recording for the step`() {
    // Family safety: if family = [android-phone, android-tablet, android-foldable]
    // and step 1 has recordings ONLY for phone+tablet (foldable was missing
    // that step in v1 → ran in LLM mode), we must NOT collapse to `android:`.
    // A naive collapse would emit `android: <recording>` which at runtime
    // would resolve via closest-wins for `android-foldable` too, silently
    // giving it a recording it was never tested with.
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config: {id: x, target: x, platform: android}
        - prompts:
          - step: Tap thing
            recording:
              tools:
              - tapOnPoint: {x: 1, y: 2}
      """.trimIndent(),
      "android-tablet.trail.yaml" to """
        - config: {id: x, target: x, platform: android}
        - prompts:
          - step: Tap thing
            recording:
              tools:
              - tapOnPoint: {x: 1, y: 2}
      """.trimIndent(),
      "android-foldable.trail.yaml" to """
        - config: {id: x, target: x, platform: android}
        - prompts:
          - step: Tap thing
      """.trimIndent(), // ← foldable had NO recording for this step in v1
    )
    val result = migrator.migrate(dir)
    val step = result.trail.trail.single()
    // No `android:` key — phone and tablet's recordings stay separate so
    // android-foldable doesn't accidentally inherit one at runtime.
    assertEquals(
      setOf("android-phone", "android-tablet"),
      step.recordings.keys,
      "family must not collapse when any member lacks a recording for this step — " +
        "the collapsed entry would silently expand the recording's runtime reach " +
        "to that member via closest-wins",
    )
  }

  @Test
  fun `migrator refuses input with top-level tools blocks`() {
    // Per Codex P1: v1 trails sometimes ship setup tool calls as a top-level
    // `- tools:` block alongside `- prompts:`. Silently dropping those during
    // migration would change runtime behavior (login state never gets set
    // up). Fail loud instead.
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config: {id: x, target: x, platform: android}
        - tools:
          - launchApp:
              appId: com.example
        - prompts:
          - step: Step one
            recording:
              tools:
              - tapOnPoint: {x: 1, y: 2}
      """.trimIndent(),
    )
    val ex = kotlin.test.assertFailsWith<IllegalArgumentException> { migrator.migrate(dir) }
    kotlin.test.assertTrue(
      ex.message?.contains("top-level `- tools:` block") == true,
      "expected the top-level-tools refusal message, got: ${ex.message}",
    )
  }

  @Test
  fun `real-world six-classifier fixture migrates and reparses`() {
    // Optional integration smoke against a real production case dir that
    // ships with six per-platform files (android-phone + android-tablet +
    // ios-iphone + ios-ipad + a two-member additional family). The path is
    // relative to the project root via System.getProperty("user.dir"); if
    // not present in this checkout (the case lives in the parent mirror,
    // not in the OSS tree), the test is skipped silently rather than
    // failing.
    val repoRoot = findRepoRoot() ?: return
    val fixtureDir = File(
      repoRoot,
      "trails/testrail/suite_86078/section_1008031/case_5649266",
    )
    if (!fixtureDir.isDirectory) {
      return
    }
    val result = migrator.migrate(fixtureDir)
    assertNotNull(result.trail.config.id)
    assertTrue(result.trail.trail.isNotEmpty())
    val emitted = yaml.encodeUnifiedTrailToString(
      result.trail,
      leadingComments = UnifiedTrailMigrator.driftComments(result.report.drift),
    )
    val reparsed = yaml.decodeUnifiedTrail(emitted)
    assertEquals(result.trail.config.id, reparsed.config.id)
    assertEquals(result.trail.trail.size, reparsed.trail.size)
  }

  @Test
  fun `divergent per-platform memory blocks surface as memoryDrift in the report`() {
    // The migrator picks canonical config from the first file (alphabetical), which means
    // if two platform files declare different `config.memory:` the second file's values
    // are silently dropped. Surface that as a drift entry so the user can reconcile rather
    // than discovering at runtime that one platform was seeded with stale values.
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config:
            id: x/y
            target: x
            platform: android
            memory:
              user: sam
              env: prod
        - prompts:
          - step: Open the app
            recording: {tools: [{tapOnPoint: {x: 1, y: 2}}]}
      """.trimIndent(),
      "ios-iphone.trail.yaml" to """
        - config:
            id: x/y
            target: x
            platform: ios
            memory:
              user: alice
              env: prod
        - prompts:
          - step: Open the app
            recording: {tools: [{tapOnPoint: {x: 3, y: 4}}]}
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    assertEquals(1, result.report.memoryDrift.size)
    val entry = result.report.memoryDrift.single()
    assertEquals(
      mapOf("user" to "sam", "env" to "prod"),
      entry.memoryByClassifier["android-phone"],
    )
    assertEquals(
      mapOf("user" to "alice", "env" to "prod"),
      entry.memoryByClassifier["ios-iphone"],
    )
    // Canonical (first-file-wins) is android-phone's memory.
    assertEquals(
      mapOf("user" to "sam", "env" to "prod"),
      result.trail.config.memory,
    )
  }

  @Test
  fun `agreeing memory blocks across platforms produce no drift entry`() {
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config:
            id: x/y
            target: x
            platform: android
            memory:
              user: sam
        - prompts:
          - step: Open the app
            recording: {tools: [{tapOnPoint: {x: 1, y: 2}}]}
      """.trimIndent(),
      "ios-iphone.trail.yaml" to """
        - config:
            id: x/y
            target: x
            platform: ios
            memory:
              user: sam
        - prompts:
          - step: Open the app
            recording: {tools: [{tapOnPoint: {x: 3, y: 4}}]}
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    assertTrue(result.report.memoryDrift.isEmpty())
  }

  @Test
  fun `memoryDriftComments renders every per-platform memory map`() {
    val drift = UnifiedTrailMigrator.MemoryDriftEntry(
      memoryByClassifier = linkedMapOf(
        "android-phone" to mapOf("user" to "sam"),
        "ios-iphone" to mapOf("user" to "alice"),
      ),
    )
    val lines = UnifiedTrailMigrator.memoryDriftComments(listOf(drift))
    assertTrue(
      lines.any { it.contains("android-phone") && it.contains("sam") },
      "comments should mention each platform's memory",
    )
    assertTrue(
      lines.any { it.contains("ios-iphone") && it.contains("alice") },
      "comments should mention each platform's memory",
    )
    assertTrue(
      lines.any { it.contains("first file's memory was used as canonical") },
      "comments should explain the canonical-selection behavior",
    )
  }

  @Test
  fun `v1 config dot memory round-trips through v1 to unified migration`() {
    // The migrator's kdoc claims `memory:` round-trips intact (the previous "silently
    // dropped" behavior was fixed alongside the seeding feature). Pin that contract:
    // a v1 trail with `config.memory:` migrates to a unified trail with the same
    // `memory:` block preserved. Without this test, a future refactor of v1ConfigToUnified
    // could silently regress the round-trip and only show up at runtime.
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config:
            id: x/y
            target: x
            platform: android
            memory:
              user: sam
              accountTier: PRO
        - prompts:
          - step: Open the app
            recording:
              tools:
              - tapOnPoint:
                  x: 1
                  y: 2
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    assertEquals(
      mapOf("user" to "sam", "accountTier" to "PRO"),
      result.trail.config.memory,
    )
  }

  @Test
  fun `each platform file's distinct v1 skip reason is keyed under its classifier`() {
    // Unified `skip:` is a per-classifier map, not a scalar — so each per-platform file's scalar
    // `skip:` keys under that file's classifier (the same way `driver:` folds into `devices:`).
    // Different reasons per platform are preserved side-by-side, not collapsed to one.
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config:
            id: x/y
            target: x
            platform: android
            skip: "blocked on android — see #123"
        - prompts:
          - step: Open the app
            recording: {tools: [{tapOnPoint: {x: 1, y: 2}}]}
      """.trimIndent(),
      "ios-iphone.trail.yaml" to """
        - config:
            id: x/y
            target: x
            platform: ios
            skip: "blocked on ios — see #456"
        - prompts:
          - step: Open the app
            recording: {tools: [{tapOnPoint: {x: 3, y: 4}}]}
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    assertEquals(
      mapOf(
        "android-phone" to "blocked on android — see #123",
        "ios-iphone" to "blocked on ios — see #456",
      ),
      result.trail.config.skip,
    )
  }

  @Test
  fun `unquoted hash in a v1 skip reason is a YAML comment — quoting preserves the issue ref`() {
    // Documents the real-world convention: a skip reason carrying an issue ref (`#123`) MUST be
    // quoted, else YAML treats ` #123` as a trailing comment. This is a property of the v1 parser,
    // but pinning both sides here keeps the skip fixtures honest.
    val unquoted = makeDir(
      "android-phone.trail.yaml" to """
        - config:
            id: x/y
            target: x
            platform: android
            skip: see #123
        - prompts:
          - step: Open the app
            recording: {tools: [{tapOnPoint: {x: 1, y: 2}}]}
      """.trimIndent(),
    )
    assertEquals(mapOf("android-phone" to "see"), migrator.migrate(unquoted).trail.config.skip)

    val quoted = makeDir(
      "android-phone.trail.yaml" to """
        - config: {id: x/y, target: x, platform: android, skip: "see #123"}
        - prompts:
          - step: Open the app
            recording: {tools: [{tapOnPoint: {x: 1, y: 2}}]}
      """.trimIndent(),
    )
    assertEquals(mapOf("android-phone" to "see #123"), migrator.migrate(quoted).trail.config.skip)
  }

  @Test
  fun `a v1 skip on one platform only leaves the other platform runnable`() {
    // android-phone is skipped; ios-iphone declares no skip. Because skip is per-classifier, the
    // unified map carries ONLY android-phone — ios-iphone stays runnable (closest-wins finds no
    // ios skip at run time). A scalar skip couldn't express "skip here, run there".
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config:
            id: x/y
            target: x
            platform: android
            skip: "flaky on android — see #123"
        - prompts:
          - step: Open the app
            recording: {tools: [{tapOnPoint: {x: 1, y: 2}}]}
      """.trimIndent(),
      "ios-iphone.trail.yaml" to """
        - config:
            id: x/y
            target: x
            platform: ios
        - prompts:
          - step: Open the app
            recording: {tools: [{tapOnPoint: {x: 3, y: 4}}]}
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    assertEquals(
      mapOf("android-phone" to "flaky on android — see #123"),
      result.trail.config.skip,
    )
  }

  @Test
  fun `a blank v1 skip is treated as not-skipped and dropped from the map`() {
    // v1 semantics: `skip: ""` is "not skipped" (guards against an accidental empty skip silently
    // disabling a trail). The migrator honors that — a blank reason contributes no map entry, and
    // with no non-blank reason anywhere the whole `skip:` block is omitted (null, not empty map).
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config:
            id: x/y
            target: x
            platform: android
            skip: ""
        - prompts:
          - step: Open the app
            recording: {tools: [{tapOnPoint: {x: 1, y: 2}}]}
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    assertNull(result.trail.config.skip)
  }

  @Test
  fun `no skip on any platform yields a null skip map, not an empty one`() {
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config: {id: x/y, target: x, platform: android}
        - prompts:
          - step: Open the app
            recording: {tools: [{tapOnPoint: {x: 1, y: 2}}]}
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    assertNull(result.trail.config.skip)
  }

  @Test
  fun `a non-blank blaze yaml skip is copied to every present classifier`() {
    // blaze.yaml's `skip:` is device-agnostic (the CLI honors it as a standalone runnable trail
    // file when no platform recording matches), so it means "skip everywhere" — not "skip nowhere",
    // which is what would happen if it were silently dropped. The unified `skip:` map has no
    // universal wildcard key, so the only faithful translation is to copy the reason onto every
    // classifier this trail actually targets.
    val dir = makeDir(
      "blaze.yaml" to """
        - config: {id: x/y, target: x, skip: "blocked globally — see #123"}
        - prompts:
          - step: Open the app
      """.trimIndent(),
      "android-phone.trail.yaml" to """
        - config: {id: x/y, target: x, platform: android}
        - prompts:
          - step: Open the app
            recording: {tools: [{tapOnPoint: {x: 1, y: 2}}]}
      """.trimIndent(),
      "ios-iphone.trail.yaml" to """
        - config: {id: x/y, target: x, platform: ios}
        - prompts:
          - step: Open the app
            recording: {tools: [{tapOnPoint: {x: 3, y: 4}}]}
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    assertEquals(
      mapOf(
        "android-phone" to "blocked globally — see #123",
        "ios-iphone" to "blocked globally — see #123",
      ),
      result.trail.config.skip,
    )
  }

  @Test
  fun `a platform's own skip reason wins over a device-agnostic blaze yaml skip`() {
    // android-phone declares its own (more specific) skip reason; blaze.yaml's device-agnostic
    // reason only fills in for classifiers that don't already have one — it must not clobber a
    // platform's explicit override.
    val dir = makeDir(
      "blaze.yaml" to """
        - config: {id: x/y, target: x, skip: "blocked globally"}
        - prompts:
          - step: Open the app
      """.trimIndent(),
      "android-phone.trail.yaml" to """
        - config: {id: x/y, target: x, platform: android, skip: "flaky on android specifically"}
        - prompts:
          - step: Open the app
            recording: {tools: [{tapOnPoint: {x: 1, y: 2}}]}
      """.trimIndent(),
      "ios-iphone.trail.yaml" to """
        - config: {id: x/y, target: x, platform: ios}
        - prompts:
          - step: Open the app
            recording: {tools: [{tapOnPoint: {x: 3, y: 4}}]}
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    assertEquals(
      mapOf(
        "android-phone" to "flaky on android specifically",
        "ios-iphone" to "blocked globally",
      ),
      result.trail.config.skip,
    )
  }

  @Test
  fun `v1 tags carry through migration as a flat trail-level list`() {
    // Tags name the whole test (not a device), so they stay a flat list — no per-classifier
    // keying, no reordering. Single-file case: the list carries through unchanged.
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config:
            id: x/y
            target: x
            platform: android
            tags: [smoke, login, flaky]
        - prompts:
          - step: Open the app
            recording: {tools: [{tapOnPoint: {x: 1, y: 2}}]}
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    assertEquals(listOf("smoke", "login", "flaky"), result.trail.config.tags)
  }

  @Test
  fun `tags are unioned across platform files — deduped, first-seen order`() {
    // Tags are device-agnostic and name the whole test, so a tag on ANY platform file describes
    // the test. When per-platform files disagree the migrator unions them (not first-file-wins,
    // which would silently drop a tag only one platform declared), de-duplicating a shared tag and
    // preserving first-seen order (android-phone sorts before ios-iphone).
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config: {id: x/y, target: x, platform: android, tags: [smoke, login]}
        - prompts:
          - step: Open the app
            recording: {tools: [{tapOnPoint: {x: 1, y: 2}}]}
      """.trimIndent(),
      "ios-iphone.trail.yaml" to """
        - config: {id: x/y, target: x, platform: ios, tags: [smoke, flaky]}
        - prompts:
          - step: Open the app
            recording: {tools: [{tapOnPoint: {x: 3, y: 4}}]}
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    assertEquals(listOf("smoke", "login", "flaky"), result.trail.config.tags)
  }

  @Test
  fun `absent v1 tags yield null tags`() {
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config: {id: x/y, target: x, platform: android}
        - prompts:
          - step: Open the app
            recording: {tools: [{tapOnPoint: {x: 1, y: 2}}]}
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    assertNull(result.trail.config.tags)
  }

  @Test
  fun `migrated skip and tags round-trip through the unified emitter and parser`() {
    // The migrated skip map + tags list must survive being written to unified YAML and reparsed —
    // guards against the emitter/parser silently dropping either field.
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config:
            id: x/y
            target: x
            platform: android
            skip: "blocked on android — see #123"
            tags: [smoke, flaky]
        - prompts:
          - step: Open the app
            recording: {tools: [{tapOnPoint: {x: 1, y: 2}}]}
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    val emitted = yaml.encodeUnifiedTrailToString(result.trail)
    val reparsed = yaml.decodeUnifiedTrail(emitted)
    assertEquals(mapOf("android-phone" to "blocked on android — see #123"), reparsed.config.skip)
    assertEquals(listOf("smoke", "flaky"), reparsed.config.tags)
  }

  private fun makeDir(vararg files: Pair<String, String>): File {
    val dir = Files.createTempDirectory("unified-trail-migrator-test").toFile()
    for ((name, contents) in files) {
      File(dir, name).writeText(contents)
    }
    return dir
  }

  /** Walk up from the test's working directory looking for the repo root marker (`.git`). */
  private fun findRepoRoot(): File? {
    var cur: File? = File(System.getProperty("user.dir")).absoluteFile
    while (cur != null) {
      if (File(cur, ".git").exists()) return cur
      cur = cur.parentFile
    }
    return null
  }
}
