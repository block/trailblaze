package xyz.block.trailblaze.host.yaml

import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.mcp.AgentImplementation

/**
 * Where [DesktopYamlRunner] sends a run: the agent loop's location and the channel it uses to
 * reach the device.
 */
enum class DispatchPath {
  /** In-process Koog strategy-graph agent on the host, via `TrailblazeHostYamlRunner.runHostYaml`. */
  HOST_IN_PROCESS_KOOG,

  /** V3 planner/analyzer on the host JVM, individual tool calls to the device over RPC. */
  V3_ACCESSIBILITY_ON_HOST,

  /**
   * Host agent loop, individual tool calls to the device over RPC. The only path wired for
   * multi-device configurations.
   */
  HOST_AGENT_OVER_ONDEVICE_RPC,

  /** Whole YAML shipped to the device; the agent loop runs on-device. */
  ON_DEVICE_AGENT,

  /** Default host path — Maestro and the other host-resident drivers. */
  HOST_DEFAULT,
}

/**
 * The dispatch decision, extracted from [DesktopYamlRunner]'s branch conditions so it can be
 * exercised without a device.
 *
 * This exists because the decision is not a switch on driver type: it is a function of the
 * driver's declared capabilities, the requested agent implementation, and one config toggle.
 * Holding it in one pure function is what lets the multi-device gate ask which path a run will
 * actually take instead of re-deriving a predicate that has to be kept in agreement by hand.
 */
object DesktopDispatchDecision {

  /**
   * Resolves the dispatch path. Arms are evaluated in the same order as the runner's `when`.
   */
  fun decide(
    driverType: TrailblazeDriverType,
    agentImplementation: AgentImplementation,
    preferHostAgent: Boolean,
  ): DispatchPath = when {
    // Opt-in Koog strategy-graph agent, top priority so it short-circuits driver-based routing.
    // On-device drivers are excluded: they need the device attached via the on-device RPC server,
    // which the host path cannot provide. They run the Koog agent ON the device instead (the
    // ON_DEVICE_AGENT path below), or host-side over RPC when `preferHostAgent` opts in.
    agentImplementation == AgentImplementation.KOOG_STRATEGY_GRAPH &&
      !driverType.executesToolsOnDevice -> DispatchPath.HOST_IN_PROCESS_KOOG

    driverType == TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY &&
      agentImplementation == AgentImplementation.MULTI_AGENT_V3 -> DispatchPath.V3_ACCESSIBILITY_ON_HOST

    driverType.hostRpcReachable &&
      driverType.hostAgentDispatchable &&
      agentImplementation != AgentImplementation.MULTI_AGENT_V3 &&
      preferHostAgent -> DispatchPath.HOST_AGENT_OVER_ONDEVICE_RPC

    driverType.hostRpcReachable -> DispatchPath.ON_DEVICE_AGENT

    else -> DispatchPath.HOST_DEFAULT
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
   * Why a multi-device trail cannot run here, phrased as the change that would make it
   * dispatchable.
   *
   * Keyed off the resolved [path] rather than the driver alone, because the same driver is blocked
   * for different reasons depending on the agent and the toggle — telling someone to enable
   * `preferHostAgent` when it is already on sends them looking in the wrong place.
   */
  fun multiDeviceRemedy(path: DispatchPath, driverType: TrailblazeDriverType): String = when (path) {
    DispatchPath.HOST_AGENT_OVER_ONDEVICE_RPC ->
      "This configuration does dispatch multi-device."

    DispatchPath.V3_ACCESSIBILITY_ON_HOST ->
      "The $driverType driver dispatches multi-device, but not under the V3 multi-agent " +
        "implementation. Re-run with the default agent implementation."

    DispatchPath.ON_DEVICE_AGENT ->
      if (!driverType.hostAgentDispatchable) {
        "The $driverType driver never dispatches multi-device: it runs the trail inside the " +
          "app's own instrumentation test, which knows only the device it is running on. Run " +
          "this trail on an on-device driver instead."
      } else {
        "The $driverType driver dispatches multi-device from the host agent. Enable " +
          "`preferHostAgent` and re-run."
      }

    DispatchPath.HOST_IN_PROCESS_KOOG,
    DispatchPath.HOST_DEFAULT,
    ->
      "The $driverType driver runs entirely on the host and is never reached over the on-device " +
        "RPC server. Run this trail on an Android device with an on-device driver and " +
        "`preferHostAgent` enabled."
  }
}
