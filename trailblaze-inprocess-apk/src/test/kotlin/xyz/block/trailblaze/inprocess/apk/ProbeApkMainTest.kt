package xyz.block.trailblaze.inprocess.apk

import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The lean entry point's command line — the one the farm's pre-flight calls. */
class ProbeApkMainTest {

  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun `every flag reaches the field it names`() {
    val options = ProbeApkMain.parse(
      arrayOf(
        "app.apk",
        "--shell", "shell.apk",
        "--declared-deps", "deps.yaml",
        "--shell-floor", "floor.yaml",
        "--out", "fingerprint.yaml",
        "--fail-on", "NO_LAUNCHER_ACTIVITY,DEX_OVERLAP_UNCHECKED",
      ),
    )
    assertEquals(File("app.apk"), options.appApk)
    assertEquals(File("shell.apk"), options.shellApk)
    assertEquals(File("deps.yaml"), options.declaredDepsFile)
    assertEquals(File("floor.yaml"), options.shellFloorFile)
    assertEquals(File("fingerprint.yaml"), options.outFile)
    assertEquals(
      setOf(ProbeReasonCode.NO_LAUNCHER_ACTIVITY, ProbeReasonCode.DEX_OVERLAP_UNCHECKED),
      options.failOn,
    )
  }

  @Test
  fun `no fail-on means every disqualifier is fatal`() {
    // Null, not an empty set: an empty set would mean "fail on nothing", which is the opposite.
    assertNull(ProbeApkMain.parse(arrayOf("app.apk")).failOn)
  }

  @Test
  fun `an unknown fail-on code is refused rather than dropped`() {
    // A typo that silently removed a disqualifier from a gate's enforcing set would read as a
    // passing build for a pair nothing checked.
    val error = assertFailsWith<ApkReadException> {
      ProbeApkMain.parse(arrayOf("app.apk", "--fail-on", "NO_LAUNCHER_ACTIVITY,TYPOED_CODE"))
    }
    assertTrue(error.message!!.contains("TYPOED_CODE"), error.message!!)
    // The message lists what it does know, so the fix does not need the source.
    assertTrue(error.message!!.contains("DEX_OVERLAP_WITH_SHELL"), error.message!!)
  }

  @Test
  fun `an empty fail-on is refused`() {
    assertFailsWith<ApkReadException> { ProbeApkMain.parse(arrayOf("app.apk", "--fail-on", " ")) }
  }

  @Test
  fun `a missing app APK argument prints the usage`() {
    val error = assertFailsWith<ApkReadException> { ProbeApkMain.parse(emptyArray()) }
    assertTrue(error.message!!.contains("--shell"), error.message!!)
  }

  @Test
  fun `an unknown option is refused`() {
    assertFailsWith<ApkReadException> { ProbeApkMain.parse(arrayOf("app.apk", "--wat")) }
  }

  @Test
  fun `a second positional argument is refused`() {
    // Two APKs positionally is the shape of someone meaning `--shell`, and silently ignoring the
    // second would probe without the shell and cap the verdict at INCOMPLETE for no visible reason.
    assertFailsWith<ApkReadException> { ProbeApkMain.parse(arrayOf("app.apk", "shell.apk")) }
  }

  @Test
  fun `a flag with no value after it is refused`() {
    assertFailsWith<ApkReadException> { ProbeApkMain.parse(arrayOf("app.apk", "--shell")) }
  }

  @Test
  fun `a non-GO verdict exits non-zero and a narrowed fail-on can clear it`() {
    val fingerprint = fingerprintWithReasons(
      ProbeReasonCode.ERA_BELOW_SHELL_FLOOR,
      ProbeReasonCode.DEX_OVERLAP_WITH_SHELL,
    )
    assertEquals(1, ProbeApkOutcome(fingerprint, "", failOn = null).exitCode)
    assertEquals(
      1,
      ProbeApkOutcome(fingerprint, "", failOn = setOf(ProbeReasonCode.DEX_OVERLAP_WITH_SHELL)).exitCode,
    )
    // Narrowed past both reasons that fired: the verdict still says NO_GO, and the caller has said
    // it will not gate on either.
    assertEquals(
      0,
      ProbeApkOutcome(fingerprint, "", failOn = setOf(ProbeReasonCode.NO_LAUNCHER_ACTIVITY)).exitCode,
    )
  }

  @Test
  fun `the rendered verdict prints a reason the caller chose not to enforce, and marks it`() {
    val fingerprint = fingerprintWithReasons(ProbeReasonCode.ERA_BELOW_SHELL_FLOOR)
    val rendered = ProbeApkRunner.renderVerdict(
      fingerprint,
      failOn = setOf(ProbeReasonCode.NO_LAUNCHER_ACTIVITY),
    )
    // Load-bearing: a report-only disqualifier that vanished from the log would make the gate look
    // like it found nothing.
    assertTrue(rendered.contains("ERA_BELOW_SHELL_FLOOR"), rendered)
    assertTrue(rendered.contains("not enforced"), rendered)
  }

  @Test
  fun `an app APK that does not exist is refused by path`() {
    val error = assertFailsWith<ApkReadException> {
      ProbeApkRunner.run(ProbeApkOptions(appApk = File("/nonexistent/app.apk")))
    }
    assertTrue(error.message!!.contains("/nonexistent/app.apk"), error.message!!)
  }

  @Test
  fun `an app APK that exists but cannot be read says so, rather than failing inside a reader`() {
    // Existence is not readability, and the two have different fixes: one is a wrong path, the other
    // is a permission on a CI agent. Without this check the run reaches the zip reader and dies as an
    // IOException with no path in it and the wrong exit code.
    val unreadable = File.createTempFile("probe-unreadable", ".apk")
    unreadable.deleteOnExit()
    assumeTrue("this check is meaningless as a user that can read anything", unreadable.setReadable(false, false))
    assumeTrue("running as a user that ignores the permission", !unreadable.canRead())

    val error = assertFailsWith<ApkReadException> {
      ProbeApkRunner.run(ProbeApkOptions(appApk = unreadable))
    }
    assertTrue(error.message!!.contains(unreadable.path), error.message!!)
    assertTrue(error.message!!.contains("permissions"), error.message!!)
  }

  @Test
  fun `the fingerprint --out names is written, decodes, and is what the run returned`() {
    // The farm's pre-flight points every failure message at this path and uploads the file as a
    // build artifact, so a `--out` that silently wrote nothing would leave a red build's only
    // explanation pointing at a file that does not exist. The nested directory covers the same
    // path's `mkdirs`.
    val out = File(temporaryFolder.newFolder(), "nested/dir/fingerprint.yaml")
    val outcome = run(ProbeApkOptions(appApk = apk("app.apk"), outFile = out))

    assertTrue(out.isFile, "expected a fingerprint at ${out.path}")
    assertEquals(outcome.fingerprint, AppFingerprintCodec.decode(out.readText(), out.path))
  }

  @Test
  fun `a shell-floor file replaces the built-in floor rather than adding to it`() {
    // An app on Compose Runtime 1.10.0, which the SHIPPED floor refuses. Both directions are
    // asserted from the same APK: a floor file that is read must be able to fail it, and an empty
    // one must clear it. Only the pair proves the file replaced the default — a `--shell-floor` that
    // was parsed and then discarded would still fail this app on the built-in entry.
    val app = apk("app.apk", versionFiles = mapOf(COMPOSE_RUNTIME_VERSION_FILE to "1.10.0"))

    val demanding = floorFile("demanding.yaml", COMPOSE_RUNTIME, "1.99.0")
    val refused = run(ProbeApkOptions(appApk = app, shellFloorFile = demanding))
    assertTrue(
      refused.fingerprint.verdict.reasons.any { it.code == ProbeReasonCode.ERA_BELOW_SHELL_FLOOR },
      refused.fingerprint.verdict.reasons.toString(),
    )

    val empty = temporaryFolder.newFile("empty-floor.yaml").apply { writeText("libraries: []\n") }
    val cleared = run(ProbeApkOptions(appApk = app, shellFloorFile = empty))
    assertTrue(
      cleared.fingerprint.verdict.reasons.none { it.code == ProbeReasonCode.ERA_BELOW_SHELL_FLOOR },
      cleared.fingerprint.verdict.reasons.toString(),
    )
  }

  @Test
  fun `a declared-deps file reaches the probe`() {
    // A team's declaration is the only era evidence for an APK that packages no version file, so a
    // `--declared-deps` this never passed along would report ERA_UNDETERMINABLE for an app whose
    // version was stated on the command line.
    val declared = temporaryFolder.newFile("deps.yaml").apply {
      writeText("libraries:\n  - library: \"$COMPOSE_RUNTIME\"\n    version: \"1.11.4\"\n")
    }
    val outcome = run(
      ProbeApkOptions(appApk = apk("app.apk"), declaredDepsFile = declared),
    )
    val era = outcome.fingerprint.eras.single { it.library == COMPOSE_RUNTIME }
    assertEquals("1.11.4", era.version)
    assertEquals(EraSource.DECLARED, era.source)
  }

  @Test
  fun `an unsigned APK is refused by the real certificate reader, with the reason in the message`() {
    // The one path the certificate seam above skips. An unsigned APK cannot host an instrumentation
    // at all, and the message has to say that rather than surface an apksig verification dump —
    // this is the first thing a team adopting the driver hits when they probe a build output.
    val error = assertFailsWith<ApkReadException> {
      ProbeApkRunner.run(ProbeApkOptions(appApk = apk("unsigned.apk")))
    }
    assertTrue(error.message!!.contains("unsigned.apk"), error.message!!)
    assertTrue(error.message!!.contains("no verifiable APK signature"), error.message!!)
  }

  /** [ProbeApkRunner.run] with the signature read stubbed — see that overload's KDoc. */
  private fun run(options: ProbeApkOptions) = ProbeApkRunner.run(options) { STUB_CERT_DIGEST }

  private fun floorFile(name: String, library: String, minVersion: String): File =
    temporaryFolder.newFile(name).apply {
      writeText(
        "libraries:\n  - library: \"$library\"\n    minVersion: \"$minVersion\"\n" +
          "    why: \"stated by this test\"\n",
      )
    }

  /** An APK the probe can read: the sample app's real binary manifest, one dex, and [versionFiles]. */
  private fun apk(name: String, versionFiles: Map<String, String> = emptyMap()): File {
    val file = File(temporaryFolder.newFolder(), name)
    val manifest = checkNotNull(javaClass.getResourceAsStream(MANIFEST_FIXTURE)) {
      "missing test fixture $MANIFEST_FIXTURE"
    }.use { it.readBytes() }

    ZipOutputStream(file.outputStream().buffered()).use { zip ->
      zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
      zip.write(manifest)
      zip.closeEntry()
      zip.putNextEntry(ZipEntry("classes.dex"))
      zip.write(TestDexBuilder().define("com.example.Defined").build())
      zip.closeEntry()
      versionFiles.forEach { (entry, version) ->
        zip.putNextEntry(ZipEntry(entry))
        zip.write("$version\n".toByteArray())
        zip.closeEntry()
      }
    }
    return file
  }

  private fun fingerprintWithReasons(vararg codes: ProbeReasonCode) = AppFingerprint(
    targetPackage = "com.example",
    launcherActivity = "com.example.MainActivity",
    debuggable = true,
    certSha256 = "cafebabe",
    verdict = ProbeVerdict(
      status = ProbeStatus.NO_GO,
      reasons = codes.map { ProbeReason(it, "because") },
    ),
  )

  private companion object {
    const val MANIFEST_FIXTURE = "/fixtures/sample-app-AndroidManifest.bin"
    const val COMPOSE_RUNTIME = "androidx.compose.runtime:runtime"
    const val COMPOSE_RUNTIME_VERSION_FILE = "META-INF/androidx.compose.runtime_runtime.version"
    const val STUB_CERT_DIGEST = "cafebabe"
  }
}
