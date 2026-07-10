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
import xyz.block.trailblaze.yaml.TrailSourceType
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
    // No blaze.yaml in this directory → the report says blaze did not contribute (the false case,
    // complementing the blaze-only test that asserts it true).
    assertFalse(result.report.blazeLoaded)
  }

  @Test
  fun `config description and driver are preserved through migration, not dropped`() {
    // description is runtime-surfaced (a display label); driver is an optional pin needed by
    // trails whose recordings are driver-specific. Both must survive migration — `platform`
    // is the one retired field, not these. The driver is keyed by the file's
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
  fun `verify keyword in v1 keeps its kind and NL in unified`() {
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
    assertTrue(result.trail.trail[0].verify, "a v1 verify: step must not be downgraded to step:")
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
  fun `migrates a blaze_yaml-only directory into a recording-less unified trail`() {
    // blaze.yaml is a v1 trail file with the same schema as a `<classifier>.trail.yaml` — just
    // device-agnostic and recording-less. A directory with only blaze.yaml (a prose-only trail
    // with no device recording) must migrate, not refuse: NL, step-kind, and config carry through
    // verbatim, with no per-classifier devices map and empty per-step recordings.
    val dir = makeDir(
      "blaze.yaml" to """
        - config:
            id: x/y
            title: A prose-only case
            priority: P0
            metadata:
              exampleFlag: "on"
        - prompts:
          - step: Log into an account.
          - step: Tap on "More".
          - verify: The More menu displays.
      """.trimIndent(),
    )

    val result = migrator.migrate(dir)

    assertEquals(3, result.trail.trail.size)
    assertEquals("x/y", result.trail.config.id)
    assertEquals("A prose-only case", result.trail.config.title)
    assertEquals("P0", result.trail.config.priority)
    // Arbitrary config metadata is carried through untouched.
    assertEquals("on", result.trail.config.metadata?.get("exampleFlag"))
    // No platform files → no device pins and no recordings on any step.
    assertNull(result.trail.config.devices)
    assertTrue(result.trail.trail.all { it.recordings.isEmpty() })
    // NL and step-kind carried through verbatim.
    assertEquals("Log into an account.", result.trail.trail[0].step)
    assertFalse(result.trail.trail[0].verify)
    assertEquals("The More menu displays.", result.trail.trail[2].step)
    assertTrue(result.trail.trail[2].verify)
    // The report attributes the migration to blaze.yaml, with zero platform files.
    assertTrue(result.report.platformFilesLoaded.isEmpty())
    assertTrue(result.report.blazeLoaded)
    // Output must round-trip through the unified parser (same gate as the other migration tests).
    val reparsed = yaml.decodeUnifiedTrail(yaml.encodeUnifiedTrailToString(result.trail))
    assertEquals(result.trail.config, reparsed.config)
    assertEquals(result.trail.trail.size, reparsed.trail.size)
  }

  @Test
  fun `refuses a blaze_yaml-only directory that declares a skip`() {
    // A device-agnostic blaze.yaml `skip:` means "skip everywhere", but unified `skip:` is a
    // per-classifier map with no universal-wildcard key. With no platform file there's no
    // classifier to key it onto, so the skip would be silently lost and the trail would run.
    // Refuse rather than drop it (fail-fast is the migrator's contract for unrepresentable input).
    val dir = makeDir(
      "blaze.yaml" to """
        - config:
            id: x/y
            skip: "flaky — see #123"
        - prompts:
          - step: Log into an account.
      """.trimIndent(),
    )
    val ex = assertFailsWith<IllegalArgumentException> { migrator.migrate(dir) }
    assertTrue(ex.message!!.contains("skip"))
  }

  @Test
  fun `refuses a blaze_yaml-only directory that pins a driver`() {
    // Same reasoning as the skip case: a blaze.yaml `driver:` has no classifier to key onto in a
    // recording-less unified trail, so it would be dropped and the driver resolved at runtime.
    val dir = makeDir(
      "blaze.yaml" to """
        - config:
            id: x/y
            driver: ANDROID_ONDEVICE_INSTRUMENTATION
        - prompts:
          - step: Log into an account.
      """.trimIndent(),
    )
    val ex = assertFailsWith<IllegalArgumentException> { migrator.migrate(dir) }
    assertTrue(ex.message!!.contains("driver"))
  }

  @Test
  fun `a blaze skip is preserved when a platform file is present — the guard is blaze-only`() {
    // The refusal above is scoped to the classifier-less (blaze-only) case. When a platform file
    // is present, blaze.yaml's device-agnostic skip propagates onto that present classifier (the
    // existing blaze-skip behavior), so migration succeeds and the skip is carried, not lost.
    val dir = makeDir(
      "blaze.yaml" to """
        - config:
            id: x/y
            skip: "flaky — see #123"
        - prompts:
          - step: Log into an account.
      """.trimIndent(),
      "android-phone.trail.yaml" to """
        - config: {id: x/y, target: x, platform: android}
        - prompts:
          - step: Log into an account.
      """.trimIndent(),
    )

    val result = migrator.migrate(dir)

    assertEquals(mapOf("android-phone" to "flaky — see #123"), result.trail.config.skip)
  }

  @Test
  fun `refuses a directory with no platform files and no blaze_yaml`() {
    // The relaxed guard accepts platform files OR a blaze.yaml; with neither there is no v1 source
    // to migrate, so it must still fail fast (the false branch of the widened require).
    val dir = makeDir(
      "notes.txt" to "not a trail file",
    )
    assertFailsWith<IllegalArgumentException> { migrator.migrate(dir) }
  }

  @Test
  fun `migrates a blaze_yaml-only directory whose skip is blank — blank is not-skipped, not refused`() {
    // The blaze-only refusal keys on a NON-blank skip (`isNullOrBlank`, not `!= null`). A blank
    // `skip: ""` is v1 for "not skipped", so it must migrate cleanly with no skip map — the refusal
    // must not fire on it.
    val dir = makeDir(
      "blaze.yaml" to """
        - config:
            id: x/y
            skip: ""
        - prompts:
          - step: Log into an account.
      """.trimIndent(),
    )

    val result = migrator.migrate(dir)

    assertEquals(1, result.trail.trail.size)
    assertNull(result.trail.config.skip)
    assertTrue(result.report.blazeLoaded)
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
      lines.any { it.contains("used as canonical") },
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
  fun `a blank blaze yaml skip does not propagate to any classifier`() {
    // blaze.yaml's `skip: ""` is v1's "not skipped" convention, same as a blank platform-file skip
    // (tested above). `cfg?.skip?.takeIf { it.isNotBlank() }` must no-op here — not populate the
    // unified skip map with an empty-string reason on every classifier.
    val dir = makeDir(
      "blaze.yaml" to """
        - config: {id: x/y, target: x, skip: ""}
        - prompts:
          - step: Open the app
      """.trimIndent(),
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
  fun `an absent blaze yaml skip field does not propagate to any classifier`() {
    // Distinct code path from the blank-string case above: `cfg?.skip` is null here (no `skip:`
    // key at all) rather than an empty string, so `blazeSkip = cfg?.skip?.takeIf { … }` short-
    // circuits on the null-safe call instead of the blank check. Both must land on the same
    // outcome — no propagation — so both are covered.
    val dir = makeDir(
      "blaze.yaml" to """
        - config: {id: x/y, target: x}
        - prompts:
          - step: Open the app
      """.trimIndent(),
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
  fun `blaze yaml's own tags join the union`() {
    // blaze.yaml is loaded after platform files (see the loop order in
    // UnifiedTrailMigrator.migrate), so its unique tags appear after the platform files' tags in
    // the resulting list — same first-seen-order, dedup semantics as the platform-only union above.
    val dir = makeDir(
      "blaze.yaml" to """
        - config: {id: x/y, target: x, tags: [smoke, canary]}
        - prompts:
          - step: Open the app
      """.trimIndent(),
      "android-phone.trail.yaml" to """
        - config: {id: x/y, target: x, platform: android, tags: [smoke, login]}
        - prompts:
          - step: Open the app
            recording: {tools: [{tapOnPoint: {x: 1, y: 2}}]}
      """.trimIndent(),
      "ios-iphone.trail.yaml" to """
        - config: {id: x/y, target: x, platform: ios, tags: [flaky]}
        - prompts:
          - step: Open the app
            recording: {tools: [{tapOnPoint: {x: 3, y: 4}}]}
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    assertEquals(listOf("smoke", "login", "flaky", "canary"), result.trail.config.tags)
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

  @Test
  fun `v1 verify steps migrate to unified verify steps and round-trip`() {
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config: {id: x/y, target: x, platform: android}
        - prompts:
          - step: Open the app
            recording: {tools: [{tapOnPoint: {x: 1, y: 2}}]}
          - verify: The home tab is selected
            recording: {tools: [{assertVisibleWithText: {text: Home}}]}
      """.trimIndent(),
      "ios-iphone.trail.yaml" to """
        - config: {id: x/y, target: x, platform: ios}
        - prompts:
          - step: Open the app
            recording: {tools: [{tapOnPoint: {x: 3, y: 4}}]}
          - verify: The home tab is selected
            recording: {tools: [{assertVisibleWithText: {text: Home}}]}
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    assertFalse(result.trail.trail[0].verify, "a v1 step: migrates as a plain step")
    assertTrue(result.trail.trail[1].verify, "a v1 verify: migrates as a unified verify step")
    assertTrue(result.report.kindDrift.isEmpty(), "agreeing kinds are not drift")

    // And the kind survives the emit → reparse round-trip of the migrated file.
    val reparsed = yaml.decodeUnifiedTrail(yaml.encodeUnifiedTrailToString(result.trail))
    assertTrue(reparsed.trail[1].verify)
    assertEquals("The home tab is selected", reparsed.trail[1].step)
  }

  @Test
  fun `platforms disagreeing on step kind surface as kind drift with first platform canonical`() {
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config: {id: x/y, target: x, platform: android}
        - prompts:
          - verify: The home tab is selected
      """.trimIndent(),
      "ios-iphone.trail.yaml" to """
        - config: {id: x/y, target: x, platform: ios}
        - prompts:
          - step: The home tab is selected
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    assertEquals(1, result.report.kindDrift.size, "kind disagreement must surface as drift")
    val entry = result.report.kindDrift.single()
    assertEquals(0, entry.stepIndex)
    assertEquals(
      mapOf("android-phone" to true, "ios-iphone" to false),
      entry.verifyByClassifier,
    )
    // Canonical kind follows the same preference as NL: first platform (android-phone → verify).
    assertTrue(result.trail.trail[0].verify)
    // And the drift renders as leading comments so it's visible in the migrated file.
    val comments = UnifiedTrailMigrator.kindDriftComments(result.report.kindDrift)
    assertTrue(comments.any { "step: vs verify:" in it }, "expected a kind-drift warning, got: $comments")
  }

  @Test
  fun `a step absent from one platform is not kind drift`() {
    // Unequal step counts: ios never reached step 2. Only android declares a kind there, so a
    // single-voice step must not be reported as kind drift.
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config: {id: x/y, target: x, platform: android}
        - prompts:
          - step: Open the app
          - verify: The home tab is selected
      """.trimIndent(),
      "ios-iphone.trail.yaml" to """
        - config: {id: x/y, target: x, platform: ios}
        - prompts:
          - step: Open the app
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    assertTrue(result.report.kindDrift.isEmpty(), "absence is not disagreement")
    assertTrue(result.trail.trail[1].verify, "the only voice's kind is canonical")
  }

  @Test
  fun `blaze yaml's step kind is canonical when platforms disagree with it`() {
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config: {id: x/y, target: x, platform: android}
        - prompts:
          - step: The home tab is selected
      """.trimIndent(),
      "blaze.yaml" to """
        - config: {id: x/y, target: x}
        - prompts:
          - verify: The home tab is selected
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    assertTrue(
      result.trail.trail[0].verify,
      "blaze.yaml is the hand-authored intent — its verify: kind wins over the platform's step:",
    )
    assertEquals(1, result.report.kindDrift.size)
  }

  @Test
  fun `title priority and source are preserved through migration and the emitter round-trip`() {
    // Over a thousand corpus trails carry `title:`/`priority:` and hundreds carry `source:` —
    // the mass migration must not silently drop them. Assert the migrated model, the emitted
    // YAML round-trip, and the lowered v1 view the runtime consumers (report names, CI priority
    // filters, source-system mapping) actually read.
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config:
            id: x/y
            target: x
            platform: android
            title: Checkout with a saved card
            priority: P1
            source:
              type: HANDWRITTEN
              reason: authored by hand
        - prompts:
          - step: Open the app
            recording: {tools: [{tapOnPoint: {x: 1, y: 2}}]}
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    assertEquals("Checkout with a saved card", result.trail.config.title)
    // priority is a top-level unified field; source is metadata by nature — migration bridges
    // it into the reserved metadata keys.
    assertEquals("P1", result.trail.config.priority)
    assertEquals("HANDWRITTEN", result.trail.config.metadata?.get("source"))
    assertEquals("authored by hand", result.trail.config.metadata?.get("sourceReason"))

    // They survive being written to unified YAML and reparsed …
    val emitted = yaml.encodeUnifiedTrailToString(result.trail)
    val reparsed = yaml.decodeUnifiedTrail(emitted)
    assertEquals(result.trail.config, reparsed.config)

    // … and lower back onto the v1 config runtime consumers read, from the unified file alone.
    val lowered = yaml.extractTrailConfig(emitted)
    assertEquals("Checkout with a saved card", lowered?.title)
    assertEquals("P1", lowered?.priority)
    assertEquals(TrailSourceType.HANDWRITTEN, lowered?.source?.type)
    assertEquals("authored by hand", lowered?.source?.reason)
  }

  @Test
  fun `a config field declared only in blaze yaml is preserved, first file to declare a scalar still wins`() {
    // Mirrors real corpus dirs where the platform file carries title/priority but only
    // blaze.yaml declares source: — canonical seeding must fill fields later files declare,
    // not adopt the first config seen wholesale and drop the rest.
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config:
            id: x/y
            target: x
            platform: android
            title: Library tab UI display
            priority: P2
        - prompts:
          - step: Open the app
      """.trimIndent(),
      "blaze.yaml" to """
        - config:
            id: x/y
            target: x
            title: Library tab UI display (blaze wording)
            source:
              type: HANDWRITTEN
            description: Only blaze.yaml carries this.
        - prompts:
          - step: Open the app
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    // Scalars both sides declare: the platform file (read first) stays canonical.
    assertEquals("Library tab UI display", result.trail.config.title)
    assertEquals("P2", result.trail.config.priority)
    // Fields only blaze.yaml declares are filled, not dropped (source rides metadata's
    // reserved bridge key; description is a first-class scalar).
    assertEquals("HANDWRITTEN", result.trail.config.metadata?.get("source"))
    assertEquals("Only blaze.yaml carries this.", result.trail.config.description)
  }

  @Test
  fun `scalar fold across platform files — first file in filename order wins, later files fill gaps`() {
    // android.trail.yaml sorts before ios-iphone.trail.yaml, so android's title is canonical;
    // ios's source (which android lacks) is filled rather than dropped.
    val dir = makeDir(
      "android.trail.yaml" to """
        - config: {id: x, target: x, platform: android, title: Android wording}
        - prompts:
          - step: Open the app
      """.trimIndent(),
      "ios-iphone.trail.yaml" to """
        - config:
            id: x
            target: x
            platform: ios
            title: iOS wording
            source:
              type: HANDWRITTEN
        - prompts:
          - step: Open the app
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    assertEquals("Android wording", result.trail.config.title)
    assertEquals("HANDWRITTEN", result.trail.config.metadata?.get("source"))
    // The losing title is surfaced as config drift, not silently dropped.
    val titleDrift = result.report.configDrift.single { it.field == "title" }
    assertEquals(
      mapOf("android" to "Android wording", "ios-iphone" to "iOS wording"),
      titleDrift.valueBySource,
    )
    // And it renders into the leading-comment warning block.
    val comments = UnifiedTrailMigrator.configDriftComments(result.report.configDrift)
    assertTrue(comments.any { "iOS wording" in it }, "expected the losing title in the drift comments, got: $comments")
  }

  @Test
  fun `agreeing scalars across files produce no config drift`() {
    val dir = makeDir(
      "android.trail.yaml" to """
        - config: {id: x, target: x, platform: android, title: Same title, priority: P1}
        - prompts:
          - step: Open the app
      """.trimIndent(),
      "ios-iphone.trail.yaml" to """
        - config: {id: x, target: x, platform: ios, title: Same title}
        - prompts:
          - step: Open the app
      """.trimIndent(),
    )
    val result = migrator.migrate(dir)
    assertTrue(
      result.report.configDrift.isEmpty(),
      "identical titles + a single-file priority are not drift, got: ${result.report.configDrift}",
    )
  }

  @Test
  fun `malformed positional anchors that the decode drops surface as a dropped-content warning`() {
    // The exact hazard from PR #4641 / case_4837682: hand-authored positional anchors written in a
    // shape the schema can't carry are silently discarded on decode. (i) a `below:` at the TOOL
    // level — a sibling of `nodeSelector`, which `assertVisibleBySelector` has no field for; and
    // (ii) a nested `rightOf: {textRegex: ...}` whose bare `textRegex` isn't a field on
    // `TrailblazeNodeSelector` (its matchers are dialect-scoped, e.g. `androidAccessibility`).
    // Both vanish with zero signal today; the migrator must now WARN, naming each dropped anchor.
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config:
            id: x/y
            target: x
            platform: android
        - prompts:
          - step: Verify the total row
            recording:
              tools:
              - assertVisibleBySelector:
                  nodeSelector:
                    androidAccessibility:
                      textRegex: Total
                  below:
                    androidAccessibility:
                      textRegex: Subtotal
          - step: Verify the confirm button
            recording:
              tools:
              - assertVisibleBySelector:
                  nodeSelector:
                    androidAccessibility:
                      textRegex: Confirm
                    rightOf:
                      textRegex: Cancel
      """.trimIndent(),
    )

    val result = migrator.migrate(dir)

    // The lenient decode still migrates the clean parts — this is purely an added diagnostic.
    assertEquals(2, result.trail.trail.size)

    val dropped = result.report.droppedContent
    assertTrue(
      dropped.any { it.key == "below" && it.path.contains("below") },
      "expected the tool-level `below` anchor flagged as dropped, got: $dropped",
    )
    assertTrue(
      dropped.any { it.key == "textRegex" && it.path.contains("rightOf") },
      "expected the bare `rightOf: {textRegex}` anchor flagged as dropped, got: $dropped",
    )

    // The warning must ride into the migrated file as leading comments, naming both anchors so a
    // reviewer can re-author them.
    val comments = UnifiedTrailMigrator.droppedContentComments(result.report.droppedContent)
    assertTrue(comments.any { it.contains("below") }, "warning must name `below`, got: $comments")
    assertTrue(comments.any { it.contains("rightOf") }, "warning must name `rightOf`, got: $comments")

    val emitted = yaml.encodeUnifiedTrailToString(result.trail, leadingComments = comments)
    assertTrue(emitted.contains("DROPPED"), "migrated file must carry the DROPPED warning header:\n$emitted")
    assertTrue(emitted.contains("android-phone.trail.yaml"), "warning must name the source file:\n$emitted")
    assertTrue(emitted.contains("below") && emitted.contains("rightOf"), "warning must name both anchors:\n$emitted")
  }

  @Test
  fun `a sibling key dedented out of a tool's args surfaces as a dropped-content warning`() {
    // TrailblazeToolYamlWrapperSerializer decodes only the FIRST key of each tool-list item, so a
    // sibling key at the item level (here `below`, a positional anchor the author meant to nest under
    // the selector but dedented one level) is silently dropped — and strict decode can't see it
    // either, because the serializer never hands the extra key to a decoder. The structural pre-scan
    // must still flag it so the migrated file warns instead of silently losing the assertion.
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config: {id: x/y, target: x, platform: android}
        - prompts:
          - step: Verify the total row
            recording:
              tools:
              - assertVisibleBySelector:
                  nodeSelector:
                    androidAccessibility:
                      textRegex: Total
                below:
                  androidAccessibility:
                    textRegex: Subtotal
      """.trimIndent(),
    )

    val result = migrator.migrate(dir)
    assertEquals(1, result.trail.trail.size)
    assertTrue(
      result.report.droppedContent.any { it.key == "below" && it.path.endsWith("tools[0].below") },
      "expected the dedented sibling `below` flagged, got: ${result.report.droppedContent}",
    )

    val comments = UnifiedTrailMigrator.droppedContentComments(result.report.droppedContent)
    val emitted = yaml.encodeUnifiedTrailToString(result.trail, leadingComments = comments)
    assertTrue(
      emitted.contains("DROPPED") && emitted.contains("below"),
      "migrated file must warn about the dropped sibling anchor:\n$emitted",
    )
  }

  @Test
  fun `a clean input produces no dropped-content warning`() {
    // Every key here is schema-recognized (config scalars, a `tapOnPoint`, and a well-formed
    // `assertVisibleBySelector` whose anchor lives correctly on the nested selector). Nothing is
    // dropped, so the added diagnostic must stay silent — clean inputs are unchanged.
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config:
            id: x/y
            target: x
            platform: android
        - prompts:
          - step: Open the app
            recording:
              tools:
              - tapOnPoint:
                  x: 1
                  y: 2
          - step: Verify the confirm button is right of cancel
            recording:
              tools:
              - assertVisibleBySelector:
                  nodeSelector:
                    androidAccessibility:
                      textRegex: Confirm
                    rightOf:
                      androidAccessibility:
                        textRegex: Cancel
      """.trimIndent(),
    )

    val result = migrator.migrate(dir)

    assertTrue(
      result.report.droppedContent.isEmpty(),
      "clean input must not report dropped content, got: ${result.report.droppedContent}",
    )
    assertEquals(
      emptyList(),
      UnifiedTrailMigrator.droppedContentComments(result.report.droppedContent),
      "clean input must emit no dropped-content comments",
    )
  }

  @Test
  fun `dropped content from multiple platform files is attributed to each source file`() {
    // A bundle migrates several per-platform inputs at once; each carries its OWN dropped anchor.
    // The report must attribute every drop to the file it came from, and the warning comment must
    // group them under separate per-file headers — so a reviewer knows which platform to fix.
    val dir = makeDir(
      "android-phone.trail.yaml" to """
        - config: {id: x/y, target: x, platform: android}
        - prompts:
          - step: Verify the total row
            recording:
              tools:
              - assertVisibleBySelector:
                  nodeSelector:
                    androidAccessibility:
                      textRegex: Total
                  below:
                    androidAccessibility:
                      textRegex: Subtotal
      """.trimIndent(),
      "ios-iphone.trail.yaml" to """
        - config: {id: x/y, target: x, platform: ios}
        - prompts:
          - step: Verify the total row
            recording:
              tools:
              - assertVisibleBySelector:
                  nodeSelector:
                    iosAccessibility:
                      textRegex: Total
                  above:
                    iosAccessibility:
                      textRegex: Header
      """.trimIndent(),
    )

    val result = migrator.migrate(dir)

    val byFile = result.report.droppedContent.groupBy { it.file }
    assertEquals(
      setOf("android-phone.trail.yaml", "ios-iphone.trail.yaml"),
      byFile.keys,
      "each platform file with a dropped key must be attributed, got: ${result.report.droppedContent}",
    )
    assertTrue(
      byFile["android-phone.trail.yaml"]!!.any { it.key == "below" },
      "android's `below` must be attributed to android-phone, got: ${byFile["android-phone.trail.yaml"]}",
    )
    assertTrue(
      byFile["ios-iphone.trail.yaml"]!!.any { it.key == "above" },
      "ios's `above` must be attributed to ios-iphone, got: ${byFile["ios-iphone.trail.yaml"]}",
    )

    val comments = UnifiedTrailMigrator.droppedContentComments(result.report.droppedContent)
    assertTrue(
      comments.any { it.contains("android-phone.trail.yaml") } &&
        comments.any { it.contains("ios-iphone.trail.yaml") },
      "warning must name both source files under their own headers, got: $comments",
    )
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
