package xyz.block.trailblaze.host.yaml

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.mcp.AgentImplementation
import kotlin.test.Test

/**
 * Characterization of [DesktopDispatchDecision] over the whole
 * (driver × agent implementation × preferHostAgent) space.
 *
 * The dispatch decision used to live as four compound predicates inside a `when {}` that could
 * only be exercised with a device attached, and the multi-device gate re-derived one of those
 * predicates by hand. This table is the behavioral contract both now read: every combination
 * states the path it resolves to, so a change to any driver capability shows up here as a named
 * row rather than as a routing surprise on a device.
 */
class DesktopDispatchDecisionTest {

  private fun decide(
    driver: TrailblazeDriverType,
    agent: AgentImplementation = AgentImplementation.TRAILBLAZE_RUNNER,
    preferHostAgent: Boolean = false,
  ) = DesktopDispatchDecision.decide(driver, agent, preferHostAgent)

  /**
   * The full table. Every (driver, agent, preferHostAgent) triple maps to exactly one path, and
   * the map is asserted whole — a new driver has no row until someone writes one, so it cannot
   * quietly inherit a fallthrough.
   */
  @Test
  fun `dispatch path for every driver, agent and host-agent preference`() {
    val actual: Map<String, DispatchPath> = buildMap {
      TrailblazeDriverType.entries.forEach { driver ->
        AgentImplementation.entries.forEach { agent ->
          listOf(false, true).forEach { preferHostAgent ->
            put("$driver/$agent/preferHostAgent=$preferHostAgent", decide(driver, agent, preferHostAgent))
          }
        }
      }
    }

    val onDevice = DispatchPath.ON_DEVICE_AGENT
    val hostRpc = DispatchPath.HOST_AGENT_OVER_ONDEVICE_RPC
    val v3Host = DispatchPath.V3_ACCESSIBILITY_ON_HOST
    val koog = DispatchPath.HOST_IN_PROCESS_KOOG
    val host = DispatchPath.HOST_DEFAULT

    assertThat(actual).isEqualTo(
      mapOf(
        // Accessibility: on-device by default, host agent when opted in, V3 gets its own host path
        // regardless of the toggle.
        "ANDROID_ONDEVICE_ACCESSIBILITY/TRAILBLAZE_RUNNER/preferHostAgent=false" to onDevice,
        "ANDROID_ONDEVICE_ACCESSIBILITY/TRAILBLAZE_RUNNER/preferHostAgent=true" to hostRpc,
        "ANDROID_ONDEVICE_ACCESSIBILITY/MULTI_AGENT_V3/preferHostAgent=false" to v3Host,
        "ANDROID_ONDEVICE_ACCESSIBILITY/MULTI_AGENT_V3/preferHostAgent=true" to v3Host,
        "ANDROID_ONDEVICE_ACCESSIBILITY/KOOG_STRATEGY_GRAPH/preferHostAgent=false" to onDevice,
        "ANDROID_ONDEVICE_ACCESSIBILITY/KOOG_STRATEGY_GRAPH/preferHostAgent=true" to hostRpc,

        // Instrumentation: same as accessibility except V3 has no host path for it.
        "ANDROID_ONDEVICE_INSTRUMENTATION/TRAILBLAZE_RUNNER/preferHostAgent=false" to onDevice,
        "ANDROID_ONDEVICE_INSTRUMENTATION/TRAILBLAZE_RUNNER/preferHostAgent=true" to hostRpc,
        "ANDROID_ONDEVICE_INSTRUMENTATION/MULTI_AGENT_V3/preferHostAgent=false" to onDevice,
        "ANDROID_ONDEVICE_INSTRUMENTATION/MULTI_AGENT_V3/preferHostAgent=true" to onDevice,
        "ANDROID_ONDEVICE_INSTRUMENTATION/KOOG_STRATEGY_GRAPH/preferHostAgent=false" to onDevice,
        "ANDROID_ONDEVICE_INSTRUMENTATION/KOOG_STRATEGY_GRAPH/preferHostAgent=true" to hostRpc,

        // ANDROID_TEST: always on-device. `preferHostAgent` cannot pull the merge gate onto a path
        // that would hand an unrecorded step to an LLM.
        "ANDROID_TEST/TRAILBLAZE_RUNNER/preferHostAgent=false" to onDevice,
        "ANDROID_TEST/TRAILBLAZE_RUNNER/preferHostAgent=true" to onDevice,
        "ANDROID_TEST/MULTI_AGENT_V3/preferHostAgent=false" to onDevice,
        "ANDROID_TEST/MULTI_AGENT_V3/preferHostAgent=true" to onDevice,
        "ANDROID_TEST/KOOG_STRATEGY_GRAPH/preferHostAgent=false" to onDevice,
        "ANDROID_TEST/KOOG_STRATEGY_GRAPH/preferHostAgent=true" to onDevice,

        // Host-resident drivers: default host path, Koog when asked. `preferHostAgent` is inert —
        // the agent already runs on the host.
        "IOS_HOST/TRAILBLAZE_RUNNER/preferHostAgent=false" to host,
        "IOS_HOST/TRAILBLAZE_RUNNER/preferHostAgent=true" to host,
        "IOS_HOST/MULTI_AGENT_V3/preferHostAgent=false" to host,
        "IOS_HOST/MULTI_AGENT_V3/preferHostAgent=true" to host,
        "IOS_HOST/KOOG_STRATEGY_GRAPH/preferHostAgent=false" to koog,
        "IOS_HOST/KOOG_STRATEGY_GRAPH/preferHostAgent=true" to koog,

        "IOS_AXE/TRAILBLAZE_RUNNER/preferHostAgent=false" to host,
        "IOS_AXE/TRAILBLAZE_RUNNER/preferHostAgent=true" to host,
        "IOS_AXE/MULTI_AGENT_V3/preferHostAgent=false" to host,
        "IOS_AXE/MULTI_AGENT_V3/preferHostAgent=true" to host,
        "IOS_AXE/KOOG_STRATEGY_GRAPH/preferHostAgent=false" to koog,
        "IOS_AXE/KOOG_STRATEGY_GRAPH/preferHostAgent=true" to koog,

        "PLAYWRIGHT_NATIVE/TRAILBLAZE_RUNNER/preferHostAgent=false" to host,
        "PLAYWRIGHT_NATIVE/TRAILBLAZE_RUNNER/preferHostAgent=true" to host,
        "PLAYWRIGHT_NATIVE/MULTI_AGENT_V3/preferHostAgent=false" to host,
        "PLAYWRIGHT_NATIVE/MULTI_AGENT_V3/preferHostAgent=true" to host,
        "PLAYWRIGHT_NATIVE/KOOG_STRATEGY_GRAPH/preferHostAgent=false" to koog,
        "PLAYWRIGHT_NATIVE/KOOG_STRATEGY_GRAPH/preferHostAgent=true" to koog,

        "PLAYWRIGHT_ELECTRON/TRAILBLAZE_RUNNER/preferHostAgent=false" to host,
        "PLAYWRIGHT_ELECTRON/TRAILBLAZE_RUNNER/preferHostAgent=true" to host,
        "PLAYWRIGHT_ELECTRON/MULTI_AGENT_V3/preferHostAgent=false" to host,
        "PLAYWRIGHT_ELECTRON/MULTI_AGENT_V3/preferHostAgent=true" to host,
        "PLAYWRIGHT_ELECTRON/KOOG_STRATEGY_GRAPH/preferHostAgent=false" to koog,
        "PLAYWRIGHT_ELECTRON/KOOG_STRATEGY_GRAPH/preferHostAgent=true" to koog,

        "REVYL_ANDROID/TRAILBLAZE_RUNNER/preferHostAgent=false" to host,
        "REVYL_ANDROID/TRAILBLAZE_RUNNER/preferHostAgent=true" to host,
        "REVYL_ANDROID/MULTI_AGENT_V3/preferHostAgent=false" to host,
        "REVYL_ANDROID/MULTI_AGENT_V3/preferHostAgent=true" to host,
        "REVYL_ANDROID/KOOG_STRATEGY_GRAPH/preferHostAgent=false" to koog,
        "REVYL_ANDROID/KOOG_STRATEGY_GRAPH/preferHostAgent=true" to koog,

        "REVYL_IOS/TRAILBLAZE_RUNNER/preferHostAgent=false" to host,
        "REVYL_IOS/TRAILBLAZE_RUNNER/preferHostAgent=true" to host,
        "REVYL_IOS/MULTI_AGENT_V3/preferHostAgent=false" to host,
        "REVYL_IOS/MULTI_AGENT_V3/preferHostAgent=true" to host,
        "REVYL_IOS/KOOG_STRATEGY_GRAPH/preferHostAgent=false" to koog,
        "REVYL_IOS/KOOG_STRATEGY_GRAPH/preferHostAgent=true" to koog,

        "COMPOSE/TRAILBLAZE_RUNNER/preferHostAgent=false" to host,
        "COMPOSE/TRAILBLAZE_RUNNER/preferHostAgent=true" to host,
        "COMPOSE/MULTI_AGENT_V3/preferHostAgent=false" to host,
        "COMPOSE/MULTI_AGENT_V3/preferHostAgent=true" to host,
        "COMPOSE/KOOG_STRATEGY_GRAPH/preferHostAgent=false" to koog,
        "COMPOSE/KOOG_STRATEGY_GRAPH/preferHostAgent=true" to koog,
      ),
    )
  }

  /**
   * The invariant the old hand-maintained gate could not state. Multi-device only works on the
   * one path with companion connect and per-device routing, so the set of drivers the gate admits
   * must be exactly the set that can reach that path — no more (a trail that runs the launch
   * device alone and reports success) and no fewer (a trail rejected on a path that would have
   * worked). Derived from [decide] rather than restated, so a capability change moves both.
   */
  @Test
  fun `only accessibility and instrumentation can run multi-device trails`() {
    val multiDeviceCapable = TrailblazeDriverType.entries.filter { driver ->
      AgentImplementation.entries.any { agent ->
        listOf(false, true).any { preferHostAgent ->
          DesktopDispatchDecision.supportsMultiDevice(decide(driver, agent, preferHostAgent))
        }
      }
    }.toSet()

    assertThat(multiDeviceCapable).isEqualTo(
      setOf(
        TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
        TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      ),
    )
  }

  /**
   * Every rejection names the change that would actually unblock the run. Keying the remedy off
   * the driver alone got this wrong for accessibility under V3, where `preferHostAgent` is already
   * on and the agent implementation is the real blocker — advice that sends someone to flip a
   * toggle that is already flipped.
   */
  @Test
  fun `each rejection names the change that would unblock it`() {
    val accessibility = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY

    assertThat(
      DesktopDispatchDecision.multiDeviceRemedy(
        decide(accessibility, AgentImplementation.MULTI_AGENT_V3, preferHostAgent = true),
        accessibility,
      ),
    ).contains("default agent implementation")

    assertThat(
      DesktopDispatchDecision.multiDeviceRemedy(
        decide(accessibility, preferHostAgent = false),
        accessibility,
      ),
    ).contains("Enable `preferHostAgent`")

    assertThat(
      DesktopDispatchDecision.multiDeviceRemedy(
        decide(TrailblazeDriverType.ANDROID_TEST, preferHostAgent = true),
        TrailblazeDriverType.ANDROID_TEST,
      ),
    ).contains("never dispatches multi-device")

    assertThat(
      DesktopDispatchDecision.multiDeviceRemedy(
        decide(TrailblazeDriverType.IOS_HOST),
        TrailblazeDriverType.IOS_HOST,
      ),
    ).contains("runs entirely on the host")
  }

  /**
   * [DesktopDispatchDecision.decide] splits across two capabilities: the Koog arm asks
   * `executesToolsOnDevice`, the on-device arm asks `hostRpcReachable`. A driver that ran on the
   * device but was unreachable over RPC would satisfy neither and land on
   * [DispatchPath.HOST_DEFAULT] — a host Maestro run against a driver whose tools live on the
   * device. It cannot happen while the two coincide, so this pins that they do. If you add such a
   * driver, `decide` needs a new arm before this expectation is relaxed.
   */
  @Test
  fun `no driver runs on the device without the host being able to reach it`() {
    assertThat(TrailblazeDriverType.entries.filter { it.executesToolsOnDevice }.toSet())
      .isEqualTo(TrailblazeDriverType.entries.filter { it.hostRpcReachable }.toSet())
  }
}
