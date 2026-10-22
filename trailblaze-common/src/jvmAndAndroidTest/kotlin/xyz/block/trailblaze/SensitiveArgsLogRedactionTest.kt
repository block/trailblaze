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

  @Test
  fun nestedLogMasksPreCallAndCurrentValuesWithoutChangingExecutionPayload() {
    val agent = CapturingAgentContext()
    val oldValue = "FAKE_TEST_ONLY_PRE_CALL_VALUE"
    val newValue = "FAKE_TEST_ONLY_POST_CALL_VALUE"
    val before = AgentMemory().apply { rememberSensitive("pin", oldValue) }
    agent.memory.rememberSensitive("pin", newValue)
    val payload = buildJsonObject {
      put("old", oldValue)
      put("new", newValue)
      put("control", "visible")
    }
    val tool = NestedConditionalLogFixture(payload)
    val context =
      TrailblazeToolExecutionContext(
        screenState = null,
        traceId = null,
        trailblazeDeviceInfo = agent.trailblazeDeviceInfoProvider(),
        sessionProvider = agent.sessionProvider,
        trailblazeLogger = agent.trailblazeLogger,
        memory = agent.memory,
      )
    context.nestedDispatchDepth.incrementAndGet()
    agent.logToolExecution(
      tool = tool,
      timeBeforeExecution = Clock.System.now(),
      context = context,
      result =
        TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "$oldValue $newValue",
          structuredPayload = payload,
        ),
      rawTool = AuthoredRawArgsTool("block_runIf", payload),
      sensitiveMemoryBeforeExecution = before,
    )
    val log = agent.emitted.filterIsInstance<TrailblazeLog.TrailblazeToolLog>().single()
    val persisted =
      listOfNotNull(
        log.trailblazeTool.raw.toString(),
        log.rawTrailblazeTool?.raw?.toString(),
        log.exceptionMessage,
        log.errorPayload?.toString(),
      )
    assertTrue(
      persisted.none { it.contains(oldValue) || it.contains(newValue) },
      "Invocation log fields must mask both pre-call and current sensitive values",
    )
    assertEquals(payload, tool.rawToolArguments)
    assertEquals(newValue, agent.memory.variables["pin"])

    // A later invocation still masks current sensitive memory and retains harmless arguments.
    agent.logToolExecution(tool, Clock.System.now(), context, TrailblazeToolResult.Success())
    val next = agent.emitted.filterIsInstance<TrailblazeLog.TrailblazeToolLog>().last()
    assertEquals(JsonPrimitive("visible"), next.trailblazeTool.raw["control"])
    assertTrue(!next.trailblazeTool.raw.toString().contains(newValue))
  }

  @Test
  fun forwardedLogMasksOverlappingOldAndCurrentValues() {
    assertOverlappingInvocationLogIsMasked(forwarded = true, delete = false)
  }

  @Test
  fun nestedLogMasksOverlappingOldAndCurrentValues() {
    assertOverlappingInvocationLogIsMasked(forwarded = false, delete = false)
  }

  @Test
  fun forwardedLogMasksDeletedPreCallValue() {
    assertOverlappingInvocationLogIsMasked(forwarded = true, delete = true)
  }

  private fun assertOverlappingInvocationLogIsMasked(forwarded: Boolean, delete: Boolean) {
    val agent = CapturingAgentContext()
    val before = AgentMemory().apply { rememberSensitive("pin", "4921") }
    agent.memory.rememberSensitive("pin", "4921")
    if (delete) agent.memory.delete("pin") else agent.memory.remember("pin", "1")
    val payload = buildJsonObject {
      put("old", "4921")
      put("current", if (delete) "public" else "1")
      put("control", "visible")
      put("nested", JsonArray(listOf(buildJsonObject { put("4921", "old=4921") })))
    }
    val expected = buildJsonObject {
      put("old", "{{pin}}")
      put("current", if (delete) "public" else "{{pin}}")
      put("control", "visible")
      put("nested", JsonArray(listOf(buildJsonObject { put("{{pin}}", "old={{pin}}") })))
    }
    val tool = NestedConditionalLogFixture(payload)
    val context = TrailblazeToolExecutionContext(
      screenState = null,
      traceId = null,
      trailblazeDeviceInfo = agent.trailblazeDeviceInfoProvider(),
      sessionProvider = agent.sessionProvider,
      trailblazeLogger = agent.trailblazeLogger,
      memory = agent.memory,
    )
    context.nestedDispatchDepth.incrementAndGet()
    agent.logToolExecution(
      tool = tool,
      timeBeforeExecution = Clock.System.now(),
      context = context,
      result = TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "old=4921 current=" + if (delete) "public" else "1",
        structuredPayload = payload,
      ),
      rawTool = if (forwarded) AuthoredRawArgsTool("block_runIf", payload) else null,
      sensitiveMemoryBeforeExecution = before,
    )
    val log = agent.emitted.filterIsInstance<TrailblazeLog.TrailblazeToolLog>().single()
    // Assert the emitted persistence boundary, including nested values and object member names.
    assertEquals(expected, log.trailblazeTool.raw)
    log.rawTrailblazeTool?.let { assertEquals(expected, it.raw) }
    assertEquals(expected, log.errorPayload)
    assertEquals("old={{pin}} current=" + if (delete) "public" else "{{pin}}", log.exceptionMessage)
    val persisted = xyz.block.trailblaze.logs.client.TrailblazeJsonInstance.encodeToString(
      TrailblazeLog.TrailblazeToolLog.serializer(), log,
    )
    val persistedFields = listOfNotNull(
      log.trailblazeTool.raw.toString(), log.rawTrailblazeTool?.raw?.toString(),
      log.exceptionMessage, log.errorPayload?.toString(),
    )
    assertTrue(persistedFields.none { it.contains("492") }, "No old secret fragment may survive persistence")
    assertTrue(persisted.contains("{{pin}}"), "The serialized tool log must retain the redaction token")
    assertTrue(!log.trailblazeTool.raw.toString().contains("1"), "No overlapping secret suffix may survive persistence")
    assertEquals(payload, tool.rawToolArguments)
    assertEquals(if (delete) null else "1", agent.memory.variables["pin"])
    assertEquals("4921", before.variables["pin"])
  }

  /** Authored raw-args wrapper, as the dispatch boundary produces for a scripted `ctx.tools` call. */
  private data class AuthoredRawArgsTool(
    override val instanceToolName: String,
    override val rawToolArguments: kotlinx.serialization.json.JsonObject,
  ) : RawArgumentTrailblazeTool

  @xyz.block.trailblaze.toolcalls.TrailblazeToolClass(name = "block_runIf")
  private data class NestedConditionalLogFixture(
    override val rawToolArguments: kotlinx.serialization.json.JsonObject
  ) : RawArgumentTrailblazeTool, xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool {
    override val instanceToolName: String = "block_runIf"

    override suspend fun execute(
      toolExecutionContext: TrailblazeToolExecutionContext
    ): TrailblazeToolResult = error("This log-boundary fixture must never execute")
  }

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

  @Test
  fun authoredSecretWithInterpolationIsMaskedBeforeResolvedValueMasking() {
    val agent = CapturingAgentContext()
    agent.memory.putArg("account", JsonPrimitive("alice"))
    val authoredSecret = "token=private;account={{args.account}}"
    val resolvedSecret = "token=private;account=alice"
    val authored = AuthoredRawArgsTool(
      instanceToolName = "android_adbShell",
      rawToolArguments = buildJsonObject {
        putJsonArray("command") { listOf("fixture-command", authoredSecret).forEach { add(JsonPrimitive(it)) } }
        putJsonArray("secrets") { add(JsonPrimitive(authoredSecret)) }
      },
    )
    val executed = AdbShellTrailblazeTool(
      command = listOf("fixture-command", resolvedSecret), secrets = listOf(resolvedSecret),
    )

    agent.logToolExecution(
      tool = executed,
      timeBeforeExecution = Clock.System.now(),
      context = TrailblazeToolExecutionContext(
        screenState = null, traceId = null,
        trailblazeDeviceInfo = agent.trailblazeDeviceInfoProvider(),
        sessionProvider = agent.sessionProvider,
        trailblazeLogger = agent.trailblazeLogger,
        memory = agent.memory,
      ),
      result = TrailblazeToolResult.Success(),
      rawTool = authored,
    )

    val log = agent.emitted.filterIsInstance<TrailblazeLog.TrailblazeToolLog>().single()
    assertTrue(!log.rawTrailblazeTool.toString().contains("private"))
    assertTrue(!log.trailblazeTool.toString().contains("private"))
  }

  /**
   * A nested `ctx.tools.X()` call (a `runIf` branch, a composing tool) logs with no authored form:
   * its caller already resolved the arguments. A `rememberSensitive` value in them must still be
   * masked back to its token before the log is persisted.
   */
  @Test
  fun nestedCallMasksASensitiveMemoryValueItsCallerResolved() {
    val agent = CapturingAgentContext()
    agent.memory.rememberSensitive("pin", "4921")
    val context = TrailblazeToolExecutionContext(
      screenState = null,
      traceId = null,
      trailblazeDeviceInfo = agent.trailblazeDeviceInfoProvider(),
      sessionProvider = agent.sessionProvider,
      trailblazeLogger = agent.trailblazeLogger,
      memory = agent.memory,
    )
    context.nestedDispatchDepth.incrementAndGet()

    agent.logToolExecution(
      tool = AdbShellTrailblazeTool(command = listOf("input", "text", "4921")),
      timeBeforeExecution = Clock.System.now(),
      context = context,
      result = TrailblazeToolResult.Success(message = "ok"),
      rawTool = null,
    )

    val toolLog = agent.emitted.filterIsInstance<TrailblazeLog.TrailblazeToolLog>().single()
    assertEquals(JsonPrimitive("{{pin}}"), (toolLog.trailblazeTool.raw["command"] as JsonArray).last())
    assertTrue(!toolLog.trailblazeTool.raw.toString().contains("4921"))
  }

  /**
   * An arg that captured a secret stays secret after the memory key it came from is deleted, so a
   * nested call that received its resolved value masks it to the arg's own token.
   */
  @Test
  fun nestedCallMasksASensitiveArgAfterItsSourceKeyIsDeleted() {
    val agent = CapturingAgentContext()
    agent.memory.rememberSensitive("pin", "4921")
    agent.memory.putArg("vaultCode", JsonPrimitive("4921"))
    agent.memory.delete("pin")
    val context = TrailblazeToolExecutionContext(
      screenState = null,
      traceId = null,
      trailblazeDeviceInfo = agent.trailblazeDeviceInfoProvider(),
      sessionProvider = agent.sessionProvider,
      trailblazeLogger = agent.trailblazeLogger,
      memory = agent.memory,
    )
    context.nestedDispatchDepth.incrementAndGet()

    agent.logToolExecution(
      tool = AdbShellTrailblazeTool(command = listOf("input", "text", "4921")),
      timeBeforeExecution = Clock.System.now(),
      context = context,
      result = TrailblazeToolResult.Success(message = "ok"),
      rawTool = null,
    )

    val toolLog = agent.emitted.filterIsInstance<TrailblazeLog.TrailblazeToolLog>().single()
    assertEquals(JsonPrimitive("{{args.vaultCode}}"), (toolLog.trailblazeTool.raw["command"] as JsonArray).last())
    assertTrue(!toolLog.trailblazeTool.raw.toString().contains("4921"))
  }

  @Test
  fun nestedConditionalLogMasksObjectArgScalarAfterSourceDeletion() {
    val agent = CapturingAgentContext()
    val fakeSecret = "FAKE_TEST_ONLY_NOT_A_REAL_PIN_OBJECT_ARG"
    agent.memory.rememberSensitive("pin", fakeSecret)
    agent.memory.seedArgs(mapOf("credentials" to buildJsonObject { put("pin", fakeSecret) }))
    assertTrue("credentials" in agent.memory.sensitiveArgNames)
    agent.memory.delete("pin")
    assertTrue(!agent.memory.has("pin"))
    assertTrue(agent.memory.interpolateVariables("{{args.credentials.pin}}") == fakeSecret)

    // A nested conditional carries its whole resolved tree into the real logging boundary,
    // including a then branch which was not selected. No device or tool execution is needed.
    val authored = buildJsonObject {
      put(
        "condition",
        buildJsonObject {
          put(
            "selector",
            buildJsonObject {
              put("androidAccessibility", buildJsonObject { put("textRegex", "absent-test-only") })
            },
          )
        },
      )
      putJsonArray("then") {
        add(
          buildJsonObject {
            put("inputText", buildJsonObject { put("text", "{{args.credentials.pin}}") })
          }
        )
      }
    }
    val resolved =
      agent.memory.interpolateVariablesInJson(authored) as kotlinx.serialization.json.JsonObject
    val context =
      TrailblazeToolExecutionContext(
        screenState = null,
        traceId = null,
        trailblazeDeviceInfo = agent.trailblazeDeviceInfoProvider(),
        sessionProvider = agent.sessionProvider,
        trailblazeLogger = agent.trailblazeLogger,
        memory = agent.memory,
      )
    context.nestedDispatchDepth.incrementAndGet()
    agent.logToolExecution(
      tool = NestedConditionalLogFixture(resolved),
      timeBeforeExecution = Clock.System.now(),
      context = context,
      result = TrailblazeToolResult.Success(message = "runIf: condition=false ran=else (0 tools)"),
      rawTool = null,
    )
    val persisted = agent.emitted.filterIsInstance<TrailblazeLog.TrailblazeToolLog>().single()
    assertTrue(
      !persisted.trailblazeTool.raw.toString().contains(fakeSecret),
      "Persisted nested conditional log contains a sensitive object scalar",
    )
    assertTrue(persisted.trailblazeTool.raw.toString().contains("{{args.credentials.pin}}"))
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
