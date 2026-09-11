package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two pure decisions in front of [InProcessIdleAttacher]'s attach sequence: whether an
 * instrumentation arg opts a run in, and which app it attaches to. Everything after them touches a
 * device, so these are the only parts provable off-device — and they are the parts that decide
 * whether a lane that never heard of the detector pays for it.
 */
class InProcessIdleAttacherArgsTest {

  @Test
  fun onlyAnExplicitTrueOptsIn() {
    assertTrue(InProcessIdleAttacher.isArgTrue("true"))
    assertTrue(InProcessIdleAttacher.isArgTrue("TRUE"))
    assertTrue(InProcessIdleAttacher.isArgTrue("True"))
  }

  @Test
  fun anAbsentArgIsOffNotUnset() {
    // The shape a lane that never heard of the knob sees. "Unset, so try" would make every
    // on-device lane pay the work these args gate.
    assertFalse(InProcessIdleAttacher.isArgTrue(null))
  }

  @Test
  fun nonTrueValuesAreOff() {
    assertFalse(InProcessIdleAttacher.isArgTrue(""))
    assertFalse(InProcessIdleAttacher.isArgTrue("false"))
    assertFalse(InProcessIdleAttacher.isArgTrue("yes"))
    // `1`/`0` is the settle SYSPROP's vocabulary ([InProcessIdleSettleClient.parseEnabled]
    // accepts them); the instrumentation args deliberately do not share it, so neither reads as
    // an opt-in here.
    assertFalse(InProcessIdleAttacher.isArgTrue("1"))
    assertFalse(InProcessIdleAttacher.isArgTrue("0"))
  }

  @Test
  fun targetAppIdFallsBackToTheBundlesOwnDefault() {
    assertEquals(
      "com.example.app",
      InProcessIdleAttacher.resolveTargetAppId(argValue = null, defaultAppId = "com.example.app"),
    )
  }

  @Test
  fun aBlankOverrideIsNoOverride() {
    // An empty instrumentation arg (`-e trailblaze.inProcessIdle.targetApp ""`) would otherwise
    // resolve to a detector package of `xyz.block.trailblaze.inprocessidle.` and fail the install
    // with a missing-asset error instead of using the bundle's default.
    assertEquals(
      "com.example.app",
      InProcessIdleAttacher.resolveTargetAppId(argValue = "   ", defaultAppId = "com.example.app"),
    )
  }

  @Test
  fun anOverrideIsTrimmed() {
    // The resolved value is interpolated into a package name, an asset path and several shell
    // commands. Untrimmed, a stray space surfaces as a missing-asset failure several steps later
    // rather than as a bad argument.
    assertEquals(
      "com.example.other",
      InProcessIdleAttacher.resolveTargetAppId(
        argValue = "  com.example.other ",
        defaultAppId = "com.example.app",
      ),
    )
  }

  @Test
  fun anOverrideWins() {
    assertEquals(
      "com.example.other",
      InProcessIdleAttacher.resolveTargetAppId(
        argValue = "com.example.other",
        defaultAppId = "com.example.app",
      ),
    )
  }

  @Test
  fun aShellUnsafeOverrideIsRejectedAtTheArgument() {
    // The resolved id is interpolated into `pm compile $appId` and `am start -n $appId/...`, and
    // the UiAutomation shell splits its command on whitespace — so a space or a `;` here supplies
    // argv of the caller's choosing. Rejected at the argument, where the message names the arg,
    // rather than several steps later as an unexplained command failure.
    listOf(
      "com.example.app; rm -rf /sdcard",
      "com.example.app extra",
      "com.example.app`id`",
      "com.example.app|sh",
      "notapackagename",
    ).forEach { hostile ->
      val failure = assertFailsWith<IllegalArgumentException>(hostile) {
        InProcessIdleAttacher.resolveTargetAppId(argValue = hostile, defaultAppId = "com.example.app")
      }
      assertTrue(
        InProcessIdleAttacher.TARGET_APP_ARG in failure.message.orEmpty(),
        "message should name the argument, was: ${failure.message}",
      )
    }
  }

  @Test
  fun aTargetlessRunResolvesToBlankRatherThanThrowing() {
    // `AndroidTrailblazeRule.turboTargetAppId` passes `agentAppId ?: ""` and converts a blank
    // result to null — "this run could not name its target", and turbo reports itself off. Blank is
    // therefore a supported answer, not a bad argument. That getter is evaluated on EVERY
    // accessibility-driver run whether or not turbo was asked for, so a throw here would break runs
    // that never wanted the detector: the validation must only apply to a value headed for a shell.
    assertEquals("", InProcessIdleAttacher.resolveTargetAppId(argValue = null, defaultAppId = ""))
    assertEquals("", InProcessIdleAttacher.resolveTargetAppId(argValue = "   ", defaultAppId = ""))
  }

  @Test
  fun anInvalidDefaultIsRejectedToo() {
    // A build-time constant today, but the check is about where the value is USED — every path out
    // of this function reaches the same shell commands.
    assertFailsWith<IllegalArgumentException> {
      InProcessIdleAttacher.resolveTargetAppId(argValue = null, defaultAppId = "not a package")
    }
  }
}
