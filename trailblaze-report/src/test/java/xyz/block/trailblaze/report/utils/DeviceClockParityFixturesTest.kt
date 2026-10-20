package xyz.block.trailblaze.report.utils

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Test
import xyz.block.trailblaze.logs.model.DeviceClockSample
import xyz.block.trailblaze.logs.model.DeviceClockSync

/**
 * Cross-language contract for converting a device-clock stamp onto the host timeline.
 * `device-clock-parity-fixtures.json` drives both this test (the Kotlin [DeviceClockSync]) and
 * `device-clock-sync.test.ts` (the TS mirror the run report and profiler use). Change a rule in
 * both implementations AND the fixture in one change.
 */
class DeviceClockParityFixturesTest {

  @Serializable private data class TightestCase(val samples: List<DeviceClockSample>, val index: Int?)

  @Serializable
  private data class ConversionCase(
    val deviceMs: Long,
    val deviceId: String?,
    val hostMs: Long?,
    val stream: String? = null,
  )

  @Serializable
  private data class ConversionGroup(
    val name: String,
    val sync: JsonElement,
    val cases: List<ConversionCase>,
  )

  @Serializable
  private data class Fixtures(val tightest: List<TightestCase>, val conversions: List<ConversionGroup>)

  private val fixtures: Fixtures by lazy {
    val file =
      locate("trailblaze-report/src/main/resources/xyz/block/trailblaze/report/device-clock-parity-fixtures.json")
    Json { ignoreUnknownKeys = true }.decodeFromString<Fixtures>(file.readText())
  }

  @Test
  fun `the tightest sample agrees with the shared parity fixtures`() {
    check(fixtures.tightest.isNotEmpty())
    fixtures.tightest.forEach { case ->
      assertEquals(case.index?.let { case.samples[it] }, DeviceClockSample.tightest(case.samples))
    }
  }

  @Test
  fun `device-to-host conversion agrees with the shared parity fixtures`() {
    check(fixtures.conversions.isNotEmpty())
    fixtures.conversions.forEach { group ->
      val sync = DeviceClockSync.decodeOrNull(group.sync.toString()) ?: fail("${group.name}: unreadable sync")
      group.cases.forEach { case ->
        val hostMs =
          case.stream?.let { sync.toHostMsForStream(case.deviceMs, it) }
            ?: sync.toHostMs(case.deviceMs, case.deviceId).takeIf { case.stream == null }
        assertEquals(case.hostMs, hostMs, "${group.name}: $case")
      }
    }
  }

  /** Walks up from the JVM working dir, same anchor pattern as `SessionEventsParityFixturesTest`. */
  private fun locate(repoRelativePath: String): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
      val candidate = File(dir, repoRelativePath)
      if (candidate.isFile) return candidate
      dir = dir.parentFile
    }
    fail("Could not locate $repoRelativePath by walking up from ${System.getProperty("user.dir")}.")
  }
}
