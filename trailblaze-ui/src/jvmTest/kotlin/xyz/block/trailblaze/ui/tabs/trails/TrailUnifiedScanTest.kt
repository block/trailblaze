package xyz.block.trailblaze.ui.tabs.trails

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * End-to-end (on-disk) check that the desktop Trails browser derives a trail's targets from the
 * file content, not the filename: a unified single-file `trail.yaml` surfaces its recording +
 * `config.devices` coverage, while a legacy per-device `<classifier>.trail.yaml` keeps its
 * filename-derived platform. Exercises the real scan → cache → [TrailVariant] → [Trail] path.
 */
class TrailUnifiedScanTest {

  private val tempRoot: File = File.createTempFile("trails-scan", "").let { probe ->
    probe.delete()
    probe.mkdirs()
    probe
  }

  @AfterTest
  fun cleanup() {
    tempRoot.deleteRecursively()
  }

  private fun writeTrail(relativePath: String, content: String) {
    val file = File(tempRoot, relativePath)
    file.parentFile.mkdirs()
    file.writeText(content)
    // A file re-created within the same clock tick as a prior test can share a mtime with a stale
    // cache entry; invalidate so the scan re-parses this exact path.
    TrailConfigCache.invalidate(file.absolutePath)
  }

  @Test
  fun `unified trail surfaces recording plus config-devices coverage while v1 keeps filename platform`() {
    // Unified: config.devices omits iOS, but an ios recording exists → iOS must still be covered.
    // `kiosk-a` is a device-family classifier whose prefix is not a platform.
    writeTrail(
      "case_unified/trail.yaml",
      """
        config:
          target: myapp
          devices:
            android: ANDROID_ONDEVICE_ACCESSIBILITY
            kiosk-a: ANDROID_ONDEVICE_INSTRUMENTATION
        trail:
          - step: Go back
            recording:
              android:
                - pressBack: {}
              ios:
                - pressBack: {}
              kiosk-a:
                - pressBack: {}
      """.trimIndent(),
    )
    writeTrail(
      "case_v1/android.trail.yaml",
      """
        - config:
            target: myapp
        - tools:
          - pressBack: {}
      """.trimIndent(),
    )

    val trails = TrailsDirectoryScanner.scanForTrails(tempRoot)

    val unified = trails.single { it.id == "case_unified" }
    assertTrue(unified.isUnified, "trail.yaml is a unified single-file trail")
    assertEquals(1, unified.variants.size, "a unified trail is one file")
    assertEquals("Unified", unified.variants.single().displayLabel)
    assertEquals(
      listOf("android", "ios", "kiosk-a"),
      unified.unifiedTargets,
      "targets are the union of config.devices keys and recording classifiers, sorted",
    )
    assertEquals(
      setOf(TrailblazeDevicePlatform.ANDROID, TrailblazeDevicePlatform.IOS),
      unified.platforms,
      "iOS is covered by its recording even though config.devices omits it; kiosk-a maps to no platform",
    )
    assertEquals(
      mapOf("android" to "ANDROID_ONDEVICE_ACCESSIBILITY", "kiosk-a" to "ANDROID_ONDEVICE_INSTRUMENTATION"),
      unified.unifiedDevices,
    )

    val v1 = trails.single { it.id == "case_v1" }
    assertFalse(v1.isUnified, "a legacy per-device file is not unified")
    assertTrue(v1.unifiedTargets.isEmpty(), "legacy trails have no unified targets")
    assertEquals(setOf(TrailblazeDevicePlatform.ANDROID), v1.platforms)
    assertEquals(listOf("android"), v1.variants.single().classifierNames)
  }

  @Test
  fun `a unified-shaped trail that fails to decode stays flagged unified but surfaces no targets`() {
    // Content-detection (a column-0 `trail:` line scan) recognizes the unified shape cheaply, before
    // any decode. Here `trail:` is a scalar, not the required list of steps, so the fuller decode
    // throws — the flag must survive (the file still reads as unified) while the targets/drivers
    // drop rather than crash the whole directory scan.
    writeTrail(
      "case_undecodable/trail.yaml",
      """
        config:
          target: myapp
          devices:
            android: ANDROID_ONDEVICE_ACCESSIBILITY
        trail: this-should-be-a-list-of-steps-not-a-scalar
      """.trimIndent(),
    )

    val trail = TrailsDirectoryScanner.scanForTrails(tempRoot).single { it.id == "case_undecodable" }
    assertTrue(trail.isUnified, "content is unified (column-0 trail:) even though it won't decode")
    assertTrue(trail.unifiedTargets.isEmpty(), "an undecodable unified trail surfaces no targets")
    assertTrue(trail.unifiedDevices.isEmpty(), "config.devices is dropped when the decode fails")
    assertTrue(trail.platforms.isEmpty(), "no classifiers decoded → no platforms folded")
  }
}
