package xyz.block.trailblaze.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import xyz.block.trailblaze.host.HostRunTraceRecording
import xyz.block.trailblaze.report.trace.SessionTraceFile
import xyz.block.trailblaze.tracing.TraceLevel
import xyz.block.trailblaze.tracing.TrailblazeTracer
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A CLI session never ends, so the per-command export is the only thing that ever writes its
 * `trace.json`. These pin when a command's spans land in that file and when they must not.
 */
class CliCommandTraceTest {

  private val logsDir: File = createTempDirectory("cli-command-trace").toFile()
  private val traceFileFor: (String) -> File = { File(File(logsDir, it), SessionTraceFile.FILE_NAME) }

  @BeforeTest
  fun reset() {
    HostRunTraceRecording.resetForTest()
    TrailblazeTracer.clear()
  }

  @AfterTest
  fun tearDown() {
    HostRunTraceRecording.resetForTest()
    TrailblazeTracer.clear()
    logsDir.deleteRecursively()
  }

  private fun spanNames(sessionId: String): List<String> {
    CliCommandTrace.awaitWritesForTest()
    return SessionTraceFile.read(traceFileFor(sessionId)).mapNotNull { (it["name"] as? JsonPrimitive)?.content }
  }

  private fun bufferedSpanNames(): List<String> =
    Json.parseToJsonElement(TrailblazeTracer.exportJson()).jsonArray
      .mapNotNull { (it.jsonObject["name"] as? JsonPrimitive)?.content }

  private fun runTool(sessionId: String?, level: TraceLevel? = null) =
    CliCommandTrace.record("tool", mapOf("tool" to "tap"), level, traceFileFor) {
      TrailblazeTracer.trace("mcpCall", CliCommandTrace.CATEGORY) {
        sessionId?.let(CliCommandTrace::noteSession)
      }
      "result"
    }

  @Test
  fun `a command that drove a session files its spans in that session's trace`() {
    assertEquals("result", runTool("session-a"))

    assertEquals(listOf("mcpCall", "tool"), spanNames("session-a").distinct().sorted())
  }

  @Test
  fun `consecutive commands on one session accumulate in its trace`() {
    runTool("session-a")
    runTool("session-a")

    assertEquals(2, spanNames("session-a").count { it == "tool" })
  }

  @Test
  fun `each command's spans go only to the session it drove`() {
    runTool("session-a")
    runTool("session-b")

    assertEquals(1, spanNames("session-a").count { it == "tool" })
    assertEquals(1, spanNames("session-b").count { it == "tool" })
  }

  @Test
  fun `a command that never reached a session writes nothing`() {
    runTool(sessionId = null)
    runTool("session-a")

    // The first command's spans were not carried into the next session's file either.
    assertEquals(1, spanNames("session-a").count { it == "tool" })
  }

  @Test
  fun `a command during a trail run joins that run's recording`() {
    // The recorder is process-wide: draining it here would take the run's own spans with it.
    HostRunTraceRecording.begin()
    TrailblazeTracer.trace("trail-run-tool") { }

    runTool("session-a")

    assertTrue(spanNames("session-a").isEmpty())
    assertEquals(listOf("mcpCall", "tool", "trail-run-tool"), bufferedSpanNames().distinct().sorted())
  }

  @Test
  fun `a command that outlives the trail run it joined files its own tail`() {
    // The run drains the shared recorder when it ends, so the command's later spans are the only
    // copy left, and nobody else will export them.
    HostRunTraceRecording.begin()

    CliCommandTrace.record("tool", emptyMap(), level = null, traceFileFor) {
      TrailblazeTracer.trace("beforeTheRunEnded") { }
      HostRunTraceRecording.endAndDrain()
      TrailblazeTracer.trace("afterTheRunEnded") { CliCommandTrace.noteSession("session-a") }
    }

    assertEquals(listOf("afterTheRunEnded", "tool"), spanNames("session-a").distinct().sorted())
  }

  @Test
  fun `a command that finishes while a trail run uploads is the last one out`() {
    // The run takes its spans and stops counting itself in one step, before its upload. The
    // command ending during that upload must see itself as last, or its tail is stranded.
    HostRunTraceRecording.begin()
    CliCommandTrace.record("tool", emptyMap(), level = null, traceFileFor) {
      HostRunTraceRecording.endAndDrain()
      TrailblazeTracer.trace("duringTheUpload") { CliCommandTrace.noteSession("session-a") }
    }

    assertEquals(listOf("duringTheUpload", "tool"), spanNames("session-a").distinct().sorted())
    assertTrue(bufferedSpanNames().isEmpty(), "nothing left stranded in the recorder")
  }

  @Test
  fun `an unrecognized forwarded level is reported and records at normal`() {
    val warnings = mutableListOf<String>()

    assertEquals(TraceLevel.NORMAL, CliCommandTrace.levelFor("verbsoe") { warnings += it })
    assertEquals(1, warnings.size)
    assertTrue("verbsoe" in warnings.single(), warnings.single())
  }

  @Test
  fun `an absent or blank forwarded level keeps the daemon's without a warning`() {
    val noWarning: (String) -> Unit = { error("unexpected warning: $it") }

    assertEquals(null, CliCommandTrace.levelFor(null, noWarning))
    assertEquals(null, CliCommandTrace.levelFor("  ", noWarning))
    assertEquals(TraceLevel.VERBOSE, CliCommandTrace.levelFor("verbose", noWarning))
  }

  @Test
  fun `a caller that asked for no tracing gets none`() {
    runTool("session-a", level = TraceLevel.OFF)

    assertTrue(spanNames("session-a").isEmpty())
  }
}
