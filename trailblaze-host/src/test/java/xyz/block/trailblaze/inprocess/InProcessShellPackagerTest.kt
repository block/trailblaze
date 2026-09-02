package xyz.block.trailblaze.inprocess

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.TrailblazeConfigYaml
import java.io.File
import kotlin.test.assertFailsWith

/**
 * `make-test-apk`'s guards and its content assembly, end to end on the JVM — no device, no SDK.
 *
 * Every guard gets a test that proves it REFUSES, because the whole point of them is that the
 * command produces nothing when the inputs are wrong. A guard with only a happy-path test is a guard
 * that could be deleted without a failure.
 */
class InProcessShellPackagerTest {

  @get:Rule val temp = TemporaryFolder()

  private lateinit var appKeystore: File
  private lateinit var otherKeystore: File
  private lateinit var shell: File
  private lateinit var appApk: File

  @Before
  fun setUp() {
    appKeystore = InProcessTestApks.keystore(temp.root, "app.p12")
    otherKeystore = InProcessTestApks.keystore(temp.root, "other.p12")
    shell = InProcessTestApks.shellApk(temp.root)
    appApk = InProcessTestApks.signedAppApk(temp.root, appKeystore)
  }

  @Test
  fun `stamps, injects and signs`() {
    val trail = trailFile("login.trail.yaml")
    val result = InProcessShellPackager.make(request(trails = listOf(trail)))

    val manifest = ApkZipRewriter.readEntry(result.outputApk, "AndroidManifest.xml")!!
    assertThat(AndroidBinaryXml.readInstrumentationTargetPackage(manifest))
      .isEqualTo(InProcessTestApks.APP_PACKAGE)

    val entries = ApkZipRewriter.entryNames(result.outputApk)
    assertThat(entries).contains("assets/trails/InjectedTrailsLongTest/login.trail.yaml")

    // Signed with the key we asked for, and verifiable — which is also what proves the zip rewrite
    // produced a loadable APK rather than merely a zip.
    assertThat(ApkSigning.certificateSha256Digests(result.outputApk))
      .containsExactly(
        ApkSigning.keystoreCertificateSha256Digest(
          appKeystore,
          InProcessTestApks.PASSWORD.toCharArray(),
          "test",
        ),
      )
  }

  @Test
  fun `keeps resources_arsc STORED so the APK still installs on API 30 and up`() {
    val result = InProcessShellPackager.make(request())
    val method = java.util.zip.ZipFile(result.outputApk).use { zip ->
      zip.getEntry("resources.arsc").method
    }
    assertThat(method).isEqualTo(java.util.zip.ZipEntry.STORED)
  }

  @Test
  fun `writes a build record naming the shell and the target`() {
    val result = InProcessShellPackager.make(
      request(trails = listOf(trailFile("a.trail.yaml"), trailFile("b.trail.yaml"))),
    )
    assertThat(result.buildRecordFile.isFile).isTrue()
    with(result.buildRecord) {
      assertThat(shellVersion).isEqualTo("20260831.000000.abc1234")
      assertThat(targetPackage).isEqualTo(InProcessTestApks.APP_PACKAGE)
      assertThat(targetEvidence).isEqualTo("app-apk:app.apk")
      assertThat(injectedTrails.size).isEqualTo(2)
    }
  }

  // --- target evidence -------------------------------------------------------------------------

  @Test
  fun `refuses to sign with no target evidence at all`() {
    val failure = assertFailsWith<IllegalStateException> {
      InProcessShellPackager.make(request(appApk = null))
    }
    assertThat(failure.message!!).contains("No target evidence")
  }

  @Test
  fun `accepts a fingerprint in place of the app APK`() {
    val fingerprint = writeFingerprint(AppFingerprint.ofApk(appApk))
    val result = InProcessShellPackager.make(request(appApk = null, fingerprintFile = fingerprint))
    assertThat(result.buildRecord.targetEvidence).isEqualTo("fingerprint:${fingerprint.name}")
  }

  @Test
  fun `refuses a fingerprint that disagrees with the app APK beside it`() {
    val stale = writeFingerprint(AppFingerprint.ofApk(appApk).copy(debuggable = false))
    val failure = assertFailsWith<IllegalArgumentException> {
      InProcessShellPackager.make(request(fingerprintFile = stale))
    }
    assertThat(failure.message!!).contains("disagrees with --app-apk")
  }

  @Test
  fun `refuses a target package the evidence does not describe`() {
    val failure = assertFailsWith<IllegalArgumentException> {
      InProcessShellPackager.make(request(targetPackage = "com.example.somethingelse"))
    }
    assertThat(failure.message!!).contains("is not the package the target evidence describes")
  }

  // --- signing guards --------------------------------------------------------------------------

  @Test
  fun `refuses a keystore whose certificate is not the app's`() {
    val failure = assertFailsWith<IllegalArgumentException> {
      InProcessShellPackager.make(request(keystore = otherKeystore))
    }
    assertThat(failure.message!!).contains("Signing key mismatch")
  }

  @Test
  fun `refuses a certificate mismatch on the fingerprint path too`() {
    val wrongCert = AppFingerprint.ofApk(appApk).copy(
      certificateSha256 = listOf("0".repeat(64)),
    )
    val failure = assertFailsWith<IllegalArgumentException> {
      InProcessShellPackager.make(request(appApk = null, fingerprintFile = writeFingerprint(wrongCert)))
    }
    assertThat(failure.message!!).contains("Signing key mismatch")
  }

  @Test
  fun `refuses a multi-signer target however well one certificate matches`() {
    // Android compares the WHOLE signer set of both packages, and this command emits one signer,
    // so matching one of several certificates is not enough to attach.
    val real = AppFingerprint.ofApk(appApk).certificateSha256.single()
    val multiSigner = AppFingerprint.ofApk(appApk).copy(
      certificateSha256 = listOf(real, "1".repeat(64)),
    )
    val failure = assertFailsWith<IllegalArgumentException> {
      InProcessShellPackager.make(
        request(appApk = null, fingerprintFile = writeFingerprint(multiSigner)),
      )
    }
    assertThat(failure.message!!).contains("has 2 signing certificates")
  }

  @Test
  fun `accepts the uppercase colon-separated digest keytool and apksigner print`() {
    // The realistic way a fingerprint gets written by hand. Left un-normalized this fails the
    // signing guard against the very key it names, with two digests that read identically.
    val asPrinted = AppFingerprint.ofApk(appApk).certificateSha256.single()
      .chunked(2).joinToString(":").uppercase()
    val fingerprint = writeFingerprint(AppFingerprint.ofApk(appApk).copy(certificateSha256 = listOf(asPrinted)))

    val result = InProcessShellPackager.make(request(appApk = null, fingerprintFile = fingerprint))
    assertThat(result.buildRecord.targetEvidence).isEqualTo("fingerprint:${fingerprint.name}")
  }

  @Test
  fun `refuses a non-debuggable target unless --release says so`() {
    val notDebuggable = writeFingerprint(AppFingerprint.ofApk(appApk).copy(debuggable = false))
    val failure = assertFailsWith<IllegalArgumentException> {
      InProcessShellPackager.make(request(appApk = null, fingerprintFile = notDebuggable))
    }
    assertThat(failure.message!!).contains("is not debuggable")

    val allowed = InProcessShellPackager.make(
      request(appApk = null, fingerprintFile = notDebuggable, release = true),
    )
    assertThat(allowed.buildRecord.releaseMode).isTrue()
  }

  @Test
  fun `refuses to emit an APK carrying a signing key`() {
    val leaky = InProcessTestApks.shellApk(
      temp.newFolder(),
      extra = mapOf("assets/keys/release.keystore" to "pretend key".toByteArray()),
    )
    val failure = assertFailsWith<IllegalArgumentException> {
      InProcessShellPackager.make(request(shell = leaky))
    }
    assertThat(failure.message!!).contains("assets/keys/release.keystore")
    // The refusal has to happen BEFORE signing, or the leak is already on disk.
    assertThat(outputApk().exists()).isFalse()
  }

  @Test
  fun `refuses to emit an APK carrying a PKCS12 key store`() {
    val leaky = InProcessTestApks.shellApk(
      temp.newFolder(),
      extra = mapOf("assets/keys/upload.p12" to "pretend key".toByteArray()),
    )
    val failure = assertFailsWith<IllegalArgumentException> {
      InProcessShellPackager.make(request(shell = leaky))
    }
    assertThat(failure.message!!).contains("assets/keys/upload.p12")
    assertThat(outputApk().exists()).isFalse()
  }

  @Test
  fun `refuses a JKS key store renamed to look like anything else`() {
    val jks = byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFE.toByte(), 0xED.toByte(), 0, 0, 0, 2)
    val leaky = InProcessTestApks.shellApk(
      temp.newFolder(),
      extra = mapOf("assets/blobs/data.bin" to jks),
    )
    val failure = assertFailsWith<IllegalArgumentException> {
      InProcessShellPackager.make(request(shell = leaky))
    }
    assertThat(failure.message!!).contains("assets/blobs/data.bin")
    assertThat(outputApk().exists()).isFalse()
  }

  @Test
  fun `refuses an --out that is one of the inputs, and leaves that input intact`() {
    val before = appApk.readBytes().toList()
    val failure = assertFailsWith<IllegalArgumentException> {
      InProcessShellPackager.make(request(outputApk = appApk))
    }
    assertThat(failure.message!!).contains("would be written over --app-apk")
    // Signing truncates its output before writing anything, so a guard that fired too late would
    // have destroyed the file rather than refused.
    assertThat(appApk.readBytes().toList()).isEqualTo(before)
  }

  // --- target config ---------------------------------------------------------------------------

  @Test
  fun `injects the target config byte for byte, not a re-encode of it`() {
    // A decode-and-re-encode would drop whatever this build of the decoder does not model — a
    // comment here, a field a newer producer writes — and the signature would then cover a config
    // the caller never wrote. The comment is the cheap detectable case of that whole class.
    val config = writeTargetConfig(body = "# hand-written, must survive\nid: sample\ndisplay_name: Sample\n")
    val result = InProcessShellPackager.make(request(targetConfig = config))

    val injected = ApkZipRewriter.readEntry(result.outputApk, "assets/trails/config/targets/sample.yaml")
    assertThat(injected).isNotNull()
    assertThat(injected!!.toList()).isEqualTo(config.readBytes().toList())
    assertThat(result.buildRecord.targetConfigId).isEqualTo("sample")
  }

  @Test
  fun `refuses a target config id the on-device target contract rejects`() {
    val dotted = writeTargetConfig(body = "id: my.app\ndisplay_name: Sample\n")
    val failure = assertFailsWith<IllegalArgumentException> {
      InProcessShellPackager.make(request(targetConfig = dotted))
    }
    assertThat(failure.message!!).contains("is not a usable target id")
    // Signing an id the device will reject just moves the failure to test initialization.
    assertThat(outputApk().exists()).isFalse()
  }

  // --- runtime tool source opt-in --------------------------------------------------------------

  @Test
  fun `bakes the runtime-tool-source opt-in into the injected config, leaving the rest verbatim`() {
    val config = writeTargetConfig(body = "# hand-written, must survive\nid: sample\ndisplay_name: Sample\n")
    val result = InProcessShellPackager.make(
      request(targetConfig = config, allowRuntimeToolSource = true),
    )

    val injected = ApkZipRewriter.readEntry(result.outputApk, "assets/trails/config/targets/sample.yaml")!!
      .decodeToString()
    // Appended, not re-encoded: the comment and the caller's own lines are still there, and the
    // opt-in the device reads is inside the bytes the signature covers.
    assertThat(injected).contains("# hand-written, must survive")
    assertThat(injected).contains("allow_runtime_tool_source: true")
    assertThat(
      TrailblazeConfigYaml.instance
        .decodeFromString(AppTargetYamlConfig.serializer(), injected)
        .allowRuntimeToolSource,
    ).isTrue()
    assertThat(result.buildRecord.allowRuntimeToolSource).isTrue()
  }

  @Test
  fun `leaves the opt-in off by default, and says so in the record`() {
    // The default is the ceremony guarantee: a signing run that never asks for the runtime tool
    // source produces an APK that ignores anything pushed to the device.
    val result = InProcessShellPackager.make(request(targetConfig = writeTargetConfig()))

    val injected = ApkZipRewriter.readEntry(result.outputApk, "assets/trails/config/targets/sample.yaml")!!
      .decodeToString()
    assertThat(injected).doesNotContain("allow_runtime_tool_source")
    assertThat(result.buildRecord.allowRuntimeToolSource).isFalse()
    // "Says so" means the written record carries the line. An absent key would leave an auditor
    // asking whether the record predates the field; `false` answers them.
    assertThat(result.buildRecordFile.readText()).contains("allow_runtime_tool_source: false")
  }

  @Test
  fun `refuses a target config that turns the opt-in on without the flag`() {
    // Otherwise a key-ceremony run enables the runtime tool source by omission: the app team hands
    // over a config, nobody re-reads it, and the signed artifact is a bearer capability.
    val config = writeTargetConfig(
      body = "id: sample\ndisplay_name: Sample\nallow_runtime_tool_source: true\n",
    )
    val failure = assertFailsWith<IllegalArgumentException> {
      InProcessShellPackager.make(request(targetConfig = config))
    }
    assertThat(failure.message!!).contains("--allow-runtime-tool-source")
    assertThat(outputApk().exists()).isFalse()
  }

  @Test
  fun `refuses the flag when the config already writes the key with a different answer`() {
    val config = writeTargetConfig(
      body = "id: sample\ndisplay_name: Sample\nallow_runtime_tool_source: false\n",
    )
    val failure = assertFailsWith<IllegalArgumentException> {
      InProcessShellPackager.make(request(targetConfig = config, allowRuntimeToolSource = true))
    }
    assertThat(failure.message!!).contains("already sets")
  }

  @Test
  fun `refuses the flag on a shell that does not attest the runtime-tool-source loader`() {
    // A pre-runtime-tool-source shell would sign `allow_runtime_tool_source: true` and then ignore
    // every pushed bundle — a mismatch nothing on the device reports.
    val oldShell = InProcessTestApks.shellApk(
      temp.newFolder(),
      name = "old-shell.apk",
      properties = "shellVersion=20260830.000000.abc1234\nfloored=true\n",
    )
    val failure = assertFailsWith<IllegalArgumentException> {
      InProcessShellPackager.make(
        request(shell = oldShell, targetConfig = writeTargetConfig(), allowRuntimeToolSource = true),
      )
    }
    assertThat(failure.message!!).contains("runtimeToolSource")
    assertThat(outputApk().exists()).isFalse()
  }

  @Test
  fun `refuses the flag with no target config to write it into`() {
    val failure = assertFailsWith<IllegalArgumentException> {
      InProcessShellPackager.make(request(allowRuntimeToolSource = true))
    }
    assertThat(failure.message!!).contains("needs a --target-config")
    assertThat(outputApk().exists()).isFalse()
  }

  @Test
  fun `refuses a target config that is not one`() {
    val notAConfig = File(temp.newFolder(), "sample.yaml").apply {
      writeText("this: [is, not, a, target, config\n")
    }
    val failure = assertFailsWith<IllegalArgumentException> {
      InProcessShellPackager.make(request(targetConfig = notAConfig))
    }
    assertThat(failure.message!!).contains("is not a Trailblaze target config")
  }

  @Test
  fun `records no target config when none was injected`() {
    val result = InProcessShellPackager.make(request())
    assertThat(result.buildRecord.targetConfigId).isNull()
    assertThat(result.buildRecord.trailmapRevision).isNull()
  }

  // --- trailmaps -------------------------------------------------------------------------------

  @Test
  fun `injects pre-built bundles with no esbuild anywhere`() {
    val trailmap = File(temp.newFolder(), "sample").apply { File(this, "tools").mkdirs() }
    File(trailmap, "tools/sample_doThing.bundle.js").writeText("globalThis.x = 1;\n")
    File(trailmap, "tools/nested").mkdirs()
    File(trailmap, "tools/nested/sample_other.bundle.js").writeText("globalThis.y = 2;\n")

    val result = InProcessShellPackager.make(
      request(trailmapDirs = listOf(trailmap), esbuildBinary = null),
    )
    assertThat(ApkZipRewriter.entryNames(result.outputApk)).contains(
      "assets/trails/config/trailmaps/sample/tools/sample_doThing.bundle.js",
    )
    // Sorted by tools-relative path, so the same inputs always produce the same record.
    assertThat(result.buildRecord.injectedToolBundles).containsExactly(
      "assets/trails/config/trailmaps/sample/tools/nested/sample_other.bundle.js",
      "assets/trails/config/trailmaps/sample/tools/sample_doThing.bundle.js",
    )
    assertThat(result.buildRecord.trailmapRevision).isNotNull()
  }

  @Test
  fun `names esbuild as the prerequisite when a trailmap is TypeScript source`() {
    val trailmap = File(temp.newFolder(), "sample").apply { File(this, "tools").mkdirs() }
    File(trailmap, "tools/sample_doThing.ts").writeText(
      "export const sample_doThing = trailblaze.tool({ name: 'sample_doThing' }, async () => {});\n",
    )
    val failure = assertFailsWith<IllegalStateException> {
      InProcessShellPackager.make(request(trailmapDirs = listOf(trailmap), esbuildBinary = null))
    }
    assertThat(failure.message!!).contains("needs esbuild on this host")
  }

  @Test
  fun `refuses two trails with the same file name`() {
    val a = File(temp.newFolder(), "dupe.trail.yaml").apply { parentFile.mkdirs(); writeText("trail: []\n") }
    val b = File(temp.newFolder(), "dupe.trail.yaml").apply { parentFile.mkdirs(); writeText("trail: []\n") }
    val failure = assertFailsWith<IllegalArgumentException> {
      InProcessShellPackager.make(request(trails = listOf(a, b)))
    }
    assertThat(failure.message!!).contains("both named dupe.trail.yaml")
  }

  // --- signing ---------------------------------------------------------------------------------

  @Test
  fun `re-signs a shell that already carried a signature, leaving only the app's`() {
    // A shell off a build server is signed — by a debug key that is not the app's. Nothing about
    // building an APK here from scratch exercises the strip that has to happen before re-signing,
    // and an APK carrying two v1 signers is one Android refuses to install.
    val prebuilt = InProcessTestApks.signedShellApk(temp.root, otherKeystore)
    val result = InProcessShellPackager.make(request(shell = prebuilt))

    assertThat(ApkSigning.certificateSha256Digests(result.outputApk))
      .containsExactly(
        ApkSigning.keystoreCertificateSha256Digest(appKeystore, InProcessTestApks.PASSWORD.toCharArray(), "test"),
      )
  }

  @Test
  fun `the zip rewrite drops the shell's existing signature entries`() {
    val prebuilt = InProcessTestApks.signedShellApk(temp.root, otherKeystore, "to-rewrite.apk")
    assertThat(ApkZipRewriter.entryNames(prebuilt).filter { it.startsWith("META-INF/") }).isNotEmpty()

    val rewritten = File(temp.newFolder(), "rewritten.apk")
    ApkZipRewriter.rewrite(prebuilt, rewritten, replacements = emptyMap(), additions = emptyMap())

    // The rewriter's output is an APK in its own right, and one carrying another party's signature
    // over content that has just been changed is not one. Leaving it to whoever signs next means
    // the rewriter only produces a valid APK when its caller happens to strip them.
    assertThat(ApkZipRewriter.entryNames(rewritten).filter { it.startsWith("META-INF/") }).isEmpty()
  }

  @Test
  fun `accepts a JKS keystore, not only PKCS12`() {
    // Every other test here uses PKCS12, so "--keystore takes JKS or PKCS12" is otherwise an
    // untested claim in the help text. What makes it hold is partly the JDK's own PKCS12
    // compatibility mode, which is exactly why it is worth asserting rather than assuming.
    val jks = InProcessTestApks.keystore(temp.root, "app.jks", storeType = "JKS")
    val jksApp = InProcessTestApks.signedAppApk(temp.root, jks, "app-jks.apk")

    val result = InProcessShellPackager.make(request(keystore = jks, appApk = jksApp))

    assertThat(ApkSigning.certificateSha256Digests(result.outputApk))
      .containsExactly(
        ApkSigning.keystoreCertificateSha256Digest(jks, InProcessTestApks.PASSWORD.toCharArray(), "test"),
      )
  }

  @Test
  fun `refuses a wrong keystore password, and names the keystore`() {
    val failure = assertFailsWith<IllegalStateException> {
      InProcessShellPackager.make(request(storePassword = "not-the-password".toCharArray()))
    }
    assertThat(failure.message!!).contains(appKeystore.name)
    assertThat(failure.message!!).contains("wrong password")
  }

  @Test
  fun `refuses an --out that is the keystore, and leaves the keystore intact`() {
    // The worst aliasing case: signing truncates its output first, so a guard that fired late
    // would destroy the app's signing key rather than refuse the run.
    val before = appKeystore.readBytes().toList()
    val failure = assertFailsWith<IllegalArgumentException> {
      InProcessShellPackager.make(request(outputApk = appKeystore))
    }
    assertThat(failure.message!!).contains("would be written over --keystore")
    assertThat(appKeystore.readBytes().toList()).isEqualTo(before)
  }

  @Test
  fun `leaves no signed APK behind when the build record cannot be written`() {
    // The record is what says which app an APK targets and what it carries. A signed APK sitting
    // alone after a failed run is one somebody installs without knowing either.
    val out = outputApk()
    File(out.parentFile, "${out.nameWithoutExtension}.build-record.yaml").mkdirs()

    assertFailsWith<java.io.IOException> { InProcessShellPackager.make(request(outputApk = out)) }

    assertThat(out.exists()).isFalse()
  }

  // --- shell provenance ------------------------------------------------------------------------

  @Test
  fun `refuses a shell built without the in-process compile floor`() {
    val unfloored = InProcessTestApks.shellApk(
      temp.newFolder(),
      properties = "shellVersion=20260831.000000.abc1234\nfloored=false\n",
    )
    val failure = assertFailsWith<IllegalStateException> {
      InProcessShellPackager.make(request(shell = unfloored))
    }
    assertThat(failure.message!!).contains("WITHOUT the in-process compile floor")
    assertThat(outputApk().exists()).isFalse()
  }

  @Test
  fun `warns rather than refuses when the shell predates the floor marker`() {
    // Shells built before the marker existed are still usable; refusing them would make the check
    // a breaking change rather than a check.
    val unmarked = InProcessTestApks.shellApk(
      temp.newFolder(),
      properties = "shellVersion=20260831.000000.abc1234\n",
    )
    val result = InProcessShellPackager.make(request(shell = unmarked))

    assertThat(result.warnings.single()).contains("no compile-floor marker")
    assertThat(result.buildRecord.shellVersion).isEqualTo("20260831.000000.abc1234")
  }

  // --- injected paths --------------------------------------------------------------------------

  @Test
  fun `refuses a --trail that is not a trail yaml`() {
    val notATrail = File(temp.newFolder(), "login.yaml").apply { writeText("trail: []\n") }
    val failure = assertFailsWith<IllegalArgumentException> {
      InProcessShellPackager.make(request(trails = listOf(notATrail)))
    }
    assertThat(failure.message!!).contains("takes a `*.trail.yaml` file")
  }

  @Test
  fun `refuses a trailmap directory whose name is not a usable trailmap id`() {
    // The directory name becomes both the trailmap id and a path segment inside a SIGNED APK, so
    // it gets the same validation the target config's id does.
    val trailmap = File(temp.newFolder(), "my.trailmap").apply { mkdirs() }
    File(trailmap, "tools").mkdirs()
    File(trailmap, "tools/sample.bundle.js").writeText("// bundle\n")

    val failure = assertFailsWith<IllegalArgumentException> {
      InProcessShellPackager.make(request(trailmapDirs = listOf(trailmap)))
    }
    assertThat(failure.message!!).contains("not a usable id")
    assertThat(outputApk().exists()).isFalse()
  }

  // --- helpers ---------------------------------------------------------------------------------

  private fun outputApk() = File(temp.root, "out/test.apk")

  private fun request(
    shell: File = this.shell,
    targetPackage: String = InProcessTestApks.APP_PACKAGE,
    keystore: File = appKeystore,
    appApk: File? = this.appApk,
    fingerprintFile: File? = null,
    release: Boolean = false,
    allowRuntimeToolSource: Boolean = false,
    targetConfig: File? = null,
    trailmapDirs: List<File> = emptyList(),
    trails: List<File> = emptyList(),
    esbuildBinary: File? = null,
    outputApk: File = outputApk(),
    storePassword: CharArray = InProcessTestApks.PASSWORD.toCharArray(),
  ) = InProcessShellPackager.Request(
    shellApk = shell,
    outputApk = outputApk,
    targetPackage = targetPackage,
    keystore = keystore,
    keyAlias = "test",
    storePassword = storePassword,
    keyPassword = storePassword,
    appApk = appApk,
    fingerprintFile = fingerprintFile,
    release = release,
    allowRuntimeToolSource = allowRuntimeToolSource,
    targetConfig = targetConfig,
    trailmapDirs = trailmapDirs,
    trails = trails,
    esbuildBinary = esbuildBinary,
  )

  private fun trailFile(name: String): File =
    File(temp.newFolder(), name).apply {
      parentFile.mkdirs()
      writeText("trail:\n  - step: do something\n")
    }

  private fun writeFingerprint(fingerprint: AppFingerprint): File =
    File(temp.newFolder(), "fingerprint.yaml").apply {
      parentFile.mkdirs()
      writeText(
        TrailblazeConfigYaml.instance.encodeToString(AppFingerprint.serializer(), fingerprint),
      )
    }

  private fun writeTargetConfig(body: String = "id: sample\ndisplay_name: Sample\n"): File =
    File(temp.newFolder(), "sample.yaml").apply {
      parentFile.mkdirs()
      writeText(body)
    }
}
