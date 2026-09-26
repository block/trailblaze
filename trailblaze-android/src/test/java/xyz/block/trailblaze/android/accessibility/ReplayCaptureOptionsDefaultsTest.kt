package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The defaulting rule for the replay-dispatch switches.
 *
 * The claim these switches ship on is "on under turbo, and the non-turbo path is byte-for-byte what
 * it was". That claim lives entirely in [ReplayCaptureOptions.resolve], so it is tested here rather
 * than argued from the call sites. The bundle-reuse switch is the exception: it defaults on.
 */
class ReplayCaptureOptionsDefaultsTest {

  @Test
  fun `an unset sysprop follows turbo`() {
    assertTrue(ReplayCaptureOptions.resolve("", turboOn = true))
    assertFalse(ReplayCaptureOptions.resolve("", turboOn = false))
  }

  @Test
  fun `zero is a kill switch even under turbo`() {
    assertFalse(ReplayCaptureOptions.resolve("0", turboOn = true))
    assertFalse(ReplayCaptureOptions.resolve("false", turboOn = true))
    assertFalse(ReplayCaptureOptions.resolve("FALSE", turboOn = true))
  }

  @Test
  fun `one forces it on without turbo`() {
    assertTrue(ReplayCaptureOptions.resolve("1", turboOn = false))
    assertTrue(ReplayCaptureOptions.resolve("true", turboOn = false))
    assertTrue(ReplayCaptureOptions.resolve("TRUE", turboOn = false))
  }

  @Test
  fun `a value nobody recognises is not read as a kill switch`() {
    // A typo must not silently disable the behaviour on a turbo device: it falls back to the
    // default, which is what an operator who set nothing would have got.
    assertTrue(ReplayCaptureOptions.resolve("yes", turboOn = true))
    assertFalse(ReplayCaptureOptions.resolve("yes", turboOn = false))
  }

  /** A `trailblaze tool` call is one dispatch, and relaunching every bundle cost it ~200 ms on Square. */
  @Test
  fun `bundles read from the APK are reused without turbo`() {
    assertTrue(ReplayCaptureOptions.resolveToolBundleReuse("", readsPushedBundles = false))
  }

  /** A host can push a new bundle between two dispatches, and the next one must load it. */
  @Test
  fun `a session that may load pushed bundles relaunches them on every dispatch`() {
    assertFalse(ReplayCaptureOptions.resolveToolBundleReuse("", readsPushedBundles = true))
    assertFalse(ReplayCaptureOptions.resolveToolBundleReuse("1", readsPushedBundles = true))
  }

  @Test
  fun `bundle reuse keeps its kill switch`() {
    assertFalse(ReplayCaptureOptions.resolveToolBundleReuse("0", readsPushedBundles = false))
  }
}
