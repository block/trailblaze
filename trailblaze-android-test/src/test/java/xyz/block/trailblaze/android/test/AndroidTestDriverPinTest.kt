package xyz.block.trailblaze.android.test

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import xyz.block.trailblaze.android.test.AndroidTestTrailblazeRule.Companion.evaluateDriverPin
import xyz.block.trailblaze.android.test.AndroidTestTrailblazeRule.DriverPinVerdict
import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * Pins the force-beats-pin precedence of [AndroidTestTrailblazeRule.Companion.evaluateDriverPin].
 *
 * The stakes on each side: WITHOUT the force downgrade, a suite-wide `trailblaze.driverType`
 * override is a no-op against per-device-pinned trails — an entire estate whose trails pin the
 * accessibility driver per-device can never replay in-process, because lowering resolves that pin
 * into the effective `config.driver` this gate reads. WITHOUT the refusal, a pinned trail wired
 * directly into an in-process test replays silently on a driver missing the capability the pin
 * declared, and fails downstream blamed on the wrong step.
 */
class AndroidTestDriverPinTest {

  @Test
  fun `no pin allows, whatever the force arg says`() {
    listOf(null, "", "  ").forEach { pin ->
      listOf(null, "ANDROID_TEST", "ANDROID_ONDEVICE_ACCESSIBILITY").forEach { forced ->
        assertIs<DriverPinVerdict.Allow>(
          evaluateDriverPin(pinnedDriver = pin, forcedDriver = forced, trailFilePath = null),
          "pin=$pin forced=$forced",
        )
      }
    }
  }

  @Test
  fun `a pin naming this driver allows, in either spelling and any case`() {
    listOf("ANDROID_TEST", "android_test", "android-test", "Android-Test").forEach { pin ->
      assertIs<DriverPinVerdict.Allow>(
        evaluateDriverPin(pinnedDriver = pin, forcedDriver = null, trailFilePath = null),
        "pin=$pin",
      )
    }
  }

  @Test
  fun `a foreign pin with no force refuses, naming the trail and both ways out`() {
    val verdict =
      evaluateDriverPin(
        pinnedDriver = "ANDROID_ONDEVICE_ACCESSIBILITY",
        forcedDriver = null,
        trailFilePath = "trails/T/case.trail.yaml",
      )
    val refusal = assertIs<DriverPinVerdict.Refuse>(verdict)
    assertTrue("'trails/T/case.trail.yaml'" in refusal.message, refusal.message)
    assertTrue("ANDROID_ONDEVICE_ACCESSIBILITY" in refusal.message, refusal.message)
    // The message must teach the override that would have been honored, not just say no.
    assertTrue("-e trailblaze.driverType ANDROID_TEST" in refusal.message, refusal.message)
  }

  @Test
  fun `a foreign pin under an explicit force of this driver downgrades to a log`() {
    val verdict =
      evaluateDriverPin(
        pinnedDriver = "ANDROID_ONDEVICE_ACCESSIBILITY",
        forcedDriver = TrailblazeDriverType.ANDROID_TEST.name,
        trailFilePath = "trails/T/case.trail.yaml",
      )
    val allowed = assertIs<DriverPinVerdict.AllowForced>(verdict)
    // The log keeps the mismatch on record for a later missing-capability failure.
    assertTrue("ANDROID_ONDEVICE_ACCESSIBILITY" in allowed.logMessage, allowed.logMessage)
    assertTrue(TrailblazeDriverType.ANDROID_TEST.name in allowed.logMessage, allowed.logMessage)
  }

  @Test
  fun `the force arg is matched exactly, because the runtime parses it with valueOf`() {
    // A pin may say `android-test`; the ARG may not. InstrumentationArgUtil.driverType() is a bare
    // TrailblazeDriverType.valueOf, so these spellings never selected this driver in the first
    // place — the run fell back to its default. Honoring one here would let an arg the runtime
    // discarded authorize ignoring a pin.
    listOf(
      TrailblazeDriverType.ANDROID_TEST.yamlKey,
      "android_test",
      "Android_Test",
      "android test",
    ).forEach { forced ->
      assertIs<DriverPinVerdict.Refuse>(
        evaluateDriverPin(
          pinnedDriver = "ANDROID_ONDEVICE_ACCESSIBILITY",
          forcedDriver = forced,
          trailFilePath = null,
        ),
        "forced=$forced",
      )
    }
  }

  @Test
  fun `a force naming some OTHER driver keeps the refusal`() {
    // trailblaze.driverType=ACCESSIBILITY while this rule executes is an incoherent run, not an
    // authorization — the force downgrade applies only when the force names THIS driver.
    assertIs<DriverPinVerdict.Refuse>(
      evaluateDriverPin(
        pinnedDriver = "ANDROID_ONDEVICE_ACCESSIBILITY",
        forcedDriver = "ANDROID_ONDEVICE_ACCESSIBILITY",
        trailFilePath = null,
      ),
    )
    assertIs<DriverPinVerdict.Refuse>(
      evaluateDriverPin(
        pinnedDriver = "ANDROID_ONDEVICE_ACCESSIBILITY",
        forcedDriver = "not-a-driver",
        trailFilePath = null,
      ),
    )
  }

  @Test
  fun `the refusal and the log line render without a path when none is known`() {
    val refusal =
      assertIs<DriverPinVerdict.Refuse>(
        evaluateDriverPin(
          pinnedDriver = "ANDROID_ONDEVICE_ACCESSIBILITY",
          forcedDriver = null,
          trailFilePath = null,
        ),
      )
    assertEquals(false, "''" in refusal.message, refusal.message)
    assertTrue(refusal.message.startsWith("Trail pins"), refusal.message)
  }
}
