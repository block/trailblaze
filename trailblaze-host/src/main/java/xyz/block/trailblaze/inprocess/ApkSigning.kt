package xyz.block.trailblaze.inprocess

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * APK signing and certificate inspection, via `apksig` — the same library `apksigner` and AGP use,
 * as a plain JVM dependency. No `zipalign`, no build-tools, no Android SDK.
 */
object ApkSigning {

  /** `minSdkVersion` the signature schemes are chosen for; matches every in-process shell's floor. */
  const val MIN_SDK_VERSION: Int = 28

  /**
   * Signs [input] to [output].
   *
   * Alignment happens here: `ApkSigner.Builder` defaults `setAlignmentPreserved(false)`, so it
   * re-aligns the output — which is what keeps `resources.arsc` and the 16 KB-page native libraries
   * loadable after [ApkZipRewriter] has moved everything around.
   */
  fun sign(
    input: File,
    output: File,
    keystore: File,
    storePassword: CharArray,
    keyAlias: String,
    keyPassword: CharArray,
  ) {
    val signerConfig = signerConfig(keystore, storePassword, keyAlias, keyPassword)
    output.parentFile?.mkdirs()
    ApkSigner.Builder(listOf(signerConfig))
      .setInputApk(input)
      .setOutputApk(output)
      .setMinSdkVersion(MIN_SDK_VERSION)
      .setV1SigningEnabled(true)
      .setV2SigningEnabled(true)
      .setV3SigningEnabled(true)
      .setCreatedBy("trailblaze")
      .build()
      .sign()
  }

  /**
   * Lowercase hex SHA-256 digests of an APK's signing certificates.
   *
   * This is the value the cert cross-check compares. Android only lets an instrumentation attach to
   * a target app when both APKs carry the same signature, so a mismatch here is a guaranteed
   * `INSTALL_FAILED_*` or a failed attach — worth refusing before writing an APK rather than
   * discovering on a device.
   */
  fun certificateSha256Digests(apk: File): List<String> {
    val result = ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(MIN_SDK_VERSION).build().verify()
    require(result.isVerified) {
      "$apk is not a validly signed APK, so its certificate cannot be compared: " +
        result.errors.joinToString().ifEmpty { "no signature found" }
    }
    return result.signerCertificates.map { sha256Hex(it.encoded) }
  }

  /** The digest of the certificate [keystore]'s [keyAlias] would sign with. */
  fun keystoreCertificateSha256Digest(
    keystore: File,
    storePassword: CharArray,
    keyAlias: String,
  ): String {
    val ks = loadKeyStore(keystore, storePassword)
    val cert = ks.getCertificate(keyAlias)
      ?: error("Keystore $keystore has no certificate for alias '$keyAlias'")
    return sha256Hex(cert.encoded)
  }

  private fun signerConfig(
    keystore: File,
    storePassword: CharArray,
    keyAlias: String,
    keyPassword: CharArray,
  ): ApkSigner.SignerConfig {
    val ks = loadKeyStore(keystore, storePassword)
    val key = ks.getKey(keyAlias, keyPassword) as? PrivateKey
      ?: error("Keystore $keystore has no private key for alias '$keyAlias'")
    val chain = (ks.getCertificateChain(keyAlias) ?: emptyArray()).map { it as X509Certificate }
    require(chain.isNotEmpty()) { "Keystore $keystore has no certificate chain for alias '$keyAlias'" }
    return ApkSigner.SignerConfig.Builder(keyAlias, key, chain).build()
  }

  /**
   * Loads a keystore without asking the caller which format it is.
   *
   * Android debug keystores are JKS, `keytool`'s default since JDK 9 is PKCS12, and an adopter has
   * whichever their app is already signed with — so the format is not a thing to make them declare.
   * Tried in order, because a load failure is the only reliable discriminator.
   */
  private fun loadKeyStore(keystore: File, storePassword: CharArray): KeyStore {
    require(keystore.isFile) { "Keystore not found: $keystore" }
    val failures = mutableListOf<String>()
    for (type in listOf("JKS", "PKCS12")) {
      try {
        val ks = KeyStore.getInstance(type)
        keystore.inputStream().use { ks.load(it, storePassword) }
        return ks
      } catch (e: Exception) {
        failures += "$type: ${e.message}"
      }
    }
    error("Could not read $keystore as a keystore (wrong password?). Tried ${failures.joinToString("; ")}")
  }

  private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
