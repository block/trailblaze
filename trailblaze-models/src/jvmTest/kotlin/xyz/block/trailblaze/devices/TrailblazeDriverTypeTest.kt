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
   * Exact-membership tripwire. Code paths over [TrailblazeDriverType] spell these drivers out
   * individually (e.g. the connect-time probe in `TrailblazeDeviceService`, and the availability
   * gate each host-native iOS driver's own `HostDriverDescriptor` applies during discovery), and
   * flipping an EXISTING entry's property silently routes it through whichever path already
   * handles it. If this test fails because you flipped one, update every path that spells the
   * drivers out, then this expectation.
   *
   * This tripwire matters more than it used to. Those `when`s were compile-time exhaustive until
   * drivers moved onto `HostDriverDescriptor`; every driver now resolves through a descriptor, so
   * a new enum entry surfaces as a failing descriptor-coverage test or a runtime throw, not from
   * the compiler.
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
   * dispatch is the regression this guards, and revoking the accessibility driver's would strand
   * multi-device trails, which run on no other path.
   */
  @Test
  fun `host-agent dispatch membership is pinned`() {
    assertEquals(
      setOf(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY),
      TrailblazeDriverType.entries.filter { it.hostAgentDispatchable }.toSet(),
    )
    assertTrue(
      TrailblazeDriverType.entries.filter { it.hostAgentDispatchable }.all { it.hostRpcReachable },
      "the host agent can only dispatch to a driver it can reach over RPC",
    )
  }

  /**
   * Narrower than "executes on device": only the accessibility and AXe drivers must run
   * `scrollUntilTextIsVisible` through the manual loop, because only they lack a Maestro instance
   * for `ScrollUntilVisibleCommand` to delegate to. Adding a driver here changes its scroll
   * behavior, so the set is pinned rather than derived.
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
   * Only the accessibility driver defaults `scrollUntilTextIsVisible` to centering its target,
   * because only its swipes have been measured to travel materially less (no fling through
   * `dispatchGesture`). Adding a driver here changes where every un-parameterized scroll on it
   * comes to rest — and therefore whether the steps recorded after that scroll still find their
   * target — so the set is pinned rather than derived from a broader trait like "runs on device".
   */
  @Test
  fun `scroll-centering default membership is pinned`() {
    assertEquals(
      setOf(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY),
      TrailblazeDriverType.entries.filter { it.centersScrollTargetByDefault }.toSet(),
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

  /**
   * Exact-membership tripwire, so retiring a driver stays a deliberate act with the deletions that
   * go with it. Adding an entry here without deleting its runtime leaves a driver nobody can run
   * and a runtime nobody can reach; removing one un-retires a driver whose runtime is already gone.
   *
   * The value itself is deliberately NOT removed from the enum — archived recordings, session logs
   * and CI configs name it, and `TrailblazeDriverTypeLenientSerializer` has to keep reading them.
   */
  @Test
  fun `retired driver membership is pinned`() {
    assertEquals(
      setOf(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION),
      TrailblazeDriverType.RETIRED_DRIVERS,
    )
  }

  /**
   * The user-facing half of retirement: `trailblaze config <platform>-driver` must not offer a
   * driver whose runtime is deleted. Derived from [TrailblazeDriverType.cliShortName] being null,
   * so this fails the moment someone hands a retired driver a short name back — and `CliConfigHelper`
   * dereferences that name with `!!` when rendering the valid values, so a retired driver carrying
   * one would also have to be genuinely selectable.
   */
  @Test
  fun `a retired driver is not CLI-selectable`() {
    TrailblazeDriverType.RETIRED_DRIVERS.forEach { driverType ->
      assertTrue(
        driverType !in TrailblazeDriverType.selectableForPlatform(driverType.platform),
        "$driverType is retired but still offered as a CLI driver choice",
      )
    }
  }

  /**
   * Retiring the next driver is meant to be a one-line edit to [TrailblazeDriverType.RETIRED_DRIVERS].
   * That only holds if the replacement is derived per-platform — a cross-platform fallback would
   * answer an iOS retirement with an Android driver, and the operator would follow the advice.
   *
   * Asserted over EVERY driver, not only today's retired one, because the bug can only appear on
   * a retirement the set does not contain yet.
   */
  @Test
  fun `the replacement offered for a retired driver is on that driver's own platform`() {
    TrailblazeDriverType.entries.forEach { driverType ->
      val replacement = TrailblazeDriverType.replacementForRetired(driverType) ?: return@forEach
      assertEquals(
        driverType.platform,
        replacement.platform,
        "retiring $driverType would point users at $replacement, on another platform",
      )
      assertTrue(
        replacement !in TrailblazeDriverType.RETIRED_DRIVERS,
        "retiring $driverType would point users at $replacement, which is itself retired",
      )
    }
  }

  /**
   * The one sentence every entrypoint reuses has to carry the two facts the reader needs: which
   * driver is gone, and what to use instead. A platform with no runnable default must not have
   * one invented for it — the message says there is none.
   */
  @Test
  fun `the retirement message names the same-platform replacement, or says there is none`() {
    TrailblazeDriverType.entries.forEach { driverType ->
      val message = TrailblazeDriverType.retiredDriverMessage(driverType)
      assertTrue(driverType.name in message, "the message must name the retired driver: $message")
      val replacement = TrailblazeDriverType.replacementForRetired(driverType)
      if (replacement != null) {
        assertTrue(
          replacement.name in message,
          "the message must name the replacement $replacement: $message",
        )
      } else {
        assertTrue(
          driverType.platform.name in message,
          "with no replacement the message must name the stranded platform: $message",
        )
      }
    }
  }
}
