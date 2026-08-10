package xyz.block.trailblaze.report

import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus

/**
 * Pins [WasmReport.compressLogsToBase64] against the pipeline it replaced. Streaming the
 * serializer straight into gzip is what removes the giant contiguous String that killed the
 * nightly Combined Report step, so what needs proving is that the payload did not change: the
 * WASM report gunzips this blob and calls `decodeFromString<List<TrailblazeLog>>` on the result.
 *
 * Only the gzip *container* framing is allowed to differ (deflate block boundaries shift when the
 * input arrives in chunks rather than one write) — no consumer reads it, they all gunzip first.
 */
class WasmReportCompressLogsTest {

  private fun startedStatus(rawYaml: String): SessionStatus.Started {
    val deviceId = TrailblazeDeviceId("web", TrailblazeDevicePlatform.WEB)
    return SessionStatus.Started(
      trailConfig = null,
      trailFilePath = "trails/example.trail.yaml",
      hasRecordedSteps = false,
      testMethodName = "compress",
      testClassName = "WasmReportCompressLogsTest",
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = deviceId,
        trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
        widthPixels = 1280,
        heightPixels = 720,
        classifiers = listOf(TrailblazeDevicePlatform.WEB.asTrailblazeDeviceClassifier()),
      ),
      trailblazeDeviceId = deviceId,
      rawYaml = rawYaml,
    )
  }

  /**
   * Carries the payload shapes that could plausibly diverge between a buffered re-encode and a
   * streamed one: polymorphic sealed types (class discriminator), and strings needing escapes.
   */
  private fun sampleLogs(): List<TrailblazeLog> {
    val session = SessionId("compress-session")
    return listOf(
      TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = startedStatus(
          rawYaml = "trail:\n  - step: Tap \"Charge\"\tand wait\n  - note: café — 100% \\ done",
        ),
        session = session,
        timestamp = Instant.fromEpochMilliseconds(1_700_000_000_000L),
      ),
      TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 5_000L),
        session = session,
        timestamp = Instant.fromEpochMilliseconds(1_700_000_005_000L),
      ),
    )
  }

  /** The pipeline this replaced: pretty-print via the shared instance, then strip whitespace. */
  private fun legacyCompactJson(logs: List<TrailblazeLog>): String {
    val reformatter = Json {
      prettyPrint = false
      ignoreUnknownKeys = true
    }
    return reformatter.encodeToString(
      JsonElement.serializer(),
      reformatter.parseToJsonElement(TrailblazeJsonInstance.encodeToString(logs)),
    )
  }

  private fun gunzipBase64(base64: String): String =
    GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(base64))).use { gzipStream ->
      gzipStream.readBytes().toString(Charsets.UTF_8)
    }

  @Test
  fun `streamed payload is byte-identical to the buffered pipeline it replaced`() {
    val logs = sampleLogs()
    val expected = legacyCompactJson(logs)

    // Negative control: the comparison is only meaningful if the fixture actually exercises the
    // discriminator and the escapes. An empty or degenerate payload would pass equality trivially.
    assertContains(expected, "\\\"Charge\\\"", message = "fixture lost its escaped quotes")
    assertContains(expected, "\\t", message = "fixture lost its escaped tab")
    assertTrue(expected.length > 200, "fixture is too small to be a meaningful comparison")
    assertTrue(!expected.contains("\n"), "legacy pipeline should already be whitespace-stripped")

    assertEquals(expected, gunzipBase64(WasmReport.compressLogsToBase64(logs)))
  }

  @Test
  fun `output round-trips through the decode path the WASM report uses`() {
    val logs = sampleLogs()

    val decoded = TrailblazeJsonInstance.decodeFromString<List<TrailblazeLog>>(
      gunzipBase64(WasmReport.compressLogsToBase64(logs)),
    )

    assertEquals(logs, decoded)
  }

  @Test
  fun `empty logs still produce a decodable payload`() {
    val encoded = WasmReport.compressLogsToBase64(emptyList())

    assertEquals("[]", gunzipBase64(encoded))
    assertEquals(
      emptyList(),
      TrailblazeJsonInstance.decodeFromString<List<TrailblazeLog>>(gunzipBase64(encoded)),
    )
  }
}
