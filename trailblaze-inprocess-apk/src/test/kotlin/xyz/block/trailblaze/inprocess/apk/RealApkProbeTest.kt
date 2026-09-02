package xyz.block.trailblaze.inprocess.apk

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The probe against real APKs, which the rest of the suite deliberately does not use.
 *
 * Everything else here reads bytes this repository wrote, so the one thing it cannot check is
 * whether the readers survive contact with a real AGP-built APK — a signing block, dozens of dex
 * files, a manifest with attributes no fixture has.
 *
 * The inputs are build outputs, not committed fixtures, so they arrive by system property — an app
 * APK, and the in-process test APK built to attach to it:
 *
 * ```
 * ./gradlew :trailblaze-inprocess-apk:test --tests "*RealApkProbeTest*" \
 *   -Dtrailblaze.probe.appApk=/abs/path/to/app-debug.apk \
 *   -Dtrailblaze.probe.shellApk=/abs/path/to/app-inprocess-debug.apk
 * ```
 *
 * The sample app and its in-process test APK are the pair this was written against; any real app and
 * the test APK built for it works.
 *
 * A property that is set but names a missing file is a hard failure, not a skip. A gated test that
 * silently passes when its input is absent is how a suite reports green for a check that never ran.
 */
class RealApkProbeTest {

  @Test
  fun `the sample app paired with its in-process shell is GO`() {
    val app = apkProperty("trailblaze.probe.appApk") ?: return assumeAbsent()
    val shell = apkProperty("trailblaze.probe.shellApk") ?: return assumeAbsent()

    val fingerprint = ApkProbe().probe(app, shell)

    assertEquals(
      ProbeStatus.GO,
      fingerprint.verdict.status,
      "expected GO, got ${ProbeApkRunner.renderVerdict(fingerprint)}",
    )
    // A real signing block, read by apksig rather than stubbed — the field `make-test-apk`'s guards
    // are built on, and the only one nothing else in this suite exercises.
    assertTrue(
      fingerprint.certSha256.matches(Regex("[0-9a-f]{64}")),
      "expected a SHA-256 hex digest, got '${fingerprint.certSha256}'",
    )
    // The shell is built with the app's own libraries deduped out, so the intersection is the empty
    // set. Asserting the count rather than "no overlap reason" keeps this honest if the verdict
    // rules change.
    assertEquals(0, fingerprint.dexOverlap?.classCount)
  }

  @Test
  fun `an app probed without a shell is INCOMPLETE, never GO`() {
    val app = apkProperty("trailblaze.probe.appApk") ?: return assumeAbsent()
    val fingerprint = ApkProbe().probe(app, shellApk = null)
    assertEquals(ProbeStatus.INCOMPLETE, fingerprint.verdict.status)
  }

  private fun apkProperty(key: String): File? {
    val path = System.getProperty(key)?.takeIf { it.isNotBlank() } ?: return null
    val file = File(path)
    check(file.isFile) { "-D$key points at $path, which is not a file. Build it, or drop the property." }
    return file
  }

  private fun assumeAbsent() = assumeTrue(
    "set -Dtrailblaze.probe.appApk and -Dtrailblaze.probe.shellApk to run the real-APK probe; " +
      "see this class's KDoc for the command",
    false,
  )
}
