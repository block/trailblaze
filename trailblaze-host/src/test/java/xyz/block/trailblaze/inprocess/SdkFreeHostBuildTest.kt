package xyz.block.trailblaze.inprocess

import assertk.assertThat
import assertk.assertions.contains
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The SDK-free claim, tested as a property of the ENVIRONMENT rather than of the code.
 *
 * `make-test-apk` exists so a team's first in-process run does not require adopting a Gradle module,
 * which means it has to work on a host that has neither the Android SDK nor Gradle — a CI signing
 * step, a key-custody workstation. Asserting that from inside a Gradle test worker proves nothing:
 * the worker inherits `ANDROID_HOME` and Gradle's own variables. So this forks a JVM with the
 * environment cleared and lets the fork assert its own emptiness before doing the work.
 *
 * `PATH` is set to the system directories only — no `adb`, no `aapt2`, no `gradle`, and no
 * `node_modules/.bin`, so nothing can quietly shell out to a tool the claim says it does not need.
 */
class SdkFreeHostBuildTest {

  @get:Rule val temp = TemporaryFolder()

  @Test
  fun `builds a signed test APK on a host with no ANDROID_HOME and no Gradle`() {
    val keystore = InProcessTestApks.keystore(temp.root, "app.p12")
    val shell = InProcessTestApks.shellApk(temp.root)
    val appApk = InProcessTestApks.signedAppApk(temp.root, keystore)
    val out = File(temp.root, "out/test.apk")

    val java = File(File(System.getProperty("java.home"), "bin"), "java")
    val builder = ProcessBuilder(
      java.absolutePath,
      "-cp", System.getProperty("java.class.path"),
      SdkFreeMakeTestApkMain::class.java.name,
      shell.absolutePath,
      appApk.absolutePath,
      keystore.absolutePath,
      InProcessTestApks.APP_PACKAGE,
      out.absolutePath,
    ).redirectErrorStream(true)
    builder.environment().clear()
    builder.environment()["PATH"] = "/usr/bin:/bin:/usr/sbin:/sbin"
    // A HOME the fork can write to, so nothing falls back to the real user's app-data directory.
    builder.environment()["HOME"] = temp.newFolder("home").absolutePath

    val process = builder.start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor(5, TimeUnit.MINUTES)) { "forked build did not finish: $output" }
    check(process.exitValue() == 0) { "forked build failed (exit ${process.exitValue()}):\n$output" }

    assertThat(output).contains("STAMPED=${InProcessTestApks.APP_PACKAGE}")
    assertThat(output).contains(
      "SIGNED=" + ApkSigning.keystoreCertificateSha256Digest(
        keystore,
        InProcessTestApks.PASSWORD.toCharArray(),
        "test",
      ),
    )
    assertThat(output).contains("RECORD=test.build-record.yaml")
  }
}
