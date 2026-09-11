package xyz.block.trailblaze.devices

/**
 * Which Android drivers reach the screen through Android's accessibility service — the service
 * supplies both their view hierarchy and their gesture dispatch.
 *
 * Answers one question for the two things every caller does with it: bind the service before
 * connecting, and require it in the readiness / screen-state probe. Those are inseparable, not two
 * policies — a driver whose taps go through the service cannot act while it is unbound, and a probe
 * that does not require it reports ready against a device that will fail its first tap.
 *
 * Deliberately not a property on [TrailblazeDriverType]. That enum describes drivers generically
 * across platforms, and as a constructor property this question made every iOS, web and desktop
 * driver declare a `false` about Android's accessibility service.
 *
 * It does live beside the enum in `:trailblaze-models` rather than on `HostDriverDescriptor`,
 * because it is read on both sides of the RPC boundary — the host's connect and probe paths, and
 * the on-device rule's own accessibility mode — and the device cannot see the host's descriptors.
 */
object AndroidAccessibilityServiceDrivers {

  /**
   * Whether [driverType] drives the screen through Android's accessibility service. A null driver
   * — none resolved yet — is not one.
   *
   * The `when` is exhaustive with no `else` on purpose, and it is the reason this is a `when` and
   * not a set of members: a new driver stops the build here and has to answer. An unlisted driver
   * in a set would answer "no" silently, and that failure mode is a driver that never binds the
   * service and then fails its first tap against a device the probe already called ready.
   *
   * Not the same question as [TrailblazeDriverType.usesManualScrollLoop], which `IOS_AXE` also
   * answers yes to, nor as "produces a Trailblaze node tree": the on-device instrumentation driver
   * can be asked for an accessibility-shaped side-channel tree via
   * `trailblaze.captureSecondaryTree` while still driving the device through Maestro.
   *
   * Three gates deliberately name [TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY] instead of
   * asking this, and all would break if widened to a capability: `DesktopDispatchDecision`'s V3 arm
   * means "V3 was built against that driver", `AndroidTrailblazeRule`'s migration-capture gate
   * means "the agent is an `AccessibilityTrailblazeAgent`", which only that driver builds, and that
   * rule's agent factory picks the agent class itself — a second service-backed driver wants its
   * own agent, not this one's.
   *
   * So a caller reading this must not assume the agent is an `AccessibilityTrailblazeAgent`. Ask
   * the agent instead: `executeNodeSelector*` returns null when it has no native implementation,
   * which is the documented signal to use the Maestro command path (see `SquareTrailblazeRule`'s
   * sign-in wait, where discarding that null would skip the wait rather than fall back).
   */
  fun includes(driverType: TrailblazeDriverType?): Boolean = when (driverType) {
    TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY -> true
    // Android, but not through the service: Maestro dispatched on the device, and in-process
    // Espresso against the app's own hierarchy.
    TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    TrailblazeDriverType.ANDROID_TEST,
    // Host-resident, or not Android at all — there is no service on the device this host drives,
    // and for the cloud drivers no device the host can reach to bind one.
    TrailblazeDriverType.REVYL_ANDROID,
    TrailblazeDriverType.IOS_HOST,
    TrailblazeDriverType.IOS_AXE,
    TrailblazeDriverType.REVYL_IOS,
    TrailblazeDriverType.PLAYWRIGHT_NATIVE,
    TrailblazeDriverType.PLAYWRIGHT_ELECTRON,
    TrailblazeDriverType.COMPOSE,
    null,
    -> false
  }
}
