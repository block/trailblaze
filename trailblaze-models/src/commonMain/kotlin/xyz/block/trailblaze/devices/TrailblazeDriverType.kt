package xyz.block.trailblaze.devices

enum class TrailblazeDriverType(
  val platform: TrailblazeDevicePlatform,
  /**
   * Whether this driver requires a host machine to operate. Drivers with `requiresHost = false`
   * (e.g., on-device Android drivers) can run autonomously on the device via RPC; drivers with
   * `requiresHost = true` need a host-resident process (Maestro, Playwright, Revyl API, etc.).
   */
  val requiresHost: Boolean,
  /**
   * The YAML key used to reference this specific driver type in `trails/config/` YAML files
   * (targets, toolsets). Case-insensitive. Matches the keys in [DriverTypeKey].
   */
  val yamlKey: String,
  /**
   * Short identifier users type at the CLI to select this driver, e.g.
   * `trailblaze config android-driver accessibility`. `null` for drivers that aren't
   * user-selectable as a per-platform override (web drivers, Revyl cloud drivers). Kept
   * distinct from [yamlKey] because the CLI form drops the platform prefix — you already
   * know the platform from the config key (`android-driver` vs. `ios-driver`).
   */
  val cliShortName: String?,
  /**
   * Whether this driver's tools execute on the device itself rather than on the host.
   *
   * Distinct from [hostRpcReachable]: this answers "where does the tool run", that one answers
   * "can the host talk to it". A driver could execute tools on-device while the host has no
   * channel to reach it.
   */
  val executesToolsOnDevice: Boolean,
  /**
   * Whether the host can reach this driver over the on-device RPC server.
   *
   * [ANDROID_TEST] is reachable with a caveat: its RPC server is hosted by the target app's own
   * in-process test APK (`InProcessStandaloneServerTest` and kin), which the app's build
   * installs — it is never bundled with the CLI. A target that declares no in-process harness
   * (`TrailblazeHostAppTarget.getAndroidTestInstrumentationTarget()` returns null) is
   * unreachable on this driver, and dispatch fails naming the missing declaration rather than
   * instrumenting a runner that doesn't exist.
   */
  val hostRpcReachable: Boolean,
  /**
   * Whether the binary protobuf wire can carry this driver's screen state. `false` pins the host
   * to HTTP/JSON regardless of the `TRAILBLAZE_ANDROID_WIRE_TRANSPORT` switch, because for those
   * drivers protobuf is not merely slower or newer — it fails outright.
   *
   * `OnDeviceRpcProtoCodec.toProto` encodes only `androidAccessibility` and `androidMaestro`
   * node detail. [ANDROID_TEST] reports the app's own hierarchy as `androidView` / `compose`
   * nodes, so every tree-bearing response over the WebSocket comes back an encode failure —
   * including the readiness probe, which asks for the tree by default. The symptom without this
   * flag is a full readiness timeout on a server that is up and answering.
   *
   * Teaching the codec both variants (new proto messages plus both mapping directions) is the
   * real fix and makes every driver proto-safe; `trailblaze-android-test/README.md` tracks it.
   */
  val protoWireSafe: Boolean,
  /**
   * Whether this driver drives the simulator natively from the host, opening no Maestro/XCUITest
   * connection at all. Every gate that skips Maestro-driver plumbing (host-runner construction,
   * active-driver registration, the manual scroll loop) keys off this, and each such driver's
   * connected device is an `IosNativeConnectedDevice` exposing the driver's `IosDeviceManager`.
   */
  val hostNativeSimulatorDriver: Boolean,
  /**
   * Whether the host agent may run the reasoning loop and dispatch this driver's tools one at a
   * time over RPC (the `preferHostAgent` path).
   *
   * `false` for [ANDROID_TEST] as a driver contract, not a host preference: that driver exists to
   * be a merge-blocking gate, and its on-device runner fails an unrecorded step BY NAME rather
   * than improvising. The host-agent path builds a dynamic LLM client and delegates unrecorded
   * and self-heal steps to it — a gate that can improvise is a gate that can pass for the wrong
   * reason.
   */
  val hostAgentDispatchable: Boolean,
  /**
   * Whether `scrollUntilTextIsVisible` must run its manual scroll loop instead of delegating to
   * Maestro's `ScrollUntilVisibleCommand`.
   *
   * Named for the gate it feeds rather than for "has a Maestro driver", because the two are not
   * the same question. The on-device instrumentation driver runs Maestro ON the device and
   * delegates fine. The in-process [ANDROID_TEST] driver has no Maestro driver either, but has
   * never been on the manual loop — whether it should be is open, and answering it needs a
   * behavioral test, not a rename.
   */
  val usesManualScrollLoop: Boolean,
) {
  ANDROID_ONDEVICE_ACCESSIBILITY(
    platform = TrailblazeDevicePlatform.ANDROID,
    requiresHost = false,
    yamlKey = "android-ondevice-accessibility",
    cliShortName = "accessibility",
    executesToolsOnDevice = true,
    hostRpcReachable = true,
    protoWireSafe = true,
    hostNativeSimulatorDriver = false,
    hostAgentDispatchable = true,
    usesManualScrollLoop = true,
  ),
  ANDROID_ONDEVICE_INSTRUMENTATION(
    platform = TrailblazeDevicePlatform.ANDROID,
    requiresHost = false,
    yamlKey = "android-ondevice-instrumentation",
    cliShortName = "instrumentation",
    executesToolsOnDevice = true,
    hostRpcReachable = true,
    protoWireSafe = true,
    hostNativeSimulatorDriver = false,
    hostAgentDispatchable = true,
    usesManualScrollLoop = false,
  ),
  ANDROID_TEST(
    platform = TrailblazeDevicePlatform.ANDROID,
    requiresHost = false,
    yamlKey = "android-test",
    // CLI-selectable since the host learned to drive this driver over RPC: device discovery
    // offers it on every connected Android device, and the host instruments the target's own
    // in-process test APK (see TrailblazeHostAppTarget.getAndroidTestInstrumentationTarget).
    cliShortName = "in-process",
    executesToolsOnDevice = true,
    hostRpcReachable = true,
    protoWireSafe = false,
    hostNativeSimulatorDriver = false,
    hostAgentDispatchable = false,
    usesManualScrollLoop = false,
  ),
  IOS_HOST(
    platform = TrailblazeDevicePlatform.IOS,
    requiresHost = true,
    yamlKey = "ios-host",
    cliShortName = "host",
    executesToolsOnDevice = false,
    hostRpcReachable = false,
    protoWireSafe = true,
    hostNativeSimulatorDriver = false,
    hostAgentDispatchable = false,
    usesManualScrollLoop = false,
  ),
  IOS_AXE(
    platform = TrailblazeDevicePlatform.IOS,
    requiresHost = true,
    yamlKey = "ios-axe",
    cliShortName = "axe",
    executesToolsOnDevice = false,
    hostRpcReachable = false,
    protoWireSafe = true,
    hostNativeSimulatorDriver = true,
    hostAgentDispatchable = false,
    usesManualScrollLoop = true,
  ),
  PLAYWRIGHT_NATIVE(
    platform = TrailblazeDevicePlatform.WEB,
    requiresHost = true,
    yamlKey = "playwright-native",
    cliShortName = null,
    executesToolsOnDevice = false,
    hostRpcReachable = false,
    protoWireSafe = true,
    hostNativeSimulatorDriver = false,
    hostAgentDispatchable = false,
    usesManualScrollLoop = false,
  ),
  PLAYWRIGHT_ELECTRON(
    platform = TrailblazeDevicePlatform.WEB,
    requiresHost = true,
    yamlKey = "playwright-electron",
    cliShortName = null,
    executesToolsOnDevice = false,
    hostRpcReachable = false,
    protoWireSafe = true,
    hostNativeSimulatorDriver = false,
    hostAgentDispatchable = false,
    usesManualScrollLoop = false,
  ),
  REVYL_ANDROID(
    platform = TrailblazeDevicePlatform.ANDROID,
    requiresHost = true,
    yamlKey = "revyl-android",
    cliShortName = null,
    executesToolsOnDevice = false,
    hostRpcReachable = false,
    protoWireSafe = true,
    hostNativeSimulatorDriver = false,
    hostAgentDispatchable = false,
    usesManualScrollLoop = false,
  ),
  REVYL_IOS(
    platform = TrailblazeDevicePlatform.IOS,
    requiresHost = true,
    yamlKey = "revyl-ios",
    cliShortName = null,
    executesToolsOnDevice = false,
    hostRpcReachable = false,
    protoWireSafe = true,
    hostNativeSimulatorDriver = false,
    hostAgentDispatchable = false,
    usesManualScrollLoop = false,
  ),
  // The Compose desktop driver. Bound to TrailblazeDevicePlatform.DESKTOP. Previously
  // bound to WEB as a workaround because adding DESKTOP would have required touching
  // every exhaustive `when` on the platform enum — that surgery has now landed, so
  // DESKTOP is the correct platform here.
  COMPOSE(
    platform = TrailblazeDevicePlatform.DESKTOP,
    requiresHost = true,
    yamlKey = "compose",
    cliShortName = null,
    executesToolsOnDevice = false,
    hostRpcReachable = false,
    protoWireSafe = true,
    hostNativeSimulatorDriver = false,
    hostAgentDispatchable = false,
    usesManualScrollLoop = false,
  ),
  ;

  companion object {
    val DEFAULT_ANDROID = ANDROID_ONDEVICE_ACCESSIBILITY
    val DEFAULT_IOS = IOS_HOST
    val DEFAULT_DESKTOP = COMPOSE

    /**
     * The `am instrument -e` key carrying a suite-wide driver FORCE to the on-device runtime, in
     * the one place both sides can see it: the host writes it when launching instrumentation, the
     * device reads it (`InstrumentationArgUtil.driverType`) to select its driver and to decide
     * whether a trail's `config.driver:` pin is overridden rather than fatal
     * (`AndroidTestTrailblazeRule.evaluateDriverPin`).
     *
     * Shared rather than spelled once per side, because drift here fails silently in the worst
     * direction: the runtime falls back to its default driver and the gate sees no force, so the
     * run refuses a pin the operator did explicitly override.
     *
     * The device parses the value with `valueOf`, so only an exact [name] forces anything — a
     * yamlKey or case variant is discarded there.
     */
    const val INSTRUMENTATION_ARG_KEY = "trailblaze.driverType"

    /**
     * The driver type used when the user hasn't set an explicit per-platform override.
     * Returns `null` for platforms that don't have a user-togglable default (e.g. `WEB`).
     */
    fun defaultForPlatform(platform: TrailblazeDevicePlatform): TrailblazeDriverType? =
      when (platform) {
        TrailblazeDevicePlatform.ANDROID -> DEFAULT_ANDROID
        TrailblazeDevicePlatform.IOS -> DEFAULT_IOS
        else -> null
      }

    /**
     * Drivers that the CLI exposes as a per-platform override via `config <platform>-driver`.
     * Determined by [cliShortName] being non-null, so adding a new user-selectable driver to
     * the enum automatically surfaces it in the CLI — no second list to keep in sync.
     */
    fun selectableForPlatform(platform: TrailblazeDevicePlatform): List<TrailblazeDriverType> =
      entries.filter { it.platform == platform && it.cliShortName != null }

    fun fromString(value: String): TrailblazeDriverType? =
      entries.find { it.name.equals(value, ignoreCase = true) }
  }
}
