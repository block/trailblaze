package xyz.block.trailblaze.device

import java.util.Base64
import kotlin.test.assertEquals

/**
 * Simulates the device side of the shell-less trampoline for tests: Runtime.exec whitespace
 * tokenization, `${IFS}` expansion, base64 decode. Returns the inner script the device-side `sh`
 * would execute. Fails the calling test if the wrapped command doesn't tokenize to exactly
 * `[sh, -c, payload]` — anything else means the payload shattered and the inner command never
 * runs. Single owner of the simulation so a trampoline-encoding change breaks one helper, not
 * per-file copies.
 */
internal fun decodeShellTrampoline(transportCommand: String): String {
  val tokens = transportCommand.split(Regex("\\s+"))
  assertEquals(3, tokens.size, "expected [sh, -c, payload], got $tokens")
  assertEquals("sh", tokens[0])
  assertEquals("-c", tokens[1])
  val b64 = tokens.last().replace("\${IFS}", " ").removePrefix("printf %s ").substringBefore("|")
  return String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
}
