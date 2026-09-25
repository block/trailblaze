package xyz.block.trailblaze

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
import xyz.block.trailblaze.mobile.tools.AdbShellTrailblazeTool
import xyz.block.trailblaze.mobile.tools.AndroidWriteBytesToFileTrailblazeTool
import xyz.block.trailblaze.toolcalls.REDACTED_TOOL_ARG_PLACEHOLDER
import xyz.block.trailblaze.toolcalls.RawArgumentTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
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
 *  3. `logToolExecution`'s RESOLVED payload after memory interpolation: a secret that reached
 *     the tool through an ordinary `{{token}}` is absent from the authored form and written in
 *     by resolution, so the executed instance's declared values must be masked again after it.
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

  /**
   * The authored form carries `{{token}}`, not the secret, so masking the raw payload finds
   * nothing. Resolution then writes the real value from ORDINARY (non-sensitive) memory into the
   * resolved payload — which is the executed tool's `secrets` value and must be masked there too,
   * after resolution. Ordinary memory is the realistic case: a fetched credential lands via
   * `remember`, not `rememberSensitive`, unless the author knew to say so.
   *
   * Both `logToolExecution` overloads build the resolved payload independently, so both are
   * driven: the context-carrying one is what the dispatch boundary calls.
   */
  @Test
  fun contextCarryingLogToolExecutionMasksASecretThatArrivedThroughOrdinaryMemory() {
    assertMemorySuppliedSecretIsMaskedAfterResolution { agent, executed, authored ->
      agent.logToolExecution(
        tool = executed,
        timeBeforeExecution = Clock.System.now(),
        context = TrailblazeToolExecutionContext(
          screenState = null,
          traceId = null,
          trailblazeDeviceInfo = agent.trailblazeDeviceInfoProvider(),
          sessionProvider = agent.sessionProvider,
          trailblazeLogger = agent.trailblazeLogger,
          memory = agent.memory,
        ),
        result = TrailblazeToolResult.Success(message = "Result: Parcel(00000000)"),
        rawTool = authored,
      )
    }
  }

  @Test
  fun traceIdLogToolExecutionMasksASecretThatArrivedThroughOrdinaryMemory() {
    assertMemorySuppliedSecretIsMaskedAfterResolution { agent, executed, authored ->
      agent.logToolExecution(
        tool = executed,
        timeBeforeExecution = Clock.System.now(),
        traceId = TraceId.generate(TraceId.Companion.TraceOrigin.TOOL),
        result = TrailblazeToolResult.Success(message = "Result: Parcel(00000000)"),
        rawTool = authored,
      )
    }
  }

  private fun assertMemorySuppliedSecretIsMaskedAfterResolution(
    log: (agent: CapturingAgentContext, executed: AdbShellTrailblazeTool, authored: AuthoredRawArgsTool) -> Unit,
  ) {
    val context = CapturingAgentContext()
    context.memory.remember("token", "tok-abc123")
    val executed = AdbShellTrailblazeTool(
      command = listOf("service", "call", "com.vendor.deviceauth", "1", "s16", "tok-abc123"),
      secrets = listOf("tok-abc123"),
    )
    val authored = AuthoredRawArgsTool(
      instanceToolName = "android_adbShell",
      rawToolArguments = buildJsonObject {
        putJsonArray("command") {
          listOf("service", "call", "com.vendor.deviceauth", "1", "s16", "{{token}}").forEach { add(JsonPrimitive(it)) }
        }
        putJsonArray("secrets") { add(JsonPrimitive("{{token}}")) }
      },
    )

    log(context, executed, authored)

    val toolLog = context.emitted.filterIsInstance<TrailblazeLog.TrailblazeToolLog>().single()
    val resolvedCommand = toolLog.trailblazeTool.raw["command"] as JsonArray
    assertEquals(JsonPrimitive("service"), resolvedCommand.first())
    assertEquals(JsonPrimitive(REDACTED_TOOL_ARG_PLACEHOLDER), resolvedCommand.last())
    assertEquals(JsonArray(listOf(JsonPrimitive(REDACTED_TOOL_ARG_PLACEHOLDER))), toolLog.trailblazeTool.raw["secrets"])
    // The authored form keeps its token — that is what a recording is regenerated from.
    assertEquals(JsonPrimitive("{{token}}"), (toolLog.rawTrailblazeTool!!.raw["command"] as JsonArray).last())
    listOfNotNull(toolLog.trailblazeTool, toolLog.rawTrailblazeTool).forEach { payload ->
      assertTrue(!payload.raw.toString().contains("tok-abc123"))
    }
  }
}
