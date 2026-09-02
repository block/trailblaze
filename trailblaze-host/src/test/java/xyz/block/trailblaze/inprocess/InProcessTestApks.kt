package xyz.block.trailblaze.inprocess

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds the inputs `make-test-apk` takes — a shell test APK, an app APK, and keystores — small
 * enough to make in a test but real enough to sign and verify.
 *
 * The manifests are the committed AXML fixtures (see `AndroidBinaryXmlTest`), so the APKs these
 * produce are parsed by exactly the code path a real one is. Keystores come from the JDK's own
 * `keytool` rather than a committed key: a signing key in a repository is a thing to avoid even when
 * it is only worth what it can sign.
 */
object InProcessTestApks {

  const val SHELL_PACKAGE: String = "xyz.block.trailblaze.inprocess.shell"
  const val APP_PACKAGE: String = "xyz.block.trailblaze.examples.sampleapp"
  const val PASSWORD: String = "trailblaze-test"

  fun shellManifest(): ByteArray = fixture("shell-AndroidManifest.xml")

  fun appManifest(): ByteArray = fixture("app-debug-AndroidManifest.xml")

  /**
   * An unsigned shell test APK: the fixture manifest plus a couple of entries that stand in for the
   * things a real shell carries, including a `STORED` one so the zip rewrite's method preservation
   * is exercised.
   */
  fun shellApk(
    dir: File,
    name: String = "shell.apk",
    extra: Map<String, ByteArray> = emptyMap(),
    /** The shell's own properties asset. Null omits it, as a shell built before it existed would. */
    properties: String? = "shellVersion=20260831.000000.abc1234\nfloored=true\nruntimeToolSource=true\n",
  ): File {
    val apk = File(dir, name)
    ZipOutputStream(apk.outputStream().buffered()).use { out ->
      writeEntry(out, "AndroidManifest.xml", shellManifest(), ZipEntry.DEFLATED)
      writeEntry(out, "resources.arsc", ByteArray(64) { it.toByte() }, ZipEntry.STORED)
      properties?.let {
        writeEntry(out, "assets/trailblaze/inprocess-shell.properties", it.toByteArray(), ZipEntry.DEFLATED)
      }
      writeEntry(out, "classes.dex", "not really dex".toByteArray(), ZipEntry.DEFLATED)
      extra.forEach { (path, bytes) -> writeEntry(out, path, bytes, ZipEntry.DEFLATED) }
    }
    return apk
  }

  /** A signed app APK, so [AppFingerprint.ofApk] can read a real certificate off it. */
  fun signedAppApk(dir: File, keystore: File, name: String = "app.apk"): File {
    val unsigned = File(dir, "$name.unsigned")
    ZipOutputStream(unsigned.outputStream().buffered()).use { out ->
      writeEntry(out, "AndroidManifest.xml", appManifest(), ZipEntry.DEFLATED)
      writeEntry(out, "classes.dex", "not really dex".toByteArray(), ZipEntry.DEFLATED)
    }
    val signed = File(dir, name)
    ApkSigning.sign(
      input = unsigned,
      output = signed,
      keystore = keystore,
      storePassword = PASSWORD.toCharArray(),
      keyAlias = "test",
      keyPassword = PASSWORD.toCharArray(),
    )
    unsigned.delete()
    return signed
  }

  /**
   * A shell test APK already signed by [keystore] — what a shell that came off a build server
   * actually looks like. `make-test-apk` must strip that signature rather than carry it alongside
   * its own, which no `.apk` built here from scratch would exercise.
   */
  fun signedShellApk(dir: File, keystore: File, name: String = "signed-shell.apk"): File {
    val unsigned = shellApk(dir, "$name.unsigned")
    val signed = File(dir, name)
    ApkSigning.sign(
      input = unsigned,
      output = signed,
      keystore = keystore,
      storePassword = PASSWORD.toCharArray(),
      keyAlias = "test",
      keyPassword = PASSWORD.toCharArray(),
    )
    unsigned.delete()
    return signed
  }

  /** Generates a keystore with `keytool` from the running JDK — always present, never committed. */
  fun keystore(dir: File, name: String, storeType: String = "PKCS12"): File {
    val keystore = File(dir, name)
    val keytool = File(File(System.getProperty("java.home"), "bin"), "keytool")
    check(keytool.canExecute()) { "No keytool at $keytool — these tests need a JDK, not a JRE." }
    val process = ProcessBuilder(
      keytool.absolutePath,
      "-genkeypair",
      "-keystore", keystore.absolutePath,
      "-storetype", storeType,
      "-storepass", PASSWORD,
      "-keypass", PASSWORD,
      "-alias", "test",
      "-keyalg", "RSA",
      "-keysize", "2048",
      "-validity", "365",
      "-dname", "CN=Trailblaze Test, O=Test, C=US",
      "-noprompt",
    ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "keytool failed: $output" }
    return keystore
  }

  private fun writeEntry(out: ZipOutputStream, name: String, bytes: ByteArray, method: Int) {
    val entry = ZipEntry(name).apply {
      this.method = method
      time = 0
      if (method == ZipEntry.STORED) {
        size = bytes.size.toLong()
        compressedSize = bytes.size.toLong()
        crc = java.util.zip.CRC32().apply { update(bytes) }.value
      }
    }
    out.putNextEntry(entry)
    out.write(bytes)
    out.closeEntry()
  }

  private fun fixture(name: String): ByteArray =
    checkNotNull(InProcessTestApks::class.java.getResourceAsStream("/inprocess/$name")) {
      "missing fixture $name"
    }.use { it.readBytes() }
}
