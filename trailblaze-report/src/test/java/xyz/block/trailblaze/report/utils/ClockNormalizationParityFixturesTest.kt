package xyz.block.trailblaze.report.utils

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.deviceClockOffsets
import xyz.block.trailblaze.logs.client.normalizedMs
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.TrailblazeClockDomain

/**
 * Cross-language behavioral contract for device-clock normalization.
 *
 * `src/main/resources/xyz/block/trailblaze/report/clock-normalization-parity-fixtures.json` is the
 * single source of truth for how a session's device→host offsets are derived and where each log
 * lands on the host timeline, consumed by BOTH this test (driving the real
 * `TrailblazeLogClockNormalization.kt`, which every Kotlin reader shares) and the profiler's
 * `perf-extract.test.ts` (driving the real `perf-extract.ts`, which re-implements the derivation
 * because it runs in a browser over raw JSON). A semantic drift in either fails that side's suite.
 *
 * To change a derivation rule: update both implementations AND the fixture in the same change.
 * (Same fixture-parity pattern as [SessionEventsParityFixturesTest].)
 */
class ClockNormalizationParityFixturesTest {

  /** `deviceName` absent means the log carries none; `durationMs` absent means 0. */
  @Serializable
  private data class LogCase(
    val id: String,
    val type: String,
    val clock: String,
    val timestampMs: Long,
    val durationMs: Long? = null,
    val deviceName: String? = null,
    val hostReceivedAtMs: Long? = null,
    val expectedHostMs: Long,
  )

  /** `byDeviceName` spells "no device name" as the empty string, the one key JSON can hold. */
  @Serializable
  private data class OffsetsCase(val byDeviceName: Map<String, Long>, val sessionWideMs: Long)

  @Serializable
  private data class SessionCase(val name: String, val logs: List<LogCase>, val expectedOffsets: OffsetsCase? = null)

  @Serializable
  private data class ParityFixtures(val cases: List<SessionCase>)

  private val fixtures: ParityFixtures by lazy {
    val file = locate("trailblaze-report/src/main/resources/xyz/block/trailblaze/report/clock-normalization-parity-fixtures.json")
    Json { ignoreUnknownKeys = true }.decodeFromString<ParityFixtures>(file.readText())
  }

  @Test
  fun `offset derivation agrees with the shared parity fixtures`() {
    check(fixtures.cases.isNotEmpty())
    fixtures.cases.forEach { case ->
      val offsets = case.logs.map { it.toLog() }.deviceClockOffsets()
      if (case.expectedOffsets == null) {
        assertEquals(null, offsets, "${case.name}: expected no offsets")
        return@forEach
      }
      assertEquals(
        case.expectedOffsets.byDeviceName,
        case.expectedOffsets.byDeviceName.keys.associateWith { key ->
          // Read back through the public accessor, keyed by a tool log naming that device.
          offsets!!.offsetMsFor(toolNamed(key.ifEmpty { null }))
        },
        "${case.name}: per-device offsets",
      )
      assertEquals(case.expectedOffsets.sessionWideMs, offsets!!.sessionWideOffsetMs, "${case.name}: session-wide offset")
    }
  }

  @Test
  fun `host-timeline placement agrees with the shared parity fixtures`() {
    check(fixtures.cases.isNotEmpty())
    fixtures.cases.forEach { case ->
      val logs = case.logs.map { it.toLog() }
      val offsets = logs.deviceClockOffsets()
      assertEquals(
        case.logs.associate { it.id to it.expectedHostMs },
        case.logs.zip(logs).associate { (spec, log) -> spec.id to log.normalizedMs(offsets) },
        case.name,
      )
    }
  }

  private fun LogCase.toLog(): TrailblazeLog {
    val domain = TrailblazeClockDomain.entries.single { it.wireName == clock }
    val timestamp = Instant.fromEpochMilliseconds(timestampMs)
    return when (type) {
      "tool" -> TrailblazeLog.TrailblazeToolLog(
        trailblazeTool = OtherTrailblazeTool(toolName = "tapOn"),
        toolName = "tapOn",
        successful = true,
        traceId = null,
        durationMs = durationMs ?: 0L,
        session = SESSION,
        timestamp = timestamp,
        deviceName = deviceName,
        clock = domain,
        hostReceivedAt = hostReceivedAtMs?.let { Instant.fromEpochMilliseconds(it) },
      )
      "status" -> TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = SessionStatus.Unknown,
        session = SESSION,
        timestamp = timestamp,
        clock = domain,
        hostReceivedAt = hostReceivedAtMs?.let { Instant.fromEpochMilliseconds(it) },
      )
      else -> fail("Unknown fixture log type '$type' (expected 'tool' or 'status')")
    }
  }

  private fun toolNamed(deviceName: String?) = TrailblazeLog.TrailblazeToolLog(
    trailblazeTool = OtherTrailblazeTool(toolName = "tapOn"),
    toolName = "tapOn",
    successful = true,
    traceId = null,
    durationMs = 0L,
    session = SESSION,
    timestamp = Instant.fromEpochMilliseconds(0),
    deviceName = deviceName,
    clock = TrailblazeClockDomain.DEVICE,
  )

  /** Walk up from the JVM working dir to the repo-root-anchored fixture, as the sibling parity tests do. */
  private fun locate(repoRelativePath: String): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
      val candidate = File(dir, repoRelativePath)
      if (candidate.isFile) return candidate
      dir = dir.parentFile
    }
    fail("Could not locate $repoRelativePath by walking up from ${System.getProperty("user.dir")}.")
  }

  private companion object {
    val SESSION = SessionId("clock-normalization-parity")
  }
}
