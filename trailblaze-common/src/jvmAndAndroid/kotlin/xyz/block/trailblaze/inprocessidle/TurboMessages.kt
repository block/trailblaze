package xyz.block.trailblaze.inprocessidle

/**
 * The wording of the turbo VERDICT lines — did turbo engage, and if not why — in one place, because
 * two different attachers emit them.
 *
 * A host run attaches the in-process idle detector over adb; an on-device farm run attaches it from
 * inside the test APK with no host present. The mechanisms genuinely differ (adb + a CLI-bundled
 * APK vs `PackageInstaller` + an APK staged in test assets), so they stay separate — but what a
 * reader has to grep for must not. Anyone reading a build log should be able to answer "did turbo
 * engage?" with the same search whether the run was driven from a laptop or from a device farm, and
 * the CI checks that assert on these lines should not need to know which one produced them.
 *
 * NOT every `[turbo]`-prefixed line in the codebase, and not the step-by-step narration of an
 * attach. The host attacher and `InProcessIdleAttacher` both log their own progress under their own
 * tags (`[inprocess-idle-farm]` on-device), and those stay where they are — they describe one
 * mechanism each, so sharing their wording would mean pretending two different sequences are the
 * same one. What is shared here is only the part a reader or a grep treats as the answer.
 *
 * Pure string building, so both callers can be unit-tested without a device.
 *
 * [scope] is whatever identifies the run to its reader — a session id on the host, the trail's test
 * name on-device.
 */
object TurboMessages {

  /** Prefix every line here carries. Callers grep for this; treat it as a contract. */
  const val LOG_TAG = "[turbo]"

  /** The detector is attached and the settle gates are racing it. */
  fun turboOn(scope: String, appId: String, deviceLabel: String): String =
    "$LOG_TAG $scope: turbo on for $appId on $deviceLabel"

  /**
   * Turbo was asked for and could not be had. Always names a reason, and always says the run
   * continues — a run that quietly says nothing here is indistinguishable from one that never
   * asked, and turbo failing must never read as the run failing.
   */
  fun normalSpeed(scope: String, reason: String): String =
    "$LOG_TAG $scope: $reason — running at normal speed"

  /** Turbo is off for this run, and a switch an earlier run left on is being turned off. */
  fun clearing(scope: String, deviceLabel: String): String =
    "$LOG_TAG $scope: turbo is off — clearing it on $deviceLabel"

  /**
   * Turbo is off for this run, the switch is on, and a live detector answers on the port — so the
   * switch is that detector's and not a leftover. Says so instead of clearing, because a run that
   * attaches the detector itself rather than through the turbo arg is a legitimate owner, and
   * turning its switch off would drop it to heuristic speed while it still reported success.
   */
  fun leavingSwitchToItsOwner(scope: String, appId: String, deviceLabel: String): String =
    "$LOG_TAG $scope: turbo was not requested, but the idle detector attached to $appId is live on " +
      "$deviceLabel — leaving the settle race on, the switch belongs to that detector"

  /**
   * The switch could not be turned off. Loud on purpose: the settle gates' idle request is not
   * app-scoped, so a switch left on can end a wait early on whatever process still holds the
   * detector port. Names the one-line manual fix rather than only the failure.
   */
  fun mayStillBeOn(scope: String, deviceLabel: String, reason: String, clearCommand: String): String =
    "$LOG_TAG $scope: WARNING — turbo may still be on for $deviceLabel and could not be turned off " +
      "($reason). Waits in this run can end early on another app's idle. Clear it with: $clearCommand"
}
