package xyz.block.trailblaze.yaml.unified

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * Pins the pure coverage-derivation [UnifiedTrailTargets] uses to answer "what devices does this
 * unified trail cover" from the file content (recordings + `config.devices`), independent of the
 * filename. This is the derivation the desktop Trails browser and
 * [xyz.block.trailblaze.devices.TrailDeviceSelector.supportedPlatformsForTrail] both rely on.
 */
class UnifiedTrailTargetsTest {

  private fun decode(yaml: String): UnifiedTrail = TrailblazeYaml.Default.decodeUnifiedTrail(yaml)

  /**
   * A real-world shape: a broad launch step (`android`/`ios`/`kiosk`), device-specific taps
   * (`android-phone`/`android-tablet`/`kiosk-a`/`kiosk-b`), and a `config.devices` run matrix that
   * deliberately OMITS iOS even though every step records an `ios:` block. Coverage must be the full
   * union of both sources — iOS included, because recordings for it exist. `kiosk-*` is a
   * device-family classifier whose prefix is not a platform.
   */
  private val mixedCoverageShape = """
    config:
      target: myapp
      devices:
        android-phone: ANDROID_ONDEVICE_ACCESSIBILITY
        android-tablet: ANDROID_ONDEVICE_INSTRUMENTATION
        kiosk-a: ANDROID_ONDEVICE_ACCESSIBILITY
        kiosk-b: ANDROID_ONDEVICE_INSTRUMENTATION
    trail:
      - step: Launch the app
        recording:
          android:
            - pressBack: {}
          ios:
            - pressBack: {}
          kiosk:
            - pressBack: {}
      - step: Tap More
        recording:
          android-phone:
            - pressBack: {}
          android-tablet:
            - pressBack: {}
          ios:
            - pressBack: {}
          kiosk-a:
            - pressBack: {}
          kiosk-b:
            - pressBack: {}
  """.trimIndent()

  @Test
  fun `declaredClassifiers is the union of config-devices keys and every recording classifier`() {
    assertEquals(
      setOf(
        "android-phone", "android-tablet", "kiosk-a", "kiosk-b", // config.devices + device-specific recordings
        "android", "ios", "kiosk", // broad launch-step recordings
      ),
      UnifiedTrailTargets.declaredClassifiers(decode(mixedCoverageShape)),
    )
  }

  @Test
  fun `declaredClassifiers keeps a recording classifier that config-devices omits`() {
    // iOS is intentionally absent from config.devices, but ios recordings exist — coverage keeps it.
    assertTrue(
      "ios" in UnifiedTrailTargets.declaredClassifiers(decode(mixedCoverageShape)),
      "an ios recording keeps iOS covered even though config.devices omits it",
    )
  }

  @Test
  fun `declaredPlatforms folds classifiers up to their platform prefix`() {
    // kiosk-* has no platform prefix → contributes no platform, but the broad `android` recording
    // and the android-* classifiers still yield ANDROID, and the `ios` recording yields IOS.
    assertEquals(
      setOf(TrailblazeDevicePlatform.ANDROID, TrailblazeDevicePlatform.IOS),
      UnifiedTrailTargets.declaredPlatforms(decode(mixedCoverageShape)),
    )
  }

  @Test
  fun `trailhead recordings contribute to coverage`() {
    val yaml = """
      config:
        target: myapp
      trailhead:
        step: Login
        recording:
          ios-iphone:
            pressBack: {}
      trail:
        - verify: Home shown
    """.trimIndent()
    assertEquals(setOf("ios-iphone"), UnifiedTrailTargets.declaredClassifiers(decode(yaml)))
    assertEquals(setOf(TrailblazeDevicePlatform.IOS), UnifiedTrailTargets.declaredPlatforms(decode(yaml)))
  }

  @Test
  fun `an NL-only trail declares no classifiers`() {
    val yaml = """
      config:
        target: myapp
      trail:
        - step: do something in natural language
    """.trimIndent()
    assertEquals(emptySet(), UnifiedTrailTargets.declaredClassifiers(decode(yaml)))
    assertEquals(emptySet(), UnifiedTrailTargets.declaredPlatforms(decode(yaml)))
  }

  @Test
  fun `platformFor folds a single classifier to its platform prefix`() {
    // The single source of truth every UI/selection site holding classifier strings routes through.
    assertEquals(TrailblazeDevicePlatform.ANDROID, UnifiedTrailTargets.platformFor("android"))
    assertEquals(TrailblazeDevicePlatform.ANDROID, UnifiedTrailTargets.platformFor("android-tablet"))
    assertEquals(TrailblazeDevicePlatform.IOS, UnifiedTrailTargets.platformFor("ios-iphone-portrait"))
    assertEquals(null, UnifiedTrailTargets.platformFor("kiosk-a"), "a non-platform prefix folds to null")
  }

  @Test
  fun `platformsOf folds a collection dropping non-platform classifiers and de-duplicating`() {
    assertEquals(
      setOf(TrailblazeDevicePlatform.ANDROID, TrailblazeDevicePlatform.IOS),
      UnifiedTrailTargets.platformsOf(listOf("android", "android-tablet", "ios", "kiosk-a")),
    )
    assertEquals(emptySet(), UnifiedTrailTargets.platformsOf(emptyList()))
  }
}
