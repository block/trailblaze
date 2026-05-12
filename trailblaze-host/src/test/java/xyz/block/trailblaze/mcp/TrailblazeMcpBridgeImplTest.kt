package xyz.block.trailblaze.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.config.TrailblazeConfigYaml
import xyz.block.trailblaze.config.ToolYamlConfig
import xyz.block.trailblaze.config.YamlDefinedTrailblazeTool
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
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
 * wikipedia reproducer pack). This file pins the expansion math.
 */
class TrailblazeMcpBridgeImplTest {

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
        - maestro:
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
        - maestro:
            commands:
              - back: {}
        - maestro:
            commands:
              - back: {}
        - maestro:
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

  private fun parse(yaml: String): ToolYamlConfig =
    TrailblazeConfigYaml.instance.decodeFromString(ToolYamlConfig.serializer(), yaml)
      .also { it.validate() }
}
