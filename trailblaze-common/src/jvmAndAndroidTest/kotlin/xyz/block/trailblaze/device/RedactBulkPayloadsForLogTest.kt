package xyz.block.trailblaze.device

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.android.tools.shellEscape

/**
 * Pins [redactBulkPayloadsForLog], the guard that keeps a seeded file's bytes out of shell-command
 * logs.
 *
 * `writeFileAs` carries the body base64-encoded inside the command line, and the bodies callers
 * seed are exactly the sensitive ones — `android_writeBytesToFile` masks its `base64Content` in
 * session logs because seeded auth/session files hold live tokens, and the shell-command log lands
 * in the same CI artifacts. Both transports log the command they run, so the redaction has to hold
 * for both shapes the payload takes.
 *
 * The counter-test matters as much as the redaction: a guard that swallowed ordinary commands
 * would blind on-device shell debugging, which is why the length threshold exists.
 *
 * The second half of the suite covers the other shape a secret arrives in — a credential the
 * caller passed as an ordinary argv element, which no pattern can recognize on its own and so is
 * registered with [DeviceCommandLogSecrets] for the duration of the call carrying it.
 */
class RedactBulkPayloadsForLogTest {

  private val secret = "super-secret-oauth-token-value"
  private val secretB64 = Base64.getEncoder().encodeToString(secret.toByteArray())

  @Test
  fun `redacts the file body out of a shell-transport run-as write`() {
    val command = wrapShellPipelineForTransport(
      usesShellInterpreter = true,
      innerCommand = buildRunAsFileWriteCommand(
        devicePath = "/data/data/com.example.app/shared_prefs/auth.xml",
        content = secret.toByteArray(),
      ),
    )
    val redacted = redactBulkPayloadsForLog(command)

    assertFalse(redacted.contains(secretB64), "payload survived redaction: $redacted")
    // The shape stays readable — a reader still sees which file was written and how.
    assertTrue(redacted.contains("/data/data/com.example.app/shared_prefs/auth.xml"), redacted)
    assertTrue(redacted.contains("base64 -d"), redacted)
    assertTrue(redacted.contains("redacted"), redacted)
  }

  @Test
  fun `redacts the file body out of a shell-less transport run-as write`() {
    // Here the whole inner command — payload included — is re-encoded into one opaque token, so
    // the `printf %s` rule can't see inside it and the length rule is what has to fire.
    val command = wrapShellPipelineForTransport(
      usesShellInterpreter = false,
      innerCommand = buildRunAsFileWriteCommand(
        devicePath = "/data/data/com.example.app/shared_prefs/auth.xml",
        content = ByteArray(4096) { 'A'.code.toByte() },
      ),
    )
    val redacted = redactBulkPayloadsForLog(command)

    assertFalse(
      redacted.split(Regex("\\s+")).any { it.length > 1024 },
      "an over-long token survived redaction: $redacted",
    )
    assertTrue(redacted.contains("redacted"), redacted)
  }

  @Test
  fun `redacts a small file body carried inside a shell-less trampoline`() {
    // The size-independent case, and the one a length threshold alone cannot reach: a 30-byte
    // secret re-encodes to a ~230-character token, well under MAX_LOGGED_BASE64_RUN. The token has
    // to be decoded and judged on what it contains.
    val command = wrapShellPipelineForTransport(
      usesShellInterpreter = false,
      innerCommand = buildRunAsFileWriteCommand(
        devicePath = "/data/data/com.example.app/shared_prefs/auth.xml",
        content = secret.toByteArray(),
      ),
    )
    val token = command.substringAfter("%s\${IFS}").substringBefore("|base64")
    assertTrue(token.length < 512, "fixture must sit under the length rule, was ${token.length}")

    val redacted = redactBulkPayloadsForLog(command)

    assertFalse(redacted.contains(token), "payload survived redaction: $redacted")
    assertTrue(redacted.contains("redacted"), redacted)
  }

  @Test
  fun `leaves an ordinary trampolined command readable`() {
    // The on-device transport base64-packs EVERY command, not just writes. Redacting those too
    // would blind on-device shell debugging, so short payloads must pass through untouched.
    val command = wrapShellPipelineForTransport(
      usesShellInterpreter = false,
      innerCommand = "am force-stop com.example.app",
    )

    assertEquals(command, redactBulkPayloadsForLog(command))
  }

  @Test
  fun `leaves an ordinary shell command untouched`() {
    val command = "pm list packages | grep com.example"

    assertEquals(command, redactBulkPayloadsForLog(command))
  }

  @Test
  fun `redacts a short printf payload that the length rule alone would miss`() {
    // A tiny file body is still a file body. The `printf %s` rule redacts at any length, which is
    // the half of the guard the length threshold cannot cover.
    val redacted = redactBulkPayloadsForLog("printf %s $secretB64 | base64 -d > /data/data/x/y")

    assertFalse(redacted.contains(secretB64), redacted)
    assertTrue(redacted.contains("/data/data/x/y"), redacted)
  }

  // ---------------------------------------------------------------------------------------------
  // A credential passed as an ordinary argv element — the shape no pattern above can recognize,
  // so the value has to be registered for the duration of the call that carries it.
  // ---------------------------------------------------------------------------------------------

  @Test
  fun `masks a registered secret in a plain shell command`() {
    val token = "tok-abc123"
    val command = "service call com.vendor.deviceauth 1 s16 '$token'"

    val redacted = DeviceCommandLogSecrets.withSecretsRedacted(listOf(token)) {
      redactBulkPayloadsForLog(command)
    }

    assertFalse(redacted.contains(token), "credential survived into the log line: $redacted")
    // The rest of the command stays legible — that is the whole point of registering the value
    // rather than blanking the command.
    assertTrue(redacted.contains("service call com.vendor.deviceauth"), redacted)
  }

  @Test
  fun `stops masking once the call carrying the secret has returned`() {
    val token = "tok-abc123"
    val command = "getprop persist.vendor.session '$token'"

    DeviceCommandLogSecrets.withSecretsRedacted(listOf(token)) { redactBulkPayloadsForLog(command) }

    // Registration is scoped to the dispatch, not the process: a later unrelated command that
    // happens to contain the same text is not silently rewritten, and the registry does not grow
    // without bound across a long session.
    assertEquals(command, redactBulkPayloadsForLog(command))
  }

  @Test
  fun `masks a secret that shell-escaping rewrote before it reached the log`() {
    // `shellEscape` runs before anything is logged, so a value holding a single quote reaches the
    // command line quote-doubled. A raw-literal scrub of the original value would miss it
    // entirely — which is why both forms are registered.
    val token = "pa'ss"
    val command = "service call com.vendor.deviceauth 1 s16 ${token.shellEscape()}"

    val redacted = DeviceCommandLogSecrets.withSecretsRedacted(listOf(token)) {
      redactBulkPayloadsForLog(command)
    }

    assertFalse(redacted.contains("pa'\\''ss"), "escaped credential survived: $redacted")
    assertFalse(redacted.contains(token), "credential survived: $redacted")
  }

  @Test
  fun `masks a secret carried inside a trampoline token`() {
    // The shell-less transport base64-packs the whole command, so the literal scrub cannot see the
    // credential at all. The token has to be judged on what it decodes to.
    val token = "tok-abc123"
    val command = wrapShellPipelineForTransport(
      usesShellInterpreter = false,
      innerCommand = "service call com.vendor.deviceauth 1 s16 '$token'",
    )
    val payload = SHELL_LESS_TRAMPOLINE_PAYLOAD.find(command)!!.groupValues[1]
    // Guard the premise: without registration this token is short enough to pass every length and
    // file-body rule, i.e. it really does reach the log intact today.
    assertEquals(command, redactBulkPayloadsForLog(command))

    val redacted = DeviceCommandLogSecrets.withSecretsRedacted(listOf(token)) {
      redactBulkPayloadsForLog(command)
    }

    assertFalse(redacted.contains(payload), "base64-packed credential survived: $redacted")
    assertTrue(redacted.contains("base64 of:"), redacted)
    // The command stays legible — only the credential is gone.
    assertTrue(redacted.contains("service call com.vendor.deviceauth"), redacted)
  }

  @Test
  fun `a secret that also occurs inside the base64 token does not break the token open`() {
    // Literal masking has to run AFTER the token is judged. A registered value — the finding's
    // example is a bare `1` from the same argv — can occur by chance inside the base64 alphabet of
    // the token itself. Scrubbing it there first splits the token, the trampoline regex then sees
    // only the prefix before the split, and that prefix still decodes to the head of the inner
    // command — credential included — while the guard believes nothing was left to judge.
    val token = "tok-abc123"
    val command = wrapShellPipelineForTransport(
      usesShellInterpreter = false,
      innerCommand = "service call com.vendor.deviceauth 1 s16 '$token'",
    )
    val payload = SHELL_LESS_TRAMPOLINE_PAYLOAD.find(command)!!.groupValues[1]
    // A value that is certainly inside the token, taken from the token itself so the test does not
    // depend on which characters base64 happens to produce for this fixture.
    val decoy = payload.substring(12, 16)
    assertTrue(payload.indexOf(decoy) < payload.length - 40, "decoy must split the token well before its end")

    val redacted = DeviceCommandLogSecrets.withSecretsRedacted(listOf(token, decoy)) {
      redactBulkPayloadsForLog(command)
    }

    assertFalse(redacted.contains(payload.take(12)), "a decodable base64 prefix survived: $redacted")
    assertTrue(redacted.contains("base64 of:"), redacted)
    assertFalse(redacted.contains(token), redacted)
    assertTrue(redacted.contains("service call com.vendor.deviceauth"), redacted)
  }

  @Test
  fun `an inner call returning does not unmask an outer secret still in flight`() {
    // Reference counting, which matters because two dispatches can hold the same secret
    // concurrently: the inner one finishing must not drop the outer one's masking.
    val token = "tok-abc123"
    val command = "getprop persist.vendor.session '$token'"

    val redacted = DeviceCommandLogSecrets.withSecretsRedacted(listOf(token)) {
      DeviceCommandLogSecrets.withSecretsRedacted(listOf(token)) { redactBulkPayloadsForLog(command) }
      redactBulkPayloadsForLog(command)
    }

    assertFalse(redacted.contains(token), "outer secret unmasked by the inner call: $redacted")
  }

  @Test
  fun `masks the longest secret first so one containing another is fully covered`() {
    // Folding in caller order would let the shorter value destroy the longer one's only literal
    // occurrence, leaving the remainder in the log as `sess-<redacted>-abcd`.
    val scrubbed = redactSecretLiteralsForLog("value=sess-1234-abcd", listOf("1234", "sess-1234-abcd"))

    assertEquals("value=<redacted>", scrubbed)
  }

  @Test
  fun `ignores blank secrets instead of shredding the text`() {
    assertEquals("keep me", redactSecretLiteralsForLog("keep me", listOf("", "   ")))
  }

  /** The trampoline's base64 token, for asserting the packed form is gone. */
  private val SHELL_LESS_TRAMPOLINE_PAYLOAD =
    Regex("""printf\$\{IFS\}%s\$\{IFS\}([A-Za-z0-9+/]+={0,2})""")
}
