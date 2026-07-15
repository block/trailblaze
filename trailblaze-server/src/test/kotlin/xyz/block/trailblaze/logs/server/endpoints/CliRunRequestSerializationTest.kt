package xyz.block.trailblaze.logs.server.endpoints

import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Wire-contract tests for [CliRunRequest.callerWorkspaceDir].
 *
 * The daemon anchors a dispatched run's workspace `defaults.target` at this forwarded value; the
 * field only earns its keep if it actually survives the CLI→daemon JSON hop AND stays
 * backward-compatible with older CLI clients that never send it. Both are otherwise
 * inspection-only.
 */
class CliRunRequestSerializationTest {

  // Mirrors the daemon's decode leniency (see other endpoint tests): unknown keys are ignored so
  // a newer daemon can read an older CLI's payload.
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun `callerWorkspaceDir round-trips through JSON`() {
    val request = CliRunRequest(
      yamlContent = "- step: sign in",
      callerWorkspaceDir = "/Users/dev/my-workspace",
    )

    val decoded = json.decodeFromString(
      CliRunRequest.serializer(),
      json.encodeToString(CliRunRequest.serializer(), request),
    )

    assertEquals("/Users/dev/my-workspace", decoded.callerWorkspaceDir)
  }

  @Test
  fun `payload from an older CLI without the field decodes to null`() {
    // An older CLI shim that predates the field sends no callerWorkspaceDir key at all; the daemon
    // must default it to null (which its resolver maps to the daemon-anchored fallback).
    val legacyPayload = """{"yamlContent":"- step: sign in"}"""

    val decoded = json.decodeFromString(CliRunRequest.serializer(), legacyPayload)

    assertNull(decoded.callerWorkspaceDir)
  }
}
