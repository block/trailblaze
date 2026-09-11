package xyz.block.trailblaze.ui.tabs.settings

import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.yaml.DesktopDispatchDecision
import xyz.block.trailblaze.host.yaml.DispatchPath
import xyz.block.trailblaze.mcp.AgentImplementation
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostAgentToggleVisibilityTest {

  @Test
  fun `the toggle is offered for a driver the host agent can dispatch`() {
    assertTrue(
      HostAgentToggleVisibility.shouldShow(
        TrailblazeDevicePlatform.ANDROID,
        TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      ),
    )
  }

  /**
   * The in-process driver runs the trail inside the app's own instrumentation test, so the agent
   * is on-device by construction and `preferHostAgent` cannot move it. Offering the switch anyway
   * would write a setting dispatch ignores — a control that appears to work and does nothing.
   */
  @Test
  fun `the toggle is withheld for the in-process driver`() {
    assertFalse(
      HostAgentToggleVisibility.shouldShow(
        TrailblazeDevicePlatform.ANDROID,
        TrailblazeDriverType.ANDROID_TEST,
      ),
    )
  }

  /**
   * The claim the gate rests on, asserted rather than assumed: for every driver the toggle is
   * withheld from, flipping `preferHostAgent` on genuinely changes nothing about where the agent
   * runs. If a future driver breaks that, the gate is hiding a switch that mattered.
   */
  @Test
  fun `withholding the toggle matches drivers whose dispatch ignores it`() {
    TrailblazeDriverType.entries
      .filterNot { HostAgentToggleVisibility.shouldShow(TrailblazeDevicePlatform.ANDROID, it) }
      .forEach { driverType ->
        val withToggleOff = DesktopDispatchDecision.decide(
          driverType = driverType,
          agentImplementation = AgentImplementation.TRAILBLAZE_RUNNER,
          preferHostAgent = false,
        )
        val withToggleOn = DesktopDispatchDecision.decide(
          driverType = driverType,
          agentImplementation = AgentImplementation.TRAILBLAZE_RUNNER,
          preferHostAgent = true,
        )

        assertTrue(
          withToggleOff == withToggleOn,
          "the toggle is hidden for $driverType, but flipping it moves dispatch from " +
            "$withToggleOff to $withToggleOn — so it is a switch that mattered",
        )
      }
  }

  /**
   * And the converse: the driver the toggle IS offered for is one where flipping it moves
   * dispatch. Without this, a gate that hid the switch from everything would pass the test above.
   */
  @Test
  fun `offering the toggle matches a driver whose dispatch honors it`() {
    val shown = TrailblazeDriverType.entries
      .filter { HostAgentToggleVisibility.shouldShow(TrailblazeDevicePlatform.ANDROID, it) }
    assertTrue(shown.isNotEmpty(), "no driver offers the toggle, so the gate hides everything")

    shown.forEach { driverType ->
      assertTrue(
        DesktopDispatchDecision.decide(
          driverType = driverType,
          agentImplementation = AgentImplementation.TRAILBLAZE_RUNNER,
          preferHostAgent = true,
        ) == DispatchPath.HOST_AGENT_OVER_ONDEVICE_RPC,
        "the toggle is offered for $driverType, so enabling it must reach the host agent",
      )
    }
  }

  /** The switch is about the on-device RPC server, which no iOS or web driver has. */
  @Test
  fun `the toggle is withheld on every other platform`() {
    assertFalse(HostAgentToggleVisibility.shouldShow(TrailblazeDevicePlatform.IOS, TrailblazeDriverType.IOS_HOST))
    assertFalse(
      HostAgentToggleVisibility.shouldShow(
        TrailblazeDevicePlatform.WEB,
        TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      ),
    )
  }

  /** No driver selected for the platform means nothing to configure yet. */
  @Test
  fun `the toggle is withheld when no driver is selected`() {
    assertFalse(HostAgentToggleVisibility.shouldShow(TrailblazeDevicePlatform.ANDROID, null))
  }
}
