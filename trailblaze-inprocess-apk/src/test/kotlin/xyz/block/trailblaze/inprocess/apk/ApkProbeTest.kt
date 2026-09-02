package xyz.block.trailblaze.inprocess.apk

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The probe end to end, over APKs this test writes.
 *
 * Written rather than committed because the interesting cases are apps that do not exist: an app one
 * Compose release below the shell's floor, an app that packages a version file, an app whose only
 * era evidence is a dex marker. Each is a few hundred bytes here and would be a checked-in binary
 * otherwise.
 *
 * The certificate reader is injected. Signing a fixture would mean generating a key per test run to
 * assert something no case here is about — `RealApkProbeTest` reads a real signature off a real APK.
 */
class ApkProbeTest {

  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun `an app a Compose release below the shell's floor names the floor failure`() {
    val app = apk(
      "app-old-compose.apk",
      versionFiles = mapOf(COMPOSE_UI_VERSION_FILE to "1.9.0"),
      definedClasses = listOf(COMPOSE_PRESENCE_CLASS),
    )
    // The shell packages ui-test-junit4 1.11.4, and that IS the statement about the app's Compose:
    // its code runs against whichever copy of `androidx.compose.ui:ui` the app ships.
    val shell = apk(
      "shell.apk",
      versionFiles = mapOf(COMPOSE_UI_TEST_VERSION_FILE to "1.11.4"),
      definedClasses = listOf("androidx.compose.ui.test.junit4.AndroidComposeTestRule"),
    )

    val fingerprint = probe().probe(app, shell)

    val reason = fingerprint.verdict.reasons.single { it.code == ProbeReasonCode.ERA_BELOW_SHELL_FLOOR }
    assertEquals(ProbeStatus.NO_GO, fingerprint.verdict.status)
    assertTrue(reason.message.contains("androidx.compose.ui:ui"), reason.message)
    assertTrue(reason.message.contains("1.9.0"), reason.message)
    assertTrue(reason.message.contains("1.11.4"), reason.message)
  }

  @Test
  fun `the same app clears the same shell once its era reaches the floor`() {
    // Same pair, one field different. Without this the test above would pass for an app that fails
    // the floor check for any reason at all, including a bug that fails every app.
    val app = apk(
      "app-current-compose.apk",
      versionFiles = mapOf(COMPOSE_UI_VERSION_FILE to "1.11.4"),
      definedClasses = listOf(COMPOSE_PRESENCE_CLASS),
    )
    val shell = apk(
      "shell.apk",
      versionFiles = mapOf(COMPOSE_UI_TEST_VERSION_FILE to "1.11.4"),
      definedClasses = listOf("androidx.compose.ui.test.junit4.AndroidComposeTestRule"),
    )

    val fingerprint = probe().probe(app, shell)

    assertEquals(ProbeStatus.GO, fingerprint.verdict.status)
    assertEquals(emptyList(), fingerprint.verdict.reasons)
  }

  @Test
  fun `a packaged version file wins over a dex marker`() {
    // The app defines a marker that says "1.11.0 or later" AND packages a version file saying
    // 1.9.0. The file is ground truth; the marker is an inference.
    val app = apk(
      "app.apk",
      versionFiles = mapOf(COMPOSE_UI_VERSION_FILE to "1.9.0"),
      definedClasses = listOf(COMPOSE_PRESENCE_CLASS, "androidx.compose.ui.platform.ComposeViewContext"),
    )
    val era = probe().probe(app, shellApk = null).eras.single { it.library == COMPOSE_UI }
    assertEquals("1.9.0", era.version)
    assertEquals(EraSource.PACKAGED_VERSION_FILE, era.source)
  }

  @Test
  fun `a declared version is used when the APK packages no version file, and its evidence says so`() {
    val app = apk("app.apk", definedClasses = listOf(COMPOSE_PRESENCE_CLASS))
    val era = probe().probe(
      app,
      shellApk = null,
      declared = DeclaredDependencies(listOf(DeclaredLibraryVersion(COMPOSE_UI, "1.11.4"))),
    ).eras.single { it.library == COMPOSE_UI }
    assertEquals("1.11.4", era.version)
    assertEquals(EraSource.DECLARED, era.source)
  }

  @Test
  fun `a declared version the dex contradicts is reported with the contradiction`() {
    // The caller says 1.11.4; the dex says the class 1.11.0 introduced is absent. Taking the
    // caller's word silently would let a wrong declaration wave an app through the floor check.
    val app = apk("app.apk", definedClasses = listOf(COMPOSE_PRESENCE_CLASS))
    val era = probe().probe(
      app,
      shellApk = null,
      declared = DeclaredDependencies(listOf(DeclaredLibraryVersion(COMPOSE_UI, "1.11.4"))),
    ).eras.single { it.library == COMPOSE_UI }
    assertTrue(era.evidence.contains("the dex says otherwise"), era.evidence)
  }

  @Test
  fun `a declared version consistent with the dex is not reported as contradicted`() {
    // Negative control for the case above: without it, a `contradiction()` that returned something
    // for every input would pass the whole suite and every declared version would read as suspect.
    val app = apk(
      "app.apk",
      definedClasses = listOf(COMPOSE_PRESENCE_CLASS, COMPOSE_1_11_MARKER, COMPOSE_1_10_MARKER),
    )
    val era = probe().probe(
      app,
      shellApk = null,
      declared = DeclaredDependencies(listOf(DeclaredLibraryVersion(COMPOSE_UI, "1.11.4"))),
    ).eras.single { it.library == COMPOSE_UI }
    assertTrue(!era.evidence.contains("the dex says otherwise"), era.evidence)
  }

  @Test
  fun `a packaged version file is checked against the floor even when the presence class is gone`() {
    // R8 renames and strips library classes, so "the presence class is absent" does not mean the
    // library is. Reading it as absence skipped the floor check entirely and cleared an app whose
    // below-floor version was written in the APK in plain text.
    val app = apk(
      "app-minified.apk",
      versionFiles = mapOf(COMPOSE_UI_VERSION_FILE to "1.9.0"),
      definedClasses = emptyList(),
    )
    val shell = apk("shell.apk", versionFiles = mapOf(COMPOSE_UI_TEST_VERSION_FILE to "1.11.4"))

    val fingerprint = probe().probe(app, shell)

    val reason = fingerprint.verdict.reasons.single { it.code == ProbeReasonCode.ERA_BELOW_SHELL_FLOOR }
    assertTrue(reason.message.contains("1.9.0"), reason.message)
  }

  @Test
  fun `a pre-release of the floor version does not satisfy the floor`() {
    // `1.11.4-alpha01` is not `1.11.4`: it is missing whatever the release added, which is exactly
    // what the shell's code links against. Comparing on the numbers alone cleared it.
    val app = apk(
      "app-alpha.apk",
      versionFiles = mapOf(COMPOSE_UI_VERSION_FILE to "1.11.4-alpha01"),
      definedClasses = listOf(COMPOSE_PRESENCE_CLASS),
    )
    val shell = apk("shell.apk", versionFiles = mapOf(COMPOSE_UI_TEST_VERSION_FILE to "1.11.4"))

    val fingerprint = probe().probe(app, shell)

    assertTrue(
      fingerprint.verdict.reasons.any { it.code == ProbeReasonCode.ERA_BELOW_SHELL_FLOOR },
      fingerprint.verdict.reasons.toString(),
    )
  }

  @Test
  fun `a manifest whose header lies is refused as an unreadable input, not as a verdict`() {
    // The parser checks the chunks it walks, but the file header's own size field is used before
    // any of that: `position(fileHeaderSize)` on a header claiming 0xFFFF bytes throws
    // IllegalArgumentException. Unwrapped, the farm's pre-flight prints a stack trace and exits as
    // though the app were unfit rather than as an input it could not read.
    val manifest = checkNotNull(javaClass.getResourceAsStream(MANIFEST_FIXTURE)).use { it.readBytes() }
    manifest[2] = 0xFF.toByte()
    manifest[3] = 0xFF.toByte()
    val file = File(temporaryFolder.newFolder(), "lying-header.apk")
    ZipOutputStream(file.outputStream().buffered()).use { zip ->
      zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
      zip.write(manifest)
      zip.closeEntry()
    }

    val error = kotlin.runCatching { probe().probe(file, shellApk = null) }.exceptionOrNull()

    assertTrue(error is ApkReadException, "expected ApkReadException, got $error")
    assertTrue(error.message!!.contains("lying-header.apk"), error.message!!)
  }

  @Test
  fun `the fingerprint records the mandatory signing evidence and the launcher`() {
    val app = apk("app.apk", definedClasses = listOf(COMPOSE_PRESENCE_CLASS))
    val fingerprint = probe().probe(app, shellApk = null)
    assertEquals("xyz.block.trailblaze.examples.sampleapp", fingerprint.targetPackage)
    assertEquals(STUB_CERT_DIGEST, fingerprint.certSha256)
    assertTrue(fingerprint.debuggable)
    assertEquals(
      "xyz.block.trailblaze.examples.sampleapp.SampleAppActivity",
      fingerprint.launcherActivity,
    )
    assertTrue(fingerprint.providers.single().androidxStartup)
  }

  @Test
  fun `the dex overlap names the shell and counts only classes both define`() {
    val app = apk("app.apk", definedClasses = listOf("com.example.Shared", "com.example.AppOnly"))
    val shell = apk("shell.apk", definedClasses = listOf("com.example.Shared", "com.example.ShellOnly"))
    val overlap = checkNotNull(probe().probe(app, shell).dexOverlap)
    assertEquals("shell.apk", overlap.shell)
    assertEquals(1, overlap.classCount)
    assertEquals(listOf("com.example.Shared"), overlap.classes)
  }

  @Test
  fun `an unreadable APK is refused by name`() {
    val notAnApk = temporaryFolder.newFile("not-an-apk.apk").apply { writeText("nope") }
    val error = kotlin.runCatching { probe().probe(notAnApk, shellApk = null) }.exceptionOrNull()
    assertTrue(error is ApkReadException, "expected ApkReadException, got $error")
    assertTrue(error.message!!.contains("not-an-apk.apk"), error.message!!)
  }

  private fun probe() = ApkProbe(
    // A floor stated by the caller rather than the built-in one: this test is about the probe's
    // mechanics, and pinning it to the shipped default would turn every future floor change into a
    // failure here.
    declaredFloor = ShellFloor(),
    certificateDigest = { STUB_CERT_DIGEST },
  )

  /**
   * Writes an APK: the sample app's real binary manifest, one synthetic dex defining
   * [definedClasses], and a `META-INF` version file per entry of [versionFiles].
   */
  private fun apk(
    name: String,
    versionFiles: Map<String, String> = emptyMap(),
    definedClasses: List<String> = emptyList(),
  ): File {
    val file = File(temporaryFolder.newFolder(), name)
    val manifest = checkNotNull(javaClass.getResourceAsStream(MANIFEST_FIXTURE)) {
      "missing test fixture $MANIFEST_FIXTURE"
    }.use { it.readBytes() }

    ZipOutputStream(file.outputStream().buffered()).use { zip ->
      zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
      zip.write(manifest)
      zip.closeEntry()

      val dex = TestDexBuilder().apply { definedClasses.forEach { define(it) } }.build()
      zip.putNextEntry(ZipEntry("classes.dex"))
      zip.write(dex)
      zip.closeEntry()

      versionFiles.forEach { (entry, version) ->
        zip.putNextEntry(ZipEntry(entry))
        zip.write("$version\n".toByteArray())
        zip.closeEntry()
      }
    }
    return file
  }

  private companion object {
    const val MANIFEST_FIXTURE = "/fixtures/sample-app-AndroidManifest.bin"
    const val COMPOSE_UI = "androidx.compose.ui:ui"
    const val COMPOSE_PRESENCE_CLASS = "androidx.compose.ui.platform.AndroidComposeView"
    const val COMPOSE_1_11_MARKER = "androidx.compose.ui.platform.ComposeViewContext"
    const val COMPOSE_1_10_MARKER = "androidx.compose.ui.autofill.FillableData"
    const val COMPOSE_UI_VERSION_FILE = "META-INF/androidx.compose.ui_ui.version"
    const val COMPOSE_UI_TEST_VERSION_FILE = "META-INF/androidx.compose.ui_ui-test-junit4.version"
    const val STUB_CERT_DIGEST = "cafebabe"
  }
}
