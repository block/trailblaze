package xyz.block.trailblaze.cli

import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.host.lease.DeviceLeaseClient
import xyz.block.trailblaze.host.lease.DeviceLeaseEndpoint
import xyz.block.trailblaze.host.lease.PairedDevicePools
import xyz.block.trailblaze.logs.server.LendingPort
import xyz.block.trailblaze.util.Console
import java.util.concurrent.Callable

/**
 * Pairs with a Trailblaze daemon that lends its devices, of any platform, so they appear in
 * `device list` and can be used like this machine's own. Leasing and returning them is automatic.
 */
@Command(
  name = "pair",
  mixinStandardHelpOptions = true,
  description = ["Use another machine's devices: pair with a Trailblaze daemon that lends them"],
  footer = [
    "",
    "The other daemon must run with ${DeviceLeaseEndpoint.ENV_VAR}=1. Its",
    "devices then show in 'trailblaze device list' as",
    "<platform>/<device>@<host>:<port> and run trails and tools like your own;",
    "using one leases it until you are done. With no URL, lists the paired",
    "daemons.",
    "",
    "A lending daemon answers only its own machine, so bring its lending",
    "port here over an ssh tunnel first: its daemon port minus 5 (52520 for",
    "the default 52525), or its ${LendingPort.ENV_VAR}. Never tunnel the",
    "daemon port itself, which trusts whatever reaches it over loopback.",
    "",
    "Example (lender on the default port):",
    "  ssh -N -L 31900:localhost:52520 build-box &",
    "  trailblaze device pair http://localhost:31900",
  ],
)
class DevicePairCommand : Callable<Int> {

  @Parameters(arity = "0..1", paramLabel = "URL", description = ["The lending daemon, as http://<host>:<port>"])
  var url: String? = null

  internal var paired: PairedDevicePools = PairedDevicePools()
  internal var clientFor: (String) -> DeviceLeaseClient = ::DeviceLeaseClient

  override fun call(): Int {
    val given = url ?: return list()
    val normalized = PairedDevicePools.normalize(given) ?: run {
      Console.error("Expected a URL like http://<host>:<port>, got: $given")
      return TrailblazeExitCode.MISUSE.code
    }
    val devices = try {
      clientFor(normalized).devices()
    } catch (e: Exception) {
      // Unreachable (IOException), or something there that isn't a lending daemon: it answered, but
      // not with a device list.
      Console.error("Could not pair with $normalized: ${e.message}")
      return TrailblazeExitCode.INFRA_FAILED.code
    }
    val added = paired.add(normalized)
    val free = devices.count { !it.leased }
    Console.info("${if (added) "Paired with" else "Already paired with"} $normalized: $free of ${devices.size} device(s) free.")
    return TrailblazeExitCode.SUCCESS.code
  }

  private fun list(): Int {
    val urls = paired.urls()
    if (urls.isEmpty()) Console.info("No paired daemons. Pair with: trailblaze device pair http://<host>:<port>")
    urls.forEach { Console.info(it) }
    return TrailblazeExitCode.SUCCESS.code
  }
}

/** Stops using a paired daemon's devices. A device leased from it now is returned as usual. */
@Command(
  name = "unpair",
  mixinStandardHelpOptions = true,
  description = ["Stop using a paired daemon's devices"],
)
class DeviceUnpairCommand : Callable<Int> {

  @Parameters(arity = "1", paramLabel = "URL", description = ["The paired daemon, as given to 'device pair'"])
  lateinit var url: String

  internal var paired: PairedDevicePools = PairedDevicePools()

  override fun call(): Int {
    val normalized = PairedDevicePools.normalize(url) ?: url.trim()
    if (!paired.remove(normalized)) {
      Console.error("Not paired with $normalized")
      return TrailblazeExitCode.MISUSE.code
    }
    Console.info("Unpaired $normalized.")
    return TrailblazeExitCode.SUCCESS.code
  }
}
