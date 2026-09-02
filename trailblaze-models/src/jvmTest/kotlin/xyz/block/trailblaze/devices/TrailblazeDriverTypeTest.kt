package xyz.block.trailblaze.devices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrailblazeDriverTypeTest {

  /**
   * Every gate keyed off [TrailblazeDriverType.hostNativeSimulatorDriver] (host-runner
   * guard, Maestro-driver registration skip, manual scroll loop) assumes such a driver is
   * a host-resident iOS driver. One violating either property would silently route
   * through the wrong plumbing.
   */
  @Test
  fun `host-native iOS drivers are iOS platform and host-resident`() {
    TrailblazeDriverType.entries.filter { it.hostNativeSimulatorDriver }.forEach { driverType ->
      assertEquals(TrailblazeDevicePlatform.IOS, driverType.platform, "$driverType must target iOS")
      assertTrue(driverType.requiresHost, "$driverType must be host-resident")
    }
  }

  /**
   * Exact-membership tripwire. `when` branches over [TrailblazeDriverType] spell these drivers
   * out individually (e.g. the screen-state capture arm in
   * `TrailblazeDeviceManager.getCurrentScreenState`), and flipping an EXISTING entry's property
   * silently routes it through whichever arm already handles it. If this test fails because you
   * flipped one, update every branch that spells the drivers out, then this expectation.
   *
   * This tripwire matters more than it used to. Those `when`s were compile-time exhaustive until
   * drivers began moving onto `HostDriverDescriptor`; a converted driver returns before the `when`
   * and it ends in a throwing `else`, so a new enum entry now surfaces at startup via
   * `HostDriverDescriptorRegistry.validateCovers` or at runtime, not from the compiler.
   */
  @Test
  fun `host-native simulator driver membership is pinned`() {
    assertEquals(
      setOf(TrailblazeDriverType.IOS_AXE),
      TrailblazeDriverType.entries.filter { it.hostNativeSimulatorDriver }.toSet(),
    )
  }

  /**
   * "Runs on the device" and "the host can reach it over RPC" are different questions —
   * ANDROID_TEST separated them when its server lived only on the farm, and now that the target
   * app's own in-process harness hosts the RPC server it declares BOTH. Host routing
   * (`DesktopDispatchDecision`, `TrailblazeDeviceManager`'s screen-state capture) gates on RPC
   * reachability, so a new on-device driver that declares the wrong one would have RPC sent at
   * something that cannot answer — and the symptom is a hang until timeout, not an error.
   */
  @Test
  fun `RPC-reachable drivers are a subset of the on-device ones`() {
    assertTrue(
      TrailblazeDriverType.ANDROID_TEST.executesToolsOnDevice,
      "ANDROID_TEST runs on the device",
    )
    assertTrue(
      TrailblazeDriverType.ANDROID_TEST.hostRpcReachable,
      "the in-process harness hosts the RPC server, so the host reaches ANDROID_TEST over RPC",
    )
    assertTrue(
      TrailblazeDriverType.entries.filter { it.hostRpcReachable }.all { it.executesToolsOnDevice },
      "every RPC-reachable driver must also be an on-device driver",
    )
  }

  /**
   * The JSON pin exists because `OnDeviceRpcProtoCodec.toProto` cannot encode this driver's
   * `androidView` / `compose` node detail, so a tree over the binary wire is an encode failure
   * rather than a slow path. Membership is pinned both ways: marking ANDROID_TEST proto-safe
   * reintroduces a readiness timeout against a healthy server, and un-marking a driver whose tree
   * the codec CAN carry silently costs it the binary transport. Teaching the codec both variants
   * is what should make every driver proto-safe.
   */
  @Test
  fun `only drivers the binary codec cannot encode are pinned to JSON`() {
    assertEquals(
      setOf(TrailblazeDriverType.ANDROID_TEST),
      TrailblazeDriverType.entries.filter { !it.protoWireSafe }.toSet(),
    )
    assertTrue(
      TrailblazeDriverType.entries.filter { !it.protoWireSafe }.all { it.hostRpcReachable },
      "a driver pinned to a wire must be one the host reaches over RPC at all",
    )
  }

  /**
   * The host-agent-over-RPC path builds a dynamic LLM client and delegates unrecorded and
   * self-heal steps to it. ANDROID_TEST opts out as a driver contract: it is a merge-blocking
   * gate whose on-device runner fails an unrecorded step BY NAME, and a gate that can improvise
   * is a gate that can pass for the wrong reason. Pinned both ways — granting it host-agent
   * dispatch is the regression this guards, and revoking either of the other two would strand
   * multi-device trails, which run on no other path.
   */
  @Test
  fun `host-agent dispatch membership is pinned`() {
    assertEquals(
      setOf(
        TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
        TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      ),
      TrailblazeDriverType.entries.filter { it.hostAgentDispatchable }.toSet(),
    )
    assertTrue(
      TrailblazeDriverType.entries.filter { it.hostAgentDispatchable }.all { it.hostRpcReachable },
      "the host agent can only dispatch to a driver it can reach over RPC",
    )
  }

  /**
   * Narrower than "executes on device": the on-device instrumentation driver runs Maestro ON the
   * device, so `ScrollUntilVisibleCommand` still has an instance to delegate to. Only the
   * accessibility and AXe drivers must run `scrollUntilTextIsVisible` through the manual loop.
   * Adding a driver here changes its scroll behavior, so the set is pinned rather than derived.
   */
  @Test
  fun `manual scroll loop membership is pinned`() {
    assertEquals(
      setOf(
        TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
        TrailblazeDriverType.IOS_AXE,
      ),
      TrailblazeDriverType.entries.filter { it.usesManualScrollLoop }.toSet(),
    )
  }

  /**
   * A driver with no [TrailblazeDriverType.cliShortName] is not offered by `trailblaze config
   * <platform>-driver`. ANDROID_TEST is selectable as `in-process` now that host device
   * discovery produces an ANDROID_TEST entry per connected Android device.
   */
  @Test
  fun `only CLI-nameable drivers are selectable`() {
    TrailblazeDevicePlatform.entries.forEach { platform ->
      TrailblazeDriverType.selectableForPlatform(platform).forEach { driverType ->
        assertTrue(driverType.cliShortName != null, "$driverType is selectable without a name")
      }
    }
    assertTrue(
      TrailblazeDriverType.ANDROID_TEST in
        TrailblazeDriverType.selectableForPlatform(TrailblazeDevicePlatform.ANDROID),
      "ANDROID_TEST must be offered as a CLI driver choice (`in-process`)",
    )
  }
}
