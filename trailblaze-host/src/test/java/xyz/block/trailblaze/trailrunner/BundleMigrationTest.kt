package xyz.block.trailblaze.trailrunner

import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.unified.TrailDocument
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [BundleMigration.migrateFolder] — the file-mutation layer behind the Trail Runner
 * "Migrate to unified" button. Exercised against temp dirs holding real v1 bundle content. The
 * deep migration-algorithm coverage lives in `UnifiedTrailMigratorTest`; this pins the write/delete
 * behavior and the refusal mapping the route relies on.
 */
class BundleMigrationTest {

  private val workDir: File = createTempDirectory("bundle-migration-test").toFile()

  @AfterTest fun cleanup() { workDir.deleteRecursively() }

  private fun bundle(name: String, files: Map<String, String>): File {
    val dir = File(workDir, name).apply { mkdirs() }
    files.forEach { (fileName, content) -> File(dir, fileName).writeText(content.trimIndent()) }
    return dir
  }

  @Test
  fun `writes one unified file named after the folder and deletes the per-platform inputs`() {
    val dir = bundle(
      "case_123",
      mapOf(
        "android-phone.trail.yaml" to """
          - config: {id: case_123, target: sample, platform: android}
          - prompts:
            - step: Open the app
              recording:
                tools:
                - tapOnPoint: {x: 1, y: 2}
        """,
        "ios-iphone.trail.yaml" to """
          - config: {id: case_123, target: sample, platform: ios}
          - prompts:
            - step: Open the app
              recording:
                tools:
                - tapOnPoint: {x: 3, y: 4}
        """,
      ),
    )

    val outcome = BundleMigration.migrateFolder(dir)

    assertEquals("case_123.trail.yaml", outcome.outputName)
    assertTrue(outcome.steps >= 1, "expected at least one migrated step")
    val out = File(dir, "case_123.trail.yaml")
    assertTrue(out.isFile, "unified file should exist; dir has: ${dir.list()?.toList()}")
    // Indexable by TrailIndexBuilder (ends in .trail.yaml) AND parses as the unified format.
    assertTrue(out.name.endsWith(".trail.yaml"))
    assertTrue(
      TrailblazeYaml.Default.decodeTrailDocument(out.readText()) is TrailDocument.Unified,
      "migrated output must be the unified format",
    )
    // The per-platform inputs are gone.
    assertFalse(File(dir, "android-phone.trail.yaml").exists())
    assertFalse(File(dir, "ios-iphone.trail.yaml").exists())
    assertEquals(setOf("android-phone.trail.yaml", "ios-iphone.trail.yaml"), outcome.removed.toSet())
  }

  @Test
  fun `consumes and deletes blaze_yaml too`() {
    val dir = bundle(
      "case_blaze",
      mapOf(
        "blaze.yaml" to """
          - config: {id: case_blaze, target: sample}
          - prompts:
            - step: Open the app
        """,
        "android-phone.trail.yaml" to """
          - config: {id: case_blaze, target: sample, platform: android}
          - prompts:
            - step: Open the app
              recording:
                tools:
                - tapOnPoint: {x: 1, y: 2}
        """,
      ),
    )

    val outcome = BundleMigration.migrateFolder(dir)

    assertFalse(File(dir, "blaze.yaml").exists(), "blaze.yaml should be consumed + removed")
    assertTrue(outcome.removed.contains("blaze.yaml"))
    assertTrue(File(dir, "case_blaze.trail.yaml").isFile)
  }

  @Test
  fun `surfaces kind and config drift in the outcome's drift comments`() {
    // The deep drift-detection coverage lives in UnifiedTrailMigratorTest; this pins that
    // migrateFolder WIRES every drift channel into Outcome.driftComments (the route and the UI
    // modal show exactly this list). Kind drift (step: vs verify: at the same index) and config
    // drift (divergent title) were silently dropped before — only NL/memory drift was included.
    val dir = bundle(
      "case_drift",
      mapOf(
        "android-phone.trail.yaml" to """
          - config: {id: case_drift, target: sample, platform: android, title: Title A}
          - prompts:
            - step: Open the app
              recording:
                tools:
                - tapOnPoint: {x: 1, y: 2}
        """,
        "ios-iphone.trail.yaml" to """
          - config: {id: case_drift, target: sample, platform: ios, title: Title B}
          - prompts:
            - verify: Open the app
              recording:
                tools:
                - tapOnPoint: {x: 3, y: 4}
        """,
      ),
    )

    val outcome = BundleMigration.migrateFolder(dir)

    assertTrue(outcome.driftComments.isNotEmpty(), "kind/config drift must surface in driftComments")
  }

  @Test
  fun `surfaces dropped input content in the outcome's drift comments and the written file`() {
    // Pins that migrateFolder folds TrailRoundTripDropDetector's findings into Outcome.driftComments
    // (the route + UI modal read this) AND into the written unified file's leading comments. The
    // dedented `below:` anchor lives at the tool-item level, which the tool decoder silently drops.
    val dir = bundle(
      "case_drop",
      mapOf(
        "android-phone.trail.yaml" to """
          - config: {id: case_drop, target: sample, platform: android}
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
        """,
      ),
    )

    val outcome = BundleMigration.migrateFolder(dir)

    // The warning spans multiple comment lines (a DROPPED header + one detail line per key), so
    // assert the header and the key detail are both present rather than on one line.
    assertTrue(
      outcome.driftComments.any { it.contains("DROPPED") } && outcome.driftComments.any { it.contains("below") },
      "dropped content must surface in driftComments; got: ${outcome.driftComments}",
    )
    val written = File(dir, "case_drop.trail.yaml").readText()
    assertTrue("DROPPED" in written, "migrated file must carry the dropped-content warning; got:\n$written")
  }

  @Test
  fun `a lossy migration retains the v1 inputs and signals retention on the outcome`() {
    // The migrator dropped a key it could not carry (the dedented `below:` positional anchor), so
    // the v1 file is the only surviving record of that content. migrateFolder must NOT delete it —
    // it writes trail.yaml but leaves the v1 input on disk for a human to reconcile, and signals the
    // retention on the Outcome (inputsRetained + a leading note in driftComments the route/UI shows).
    val dir = bundle(
      "case_lossy",
      mapOf(
        "android-phone.trail.yaml" to """
          - config: {id: case_lossy, target: sample, platform: android}
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
        """,
      ),
    )

    val outcome = BundleMigration.migrateFolder(dir)

    assertTrue(outcome.inputsRetained, "a lossy migration must flag inputsRetained")
    // The migrated file is still written…
    assertTrue(File(dir, "case_lossy.trail.yaml").isFile, "trail.yaml should still be written")
    // …but the v1 input is left on disk, and nothing was reported as removed.
    assertTrue(File(dir, "android-phone.trail.yaml").isFile, "v1 input must be retained on a lossy migration")
    assertEquals(emptyList(), outcome.removed, "nothing should be deleted on a lossy migration")
    // The retention reason is surfaced on the Outcome (route/UI reads driftComments), naming the
    // retained file, on top of the underlying DROPPED warning.
    assertTrue(
      outcome.driftComments.any { it.contains("kept the v1 input file(s)") } &&
        outcome.driftComments.any { it.contains("android-phone.trail.yaml") },
      "retention reason must surface in driftComments; got: ${outcome.driftComments}",
    )
    assertTrue(outcome.driftComments.any { it.contains("DROPPED") }, "underlying drop warning must remain")
  }

  @Test
  fun `a drift-free bundle reports no drift comments`() {
    val dir = bundle(
      "case_clean",
      mapOf(
        "android-phone.trail.yaml" to """
          - config: {id: case_clean, target: sample, platform: android}
          - prompts:
            - step: Open the app
              recording:
                tools:
                - tapOnPoint: {x: 1, y: 2}
        """,
        "ios-iphone.trail.yaml" to """
          - config: {id: case_clean, target: sample, platform: ios}
          - prompts:
            - step: Open the app
              recording:
                tools:
                - tapOnPoint: {x: 3, y: 4}
        """,
      ),
    )

    val outcome = BundleMigration.migrateFolder(dir)

    assertEquals(emptyList(), outcome.driftComments, "identical NL/kind/config must produce no drift")
  }

  @Test
  fun `refuses a folder that is already migrated`() {
    val dir = bundle(
      "case_dup",
      mapOf(
        "android-phone.trail.yaml" to """
          - config: {id: case_dup, target: sample, platform: android}
          - prompts:
            - step: Open the app
              recording:
                tools:
                - tapOnPoint: {x: 1, y: 2}
        """,
      ),
    )
    // Pre-create the target unified file.
    File(dir, "case_dup.trail.yaml").writeText("config: {}\ntrail: []\n")

    val e = assertFailsWith<IllegalArgumentException> { BundleMigration.migrateFolder(dir) }
    assertTrue(e.message!!.contains("already migrated"), "message was: ${e.message}")
    // The input file must be untouched on refusal.
    assertTrue(File(dir, "android-phone.trail.yaml").isFile)
  }

  @Test
  fun `refuses a folder containing an already-unified trail file without merging or deleting`() {
    // The folder-view is reachable (via the back-arrow) for a directory of distinct prompt-only
    // unified trails; migrating there would merge + delete them. The all-v1 guard must refuse.
    val dir = bundle(
      "flows",
      mapOf(
        "login.trail.yaml" to """
          config: {id: login}
          trail:
            - step: Log in
        """,
        "logout.trail.yaml" to """
          config: {id: logout}
          trail:
            - step: Log out
        """,
      ),
    )

    val e = assertFailsWith<IllegalArgumentException> { BundleMigration.migrateFolder(dir) }
    assertTrue(e.message!!.contains("already unified"), "message was: ${e.message}")
    // Both distinct trails untouched; nothing merged/written.
    assertTrue(File(dir, "login.trail.yaml").isFile)
    assertTrue(File(dir, "logout.trail.yaml").isFile)
    assertFalse(File(dir, "flows.trail.yaml").exists())
  }

  @Test
  fun `propagates the migrator refusal for a trailhead block`() {
    val dir = bundle(
      "case_th",
      mapOf(
        "android-phone.trail.yaml" to """
          - config: {id: case_th, target: sample, platform: android}
          - trailhead:
              launchApp: {appId: sample}
          - prompts:
            - step: Open the app
        """,
      ),
    )
    // The migrator refuses trailhead inputs; BundleMigration surfaces that as IllegalArgumentException
    // and must NOT have written a partial output.
    assertFailsWith<IllegalArgumentException> { BundleMigration.migrateFolder(dir) }
    assertFalse(File(dir, "case_th.trail.yaml").exists(), "no output should be written on refusal")
    assertTrue(File(dir, "android-phone.trail.yaml").isFile, "input must be untouched on refusal")
  }
}
