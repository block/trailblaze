package xyz.block.trailblaze.inprocess.apk

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The fingerprint's on-disk form.
 *
 * This is the one place the schema is defined, and it crosses a trust boundary: the CLI writes a
 * fingerprint, and `make-test-apk`'s signing guards read it on the path where the app APK's bytes
 * never travel. So the decoder has to refuse anything it does not fully understand rather than fill
 * in a default — a silently-dropped field there is a guard that stops guarding.
 */
class AppFingerprintCodecTest {

  @Test
  fun `a fingerprint survives a round trip unchanged`() {
    val original = fingerprint()
    assertEquals(original, AppFingerprintCodec.decode(AppFingerprintCodec.encode(original), "test"))
  }

  @Test
  fun `the mandatory signing evidence is written even at its default`() {
    // cert digest and debuggable are what `make-test-apk`'s guards read. If either were omitted
    // when it happened to equal a Kotlin default, a guard would read a fingerprint with no evidence
    // in it and have nothing to refuse on.
    val yaml = AppFingerprintCodec.encode(fingerprint(debuggable = false))
    assertTrue(yaml.contains("certSha256:"), yaml)
    assertTrue(yaml.contains("debuggable: false"), yaml)
  }

  @Test
  fun `an unknown field is refused rather than ignored`() {
    val yaml = AppFingerprintCodec.encode(fingerprint()) + "\nsomethingNobodyKnows: true\n"
    val error = assertFailsWith<ApkReadException> { AppFingerprintCodec.decode(yaml, "fingerprint.yaml") }
    assertTrue(error.message!!.contains("fingerprint.yaml"), error.message!!)
  }

  @Test
  fun `a fingerprint from a different schema version is refused`() {
    val yaml = AppFingerprintCodec.encode(fingerprint())
      .replace("schemaVersion: $FINGERPRINT_SCHEMA_VERSION", "schemaVersion: 99")
    val error = assertFailsWith<ApkReadException> { AppFingerprintCodec.decode(yaml, "fingerprint.yaml") }
    // Both numbers, so the reader knows which side is stale.
    assertTrue(error.message!!.contains("99"), error.message!!)
    assertTrue(error.message!!.contains("$FINGERPRINT_SCHEMA_VERSION"), error.message!!)
  }

  @Test
  fun `text that is not a fingerprint at all is refused`() {
    assertFailsWith<ApkReadException> { AppFingerprintCodec.decode("just a sentence", "notes.txt") }
  }

  private fun fingerprint(debuggable: Boolean = true) = AppFingerprint(
    targetPackage = "com.example",
    launcherActivity = "com.example.MainActivity",
    debuggable = debuggable,
    certSha256 = "0123456789abcdef",
    providers = listOf(
      DeclaredProvider("androidx.startup.InitializationProvider", "com.example.androidx-startup", true),
      DeclaredProvider("com.example.Raw", null, false),
    ),
    eras = listOf(
      LibraryEra(
        library = "androidx.compose.ui:ui",
        present = true,
        version = "1.9.0",
        source = EraSource.PACKAGED_VERSION_FILE,
        evidence = "META-INF/androidx.compose.ui_ui.version",
      ),
      LibraryEra(
        library = "io.ktor:ktor-client-core",
        present = true,
        belowVersion = "3.2.0",
        source = EraSource.DEX_MARKER,
        evidence = "class io.ktor.client.call.DelegatedCall is absent (added in 3.2.0)",
      ),
    ),
    dexOverlap = DexOverlap("shell.apk", 1, listOf("com.example.Shared"), truncated = false),
    verdict = ProbeVerdict(
      status = ProbeStatus.NO_GO,
      reasons = listOf(ProbeReason(ProbeReasonCode.DEX_OVERLAP_WITH_SHELL, "one shared class")),
    ),
  )
}
