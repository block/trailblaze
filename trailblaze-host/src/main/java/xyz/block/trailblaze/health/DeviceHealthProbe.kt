package xyz.block.trailblaze.health

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlin.system.measureTimeMillis

/**
 * Structured outcome of a device-reachability health check. Serialized to JSON by
 * [DeviceHealthEndpoint] as `{deviceReachable, detail, elapsedMs}`.
 *
 * @property deviceReachable true only when a device answered the cheap round-trip within the
 *   deadline. false covers every not-healthy state: no device attached, the round-trip failed,
 *   the round-trip threw, or the deadline elapsed (a wedged transport).
 * @property detail short human-readable reason. Advisory only — supervisors branch on
 *   [deviceReachable], not on this string.
 * @property elapsedMs wall-clock milliseconds the check took, including a timed-out attempt.
 */
@Serializable
data class DeviceHealthResult(
  val deviceReachable: Boolean,
  val detail: String,
  val elapsedMs: Long,
)

/**
 * Outcome of one attempt at the cheap device round-trip, as seen by [DeviceHealthProbe]. Keeps the
 * decision logic independent of any particular device API: the caller maps its real device call
 * (dadb `getprop`, an adb ping, an iOS probe, …) onto these three cases and the probe decides the
 * [DeviceHealthResult] from them.
 */
sealed interface DeviceProbeOutcome {
  /** A device answered the round-trip. [detail] describes what came back. */
  data class Reachable(val detail: String) : DeviceProbeOutcome

  /**
   * No device answered, but the check completed (not a hang): no device attached, or the device
   * op returned a not-reachable signal. [detail] explains which.
   */
  data class Unreachable(val detail: String) : DeviceProbeOutcome
}

/**
 * Pure device-reachability decision logic, kept free of any Ktor/HTTP/device dependency so it can
 * be unit-tested by driving [deviceProbe] with plain inputs (no real device, no mock of a device
 * API type).
 *
 * The point of this signal — distinct from plain daemon-process liveness — is to catch the state
 * where the daemon and its HTTP server are alive but the device wire connection has wedged: the
 * round-trip then *hangs* rather than failing. [check] bounds every attempt with [timeoutMs] via
 * [withTimeoutOrNull], so a wedge surfaces deterministically as `deviceReachable=false` within the
 * deadline instead of blocking the HTTP response.
 */
object DeviceHealthProbe {

  /**
   * Default internal deadline for the whole device round-trip. Kept to a few seconds so a wedged
   * transport surfaces quickly to a supervisor that polls this endpoint, while leaving slack for a
   * merely-slow (not wedged) device to answer.
   */
  const val DEFAULT_TIMEOUT_MS: Long = 3_000L

  /**
   * Runs [deviceProbe] under a [timeoutMs] deadline and maps the result to a [DeviceHealthResult].
   *
   * Contract:
   *  - probe returns [DeviceProbeOutcome.Reachable] → `deviceReachable=true`.
   *  - probe returns [DeviceProbeOutcome.Unreachable] → `deviceReachable=false`.
   *  - probe throws → `deviceReachable=false` (the throwable's message is folded into [detail]).
   *  - probe does not return within [timeoutMs] → `deviceReachable=false` with a timeout [detail].
   *
   * In every case [DeviceHealthResult.elapsedMs] reflects the wall-clock spent, and the call itself
   * never blocks longer than roughly [timeoutMs].
   */
  suspend fun check(
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    deviceProbe: suspend () -> DeviceProbeOutcome,
  ): DeviceHealthResult {
    var outcome: DeviceProbeOutcome? = null
    var thrown: Throwable? = null
    val elapsedMs = measureTimeMillis {
      outcome = withTimeoutOrNull(timeoutMs) {
        try {
          deviceProbe()
        } catch (t: Throwable) {
          thrown = t
          null
        }
      }
    }
    return when {
      outcome is DeviceProbeOutcome.Reachable ->
        DeviceHealthResult(
          deviceReachable = true,
          detail = (outcome as DeviceProbeOutcome.Reachable).detail,
          elapsedMs = elapsedMs,
        )

      outcome is DeviceProbeOutcome.Unreachable ->
        DeviceHealthResult(
          deviceReachable = false,
          detail = (outcome as DeviceProbeOutcome.Unreachable).detail,
          elapsedMs = elapsedMs,
        )

      thrown != null ->
        DeviceHealthResult(
          deviceReachable = false,
          detail = "device probe failed: ${thrown?.message ?: thrown?.javaClass?.simpleName}",
          elapsedMs = elapsedMs,
        )

      else ->
        DeviceHealthResult(
          deviceReachable = false,
          detail = "device probe timed out after ${timeoutMs}ms (device connection may be wedged)",
          elapsedMs = elapsedMs,
        )
    }
  }
}
