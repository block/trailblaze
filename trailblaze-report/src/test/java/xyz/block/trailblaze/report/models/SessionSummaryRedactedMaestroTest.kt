package xyz.block.trailblaze.report.models

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

class SessionSummaryRedactedMaestroTest {
  @Test
  fun `redacted enum command remains visible in timeline and grouped report`() {
    val command = buildJsonObject {
      putJsonObject("pressKeyCommand") {
        put("code", "<redacted>")
        put("optional", false)
      }
    }
    val log = TrailblazeLog.MaestroCommandLog(
      maestroCommandJsonObj = command,
      traceId = null,
      successful = true,
      trailblazeToolResult = TrailblazeToolResult.Success(),
      session = SessionId("fixture"),
      timestamp = Clock.System.now(),
      durationMs = 1,
    )

    val summary = SessionSummary.fromLogs("fixture", listOf(log), isStandaloneFileReport = true)

    val group = summary.eventGroups.single()
    assertEquals("[$command]", group.yaml)
    assertContains((group.events.single() as SessionEvent.MaestroCommand).code, "<redacted>")
  }

  @Test
  fun `redacted numeric command remains visible without inventing a different count`() {
    val command = buildJsonObject {
      putJsonObject("eraseTextCommand") {
        put("charactersToErase", "<redacted>")
        put("optional", false)
      }
    }
    val log = TrailblazeLog.MaestroCommandLog(
      maestroCommandJsonObj = command,
      traceId = null,
      successful = true,
      trailblazeToolResult = TrailblazeToolResult.Success(),
      session = SessionId("fixture"),
      timestamp = Clock.System.now(),
      durationMs = 1,
    )

    val summary = SessionSummary.fromLogs("fixture", listOf(log), isStandaloneFileReport = true)

    assertEquals("[$command]", summary.eventGroups.single().yaml)
    assertContains((summary.eventGroups.single().events.single() as SessionEvent.MaestroCommand).code, "<redacted>")
  }
}
