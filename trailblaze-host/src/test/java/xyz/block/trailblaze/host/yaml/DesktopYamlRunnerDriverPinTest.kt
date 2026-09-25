package xyz.block.trailblaze.host.yaml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import org.junit.Test
import xyz.block.trailblaze.cli.CliRunDriverResolution
import xyz.block.trailblaze.cli.CliRunDriverResolver
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.TrailblazeLlmModels
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.ui.model.RunYamlRequestFactory
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig
import xyz.block.trailblaze.yaml.TrailConfig

/**
 * Guards the runner-side read of a trail's driver pin
 * ([DesktopYamlRunner.trailPinnedDriverResolution]).
 *
 * The daemon's `/cli/run` handler and the desktop Run path extract trail config without a device,
 * so a unified trail's per-classifier `devices:` pin arrives at the runner as
 * `RunYamlRequest.driverType = null`. The runner must resolve the pin itself against the connected
 * device's classifiers — when this regressed, CLI smoke trails carrying an explicit Android pin
 * silently ran on whatever the default driver happened to be.
 *
 * A pin naming an unknown driver must resolve to [CliRunDriverResolution.Unrecognized] — never
 * null — so callers fail loud instead of silently falling back to the default driver.
 */
class DesktopYamlRunnerDriverPinTest {

  private val androidPhone = listOf(
    TrailblazeDeviceClassifier("android"),
    TrailblazeDeviceClassifier("phone"),
  )

  private fun resolvedDriverType(yaml: String): TrailblazeDriverType? {
    val resolution = DesktopYamlRunner.trailPinnedDriverResolution(yaml, androidPhone)
    assertTrue(
      "expected Resolved but was $resolution",
      resolution is CliRunDriverResolution.Resolved,
    )
    return (resolution as CliRunDriverResolution.Resolved).driverType
  }

  @Test
  fun `device locale is suppressed for skipped trails`() {
    assertNull(
      DesktopYamlRunner.requestedDeviceLocale(
        TrailConfig(locale = "es", skip = "not supported on this device"),
      ),
    )
    assertEquals("es", DesktopYamlRunner.requestedDeviceLocale(TrailConfig(locale = "es")))
  }

  @Test
  fun `device locale forces a fresh target process`() {
    assertTrue(DesktopYamlRunner.shouldForceStopTargetApp(requested = false, locale = "es"))
    assertTrue(DesktopYamlRunner.shouldForceStopTargetApp(requested = true, locale = null))
  }

  @Test
  fun `unified devices pin resolves for a matching device`() {
    val yaml = """
      config:
        devices:
          android: ANDROID_ONDEVICE_ACCESSIBILITY
      trail:
        - step: "Open the Lists tab"
    """.trimIndent()

    assertEquals(
      TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      resolvedDriverType(yaml),
    )
  }

  @Test
  fun `unified devices pin is closest-wins per classifier`() {
    val yaml = """
      config:
        devices:
          android: ANDROID_TEST
          android-phone: ANDROID_ONDEVICE_ACCESSIBILITY
      trail:
        - step: "Open the Lists tab"
    """.trimIndent()

    assertEquals(
      TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      resolvedDriverType(yaml),
    )
  }

  @Test
  fun `unified pin for another platform resolves to null`() {
    val yaml = """
      config:
        devices:
          ios: IOS_HOST
      trail:
        - step: "Open the Lists tab"
    """.trimIndent()

    assertNull(resolvedDriverType(yaml))
  }

  @Test
  fun `trail with no pin resolves to null`() {
    val yaml = """
      trail:
        - step: "Open the Lists tab"
    """.trimIndent()

    assertNull(resolvedDriverType(yaml))
  }

  @Test
  fun `unparseable yaml resolves to null instead of throwing`() {
    assertNull(resolvedDriverType("config: [not, a, trail"))
  }

  @Test
  fun `unrecognized unified pin is rejected loud, naming the bad value and the valid drivers`() {
    val yaml = """
      config:
        devices:
          android: ANDROID_TYPO_DRIVER
      trail:
        - step: "Open the Lists tab"
    """.trimIndent()

    val resolution = DesktopYamlRunner.trailPinnedDriverResolution(yaml, androidPhone)
    assertTrue(
      "expected Unrecognized but was $resolution",
      resolution is CliRunDriverResolution.Unrecognized,
    )
    val message = (resolution as CliRunDriverResolution.Unrecognized).message
    assertTrue(
      "message should name the bad value: $message",
      message.contains("'ANDROID_TYPO_DRIVER'"),
    )
    for (driver in TrailblazeDriverType.entries - TrailblazeDriverType.RETIRED_DRIVERS) {
      assertTrue(
        "message should list valid driver ${driver.name}: $message",
        message.contains(driver.name),
      )
    }
    for (retired in TrailblazeDriverType.RETIRED_DRIVERS) {
      assertTrue(
        "message must not offer the retired driver ${retired.name}: $message",
        !message.contains(retired.name),
      )
    }
  }

  /**
   * The recovery path for a typo'd `devices:` entry re-walks the map and picks this device's
   * winning entry out of the ones that DID decode. A retired driver decodes cleanly, so handing
   * that winner straight back would smuggle it past the retirement check that every other pin
   * goes through — the run would then die downstream on a missing on-device agent instead of
   * saying what to re-record.
   */
  @Test
  fun `a retired pin that wins the typo recovery walk is still rejected`() {
    val yaml = """
      config:
        devices:
          ios: IOS_TYPO_DRIVER
          android: ANDROID_ONDEVICE_INSTRUMENTATION
      trail:
        - step: "Open the Lists tab"
    """.trimIndent()

    val resolution = DesktopYamlRunner.trailPinnedDriverResolution(yaml, androidPhone)
    assertTrue(
      "expected Unrecognized but was $resolution",
      resolution is CliRunDriverResolution.Unrecognized,
    )
    val message = (resolution as CliRunDriverResolution.Unrecognized).message
    assertTrue(
      "message should say the driver is retired, not merely unknown: $message",
      message.contains("retired"),
    )
    assertTrue(
      "message should name the retired driver: $message",
      message.contains(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION.name),
    )
    assertTrue(
      "message should name what to use instead: $message",
      message.contains(TrailblazeDriverType.DEFAULT_ANDROID.name),
    )
  }

  /**
   * The runner has a rung AHEAD of the pin rung: a `RunYamlRequest` that already carries a driver
   * (the CLI's `--driver`, an MCP on-device tool request built from a persisted setting) short-
   * circuits pin resolution entirely. It must refuse a retired driver by the SAME resolver, or a
   * stale request ships to the device and dies there with no mention of what to re-record.
   */
  @Test
  fun `the request rung refuses a retired driver and passes a runnable one`() {
    for (retired in TrailblazeDriverType.RETIRED_DRIVERS) {
      val resolution = CliRunDriverResolver.resolve(retired)
      assertTrue(
        "expected Unrecognized but was $resolution",
        resolution is CliRunDriverResolution.Unrecognized,
      )
      val message = (resolution as CliRunDriverResolution.Unrecognized).message
      assertTrue("message should name the retired driver: $message", message.contains(retired.name))
      assertTrue(
        "message should name what to use instead: $message",
        message.contains(TrailblazeDriverType.DEFAULT_ANDROID.name),
      )
    }

    // The other half: this rung only REFUSES. Every runnable driver a request can carry passes
    // through unchanged, so the new check is a no-op for every run that exists today.
    for (runnable in TrailblazeDriverType.entries - TrailblazeDriverType.RETIRED_DRIVERS) {
      val resolution = CliRunDriverResolver.resolve(runnable)
      assertTrue(
        "expected Resolved for $runnable but was $resolution",
        resolution is CliRunDriverResolution.Resolved,
      )
      assertEquals(runnable, (resolution as CliRunDriverResolution.Resolved).driverType)
    }
  }

  /**
   * The other side of that short-circuit: the desktop Run path must NOT fill the request rung.
   * Picking a device in the UI is a device choice, not a driver request — when the factory stamped
   * the device's own driver in, every desktop run arrived with the rung filled, the pin was never
   * read, and a trail pinned to a retired driver ran on the device's driver instead of refusing.
   */
  @Test
  fun `a desktop UI run leaves the request rung empty so a retired pin still refuses`() {
    val yaml = """
      config:
        devices:
          android: ANDROID_ONDEVICE_INSTRUMENTATION
      trail:
        - step: "Open the Lists tab"
    """.trimIndent()

    val request = RunYamlRequestFactory(
      appConfig = SavedTrailblazeAppConfig(selectedTrailblazeDriverTypes = emptyMap()),
      llmModel = TrailblazeLlmModels.GPT_4O_MINI,
      effectiveTargetAppId = { null },
    ).create(
      // A runnable driver, differing from the pin — the value that used to win silently.
      device = TrailblazeConnectedDeviceSummary(
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
        instanceId = "emulator-5554",
        description = "test emulator",
      ),
      yaml = yaml,
      testName = "test",
      referrer = TrailblazeReferrer.YAML_TAB,
    )

    assertNull("the desktop request must not pre-empt driver resolution", request.driverType)
    val resolution = DesktopYamlRunner.trailPinnedDriverResolution(request.yaml, androidPhone)
    assertTrue(
      "expected the retired pin to be refused but was $resolution",
      resolution is CliRunDriverResolution.Unrecognized,
    )
  }

  @Test
  fun `unrecognized pin in the object form is rejected loud too`() {
    val yaml = """
      config:
        devices:
          android:
            driver: ANDROID_TYPO_DRIVER
      trail:
        - step: "Open the Lists tab"
    """.trimIndent()

    val resolution = DesktopYamlRunner.trailPinnedDriverResolution(yaml, androidPhone)
    assertTrue(
      "expected Unrecognized but was $resolution",
      resolution is CliRunDriverResolution.Unrecognized,
    )
    val message = (resolution as CliRunDriverResolution.Unrecognized).message
    assertTrue(
      "message should name the bad value: $message",
      message.contains("'ANDROID_TYPO_DRIVER'"),
    )
  }

  @Test
  fun `unrecognized pin for another platform resolves to null for this device`() {
    // The pin is bad, but it is not reachable from this device's classifier chain — the trail
    // never runs on this driver decision, so it must not fail this device's run.
    val yaml = """
      config:
        devices:
          ios: IOS_TYPO_DRIVER
      trail:
        - step: "Open the Lists tab"
    """.trimIndent()

    assertNull(resolvedDriverType(yaml))
  }

  @Test
  fun `a valid pin for this device survives another platform's typo`() {
    // The bad ios entry comes FIRST so this also guards against aborting the devices-map decode
    // at the first bad entry — the android pin after it must still be seen and win.
    val yaml = """
      config:
        devices:
          ios: IOS_TYPO_DRIVER
          android:
            driver: ANDROID_ONDEVICE_ACCESSIBILITY
      trail:
        - step: "Open the Lists tab"
    """.trimIndent()

    assertEquals(
      TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      resolvedDriverType(yaml),
    )
  }

  @Test
  fun `unrecognized object-form pin for another platform resolves to null for this device`() {
    val yaml = """
      config:
        devices:
          ios:
            driver: IOS_TYPO_DRIVER
      trail:
        - step: "Open the Lists tab"
    """.trimIndent()

    assertNull(resolvedDriverType(yaml))
  }

  /**
   * The host runner picks its driver from the device summary's `trailblazeDriverType`, so the
   * runner hands the host branches a device tagged with the resolved pin. That swap is only safe
   * because a pin resolves to a driver on the SAME platform — this guards that invariant (a pin
   * of iOS Axe over the simulator's default IOS_HOST keeps the device's identity intact).
   */
  @Test
  fun `retagging a device with a same-platform pin preserves its identity`() {
    val simulator = TrailblazeConnectedDeviceSummary(
      trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
      instanceId = "SIM-UDID-1234",
      description = "iPhone 15 simulator",
    )

    val retagged = simulator.copy(trailblazeDriverType = TrailblazeDriverType.IOS_AXE)

    assertEquals(TrailblazeDriverType.IOS_AXE, retagged.trailblazeDriverType)
    assertEquals(simulator.platform, retagged.platform)
    assertEquals(simulator.trailblazeDeviceId, retagged.trailblazeDeviceId)
  }
}
