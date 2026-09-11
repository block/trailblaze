package xyz.block.trailblaze.inprocessidle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a reader of a build log can rely on. Two different attachers — a host one over adb and an
 * on-device one inside the test APK — write these lines, and the only reason that is safe is that
 * they write the SAME lines. So the contract pinned here is what someone (or a CI grep) can search
 * for, not how any one attacher is implemented.
 */
class TurboMessagesTest {

  @Test
  fun theTagIsTheLiteralPeopleAndScriptsActuallyGrepFor() {
    // Pinned to the literal, not to the constant. `everyLineIsFindableByTheSameTag` compares each
    // line against LOG_TAG itself, so it stays green if the tag is renamed — but `[turbo]` is what
    // CI scripts grep, what a suite's own docs tell an operator to search for, and what the
    // reference docs promise. Renaming it is a contract break, so it has to fail a test.
    assertEquals("[turbo]", TurboMessages.LOG_TAG)
  }

  @Test
  fun everyLineIsFindableByTheSameTag() {
    val lines =
      listOf(
        TurboMessages.turboOn("session-1", "com.example.app", "emulator-5554"),
        TurboMessages.normalSpeed("session-1", "no helper for com.example.app"),
        TurboMessages.clearing("session-1", "emulator-5554"),
        TurboMessages.leavingSwitchToItsOwner("session-1", "com.example.app", "emulator-5554"),
        TurboMessages.mayStillBeOn("session-1", "emulator-5554", "the property did not change", "adb shell setprop x 0"),
      )
    lines.forEach { line ->
      assertTrue(line.startsWith(TurboMessages.LOG_TAG), "not greppable by ${TurboMessages.LOG_TAG}: $line")
      assertTrue(line.contains("session-1"), "does not say which run it is about: $line")
    }
  }

  @Test
  fun turboOnSaysWhichAppOnWhichDevice() {
    val line = TurboMessages.turboOn("session-1", "com.example.app", "emulator-5554")
    // "turbo is on somewhere" is not actionable — only one app per device can be turbo, so a reader
    // has to be able to tell whether it was theirs.
    assertTrue(line.contains("com.example.app"), line)
    assertTrue(line.contains("emulator-5554"), line)
  }

  @Test
  fun aRunThatCouldNotGetTurboSaysWhyAndThatItContinues() {
    val line = TurboMessages.normalSpeed("session-1", "could not turn turbo on for com.example.app (refused)")
    assertTrue(line.contains("refused"), "drops the reason: $line")
    // The single most important thing this line says: turbo failing is not the run failing.
    assertTrue(line.contains("running at normal speed"), line)
  }

  @Test
  fun clearingSaysTurboIsOffAndNamesTheDevice() {
    val line = TurboMessages.clearing("session-1", "emulator-5554")
    // This line is the only evidence that a switch an EARLIER run left behind has been dealt with,
    // so it has to say both that turbo is off for this run and which device was cleaned up — one
    // device's leftover switch is another run's early-released wait.
    assertTrue(line.contains("turbo is off"), line)
    assertTrue(line.contains("clearing"), line)
    assertTrue(line.contains("emulator-5554"), line)
  }

  @Test
  fun aSparedSwitchNamesItsOwnerAndDoesNotClaimTurboIsOn() {
    val line = TurboMessages.leavingSwitchToItsOwner("session-1", "com.example.otherapp", "emulator-5554")
    // Which app holds the port is the whole point: only one detector fits, so a reader has to be
    // able to tell whose it is before concluding anything about their own run's speed.
    assertTrue(line.contains("com.example.otherapp"), line)
    // Must not read as turbo engaging for THIS run. It did not — the run never asked; someone else
    // owns the switch. A line saying "turbo on" here would be read as this run being turbo, and a
    // grep counting turbo runs would count it.
    assertFalse(line.contains("turbo on"), line)
    assertTrue(line.contains("not requested"), line)
  }

  @Test
  fun aSwitchThatCouldNotBeClearedIsLoudAndNamesTheFix() {
    val line =
      TurboMessages.mayStillBeOn(
        scope = "session-1",
        deviceLabel = "emulator-5554",
        reason = "the property did not change",
        clearCommand = "adb -s emulator-5554 shell setprop debug.trailblaze.settle.inProcessIdle 0",
      )
    assertTrue(line.contains("WARNING"), line)
    assertTrue(line.contains("the property did not change"), line)
    // A switch left on can end a later wait early on another app's idle, so the line has to carry
    // the one-line manual fix rather than only the diagnosis.
    assertTrue(line.contains("adb -s emulator-5554 shell setprop"), line)
  }
}
