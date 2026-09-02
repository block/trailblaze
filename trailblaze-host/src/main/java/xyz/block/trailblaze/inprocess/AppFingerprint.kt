package xyz.block.trailblaze.inprocess

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.config.TrailblazeConfigYaml
import java.io.File

/**
 * What `trailblaze inprocess make-test-apk` needs to know about an app APK it will never open.
 *
 * A key-custody team runs the signing where the app APK already is; the bytes do not travel to
 * whoever assembles the test APK. So this file describes the app once, next to it, and is what
 * crosses the gap. [certificateSha256] and [debuggable] are therefore **mandatory** — they are the
 * inputs to `make-test-apk`'s two signing guards, and a fingerprint that omits them cannot
 * substitute for the APK.
 *
 * Read through one decoder ([load]) so every producer of the file agrees about the schema. Fields
 * beyond the three mandatory ones are optional, because the signing path needs none of them: a
 * generator is free to add descriptive ones without breaking this reader.
 */
@Serializable
data class AppFingerprint(
  /** The app's applicationId — the package the test APK's instrumentation will be stamped to. */
  @SerialName("package") val packageName: String,
  /**
   * Lowercase hex SHA-256 of each signing certificate, in the order the verifier reports them.
   * Compared against the keystore's certificate: Android only lets an instrumentation attach to a
   * target whose signature matches, so a mismatch is a failed install or a failed attach.
   *
   * [load] normalizes what it reads — see [normalizeDigest].
   */
  @SerialName("certificate_sha256") val certificateSha256: List<String>,
  /**
   * `<application android:debuggable>`. A non-debuggable target cannot be instrumented on a
   * production-signed build, so `make-test-apk` refuses unless `--release` says the caller knows
   * what they are doing.
   */
  val debuggable: Boolean,
  /** Free-form provenance — where these bytes came from — so a stale fingerprint is identifiable. */
  val source: String? = null,
  /** A generator's own verdict line about the app. Not read by signing. */
  val verdict: String? = null,
) {
  companion object {
    /**
     * Puts a certificate digest into the one form the signing guards compare.
     *
     * A fingerprint is often hand-assembled from `keytool -list -v` or
     * `apksigner verify --print-certs`, and both print SHA-256 uppercase and colon-separated. Left
     * alone, such a digest fails the guard against the very key it names — a "signing key mismatch"
     * between two digests that read identically to a human, which is a guard that only teaches
     * people to distrust it.
     */
    fun normalizeDigest(digest: String): String = digest.filterNot { it == ':' || it.isWhitespace() }.lowercase()

    fun load(file: File): AppFingerprint {
      require(file.isFile) { "Fingerprint file not found: $file" }
      return try {
        TrailblazeConfigYaml.instance.decodeFromString(serializer(), file.readText())
          .let { it.copy(certificateSha256 = it.certificateSha256.map(::normalizeDigest)) }
      } catch (e: Exception) {
        throw IllegalArgumentException(
          "$file is not a Trailblaze app fingerprint. It needs `package:`, " +
            "`certificate_sha256:` (a list) and `debuggable:`, read off the app's APK on a host " +
            "that has it. Cause: ${e.message}",
          e,
        )
      }
    }

    /** Reads the two mandatory signing-guard fields straight out of an app APK. */
    fun ofApk(apk: File): AppFingerprint {
      require(apk.isFile) { "App APK not found: $apk" }
      val manifest = ApkZipRewriter.readEntry(apk, "AndroidManifest.xml")
        ?: error("$apk has no AndroidManifest.xml entry, so it is not an APK")
      return AppFingerprint(
        packageName = AndroidBinaryXml.readPackageName(manifest),
        certificateSha256 = ApkSigning.certificateSha256Digests(apk),
        debuggable = AndroidBinaryXml.readApplicationDebuggable(manifest),
        source = apk.name,
      )
    }
  }
}
