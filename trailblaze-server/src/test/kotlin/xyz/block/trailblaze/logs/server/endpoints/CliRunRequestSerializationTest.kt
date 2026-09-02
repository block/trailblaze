package xyz.block.trailblaze.logs.server.endpoints

import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Wire-contract tests for the fields a dispatched run forwards to the daemon.
 *
 * The daemon anchors a run's workspace `defaults.target` at [CliRunRequest.callerWorkspaceDir] and
 * records it at [CliRunRequest.traceLevel]; both only earn their keep if they actually survive the
 * CLI→daemon JSON hop AND stay backward-compatible with older CLI clients that never send them.
 * Otherwise inspection-only.
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

  @Test
  fun `traceLevel round-trips through JSON`() {
    val request = CliRunRequest(yamlContent = "- step: sign in", traceLevel = "verbose")

    val decoded = json.decodeFromString(
      CliRunRequest.serializer(),
      json.encodeToString(CliRunRequest.serializer(), request),
    )

    assertEquals("verbose", decoded.traceLevel)
  }

  @Test
  fun `payload from an older CLI without a trace level decodes to null`() {
    // Null is what tells the daemon to keep its own level rather than reset it, so a CLI that
    // predates the field must not decode as a request for the default.
    val legacyPayload = """{"yamlContent":"- step: sign in"}"""

    val decoded = json.decodeFromString(CliRunRequest.serializer(), legacyPayload)

    assertNull(decoded.traceLevel)
  }

  @Test
  fun `device configuration and bindings round-trip through JSON`() {
    // `run --configuration` / `run --bind` only beat the daemon-wide env var if they survive this
    // hop: the daemon reads its own environment once at startup, so a dropped field here silently
    // runs a delegated multi-device trail against the wrong device set.
    val request = CliRunRequest(
      yamlContent = "- step: sign in",
      deviceConfiguration = "pos-pair",
      deviceBindings = mapOf("buyer" to "emulator-5562"),
    )

    val decoded = json.decodeFromString(
      CliRunRequest.serializer(),
      json.encodeToString(CliRunRequest.serializer(), request),
    )

    assertEquals("pos-pair", decoded.deviceConfiguration)
    assertEquals(mapOf("buyer" to "emulator-5562"), decoded.deviceBindings)
  }

  @Test
  fun `payload from an older CLI decodes to no configuration and no bindings`() {
    // Empty bindings and a null configuration are what leave the daemon's env-var fallback in
    // charge, so an older CLI must not decode as a request that overrides it.
    val legacyPayload = """{"yamlContent":"- step: sign in"}"""

    val decoded = json.decodeFromString(CliRunRequest.serializer(), legacyPayload)

    assertNull(decoded.deviceConfiguration)
    assertEquals(emptyMap(), decoded.deviceBindings)
  }

  @Test
  fun `snapshot baseline fields round-trip through JSON`() {
    // `run --snapshot-baseline` only beats the daemon's env-var fallback if it survives this hop;
    // a dropped field silently runs a delegated trail with no comparison at all.
    val request = CliRunRequest(
      yamlContent = "- step: sign in",
      snapshotBaseline = "https://example.test/results/trail/desktop/latest_success.zip",
      snapshotBaselineThresholdPercent = 0.5,
    )

    val decoded = json.decodeFromString(
      CliRunRequest.serializer(),
      json.encodeToString(CliRunRequest.serializer(), request),
    )

    assertEquals("https://example.test/results/trail/desktop/latest_success.zip", decoded.snapshotBaseline)
    assertEquals(0.5, decoded.snapshotBaselineThresholdPercent)
  }

  @Test
  fun `payload from an older CLI decodes to no snapshot baseline`() {
    val legacyPayload = """{"yamlContent":"- step: sign in"}"""

    val decoded = json.decodeFromString(CliRunRequest.serializer(), legacyPayload)

    assertNull(decoded.snapshotBaseline)
    assertNull(decoded.snapshotBaselineThresholdPercent)
  }
}
