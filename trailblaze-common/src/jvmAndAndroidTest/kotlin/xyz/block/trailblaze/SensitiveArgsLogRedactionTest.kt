package xyz.block.trailblaze

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.LogEmitter
import xyz.block.trailblaze.logs.client.ScreenStateLogger
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mobile.tools.AndroidWriteBytesToFileTrailblazeTool
import xyz.block.trailblaze.toolcalls.REDACTED_TOOL_ARG_PLACEHOLDER
import xyz.block.trailblaze.toolcalls.RawArgumentTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.toLogPayload

/**
 * Pins the log-encode redaction contract for [xyz.block.trailblaze.toolcalls.SensitiveArgsTrailblazeTool]
 * at the two places secrets could leak into persisted session logs (which ship as CI
 * artifacts):
 *
 *  1. `toLogPayload()` — the single encode boundary every `TrailblazeToolLog` payload passes
 *     through — must mask a sensitive-args tool's declared values.
 *  2. `logToolExecution`'s authored `rawTool` payload: the authored form is often a raw-args
 *     wrapper that does NOT implement the marker itself, so the executed instance's declared
 *     args must be applied to it too.
 *
 * Only observable log output is asserted (the emitted [TrailblazeLog.TrailblazeToolLog] / the
 * returned payload), never internals — the execution/wire encode is deliberately NOT redacted
 * and stays covered by the tools' own execute tests.
 */
class SensitiveArgsLogRedactionTest {

  private val secretBase64 = "c2VjcmV0LXNlc3Npb24tdG9rZW4="

  /** Authored raw-args wrapper, as the dispatch boundary produces for a scripted `ctx.tools` call. */
  private data class AuthoredRawArgsTool(
    override val instanceToolName: String,
    override val rawToolArguments: kotlinx.serialization.json.JsonObject,
  ) : RawArgumentTrailblazeTool

  private class CapturingAgentContext : TrailblazeAgentContext {
    val emitted = mutableListOf<TrailblazeLog>()
    override val trailblazeLogger = TrailblazeLogger(
      logEmitter = LogEmitter { log -> emitted.add(log) },
      screenStateLogger = ScreenStateLogger { "" },
    )
    override val trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo = {
      TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "fixture-device",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        widthPixels = 1080,
        heightPixels = 1920,
      )
    }
    override val sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("fixture-session"), startTime = Clock.System.now())
    }
    override val memory = AgentMemory()
  }

  @Test
  fun toLogPayloadMasksDeclaredSensitiveArgs() {
    val payload = AndroidWriteBytesToFileTrailblazeTool(
      devicePath = "/data/local/tmp/seed.json",
      base64Content = secretBase64,
    ).toLogPayload()

    assertEquals(JsonPrimitive(REDACTED_TOOL_ARG_PLACEHOLDER), payload.raw["base64Content"])
    assertEquals(JsonPrimitive("/data/local/tmp/seed.json"), payload.raw["devicePath"])
  }

  @Test
  fun logToolExecutionMasksBothResolvedAndAuthoredPayloads() {
    val context = CapturingAgentContext()
    val executed = AndroidWriteBytesToFileTrailblazeTool(
      devicePath = "/data/local/tmp/seed.json",
      base64Content = secretBase64,
    )
    // The authored wrapper carries the secret literally and does not implement the marker —
    // exactly the shape that would leak the full seeded session into shipped tool logs.
    val authored = AuthoredRawArgsTool(
      instanceToolName = "android_writeBytesToFile",
      rawToolArguments = buildJsonObject {
        put("devicePath", "/data/local/tmp/seed.json")
        put("base64Content", secretBase64)
      },
    )

    context.logToolExecution(
      tool = executed,
      timeBeforeExecution = Clock.System.now(),
      traceId = TraceId.generate(TraceId.Companion.TraceOrigin.TOOL),
      result = TrailblazeToolResult.Success(message = "Wrote 20 bytes."),
      rawTool = authored,
    )

    val toolLog = context.emitted.filterIsInstance<TrailblazeLog.TrailblazeToolLog>().single()
    val persistedPayloads = listOfNotNull(toolLog.trailblazeTool, toolLog.rawTrailblazeTool)
    assertTrue(persistedPayloads.isNotEmpty())
    persistedPayloads.forEach { payload ->
      assertEquals(JsonPrimitive(REDACTED_TOOL_ARG_PLACEHOLDER), payload.raw["base64Content"])
      assertEquals(JsonPrimitive("/data/local/tmp/seed.json"), payload.raw["devicePath"])
      // Belt-and-braces: the secret must not survive anywhere in the persisted payload JSON.
      assertTrue(!payload.raw.toString().contains(secretBase64))
    }
  }
}
