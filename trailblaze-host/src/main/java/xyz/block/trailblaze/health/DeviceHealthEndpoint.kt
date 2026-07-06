package xyz.block.trailblaze.health

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.util.AndroidHostAdbUtils

/**
 * Ktor route for the daemon's device-reachability health signal.
 *
 * `GET /health/device` performs ONE cheap, bounded device round-trip and returns
 * `{deviceReachable: boolean, detail: string, elapsedMs: number}`.
 *
 * ## Why this exists (distinct from `/ping`)
 *
 * `/ping` proves the daemon process and its HTTP server are alive. But the daemon talks to an
 * Android emulator over a `dadb` wire connection that can wedge while the process and HTTP server
 * stay perfectly alive — every subsequent device op then hangs or fails "Device not ready", yet a
 * liveness probe still sees a healthy daemon. This endpoint touches the SAME dadb path that wedges
 * (`getprop`, via [AndroidHostAdbUtils.execAdbShellCommandWithTimeout]) so an external supervisor
 * can tell "device connection wedged" from "daemon + device both healthy" and restart the daemon to
 * re-establish `dadb`.
 *
 * ## Why register from trailblaze-host
 *
 * The device handle ([AndroidHostAdbUtils]) is a host-layer type; the server depends on host, not
 * the other way. Mirroring [xyz.block.trailblaze.graph.WaypointGraphEndpoint], registration is
 * inverted into the server's `additionalRouteRegistration` callback so the lower layer stays
 * unaware of host types.
 *
 * The reachability decision itself lives in the pure, injectable [DeviceHealthProbe]; this object is
 * only the thin wiring that supplies the real device round-trip and serializes the result.
 */
object DeviceHealthEndpoint {

  const val PATH = "/health/device"

  // getprop is the cheapest idempotent shell round-trip and rides the exact dadb wire path that
  // wedges. Its OUTPUT is irrelevant to transport health — a still-booting-but-reachable emulator
  // answers sys.boot_completed with a blank line — so reachability is decided on whether the call
  // COMPLETED (non-null) rather than on the value, keeping boot-readiness out of the transport probe.
  private val PROBE_ARGS = listOf("getprop", "sys.boot_completed")

  // Per-attempt shell budget. AndroidHostAdbUtils retries a timed-out shell call ONCE at the full
  // budget and runs synchronously (its blocking thread can't be interrupted by the outer coroutine
  // timeout), so half the health deadline keeps both attempts within DeviceHealthProbe's deadline.
  private const val PROBE_TIMEOUT_MS: Long = DeviceHealthProbe.DEFAULT_TIMEOUT_MS / 2

  private val JSON = Json { encodeDefaults = true }

  /**
   * Registers `GET /health/device`. Call from the server's `additionalRouteRegistration` callback.
   *
   * @param connectedDevicesProvider returns Android device ids currently visible to the daemon.
   *   Defaults to the live adb host-services list. Injectable for tests / non-default wiring.
   * @param deviceProbe runs the cheap bounded shell round-trip for a device id, returning the shell
   *   output or null on timeout/failure. Defaults to the real dadb `getprop` path. Injectable.
   */
  fun register(
    routing: Routing,
    connectedDevicesProvider: () -> List<TrailblazeDeviceId> = { AndroidHostAdbUtils.listConnectedAdbDevices() },
    deviceProbe: (TrailblazeDeviceId, Long) -> String? = { deviceId, timeoutMs ->
      AndroidHostAdbUtils.execAdbShellCommandWithTimeout(deviceId, PROBE_ARGS, timeoutMs)
    },
  ) {
    routing.apply {
      get(PATH) {
        val result = DeviceHealthProbe.check {
          withContext(Dispatchers.IO) {
            val devices = connectedDevicesProvider()
            val target = devices.firstOrNull()
              ?: return@withContext DeviceProbeOutcome.Unreachable("no Android device attached to the daemon")
            val output = deviceProbe(target, PROBE_TIMEOUT_MS)
            if (output == null) {
              DeviceProbeOutcome.Unreachable(
                "device ${target.instanceId} did not answer getprop (transport may be wedged)",
              )
            } else {
              DeviceProbeOutcome.Reachable("device ${target.instanceId} answered getprop")
            }
          }
        }
        call.respondText(
          text = JSON.encodeToString(DeviceHealthResult.serializer(), result),
          contentType = ContentType.Application.Json,
        )
      }
    }
  }
}
