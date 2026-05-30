package xyz.block.trailblaze.toolcalls

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.messageContains
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId

/**
 * Covers the [invokeFrameworkTool] surface — the Kotlin-side bridge that lets one tool
 * invoke another registered framework tool by name with typed args. Six production
 * branches:
 *
 *  1. Typed-args happy path: tool resolves + executes, args round-trip through
 *     `TrailblazeJsonInstance` end-to-end.
 *  2. Missing `toolRepo` on the context produces the "wire the field" diagnostic.
 *  3. Unknown tool name produces the "no match" diagnostic.
 *  4. Tool resolves but isn't [ExecutableTrailblazeTool] produces the "not executable" diagnostic.
 *  5. JSON-string overload dispatches identically to the typed overload — pre-serialized
 *     payloads ride the same lookup + execute path.
 *  6. Args shape mismatched against the target tool's schema raises a serialization-layer
 *     error pinned to the offending field — this branch isn't a bridge-side wrapped
 *     diagnostic (the underlying `kotlinx.serialization` exception is already specific
 *     enough), but the test exists so a future refactor that changes the failure shape
 *     fails this test instead of silently degrading the operator-facing message.
 *
 * The failure-mode tests pin the exact diagnostic strings — the whole point of splitting
 * them apart is so an operator triaging a Kotlin-side composition failure gets a
 * routable hint, not a generic `IllegalStateException`.
 */
class FrameworkToolDispatchTest {

  // -------------------------------------------------------------------------------------------
  // Test-only tool classes — kept on the type level so the repo's KClass-backed lookup finds
  // them via the `toolClasses` set on the constructor. Not annotated with `@TrailblazeToolClass`
  // (avoids leaking into the global `TrailblazeSerializationInitializer.buildAllTools()` map),
  // so the unfiltered lookup's session-scoped path is what surfaces them.
  // -------------------------------------------------------------------------------------------

  @Serializable
  data class MarkerArgs(val payload: String)

  @Serializable
  @TrailblazeToolClass(name = "test_marker_dispatch", surfaceToLlm = false)
  data class TestMarkerTool(val payload: String) : ExecutableTrailblazeTool {
    override suspend fun execute(
      toolExecutionContext: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult = TrailblazeToolResult.Success(message = "received:$payload")
  }

  @Serializable
  @TrailblazeToolClass(name = "test_declarative_only_dispatch", surfaceToLlm = false)
  data class TestDeclarativeOnlyTool(val ignored: String) : TrailblazeTool

  // -------------------------------------------------------------------------------------------
  // Fixtures
  // -------------------------------------------------------------------------------------------

  /**
   * Build a minimal context — `toolRepo` is the only field this surface reads, everything
   * else is a no-op stub so the test stays focused on the dispatch path.
   */
  private fun contextWith(repo: TrailblazeToolRepo?): TrailblazeToolExecutionContext =
    TrailblazeToolExecutionContext(
      screenState = null,
      traceId = null,
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "test",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        widthPixels = 1080,
        heightPixels = 1920,
      ),
      sessionProvider = TrailblazeSessionProvider {
        TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now())
      },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = AgentMemory(),
      toolRepo = repo,
    )

  private fun repoWith(
    vararg classes: kotlin.reflect.KClass<out TrailblazeTool>,
  ): TrailblazeToolRepo = TrailblazeToolRepo(
    trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
      name = "dispatch-test",
      toolClasses = classes.toSet(),
      yamlToolNames = emptySet(),
    ),
  )

  // -------------------------------------------------------------------------------------------
  // Tests
  // -------------------------------------------------------------------------------------------

  @Test fun `typed-args overload resolves + executes + round-trips the args`() = runBlocking {
    // Pins the contract documented on `invokeFrameworkTool`: caller passes a `@Serializable`
    // args data class, framework encodes via `TrailblazeJsonInstance`, repo decodes against
    // the target tool's serializer, the resulting `ExecutableTrailblazeTool` runs and its
    // result reaches the caller. End-to-end through the bridge.
    val repo = repoWith(TestMarkerTool::class)
    val ctx = contextWith(repo)

    val result = ctx.invokeFrameworkTool("test_marker_dispatch", MarkerArgs(payload = "hello"))

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat((result as TrailblazeToolResult.Success).message).isEqualTo("received:hello")
  }

  @Test fun `missing toolRepo produces 'wire the field' diagnostic with the tool name`() = runBlocking {
    // Test fixtures and pre-bridge dispatchers leave `toolRepo` null. The diagnostic must
    // name the requested tool AND point at the producer contract — not at the resolver.
    val ctx = contextWith(repo = null)

    val failure = assertFailure { ctx.invokeFrameworkTool("any_tool", MarkerArgs(payload = "x")) }
    failure.messageContains("requires the context's `toolRepo` field")
    failure.messageContains("any_tool")
  }

  @Test fun `unknown tool name produces 'no match' diagnostic naming the requested tool`() = runBlocking {
    // The repo's unfiltered lookup searched every layer (session classes, YAML, global
    // registry) and missed. The diagnostic must surface the offending name so the caller
    // can spot a typo or a missing `@TrailblazeToolClass` registration.
    val repo = repoWith() // no tool classes registered
    val ctx = contextWith(repo)

    val failure = assertFailure {
      ctx.invokeFrameworkTool("nonexistent_tool", MarkerArgs(payload = "x"))
    }
    failure.messageContains("Unknown framework tool")
    failure.messageContains("nonexistent_tool")
  }

  @Test fun `non-executable resolved tool produces 'declarative-only' diagnostic`() = runBlocking {
    // Edge case: a `TrailblazeTool` that isn't an `ExecutableTrailblazeTool` (data-only
    // tools meant for recording, not direct dispatch). The cast must fail with a routable
    // message that points at the tool author, not the framework's bridge code.
    val repo = repoWith(TestDeclarativeOnlyTool::class)
    val ctx = contextWith(repo)

    // Use the JSON overload so the args match the declarative tool's schema directly —
    // otherwise the deserialization step (which runs BEFORE the executable cast) would
    // throw `MissingFieldException` and we'd never exercise the cast diagnostic this test
    // is meant to pin.
    val failure = assertFailure {
      ctx.invokeFrameworkTool("test_declarative_only_dispatch", """{"ignored":"x"}""")
    }
    failure.messageContains("not an ExecutableTrailblazeTool")
    failure.messageContains("test_declarative_only_dispatch")
  }

  @Serializable
  data class MismatchedArgs(val differentField: String)

  @Test fun `args shape mismatched against tool schema raises a serialization-layer error`() = runBlocking {
    // The bridge wraps three failure modes into bridge-specific diagnostics (null repo /
    // unknown tool / not executable). A FOURTH failure mode — caller passes a typed
    // `@Serializable` data class whose shape doesn't match the target tool's schema —
    // surfaces as a raw `kotlinx.serialization.MissingFieldException` (or
    // SerializationException for type mismatches) from the underlying
    // `toolCallToTrailblazeToolUnfiltered` decode call. This test pins that the
    // exception identifies the offending field by name so an operator triaging the
    // failure can spot the mismatch without diving into the stack trace.
    //
    // If a future refactor decides to wrap this case into a bridge-level diagnostic
    // (like the other three), this test should be updated in lockstep — that's the
    // signal that the failure-mode contract changed and consumers may need to be
    // notified.
    val repo = repoWith(TestMarkerTool::class)
    val ctx = contextWith(repo)

    val failure = assertFailure {
      ctx.invokeFrameworkTool("test_marker_dispatch", MismatchedArgs(differentField = "x"))
    }
    // The target tool's required field is `payload`; the args carry `differentField`.
    // Whichever variant of serialization-error fires (missing field on decode, or
    // unknown field if the deserializer is configured strict), the field name being
    // looked for is the routable signal.
    failure.messageContains("payload")
  }

  @Test fun `JSON-string overload dispatches identically to the typed overload`() = runBlocking {
    // Pre-serialized payloads still need a path through the bridge — the JSON overload
    // exists for callers that already have a JSON string (e.g. forwarding an opaque payload
    // from another layer). Same lookup + dispatch semantics; same failure-mode contract.
    val repo = repoWith(TestMarkerTool::class)
    val ctx = contextWith(repo)

    val result = ctx.invokeFrameworkTool("test_marker_dispatch", """{"payload":"prefab"}""")

    assertThat((result as TrailblazeToolResult.Success).message).isEqualTo("received:prefab")
  }

  @Test fun `dispatch routes through nestedToolExecutor when one is wired on the context`() = runBlocking {
    // Production agents (Maestro, Playwright, Compose, Revyl) all wire `nestedToolExecutor`
    // on the context to route nested-tool dispatch through their driver-specific path
    // (logging / recording / Playwright's PlaywrightExecutableTool wrap / etc.). The bridge
    // MUST honor that hook — calling `executable.execute(this)` directly would bypass the
    // driver layer, so a Kotlin tool composing `android_grantPermission` from a Maestro-
    // driven session would skip the agent's logging/recording wrapper and a Playwright tool
    // composed from Kotlin would error out (PlaywrightExecutableTool refuses direct
    // execute()). Mirrors the same pattern in `JsScriptingCallbackDispatcher`.
    val repo = repoWith(TestMarkerTool::class)
    val executorInvocations = mutableListOf<TrailblazeTool>()
    val executorSentinel = TrailblazeToolResult.Success(message = "routed via nestedToolExecutor")
    val ctx = TrailblazeToolExecutionContext(
      screenState = null,
      traceId = null,
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "test",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        widthPixels = 1080,
        heightPixels = 1920,
      ),
      sessionProvider = TrailblazeSessionProvider {
        TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now())
      },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = AgentMemory(),
      toolRepo = repo,
      nestedToolExecutor = { tool ->
        executorInvocations += tool
        executorSentinel
      },
    )

    val result = ctx.invokeFrameworkTool("test_marker_dispatch", MarkerArgs(payload = "ignored"))

    // The executor was invoked exactly once with the resolved (deserialized) tool, and
    // its return value is what the bridge surfaces — `TestMarkerTool.execute()` was NOT
    // called directly (its sentinel message `received:ignored` would have shown up
    // otherwise).
    assertThat(executorInvocations.size).isEqualTo(1)
    assertThat(executorInvocations.single()).isInstanceOf(TestMarkerTool::class)
    assertThat((result as TrailblazeToolResult.Success).message).isEqualTo("routed via nestedToolExecutor")
  }
}
