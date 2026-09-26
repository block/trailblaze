package xyz.block.trailblaze.mcp

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import java.util.concurrent.ConcurrentHashMap

/**
 * On-device runners found dead under a ready agent, keyed by device instance id, from the moment
 * they are found dead until the agent is ready again.
 *
 * Forgetting a dead agent clears its ready flag, and without an entry here the next status read
 * would find nothing wrong: the CLI would reuse the stale association, skip the reconnect that
 * relaunches the runner, and every tool call would fail with "No device connected". So the status
 * stays actionable until [clear] — including after a relaunch that failed, which reports its own
 * reason instead of repeating "reconnect" as if nothing had been tried.
 */
internal class StoppedOnDeviceRunners {

  private sealed interface State {
    val runnerAppId: String

    data class Stopped(override val runnerAppId: String) : State

    data class RelaunchFailed(override val runnerAppId: String, val reason: String) : State
  }

  private val states = ConcurrentHashMap<String, State>()

  fun markStopped(key: String, runnerAppId: String) {
    states[key] = State.Stopped(runnerAppId)
  }

  /** Records why a relaunch failed. A no-op for a device whose runner was not found dead. */
  fun relaunchFailed(key: String, reason: String) {
    states.computeIfPresent(key) { _, state -> State.RelaunchFailed(state.runnerAppId, reason) }
  }

  /** The agent is ready again, or the association is gone: nothing left to report. */
  fun clear(key: String) {
    states.remove(key)
  }

  fun status(deviceId: TrailblazeDeviceId): String? =
    when (val state = states[deviceId.instanceId]) {
      null -> null
      is State.Stopped -> TrailblazeMcpBridgeImpl.onDeviceRunnerStoppedStatus(deviceId, state.runnerAppId)
      is State.RelaunchFailed ->
        "The Trailblaze on-device runner (${state.runnerAppId}) stopped on '${deviceId.instanceId}' " +
          "and relaunching it failed: ${state.reason}. Reconnect the device to retry."
    }
}
