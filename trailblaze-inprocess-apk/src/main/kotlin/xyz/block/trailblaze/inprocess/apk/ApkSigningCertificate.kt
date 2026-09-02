package xyz.block.trailblaze.inprocess.apk

import com.android.apksig.ApkVerifier
import java.io.File
import java.security.MessageDigest

/**
 * The app APK's signing certificate digest — one of the two mandatory pieces of **target evidence**
 * in a fingerprint.
 *
 * Read through `apksig`, the pure-Java library `apksigner` wraps, so the digest here and the digest
 * a CI step gets from `apksigner verify --print-certs` are the same string by construction rather
 * than by two implementations agreeing. That matters because `make-test-apk`'s cert cross-check
 * compares them across a trust boundary: one side may only ever see the fingerprint.
 */
internal object ApkSigningCertificate {

  /**
   * Lowercase hex SHA-256 over the DER encoding of the APK's sole signer certificate.
   *
   * @throws ApkReadException when the APK carries no verifiable signature, or when it carries more
   *   than one signer. Both are deliberately fatal rather than a null or a partial field: the
   *   fingerprint states one digest, and Android gates an instrumentation attach on the two APKs'
   *   *signer sets* matching. Recording one certificate out of several would let a key matching only
   *   the recorded one pass this check and still be refused by the platform, which is the failure
   *   this field exists to prevent.
   */
  fun sha256OfSoleSigner(apk: File): String {
    val result = try {
      ApkVerifier.Builder(apk).build().verify()
    } catch (e: Exception) {
      throw ApkReadException(
        "Could not read $apk's signature with apksig: ${e.message}. The fingerprint's cert digest " +
          "is what `make-test-apk` checks a signing key against, so the probe refuses to emit a " +
          "fingerprint without it.",
        e,
      )
    }
    if (!result.isVerified) {
      val errors = result.errors.joinToString("; ") { it.toString() }
      throw ApkReadException(
        "$apk has no verifiable APK signature" + (if (errors.isBlank()) "" else " ($errors)") +
          ". An unsigned APK cannot host an instrumentation — Android gates attach on a signature " +
          "match — and the fingerprint's cert digest is what `make-test-apk` checks a key against.",
      )
    }
    val certificates = result.signerCertificates
    if (certificates.isEmpty()) {
      throw ApkReadException(
        "$apk verified but reported no signer certificate, so there is no digest to record.",
      )
    }
    if (certificates.size > 1) {
      throw ApkReadException(
        "$apk is signed by ${certificates.size} signers (${certificates.joinToString(", ") { it.subjectX500Principal.name }}), " +
          "and the fingerprint records one digest. Android matches instrumentation against the whole " +
          "signer set, so a key matching only one of these would pass this check and still be " +
          "refused on device. Sign the app with a single key, or extend the schema to carry the set.",
      )
    }
    return MessageDigest.getInstance("SHA-256")
      .digest(certificates.single().encoded)
      .joinToString("") { "%02x".format(it) }
  }
}
