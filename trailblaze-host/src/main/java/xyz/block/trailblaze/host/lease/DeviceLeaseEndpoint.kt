package xyz.block.trailblaze.host.lease

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.util.Console
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Lends this daemon's devices, of any platform, to other Trailblaze instances.
 *
 * Off unless the daemon starts with [ENV_VAR] set. A borrowing daemon ([RemoteDevicePools]) lists
 * the free devices next to its own. When a session takes one it leases it and renews the lease
 * while it uses the device. The borrower runs the agent, keeps the logs and writes the report; each
 * tool call and screen capture comes here ([CALL_PATH]) and runs through this daemon's own driver
 * for the device, as `trailblaze tool` would run it here. A lease that is not renewed expires on its
 * own, so a borrower that vanishes cannot hold devices forever.
 *
 * - `GET  /leases/devices`        what can be lent, and what is lent out
 * - `POST /leases`                body [LeaseRequest]; 200 [Lease], or 409 when one is busy
 * - `POST /leases/{id}/renew`     [SECRET_HEADER], `?busy=true` while in use; 204, or 404 when gone
 * - `POST /leases/{id}/call`      [SECRET_HEADER], body [RemoteDeviceCall]; 200 [RemoteDeviceReply],
 *                                 or 404 when the lease is gone or does not hold the device
 * - `POST /leases/{id}/install`   [SECRET_HEADER], `?device=<id>&file=<name>`, body the app file (an
 *                                 `.apk`, or a `.zip` holding an `.app`); 200 [RemoteInstallReply],
 *                                 or 404 as for a call
 * - `DELETE /leases/{id}`         [SECRET_HEADER]; 204, or 404 when the lease is gone
 *
 * Only this machine's loopback is served, so other machines reach a lender over an ssh tunnel:
 * leasing has no authentication. A request a browser sends (it carries an
 * `Origin` header) is refused too: the daemon's CORS policy admits any web page, and none should be
 * able to lease, list or drive devices. [DeviceLeaseClient] sends none.
 */
object DeviceLeaseEndpoint {

  const val ENV_VAR = "TRAILBLAZE_LEASE_DEVICES"
  const val SECRET_HEADER = "X-Trailblaze-Lease-Secret"
  const val DEVICES_PATH = "/leases/devices"
  const val LEASES_PATH = "/leases"
  const val CALL_PATH = "/leases/{id}/call"
  const val INSTALL_PATH = "/leases/{id}/install"

  /** The app files a borrower may send to [INSTALL_PATH]: an `.app` bundle travels zipped. */
  private val INSTALLABLE_EXTENSIONS = setOf("apk", "zip")

  /**
   * This daemon's book of lent devices. It sees this machine's own sessions through
   * [RemoteDevicePools.shared], which the claim registry and runs report to.
   */
  val registry: DeviceLeaseRegistry by lazy { DeviceLeaseRegistry(locallyInUse = RemoteDevicePools.shared::isLocallyInUse) }

  private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
  }

  /** True when [ENV_VAR] asks this daemon to lend its devices. */
  fun isEnabled(env: (String) -> String? = System::getenv): Boolean =
    env(ENV_VAR)?.trim()?.lowercase() in setOf("1", "true", "yes", "on")

  /** Whether this daemon lends its devices, as [decideLending] settled it when the daemon started. */
  @Volatile
  var lending: Boolean = false
    private set

  /**
   * Settles [lending] for this daemon: on when [ENV_VAR] asks for it or the saved `lend-devices`
   * setting ([saved]) is on. An explicit off in [ENV_VAR] (`0`, `false`, `no`, `off`) wins over the
   * saved setting, so one shell can stop a machine lending without changing its saved config.
   * Returns it.
   */
  fun decideLending(saved: Boolean, env: (String) -> String? = System::getenv): Boolean {
    val asked = env(ENV_VAR)?.trim()?.lowercase()
    lending = when (asked) {
      in setOf("1", "true", "yes", "on") -> true
      in setOf("0", "false", "no", "off") -> false
      else -> saved
    }
    return lending
  }

  /**
   * @param attachedDevices this daemon's own devices, one per device with the driver it runs it
   *   with. Never the devices it borrows itself, so a lease never chains through two machines.
   * @param calls runs a borrower's call on a device it holds.
   * @param installer installs an app a borrower sent on a device it holds.
   */
  fun register(
    routing: Routing,
    attachedDevices: suspend () -> List<LeasableDevice>,
    calls: LeasedDeviceCalls,
    registry: DeviceLeaseRegistry = this.registry,
    installer: LeasedDeviceInstaller = LeasedDeviceInstaller.OnThisMachine,
    maxUploadBytes: Long = MAX_UPLOAD_BYTES,
    uploadTimeout: Duration = UPLOAD_TIMEOUT,
    uploadStartedForTest: (device: String) -> Unit = {},
  ) {
    // One upload at a time per device, so a borrower's large app never holds up another borrower's.
    val uploads = ConcurrentHashMap<String, Mutex>()
    // One call at a time per device: a call left running by a borrower that gave up finishes
    // before the next one starts, and a call that waited checks the lease again when its turn comes.
    val turns = ConcurrentHashMap<String, Mutex>()
    suspend fun <T> inTurn(device: String, block: suspend () -> T): T =
      turns.computeIfAbsent(device) { Mutex() }.withLock { block() }
    routing.apply {
      get(DEVICES_PATH) {
        if (call.refusedFromBrowserOrProxy()) return@get
        call.respondJson(ListSerializer(LeasableDeviceStatus.serializer()), registry.status(attachedDevices()))
      }
      post(LEASES_PATH) {
        if (call.refusedFromBrowserOrProxy()) return@post
        val request = runCatching { json.decodeFromString(LeaseRequest.serializer(), call.receiveText()) }
          .getOrElse { return@post call.respondError(HttpStatusCode.BadRequest, "invalid lease request: ${it.message}") }
        if (request.devices.isEmpty()) return@post call.respondError(HttpStatusCode.BadRequest, "name at least one device")
        when (val result = registry.lease(attachedDevices(), request)) {
          is LeaseResult.Granted -> call.respondJson(Lease.serializer(), result.lease)
          is LeaseResult.Busy -> call.respondError(HttpStatusCode.Conflict, result.reason, result.holder)
        }
      }
      post("$LEASES_PATH/{id}/renew") {
        if (call.refusedFromBrowserOrProxy()) return@post
        val ok = registry.renew(
          call.parameters["id"].orEmpty(),
          call.request.headers[SECRET_HEADER].orEmpty(),
          busy = call.request.queryParameters["busy"] == "true",
        )
        if (ok) call.respondText("", status = HttpStatusCode.NoContent) else call.respondGone()
      }
      post(CALL_PATH) {
        if (call.refusedFromBrowserOrProxy()) return@post
        val leaseId = call.parameters["id"].orEmpty()
        val secret = call.request.headers[SECRET_HEADER].orEmpty()
        val request = runCatching { json.decodeFromString(RemoteDeviceCall.serializer(), call.receiveText()) }
          .getOrElse { return@post call.respondError(HttpStatusCode.BadRequest, "invalid device call: ${it.message}") }
        if (!registry.holds(leaseId, secret, request.device)) return@post call.respondGone()
        val device = deviceId(request.device)
          ?: return@post call.respondError(HttpStatusCode.BadRequest, "not a device id: ${request.device}")
        val reply = try {
          inTurn(request.device) { if (registry.holds(leaseId, secret, request.device)) calls.call(device, request) else null }
            ?: return@post call.respondGone()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Console.log("[DeviceLease] ${request.op} on ${request.device} failed: ${e.message}")
          return@post call.respondError(HttpStatusCode.InternalServerError, e.message ?: e::class.java.simpleName)
        }
        call.respondJson(RemoteDeviceReply.serializer(), reply)
      }
      post(INSTALL_PATH) {
        if (call.refusedFromBrowserOrProxy()) return@post
        val leaseId = call.parameters["id"].orEmpty()
        val secret = call.request.headers[SECRET_HEADER].orEmpty()
        val lenderId = call.request.queryParameters["device"].orEmpty()
        // Checked before the upload is read, so only the device's holder can make this machine store a file.
        if (!registry.holds(leaseId, secret, lenderId)) return@post call.respondGone()
        val device = deviceId(lenderId)
          ?: return@post call.respondError(HttpStatusCode.BadRequest, "not a device id: $lenderId")
        val extension = call.request.queryParameters["file"].orEmpty().substringAfterLast('.', "").lowercase()
        if (extension !in INSTALLABLE_EXTENSIONS) {
          return@post call.respondError(HttpStatusCode.BadRequest, "send an .apk, or a .zip holding an .app bundle")
        }
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > maxUploadBytes) {
          return@post call.respondError(HttpStatusCode.PayloadTooLarge, tooLarge(maxUploadBytes))
        }
        val appFile = withContext(Dispatchers.IO) { Files.createTempFile("trailblaze-lent-install-", ".$extension").toFile() }
        try {
          // Each upload is capped in size and time, waiting for its turn included, so a borrower
          // can't fill this machine's disk or hold an upload open forever.
          val received = withTimeoutOrNull(uploadTimeout) {
            uploads.computeIfAbsent(lenderId) { Mutex() }.withLock {
              uploadStartedForTest(lenderId)
              withContext(Dispatchers.IO) {
                appFile.outputStream().use { output -> copyAtMost(call.receiveChannel(), output, maxUploadBytes) }
              }
            }
          }
          when (received) {
            null -> return@post call.respondError(HttpStatusCode.RequestTimeout, "the app upload took longer than $uploadTimeout")
            false -> return@post call.respondError(HttpStatusCode.PayloadTooLarge, tooLarge(maxUploadBytes))
            true -> Unit
          }
          val reply = try {
            inTurn(lenderId) {
              if (registry.holds(leaseId, secret, lenderId)) {
                RemoteDevicePools.asBorrowersCall(device) { installer.install(device, appFile) }
              } else {
                null
              }
            } ?: return@post call.respondGone()
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Console.log("[DeviceLease] install on $lenderId failed: ${e.message}")
            return@post call.respondError(HttpStatusCode.InternalServerError, e.message ?: e::class.java.simpleName)
          }
          Console.log("[DeviceLease] install on $lenderId: ${reply.message}")
          call.respondJson(RemoteInstallReply.serializer(), reply)
        } finally {
          appFile.delete()
        }
      }
      delete("$LEASES_PATH/{id}") {
        if (call.refusedFromBrowserOrProxy()) return@delete
        val leaseId = call.parameters["id"].orEmpty()
        val returned = registry.release(leaseId, call.request.headers[SECRET_HEADER].orEmpty())
          ?: return@delete call.respondGone()
        // Let go of each device's driver and session, so the next user starts clean. Each device
        // stays busy until its own cleanup is done, and goes back even if this request is cut off.
        try {
          returned.forEach { device ->
            try {
              val id = deviceId(device.id) ?: return@forEach
              inTurn(device.id) { calls.call(id, RemoteDeviceCall(device.id, RemoteDeviceCall.Op.END)) }
            } catch (e: CancellationException) {
              throw e
            } catch (e: Exception) {
              Console.log("[DeviceLease] ending ${device.id} failed: ${e.message}")
            } finally {
              registry.finishReturn(device.id)
            }
          }
        } finally {
          returned.forEach { registry.finishReturn(it.id) }
        }
        call.respondText("", status = HttpStatusCode.NoContent)
      }
    }
  }

  /** The largest app file a borrower may send to [INSTALL_PATH]. */
  const val MAX_UPLOAD_BYTES: Long = 4L shl 30

  /** How long a borrower may take to send an app file to [INSTALL_PATH]. */
  val UPLOAD_TIMEOUT: Duration = 15.minutes

  private fun tooLarge(max: Long) = "the app file is larger than this machine accepts (${max shr 20} MiB)"

  /** Copies [input] to [output]; false, having stopped, when it holds more than [max] bytes. */
  internal suspend fun copyAtMost(input: ByteReadChannel, output: OutputStream, max: Long): Boolean {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
      val read = input.readAvailable(buffer, 0, buffer.size)
      if (read < 0) return true
      total += read
      if (total > max) return false
      output.write(buffer, 0, read)
    }
  }

  /** Parses a [LeasableDevice.id] (`<platform>/<instance>`). */
  fun deviceId(id: String): TrailblazeDeviceId? {
    val instance = id.substringAfter('/', missingDelimiterValue = "")
    if (instance.isEmpty()) return null
    val platform = TrailblazeDevicePlatform.fromString(id) ?: return null
    return TrailblazeDeviceId(instance, platform)
  }

  /**
   * Refuses, and returns true for, a request from another machine, a browser, or a proxy. Lending
   * has no authentication, so it answers only this machine's loopback, where an ssh tunnel
   * ends. Anything else that can reach the daemon's port could lease a device and send
   * an app to run on it. A web proxy in front of the port could open it to anyone who can reach
   * that proxy.
   */
  private suspend fun ApplicationCall.refusedFromBrowserOrProxy(): Boolean {
    val reason = when {
      !isLoopback(request.local.remoteAddress) -> "device leasing is open only to this machine; reach it over an ssh tunnel"
      request.headers[HttpHeaders.Origin] != null -> "device leasing is not open to browsers"
      PROXY_HEADERS.any { request.headers[it] != null } -> "device leasing is not open through a proxy; connect over an ssh tunnel"
      else -> return false
    }
    respondError(HttpStatusCode.Forbidden, reason)
    return true
  }

  private val PROXY_HEADERS = listOf(HttpHeaders.XForwardedFor, HttpHeaders.Forwarded)

  /** Whether [address], a connection's remote address, is this machine's loopback. */
  internal fun isLoopback(address: String): Boolean =
    address == "localhost" || address == "::1" || address == "0:0:0:0:0:0:0:1" ||
      address.startsWith("127.") || address.startsWith("::ffff:127.")

  private suspend fun <T> ApplicationCall.respondJson(serializer: KSerializer<T>, value: T) =
    respondText(json.encodeToString(serializer, value), ContentType.Application.Json)

  private suspend fun ApplicationCall.respondError(status: HttpStatusCode, message: String, holder: String? = null) =
    respondText(json.encodeToString(LeaseError.serializer(), LeaseError(message, holder)), ContentType.Application.Json, status)

  private suspend fun ApplicationCall.respondGone() =
    respondError(HttpStatusCode.NotFound, "no such lease: it expired, was returned, does not hold that device, or the secret is wrong")
}
