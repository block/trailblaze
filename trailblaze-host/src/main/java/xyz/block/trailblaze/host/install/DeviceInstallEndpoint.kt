package xyz.block.trailblaze.host.install

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import java.io.File

/** Asks the daemon to install the app at [path], a file on its machine, on [device]. */
@Serializable
data class DeviceInstallRequest(
  /** As `trailblaze device list` shows it: `<platform>/<instance>`, a borrowed one with `@<host>:<port>`. */
  val device: String,
  val path: String,
)

@Serializable
data class DeviceInstallResponse(val success: Boolean, val message: String)

/**
 * `POST /cli/device-install`, body [DeviceInstallRequest]; 200 [DeviceInstallResponse] with the
 * install's outcome, 404 when the daemon has no such device. Behind `trailblaze device install`,
 * for this machine's devices and borrowed ones alike ([DeviceAppInstaller]).
 *
 * Only for the user's own shell: the request names a file on this machine, so one from another
 * machine, or from a web page (the daemon's CORS policy admits any), is refused.
 */
object DeviceInstallEndpoint {

  const val PATH = "/cli/device-install"

  private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
  }

  /** @param findDevice the daemon's device named by a [DeviceInstallRequest.device], or null. */
  fun register(
    routing: Routing,
    findDevice: suspend (String) -> TrailblazeDeviceId?,
    installer: DeviceAppInstaller,
  ) = routing.post(PATH) {
    val fromBrowser = call.request.headers[HttpHeaders.Origin] != null || call.request.headers["Sec-Fetch-Site"] != null
    if (fromBrowser || !isLoopback(call.request.local.remoteAddress)) {
      return@post call.respond(HttpStatusCode.Forbidden, false, "installing apps is only open to this machine's own shell")
    }
    val request = runCatching { json.decodeFromString(DeviceInstallRequest.serializer(), call.receiveText()) }
      .getOrElse { return@post call.respond(HttpStatusCode.BadRequest, false, "invalid install request: ${it.message}") }
    val appFile = File(request.path)
    if (!appFile.exists()) return@post call.respond(HttpStatusCode.BadRequest, false, "No such file: ${request.path}")
    val device = findDevice(request.device)
      ?: return@post call.respond(
        HttpStatusCode.NotFound,
        false,
        "No single device matches ${request.device}. Run 'trailblaze device list' and name one by its full id.",
      )
    val result = installer.install(device, appFile)
    call.respond(HttpStatusCode.OK, result.success, result.message)
  }

  /**
   * The device in [devices] that [spec] names: its full id (`android/emulator-5554`), its bare
   * instance id, or just its platform (`android`) when it is the only device of that platform.
   */
  fun pick(devices: List<TrailblazeDeviceId>, spec: String): TrailblazeDeviceId? {
    val wanted = spec.trim()
    devices.firstOrNull { it.toFullyQualifiedDeviceId() == wanted || it.instanceId == wanted }?.let { return it }
    // Distinct: the unfiltered device list names one device once per driver that can run it.
    return devices.distinct().filter { it.trailblazeDevicePlatform.name.equals(wanted, ignoreCase = true) }.singleOrNull()
  }

  private suspend fun io.ktor.server.application.ApplicationCall.respond(status: HttpStatusCode, success: Boolean, message: String) =
    respondText(json.encodeToString(DeviceInstallResponse.serializer(), DeviceInstallResponse(success, message)), ContentType.Application.Json, status)

  /** The same addresses `/cli/exec` and device leasing accept. */
  private fun isLoopback(address: String): Boolean = xyz.block.trailblaze.host.lease.DeviceLeaseEndpoint.isLoopback(address)
}
