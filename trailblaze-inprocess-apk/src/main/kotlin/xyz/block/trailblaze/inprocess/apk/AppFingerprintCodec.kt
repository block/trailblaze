package xyz.block.trailblaze.inprocess.apk

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException

/**
 * The one encoder and decoder for [AppFingerprint].
 *
 * Every consumer goes through here — the CLI that writes a fingerprint, `make-test-apk` reading one
 * as target evidence, and the farm's install-time pre-flight. A second parser somewhere else is how
 * the two sides start disagreeing about what a field means, and one of those sides is a signing
 * guard.
 */
object AppFingerprintCodec {

  private val yaml = Yaml(
    configuration = YamlConfiguration(
      // A fingerprint is read by a guard. An unrecognised key is a schema mismatch the reader must
      // not paper over, so strict mode stays on — the opposite call from the trailmap bundler, whose
      // job is to tolerate a manifest richer than the slice it reads.
      strictMode = true,
      encodeDefaults = true,
      breakScalarsAt = 120,
    ),
  )

  fun encode(fingerprint: AppFingerprint): String = yaml.encodeToString(AppFingerprint.serializer(), fingerprint)

  /**
   * Parses [text].
   *
   * @throws ApkReadException when the text is not a fingerprint, or carries a schema version this
   *   build does not know how to read. Both messages name the file the caller passed in.
   */
  fun decode(text: String, describeSource: String = "fingerprint"): AppFingerprint {
    val fingerprint = try {
      yaml.decodeFromString(AppFingerprint.serializer(), text)
    } catch (e: YamlException) {
      throw ApkReadException(
        "$describeSource is not a readable Trailblaze app fingerprint: ${e.message}. " +
          "Regenerate it with `trailblaze inprocess probe-apk <apk> --shell <shell apk>`.",
        e,
      )
    }
    if (fingerprint.schemaVersion != FINGERPRINT_SCHEMA_VERSION) {
      throw ApkReadException(
        "$describeSource declares fingerprint schemaVersion ${fingerprint.schemaVersion}, but this " +
          "build reads version $FINGERPRINT_SCHEMA_VERSION. Regenerate it with " +
          "`trailblaze inprocess probe-apk`, or use the Trailblaze release that wrote it.",
      )
    }
    return fingerprint
  }
}
