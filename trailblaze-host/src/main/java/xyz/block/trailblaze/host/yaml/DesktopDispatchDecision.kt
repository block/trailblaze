package xyz.block.trailblaze.host.yaml

import xyz.block.trailblaze.devices.TrailblazeDriverType

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
   * Host agent loop, individual tool calls to the device over RPC. The only path wired for
   * multi-device configurations.
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

  /**
   * Resolves the dispatch path. Arms are evaluated in the same order as the runner's `when`.
   */
  fun decide(
    driverType: TrailblazeDriverType,
    preferHostAgent: Boolean,
  ): DispatchPath = when {
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
      "The $driverType driver runs entirely on the host and is never reached over the on-device " +
        "RPC server. Run this trail on an Android device with an on-device driver and " +
        "`preferHostAgent` enabled."
  }
}
