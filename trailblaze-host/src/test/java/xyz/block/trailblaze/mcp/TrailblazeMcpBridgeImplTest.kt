package xyz.block.trailblaze.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import xyz.block.trailblaze.config.TrailblazeConfigYaml
import xyz.block.trailblaze.config.ToolYamlConfig
import xyz.block.trailblaze.config.YamlDefinedTrailblazeTool
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.model.TrailExecutionResult
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool

/**
 * Pins the contract of the pure expansion helper that backs `executeTrailblazeTool`'s
 * host-expansion branch for `DelegatingTrailblazeTool` with `requires_host = true`. The
 * helper is the load-bearing piece of the dispatch-side fix for workspace YAML composed
 * tools: without correct expansion, the host would RPC the delegating wrapper to the
 * device where decode fails because workspace configs don't ship on-device.
 *
 * The recursive per-child dispatch in `expandDelegatingToolAndDispatch` is covered
 * end-to-end via the OSS smoke (`trailblaze tool wikipedia_back_safe` against the
 * wikipedia reproducer trailmap). This file pins the expansion math.
 */
class TrailblazeMcpBridgeImplTest {

  @Test
  fun `androidDisconnectStatus reports only a missing Android serial`() {
    val android = TrailblazeDeviceId(
      instanceId = "emulator-5554",
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
    )
    val ios = TrailblazeDeviceId(
      instanceId = "SIM-UUID",
      trailblazeDevicePlatform = TrailblazeDevicePlatform.IOS,
    )

    assertEquals(null, TrailblazeMcpBridgeImpl.androidDisconnectStatus(android, listOf(android)))
    assertTrue(
      TrailblazeMcpBridgeImpl.androidDisconnectStatus(android, emptyList())
        ?.contains("emulator-5554") == true,
    )
    assertEquals(null, TrailblazeMcpBridgeImpl.androidDisconnectStatus(ios, emptyList()))
  }

  @Test
  fun `expandDelegatingToolHostSide flattens YAML composed tool into executable primitives`() {
    // A typical workspace pure-YAML composed tool: `requires_host: true` (added by
    // `AppTargetDiscovery.registerWorkspaceYamlTools` when null), `tools:` body that
    // wraps a maestro back press.
    val config = parse(
      """
      id: test_back_safe
      description: Wraps the maestro back primitive for the test.
      requires_host: true
      parameters: []
      tools:
        - mobile_maestro:
            commands:
              - back: {}
      """.trimIndent(),
    )
    val tool = YamlDefinedTrailblazeTool(config = config, params = emptyMap())

    val expanded: List<ExecutableTrailblazeTool> =
      TrailblazeMcpBridgeImpl.expandDelegatingToolHostSide(
        tool = tool,
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "test-emu",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        traceId = null,
      )

    // Crucial regression pins:
    // 1. Expansion produced exactly the children the YAML body declares (one maestro back).
    assertEquals(
      1,
      expanded.size,
      "Expansion should produce one primitive per `tools:` body entry; got ${expanded.size}",
    )
    // 2. The child is an `ExecutableTrailblazeTool` (per the return type), not a wrapper.
    val child = assertNotNull(expanded.firstOrNull(), "Expansion must produce a non-null child")
    // 3. CRITICAL: the child is NOT itself a DelegatingTrailblazeTool — if it were, the
    //    recursive dispatch in `expandDelegatingToolAndDispatch` could re-enter the
    //    host-expansion branch on the child, producing nested-recursion. The framework's
    //    `YamlDefinedTrailblazeTool.toExecutableTrailblazeTools` enforces this via a cast
    //    + `?: error(...)`, but we re-assert it here so a future refactor of the framework
    //    can't silently break the no-nested-expansion invariant the bridge relies on.
    assertTrue(
      child !is DelegatingTrailblazeTool,
      "Expanded child must be an executable primitive, not another delegating tool — " +
        "host-expansion's recursive dispatch assumes non-delegating children. Got: ${child::class.simpleName}",
    )
  }

  @Test
  fun `expandDelegatingToolHostSide handles multi-child compositions in order`() {
    // Pin that expansion preserves YAML declaration order — the recursive dispatcher
    // calls `executeTrailblazeTool` on each child sequentially, so order is observable.
    val config = parse(
      """
      id: test_multi
      description: Multiple primitives in declaration order.
      requires_host: true
      parameters: []
      tools:
        - mobile_maestro:
            commands:
              - back: {}
        - mobile_maestro:
            commands:
              - back: {}
        - mobile_maestro:
            commands:
              - back: {}
      """.trimIndent(),
    )
    val tool = YamlDefinedTrailblazeTool(config = config, params = emptyMap())

    val expanded = TrailblazeMcpBridgeImpl.expandDelegatingToolHostSide(
      tool = tool,
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "test-emu",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      ),
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      traceId = null,
    )

    assertEquals(
      3,
      expanded.size,
      "Three `tools:` entries should produce three expanded children; got ${expanded.size}",
    )
    expanded.forEachIndexed { idx, child ->
      assertTrue(
        child !is DelegatingTrailblazeTool,
        "Child at index $idx must be an executable primitive (not a delegating wrapper); " +
          "got ${child::class.simpleName}",
      )
    }
  }

  // -- renderExecutionResult: what `trailblaze tool` prints for the HOST/Maestro blocking path --
  //
  // This is the pure helper the HOST-blocking branch of `executeTrailblazeTool` now routes its
  // completion result through, so a read tool run via `trailblaze tool` shows its real return
  // value instead of a generic "Executed …" acknowledgement. Pins the observable contract:
  // structuredContent wins over message, message wins over the fallback, and Failed / Cancelled
  // throw so the CLI reports a non-zero exit.

  private val fallback = "Executed FooTool on device test-emu"

  @Test
  fun `renderExecutionResult prefers structured content over message and fallback`() {
    val structured = buildJsonObject { put("count", JsonPrimitive(3)) }
    val rendered = TrailblazeMcpBridgeImpl.renderExecutionResult(
      result = TrailExecutionResult.Success(
        toolMessage = "human readable message",
        toolStructuredContent = structured,
      ),
      fallback = fallback,
    )
    // The typed return value the caller/device receives is the structured content serialized
    // verbatim — the message and fallback are fully ignored when structured content is present.
    assertEquals(
      TrailblazeJsonInstance.encodeToString(JsonElement.serializer(), structured),
      rendered,
    )
  }

  @Test
  fun `renderExecutionResult surfaces the tool message when there is no structured content`() {
    val rendered = TrailblazeMcpBridgeImpl.renderExecutionResult(
      result = TrailExecutionResult.Success(toolMessage = "com.example.app is not installed"),
      fallback = fallback,
    )
    assertEquals("com.example.app is not installed", rendered)
  }

  @Test
  fun `renderExecutionResult falls back for an action tool with no payload`() {
    // A tap/swipe-style Success carries neither message nor structured content — the caller
    // should still see the generic acknowledgement rather than an empty string.
    assertEquals(fallback, TrailblazeMcpBridgeImpl.renderExecutionResult(TrailExecutionResult.Success(), fallback))
    // A blank message is treated the same as none.
    assertEquals(
      fallback,
      TrailblazeMcpBridgeImpl.renderExecutionResult(TrailExecutionResult.Success(toolMessage = "   "), fallback),
    )
  }

  @Test
  fun `renderExecutionResult throws on failure and cancellation`() {
    assertFailsWith<IllegalStateException> {
      TrailblazeMcpBridgeImpl.renderExecutionResult(TrailExecutionResult.Failed("boom"), fallback)
    }
    assertFailsWith<IllegalStateException> {
      TrailblazeMcpBridgeImpl.renderExecutionResult(TrailExecutionResult.Cancelled, fallback)
    }
  }

  private fun parse(yaml: String): ToolYamlConfig =
    TrailblazeConfigYaml.instance.decodeFromString(ToolYamlConfig.serializer(), yaml)
      .also { it.validate() }
}
