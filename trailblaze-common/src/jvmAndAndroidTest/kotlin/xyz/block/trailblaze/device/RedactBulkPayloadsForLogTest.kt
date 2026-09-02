package xyz.block.trailblaze.device

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
}
