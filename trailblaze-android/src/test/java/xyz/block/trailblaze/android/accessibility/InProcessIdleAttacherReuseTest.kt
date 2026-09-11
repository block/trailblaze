package xyz.block.trailblaze.android.accessibility

import android.content.pm.PackageInstaller
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * When [InProcessIdleAttacher] may skip installing the detector APK.
 *
 * The install is the expensive half of an attach — a `pm uninstall` plus a commit-latched
 * `PackageInstaller` session — and it is reached once per trail on any trail whose trailhead
 * force-stops or clears the app under test, because that kills the detector and defeats the PING
 * short-circuit in front of it. Skipping a byte-identical reinstall there is the whole point, so
 * these pin both directions: when it reuses, and every case where it must not.
 */
class InProcessIdleAttacherReuseTest {

  private val pkg = "xyz.block.trailblaze.inprocessidle.app"
  private val asset = "inprocess-idle-apks/trailblaze-inprocess-idle-app.apk"
  private val key = InProcessIdleAttacher.detectorInstallKey(pkg, asset)

  @Test
  fun reusesWhatThisProcessAlreadyInstalledAndIsStillThere() {
    assertTrue(
      InProcessIdleAttacher.shouldReuseInstalledDetector(
        alreadyInstalled = true,
        installedFromAssetByThisProcess = key,
        installKey = key,
      ),
    )
  }

  @Test
  fun doesNotReuseAPackageThisProcessNeverInstalled() {
    // The state a fresh instrumentation process is in when the detector is left over from an
    // earlier run: the package is present, but nothing readable here says it came from THIS test
    // APK, so it could be an older build. Reinstall — once per run, not once per trail.
    assertFalse(
      InProcessIdleAttacher.shouldReuseInstalledDetector(
        alreadyInstalled = true,
        installedFromAssetByThisProcess = null,
        installKey = key,
      ),
    )
  }

  @Test
  fun doesNotReuseWhenThePackageWasRemovedUnderneathUs() {
    // The record is the only thing that would say "already installed", and it outlives the package
    // it names. Without the installed check, an uninstall between trails would make the attach
    // skip the install and then fail at `am instrument` with nothing to run.
    assertFalse(
      InProcessIdleAttacher.shouldReuseInstalledDetector(
        alreadyInstalled = false,
        installedFromAssetByThisProcess = key,
        installKey = key,
      ),
    )
  }

  @Test
  fun doesNotReuseADetectorStagedForADifferentApp() {
    val otherKey = InProcessIdleAttacher.detectorInstallKey(
      "xyz.block.trailblaze.inprocessidle.other",
      "inprocess-idle-apks/trailblaze-inprocess-idle-other.apk",
    )
    assertNotEquals(key, otherKey)
    assertFalse(
      InProcessIdleAttacher.shouldReuseInstalledDetector(
        alreadyInstalled = true,
        installedFromAssetByThisProcess = otherKey,
        installKey = key,
      ),
    )
  }

  @Test
  fun theKeyNamesBothThePackageAndTheAsset() {
    // Either half alone is wrong. The package alone would call a detector built for a different
    // target app a match; the asset alone would ignore which package is actually on the device.
    assertTrue(key.contains(pkg))
    assertTrue(key.contains(asset))
  }

  @Test
  fun anInstallThatReportedSuccessIsRecorded() {
    assertEquals(
      key,
      InProcessIdleAttacher.recordAfterInstallAttempt(
        previousRecord = null,
        installKey = key,
        installStatus = PackageInstaller.STATUS_SUCCESS,
      ),
    )
  }

  @Test
  fun aFailedInstallIsNotRecorded() {
    // The case that matters: recording a failed install would make the NEXT attach skip an install
    // the device needs, leaving `am instrument` with nothing to run.
    assertNull(
      InProcessIdleAttacher.recordAfterInstallAttempt(
        previousRecord = null,
        installKey = key,
        installStatus = PackageInstaller.STATUS_FAILURE,
      ),
    )
  }

  @Test
  fun anInstallWithNoStatusAtAllIsNotRecorded() {
    // A commit that timed out, or anything that threw before the installer's broadcast arrived.
    // Absence of a verdict is not a success.
    assertNull(
      InProcessIdleAttacher.recordAfterInstallAttempt(
        previousRecord = null,
        installKey = key,
        installStatus = null,
      ),
    )
  }

  @Test
  fun aFailedInstallLeavesAnEarlierRecordUntouched() {
    // Not the same as "records nothing": clobbering the earlier record with null would throw away a
    // reuse that is still valid, turning one failed install into a reinstall on every later trail.
    val earlier = InProcessIdleAttacher.detectorInstallKey(
      "xyz.block.trailblaze.inprocessidle.other",
      "inprocess-idle-apks/trailblaze-inprocess-idle-other.apk",
    )
    assertEquals(
      earlier,
      InProcessIdleAttacher.recordAfterInstallAttempt(
        previousRecord = earlier,
        installKey = key,
        installStatus = PackageInstaller.STATUS_FAILURE,
      ),
    )
  }

  @Test
  fun samePackageAndAssetAlwaysProduceTheSameKey() {
    // Load-bearing for not clearing the record on uninstall: a detector package name and its asset
    // path both derive from the target applicationId, so a key mismatch always means a DIFFERENT
    // package — one the uninstall did not touch — and can never wrongly match after it.
    assertEquals(key, InProcessIdleAttacher.detectorInstallKey(pkg, asset))
  }
}
