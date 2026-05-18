package xyz.block.trailblaze.scripting.subprocess

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlinx.datetime.Clock
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import maestro.orchestra.Command
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.model.TraceId.Companion.TraceOrigin
import xyz.block.trailblaze.model.ResolvedTarget
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.scripting.mcp.TrailblazeContextEnvelope
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import kotlin.reflect.KClass
import kotlin.test.Test

/**
 * Locks in the agent → execution-context → envelope-writer chain that
 * `BundleTrailblazeTool` exercises on-device.
 *
 * Production-critical for Square card-reader scripted tools: those run in-process on a
 * device farm via the on-device QuickJS bundle path, with no host. `_meta.trailblaze.target`
 * is the only path by which a scripted tool can read `ctx.target.resolveAppId()` in that
 * deployment — and the envelope writer emits the `target` block only when the execution
 * context carries `resolvedTarget != null`. So `MaestroTrailblazeAgent.buildExecutionContext`
 * forwarding the agent's `resolvedTarget` field into the context is load-bearing.
 *
 * The existing `TrailblazeContextEnvelopeTest` covers the writer hop in isolation, and
 * `TrailblazeContextEnvelope` is well-unit-tested for the JSON shape. What this file fills
 * is the **wiring** hop — proving the agent → context propagation actually happens, so a
 * future refactor of `MaestroTrailblazeAgent`'s constructor or `buildExecutionContext` body
 * can't silently regress the on-device path. A unit test only on the writer wouldn't catch a
 * wiring drop, and on-device verification requires booting an emulator.
 */
class MaestroAgentContextChainTest {

  private class FixtureTarget : TrailblazeHostAppTarget(id = "fixture", displayName = "Fixture") {
    override fun getPossibleAppIdsForPlatform(
      platform: TrailblazeDevicePlatform,
    ): List<String>? = when (platform) {
      TrailblazeDevicePlatform.ANDROID -> listOf("com.example.dev", "com.example")
      else -> null
    }

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()
  }

  /** Minimal concrete [MaestroTrailblazeAgent] for exercising the inherited build path. */
  private class FixtureAgent(
    resolvedTarget: ResolvedTarget? = null,
    appId: String? = null,
  ) : MaestroTrailblazeAgent(
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    trailblazeDeviceInfoProvider = {
      TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "fixture-device",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
        widthPixels = 1080,
        heightPixels = 2400,
      )
    },
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("fixture-session"), startTime = Clock.System.now())
    },
    resolvedTarget = resolvedTarget,
    appId = appId,
  ) {
    override suspend fun executeMaestroCommands(
      commands: List<Command>,
      traceId: TraceId?,
    ): TrailblazeToolResult = TrailblazeToolResult.Success()

    /**
     * Public forwarder so the test class (which lives outside this `private class`) can drive
     * the inherited protected method without needing a same-class call site. Pure delegation;
     * no logic of its own.
     */
    fun publicBuildExecutionContext(traceId: TraceId) =
      buildExecutionContext(traceId = traceId, screenState = null, screenStateProvider = null)
  }

  @Test fun `buildExecutionContext forwards resolvedTarget and appId when set`() {
    val target = FixtureTarget()
    val agent = FixtureAgent(
      resolvedTarget = ResolvedTarget(
        target = target,
        deviceId = TrailblazeDeviceId(
          instanceId = "fixture-device",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
      ),
      appId = "com.example.dev",
    )

    val context = agent.publicBuildExecutionContext(TraceId.Companion.generate(TraceOrigin.TOOL))

    assertThat(context.resolvedTarget).isNotNull()
    assertThat(context.resolvedTarget!!.id).isEqualTo("fixture")
    assertThat(context.appId).isEqualTo("com.example.dev")
  }

  @Test fun `buildExecutionContext leaves resolvedTarget and appId null when unset`() {
    // The agent's defaults (null/null) flow through to the context — same shape unit-test
    // fixtures and target-agnostic rules produce today, kept here as the no-regression case.
    val agent = FixtureAgent()
    val context = agent.publicBuildExecutionContext(TraceId.Companion.generate(TraceOrigin.TOOL))

    assertThat(context.resolvedTarget).isNull()
    assertThat(context.appId).isNull()
  }

  @Test fun `agent-built context flows through envelope writer to produce target block`() {
    // End-to-end on the Kotlin side: agent → buildExecutionContext → envelope writer. This is
    // the chain BundleTrailblazeTool actually exercises on-device. Without this test, a wiring
    // regression between the agent and the writer would slip through the per-layer tests and
    // only surface as a runtime "ctx.target undefined" failure on a real device.
    val target = FixtureTarget()
    val agent = FixtureAgent(
      resolvedTarget = ResolvedTarget(
        target = target,
        deviceId = TrailblazeDeviceId(
          instanceId = "fixture-device",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
      ),
      appId = "com.example.dev",
    )
    val context = agent.publicBuildExecutionContext(TraceId.Companion.generate(TraceOrigin.TOOL))

    val envelope = TrailblazeContextEnvelope.buildMetaTrailblaze(
      context = context,
      baseUrl = null,
      sessionId = SessionId("fixture-session"),
      invocationId = "fixture-invocation",
      runtime = TrailblazeContextEnvelope.RUNTIME_ONDEVICE,
    )
    val targetBlock = envelope["target"]!!.jsonObject
    assertThat(targetBlock["id"]!!.jsonPrimitive.content).isEqualTo("fixture")
    assertThat(targetBlock["appId"]!!.jsonPrimitive.content).isEqualTo("com.example.dev")
    val appIds = targetBlock["appIds"]!!.jsonArray.map { it.jsonPrimitive.content }
    assertThat(appIds).containsExactly("com.example.dev", "com.example")
  }
}
