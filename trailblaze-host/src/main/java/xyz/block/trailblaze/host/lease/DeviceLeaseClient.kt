package xyz.block.trailblaze.host.lease

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Talks to a daemon's [DeviceLeaseEndpoint]. */
open class DeviceLeaseClient(baseUrl: String) {

  sealed interface LeaseOutcome {
    data class Granted(val lease: Lease) : LeaseOutcome

    /** [holder] is set when the device is in use by someone. */
    data class Busy(val message: String, val holder: String? = null) : LeaseOutcome
  }

  enum class RenewOutcome { RENEWED, LOST, UNREACHABLE }

  private val base = baseUrl.trimEnd('/')

  private val http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()

  private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
  }

  /**
   * Every device the pool lends, and whether it is lent out. Throws [IOException] when the pool
   * can't be reached; one that isn't there fails within [CONNECT_TIMEOUT].
   */
  open fun devices(): List<LeasableDeviceStatus> {
    val response = send(HttpRequest.newBuilder(URI("$base${DeviceLeaseEndpoint.DEVICES_PATH}")).GET())
    when (response.statusCode()) {
      200 -> Unit
      404 -> throw IOException(notLending())
      else -> throw IOException("device list failed: HTTP ${response.statusCode()} ${errorMessage(response)}")
    }
    return json.decodeFromString(ListSerializer(LeasableDeviceStatus.serializer()), response.body())
  }

  /** Throws [IOException] when the pool can't be reached or answers with something unexpected. */
  open fun lease(request: LeaseRequest): LeaseOutcome {
    val response = send(
      HttpRequest.newBuilder(URI("$base${DeviceLeaseEndpoint.LEASES_PATH}"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(LeaseRequest.serializer(), request))),
    )
    return when (response.statusCode()) {
      200 -> LeaseOutcome.Granted(json.decodeFromString(Lease.serializer(), response.body()))
      409 -> LeaseOutcome.Busy(errorMessage(response), errorHolder(response))
      404 -> throw IOException(notLending())
      else -> throw IOException("lease request failed: HTTP ${response.statusCode()} ${errorMessage(response)}")
    }
  }

  /** [busy] tells the pool the device is in use right now, so nobody else can take it. */
  open fun renew(lease: Lease, busy: Boolean = false): RenewOutcome = try {
    val response = send(
      HttpRequest.newBuilder(URI("$base${DeviceLeaseEndpoint.LEASES_PATH}/${lease.leaseId}/renew?busy=$busy"))
        .header(DeviceLeaseEndpoint.SECRET_HEADER, lease.secret)
        .POST(HttpRequest.BodyPublishers.noBody()),
    )
    when (response.statusCode()) {
      204, 200 -> RenewOutcome.RENEWED
      404 -> RenewOutcome.LOST
      else -> RenewOutcome.UNREACHABLE
    }
  } catch (e: IOException) {
    RenewOutcome.UNREACHABLE
  }

  /**
   * Runs [request] on a device [lease] holds. Throws [LeaseLostException] when the pool no longer
   * lends the device to this lease, and [IOException] when it can't be reached or the call failed
   * there. Waits as long as a tool may take.
   */
  open fun call(lease: Lease, request: RemoteDeviceCall): RemoteDeviceReply {
    val response = send(
      HttpRequest.newBuilder(URI("$base${DeviceLeaseEndpoint.LEASES_PATH}/${lease.leaseId}/call"))
        .header(DeviceLeaseEndpoint.SECRET_HEADER, lease.secret)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(RemoteDeviceCall.serializer(), request))),
      CALL_TIMEOUT,
    )
    return when (response.statusCode()) {
      200 -> json.decodeFromString(RemoteDeviceReply.serializer(), response.body())
      404 -> throw LeaseLostException("$base no longer lends ${request.device} to this machine: ${errorMessage(response)}")
      else -> throw IOException("${request.op} on ${request.device} failed: HTTP ${response.statusCode()} ${errorMessage(response)}")
    }
  }

  /**
   * Sends [appFile] (an `.apk`, or a `.zip` holding an `.app`) to be installed on [device], named as
   * the pool names it, which [lease] holds. Throws as [call] does.
   */
  open fun install(lease: Lease, device: String, appFile: File): RemoteInstallReply {
    val query = "device=${URLEncoder.encode(device, Charsets.UTF_8)}&file=${URLEncoder.encode(appFile.name, Charsets.UTF_8)}"
    val response = send(
      HttpRequest.newBuilder(URI("$base${DeviceLeaseEndpoint.LEASES_PATH}/${lease.leaseId}/install?$query"))
        .header(DeviceLeaseEndpoint.SECRET_HEADER, lease.secret)
        .header("Content-Type", "application/octet-stream")
        .POST(HttpRequest.BodyPublishers.ofFile(appFile.toPath())),
      INSTALL_TIMEOUT,
    )
    return when (response.statusCode()) {
      200 -> runCatching { json.decodeFromString(RemoteInstallReply.serializer(), response.body()) }
        .getOrElse { throw IOException("$base sent a reply this machine can't read: ${response.body().take(200)}", it) }
      // Only the lender's own "no such lease" is a lost lease. A lender too old to have this route
      // answers a bare 404, and its device is still lent: dropping it would end the borrower's use.
      404 -> leaseError(response)?.let { throw LeaseLostException("$base no longer lends $device to this machine: ${it.error}") }
        ?: throw IOException("$base can't install apps on the devices it lends; update Trailblaze there")
      else -> throw IOException("install on $device failed: HTTP ${response.statusCode()} ${errorMessage(response)}")
    }
  }

  /** Best effort: an unreachable pool takes the devices back when the lease expires. */
  open fun release(lease: Lease) {
    try {
      send(
        HttpRequest.newBuilder(URI("$base${DeviceLeaseEndpoint.LEASES_PATH}/${lease.leaseId}"))
          .header(DeviceLeaseEndpoint.SECRET_HEADER, lease.secret)
          .DELETE(),
      )
    } catch (_: IOException) {
    }
  }

  private fun send(builder: HttpRequest.Builder, timeout: Duration = REQUEST_TIMEOUT): HttpResponse<String> = try {
    http.send(builder.timeout(timeout).build(), HttpResponse.BodyHandlers.ofString())
  } catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    throw IOException("interrupted", e)
  }

  private fun notLending() = "$base does not lend devices: start that daemon with ${DeviceLeaseEndpoint.ENV_VAR}=1"

  private fun errorHolder(response: HttpResponse<String>): String? =
    runCatching { json.decodeFromString(LeaseError.serializer(), response.body()).holder }.getOrNull()

  private fun errorMessage(response: HttpResponse<String>): String = leaseError(response)?.error ?: response.body()

  private fun leaseError(response: HttpResponse<String>): LeaseError? =
    runCatching { json.decodeFromString(LeaseError.serializer(), response.body()) }.getOrNull()

  private companion object {
    /** A lender that isn't there fails fast; one that is takes a few seconds to list devices. */
    val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(3)
    val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)

    /** A single tool can wait on the device a long time (launching an app, waiting for a screen). */
    val CALL_TIMEOUT: Duration = Duration.ofMinutes(5)

    /**
     * Uploading a large app and installing it there: a production build can be over a gigabyte.
     * Longer than the lender's own budget, so the lender, not this wait, says when it took too long:
     * its upload limit ([DeviceLeaseEndpoint.UPLOAD_TIMEOUT], 15 minutes), up to 5 minutes each to
     * unzip and install, and a call already running on the device (up to [CALL_TIMEOUT]).
     */
    val INSTALL_TIMEOUT: Duration = Duration.ofMinutes(35)
  }
}

/** The lending machine took the device back, or the lease ended. */
class LeaseLostException(message: String) : IOException(message)

/**
 * One lease's renewals, saying when it is lost: when the pool says so, or when no renewal has
 * succeeded for the lease's whole TTL (the pool will have expired it by then). Brief outages shorter
 * than that are ridden out.
 */
internal class LeaseRenewals(private val lease: Lease, private val now: () -> Long = System::currentTimeMillis) {
  private var lastRenewedAt = now()

  /** Records one renewal's [outcome]; returns why the lease is lost, or null while it is still held. */
  fun record(outcome: DeviceLeaseClient.RenewOutcome): String? = when (outcome) {
    DeviceLeaseClient.RenewOutcome.RENEWED -> {
      lastRenewedAt = now()
      null
    }
    DeviceLeaseClient.RenewOutcome.LOST -> "the pool no longer holds this lease"
    DeviceLeaseClient.RenewOutcome.UNREACHABLE ->
      if (now() - lastRenewedAt >= lease.ttlMs) "the pool was unreachable for the lease's whole ${lease.ttlMs} ms" else null
  }
}
