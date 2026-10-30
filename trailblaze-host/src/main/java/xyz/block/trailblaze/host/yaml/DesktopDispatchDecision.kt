package xyz.block.trailblaze.host.yaml

import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.trailhead.TrailheadRuns

/**
 * Where [DesktopYamlRunner] sends a run: the agent loop's location and the channel it uses to
 * reach the device.
 */
enum class DispatchPath {
  /**
   * In-process agent on the host, via `TrailblazeHostYamlRunner.runHostYaml` — Maestro and the
   * other drivers that execute tools on the host.
   */
  HOST_IN_PROCESS_KOOG,

  /**
   * Host agent loop dispatching each tool to its device: an Android device's over its on-device
   * RPC, an iOS simulator's host-side. The only path wired for multi-device configurations, so a
   * multi-device session launched on an iOS simulator runs here too.
   */
  HOST_AGENT_OVER_ONDEVICE_RPC,

  /** Whole YAML shipped to the device; the agent loop runs on-device. */
  ON_DEVICE_AGENT,
}

/**
 * The dispatch decision, extracted from [DesktopYamlRunner]'s branch conditions so it can be
 * exercised without a device.
 *
 * This exists because the decision is not a switch on driver type: it is a function of the
 * driver's declared capabilities and one config toggle. Holding it in one pure function is what
 * lets the multi-device gate ask which path a run will actually take instead of re-deriving a
 * predicate that has to be kept in agreement by hand.
 */
object DesktopDispatchDecision {

  /** Launch drivers, beyond the on-device Android ones, that can start a multi-device session. */
  private val HOST_MULTI_DEVICE_LAUNCH_DRIVERS = setOf(
    TrailblazeDriverType.IOS_HOST,
    TrailblazeDriverType.IOS_AXE,
    TrailblazeDriverType.PLAYWRIGHT_NATIVE,
  )

  /**
   * Resolves the dispatch path. Arms are evaluated in the same order as the runner's `when`.
   * [selectedConfigurationName] is the multi-device configuration the run binds, if any.
   */
  fun decide(
    driverType: TrailblazeDriverType,
    preferHostAgent: Boolean,
    selectedConfigurationName: String?,
  ): DispatchPath = when {
    selectedConfigurationName != null && driverType in HOST_MULTI_DEVICE_LAUNCH_DRIVERS ->
      DispatchPath.HOST_AGENT_OVER_ONDEVICE_RPC

    // Drivers whose tools run on the host take the in-process agent. On-device drivers need the
    // device attached via the on-device RPC server, which the host path cannot provide, so they
    // run the agent on the device, or host-side over RPC when `preferHostAgent` opts in.
    !driverType.executesToolsOnDevice -> DispatchPath.HOST_IN_PROCESS_KOOG

    driverType.hostRpcReachable &&
      driverType.hostAgentDispatchable &&
      preferHostAgent -> DispatchPath.HOST_AGENT_OVER_ONDEVICE_RPC

    driverType.hostRpcReachable -> DispatchPath.ON_DEVICE_AGENT

    // An on-device driver the host RPC cannot reach. No driver declares this today (the host
    // driver registry rejects it); it falls back to the in-process host agent.
    else -> DispatchPath.HOST_IN_PROCESS_KOOG
  }

  /**
   * Whether a multi-device trail may run on this configuration.
   *
   * [DispatchPath.HOST_AGENT_OVER_ONDEVICE_RPC] is the only path with companion connect, device
   * bindings, and per-device routing. Every other path would run a multi-device trail against the
   * launch device alone and report success, with the configuration-keyed steps quietly falling
   * through to the LLM — so this asks [decide] which path the run will actually take rather than
   * re-deriving the predicate. A gate that re-derives can disagree with the branch it guards; one
   * that reads the resolved path cannot.
   */
  fun supportsMultiDevice(path: DispatchPath): Boolean = path == DispatchPath.HOST_AGENT_OVER_ONDEVICE_RPC

  /**
   * Whether a run must be refused on [path] because it binds a multi-device configuration there.
   * Keyed on the run's SELECTION ([selectedConfigurationName]), not on what the trail declares: a
   * trail declaring single-device entries beside its configuration runs single-device when no
   * companions are bound, and its iOS or web legs must stay runnable on those paths.
   */
  fun refusesMultiDeviceRun(path: DispatchPath, selectedConfigurationName: String?): Boolean =
    selectedConfigurationName != null && !supportsMultiDevice(path)

  /**
   * Whether a call that continues an open session must be refused because a browser launches its
   * multi-device configuration: each call builds a fresh browser agent, whose secret redaction
   * would not know the secrets an earlier call filled.
   */
  fun refusesContinuedBrowserLaunch(
    driverType: TrailblazeDriverType,
    selectedConfigurationName: String?,
    startsSession: Boolean,
  ): Boolean = selectedConfigurationName != null &&
    driverType == TrailblazeDriverType.PLAYWRIGHT_NATIVE &&
    !startsSession

  /**
   * Whether a run ends its session when it returns. A browser-launched configuration always does,
   * so no later call, with or without a configuration, can continue its session in another agent.
   */
  fun endsSessionOnReturn(
    driverType: TrailblazeDriverType,
    selectedConfigurationName: String?,
    sendsSessionEndLog: Boolean,
  ): Boolean = sendsSessionEndLog ||
    (selectedConfigurationName != null && driverType == TrailblazeDriverType.PLAYWRIGHT_NATIVE)

  /**
   * What to tell a run that asked for trailhead reuse (`trailblaze loop --reuse-trailhead`) on a
   * [path] that drops the request, or null when [path] honours it. The request travels in
   * `TrailblazeConfig.trailheadReuse`, which the on-device RPC does not carry: an on-device
   * agent takes reuse only from its own instrumentation argument. Without this notice the run
   * sets up from scratch every time and says nothing.
   */
  fun ignoredTrailheadReuseNotice(path: DispatchPath, driverType: TrailblazeDriverType): String? {
    if (path != DispatchPath.ON_DEVICE_AGENT) return null
    val remedy = if (driverType.hostAgentDispatchable) {
      "Enable `preferHostAgent` to reuse it."
    } else {
      "The $driverType driver takes reuse only from its `${TrailheadRuns.REUSE_INSTRUMENTATION_ARG}` " +
        "instrumentation argument."
    }
    return "Trailhead reuse was requested, but this run executes on the device, which does not " +
      "receive that request: its trailhead sets up from scratch. $remedy"
  }

  /**
   * Why a multi-device trail cannot run here, phrased as the change that would make it
   * dispatchable.
   *
   * Keyed off the resolved [path] rather than the driver alone, because the same driver is blocked
   * for different reasons depending on the toggle — telling someone to enable `preferHostAgent`
   * when it is already on sends them looking in the wrong place.
   */
  fun multiDeviceRemedy(path: DispatchPath, driverType: TrailblazeDriverType): String = when (path) {
    DispatchPath.HOST_AGENT_OVER_ONDEVICE_RPC ->
      "This configuration does dispatch multi-device."

    DispatchPath.ON_DEVICE_AGENT ->
      if (!driverType.hostAgentDispatchable) {
        "The $driverType driver never dispatches multi-device: it runs the trail inside the " +
          "app's own instrumentation test, which knows only the device it is running on. Run " +
          "this trail on an on-device driver instead."
      } else {
        "The $driverType driver dispatches multi-device from the host agent. Enable " +
          "`preferHostAgent` and re-run."
      }

    DispatchPath.HOST_IN_PROCESS_KOOG ->
      "The $driverType driver cannot launch a multi-device session. Launch this trail with " +
        "${HOST_MULTI_DEVICE_LAUNCH_DRIVERS.joinToString(", ")}, or on an Android device with an " +
        "on-device driver and `preferHostAgent` enabled."
  }
}
