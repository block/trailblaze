import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [shouldReinstallSdkNodeModules] — the pure skip-vs-reinstall decision behind
 * `ensureSdkNodeModules`. Exercised with on-disk fixtures only (no bun, no Gradle), so the
 * lockfile-fingerprint contract is guarded directly: the load-bearing property is that a `bun.lock`
 * change forces a reinstall (rather than bundling against stale deps from a warm `node_modules`).
 */
class ShouldReinstallSdkNodeModulesTest {

  private val sdkDir: File = Files.createTempDirectory("sdk-reinstall-test").toFile()

  @AfterTest
  fun cleanup() {
    sdkDir.deleteRecursively()
  }

  private fun writeBinaries(dts: Boolean = true, esbuild: Boolean = true) {
    File(sdkDir, "node_modules/.bin").mkdirs()
    if (dts) File(sdkDir, "node_modules/.bin/dts-bundle-generator").writeText("#!/usr/bin/env node\n")
    if (esbuild) File(sdkDir, "node_modules/.bin/esbuild").writeText("native-binary\n")
  }

  private fun writeLock(text: String) = File(sdkDir, "bun.lock").writeText(text)

  private fun writeStamp(text: String) {
    File(sdkDir, "node_modules").mkdirs()
    File(sdkDir, "node_modules/.trailblaze-install-lock").writeText(text)
  }

  @Test
  fun `reinstall when node_modules is absent`() {
    writeLock("lock-v1")
    assertTrue(shouldReinstallSdkNodeModules(sdkDir))
  }

  @Test
  fun `skip when both binaries present and stamp matches the current lock`() {
    writeLock("lock-v1")
    writeBinaries()
    writeStamp("lock-v1")
    assertFalse(shouldReinstallSdkNodeModules(sdkDir))
  }

  @Test
  fun `reinstall when the lockfile changed since the recorded stamp`() {
    // The load-bearing case: a warm node_modules whose bun.lock has since changed.
    writeLock("lock-v2")
    writeBinaries()
    writeStamp("lock-v1")
    assertTrue(shouldReinstallSdkNodeModules(sdkDir))
  }

  @Test
  fun `reinstall when binaries present but no stamp recorded`() {
    writeLock("lock-v1")
    writeBinaries()
    assertTrue(shouldReinstallSdkNodeModules(sdkDir))
  }

  @Test
  fun `reinstall when a required binary is missing even though the stamp matches`() {
    writeLock("lock-v1")
    writeStamp("lock-v1")
    writeBinaries(dts = false, esbuild = true)
    assertTrue(shouldReinstallSdkNodeModules(sdkDir))
  }

  @Test
  fun `skip when bun_lock is absent and the stamp records the empty fingerprint`() {
    // No bun.lock => fingerprint is "" ; a prior install that recorded "" is still a match.
    writeBinaries()
    writeStamp("")
    assertFalse(shouldReinstallSdkNodeModules(sdkDir))
  }
}
