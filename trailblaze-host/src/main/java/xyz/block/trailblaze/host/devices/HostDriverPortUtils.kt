package xyz.block.trailblaze.host.devices

import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.Console

/**
 * Shared local-TCP-port utilities: whether a port is bindable or answering, waiting for one to come
 * free, and clearing whatever a previous session left on it.
 *
 * Used by the host driver factories (Android and iOS) and by the CLI's daemon auto-start, which is
 * the reason the probes here are written against what a real server socket does rather than against
 * any one caller's port.
 */
internal object HostDriverPortUtils {

  /**
   * True iff a server could bind [port] right now, and nothing is already listening on it — the
   * direct form of "is this port usable", as opposed to [isPortReachable], which asks whether
   * something is already *answering* on it.
   *
   * **Every probe sets `SO_REUSEADDR`, because a real server socket does.** Leaving it off invents
   * held ports: a port left in `TIME_WAIT` by a listener that closed with a connection established
   * refuses a `reuseAddress = false` bind for the 30–60s linger and accepts a `reuseAddress = true`
   * one (measured on this JDK). Servers set the option, so `TIME_WAIT` never blocks them, and
   * calling it held makes every restart look like a port conflict for a minute afterwards.
   *
   * **With the option on, one bind is no longer enough**, which is why this asks more than once.
   * BSD semantics let a wildcard bind succeed next to a listener bound to a *narrower* address, so
   * a wildcard-only probe reports free for a port that already has a `127.0.0.1` listener on it —
   * and a caller would then start a server whose port is shadowed for every loopback client. The
   * loopback addresses are therefore probed too; binding the same address a listener holds fails
   * with either setting, since `SO_REUSEADDR` only ever permits *differing* addresses.
   *
   * So: the wildcard probe answers "could a server bind at all", the loopback probes answer "is
   * something already there", and `TIME_WAIT` passes all of them, as it does for a real server.
   *
   * Probe sockets close immediately, so this is a reading and not a reservation; anything can claim
   * the port in the window afterwards.
   */
  fun isPortBindable(
    port: Int,
    // Injectable so a test can supply an address this host cannot assign, which is otherwise only
    // reachable by running on an IPv4-only machine.
    loopbackProbeAddresses: List<String> = LOOPBACK_BIND_PROBE_ADDRESSES,
  ): Boolean {
    val veto = bindVeto(port = port, loopbackProbeAddresses = loopbackProbeAddresses)
    if (veto != null) Console.log("Port $port is not bindable: $veto")
    return veto == null
  }

  /**
   * [isPortBindable]'s answer with its reason attached: null when a server could bind [port], and
   * otherwise what stopped it, phrased for a log line.
   *
   * Which probe vetoed is the one field that localizes a refusal, and the user-facing hint
   * deliberately drops `-sTCP:LISTEN` (what holds the port may not be a listener), so `lsof` cannot
   * answer it either. Returned rather than logged here so a caller polling this can report a reason
   * once instead of once per poll.
   */
  private fun bindVeto(port: Int, loopbackProbeAddresses: List<String>): String? {
    // An empty list would make the search below vacuously successful, silently dropping the "is
    // something already there" half of the answer and reporting every occupied port bindable.
    require(loopbackProbeAddresses.isNotEmpty()) { "at least one loopback probe address is required" }

    if (port !in MIN_TCP_PORT..MAX_TCP_PORT) {
      // Not "held" in any interesting sense, but "a server could bind it" is still false, and
      // saying so beats letting bind() throw IllegalArgumentException past every caller.
      return "it is outside the valid TCP range $MIN_TCP_PORT..$MAX_TCP_PORT, so nothing can bind it"
    }

    // The wildcard bind is the one a server actually makes, so an inconclusive answer here is
    // treated as "not bindable" — callers use this to decide whether to start something, and
    // guessing free on an unreadable port starts a server that dies on bind.
    when (bindProbe(address = null, port = port)) {
      BindProbeResult.IN_USE -> return "the wildcard bind a server makes was refused"
      BindProbeResult.INCONCLUSIVE -> return "the wildcard bind a server makes failed unreadably"
      BindProbeResult.FREE -> Unit
    }

    val vetoingAddress = loopbackProbeAddresses.firstOrNull { host ->
      // `BindException` does NOT mean "in use" on its own -- it is also what an address that does
      // not exist on this host throws ("Can't assign requested address", measured). So on an
      // IPv4-only host a bare `::1` probe would report EVERY port occupied and this function would
      // never return true again, disabling daemon auto-start outright. Confirm the address is
      // assignable before letting it veto the port. Paid only when a probe comes back IN_USE, so
      // the common free-port path is unaffected.
      bindProbe(address = host, port = port) == BindProbeResult.IN_USE && !isAddressUnassignable(host)
    }
    return vetoingAddress?.let { "$it already has something bound to it" }
  }

  /**
   * True iff [host] is an address this machine cannot bind at all, asked by binding it on port 0 --
   * which succeeds for any assignable address and throws the same `BindException` for one that is
   * not. That is what separates "this port is taken" from "this address does not exist here".
   *
   * Only `IN_USE` means unassignable. Anything else, including a port-0 bind that failed for its own
   * unrelated reason (ephemeral exhaustion, EMFILE), leaves the veto standing: discarding a real
   * listener because this second probe was unreadable reports the port bindable and the caller then
   * starts a server that dies on bind.
   */
  private fun isAddressUnassignable(host: String): Boolean =
    bindProbe(address = host, port = 0) == BindProbeResult.IN_USE

  private enum class BindProbeResult { FREE, IN_USE, INCONCLUSIVE }

  /** One bind attempt against [address] (null meaning the wildcard address) on [port]. */
  private fun bindProbe(address: String?, port: Int): BindProbeResult = try {
    ServerSocket().use { probe ->
      probe.reuseAddress = true
      probe.bind(if (address == null) InetSocketAddress(port) else InetSocketAddress(address, port))
    }
    BindProbeResult.FREE
  } catch (e: BindException) {
    BindProbeResult.IN_USE
  } catch (e: IOException) {
    // Anything other than BindException says nothing about whether the port is taken, and the two
    // are indistinguishable in a boolean, so record which one this was. At `Console.log` level
    // (i.e. under `-v`) because every caller already reports the refusal itself as an error; this
    // line is the detail behind it, and it would otherwise repeat once per poll in
    // [waitForPortRelease].
    Console.log(
      "Bind probe for ${address ?: "the wildcard address"}:$port failed with " +
        "${e::class.simpleName} rather than BindException: ${e.message}",
    )
    BindProbeResult.INCONCLUSIVE
  }

  /**
   * The loopback addresses every probe here covers, shared with the connect probe in
   * `CliInfrastructure` so the bind and connect halves of one decision cannot drift apart. Both
   * families, because the daemon's HTTP connector binds `::` and an IPv4-only probe would miss a
   * listener on an IPv6-only loopback host.
   */
  val LOOPBACK_BIND_PROBE_ADDRESSES = listOf("127.0.0.1", "::1")

  /**
   * True iff [port] is a number a TCP socket could name at all. A port outside this range is a
   * configuration error rather than a contended port, and it never changes — so a caller that would
   * otherwise re-read a held port for a while can skip that entirely.
   */
  fun isValidTcpPort(port: Int): Boolean = port in MIN_TCP_PORT..MAX_TCP_PORT

  private const val MIN_TCP_PORT = 1
  private const val MAX_TCP_PORT = 65535

  /**
   * Waits until a server could bind [port] — which is not quite "until nothing holds it", since a
   * port left in `TIME_WAIT` is bindable to a server and this stops waiting on it. See
   * [isPortBindable].
   *
   * @return true if the port became bindable within the timeout, false otherwise.
   */
  fun waitForPortRelease(
    port: Int,
    timeoutMs: Long,
  ): Boolean {
    val startTime = System.currentTimeMillis()
    var attempts = 0
    // Only when it changes: this polls every 100ms, and the reason is the same on nearly every
    // iteration by the nature of what it is waiting for.
    var reportedVeto: String? = null
    while (System.currentTimeMillis() - startTime < timeoutMs) {
      val veto = bindVeto(port = port, loopbackProbeAddresses = LOOPBACK_BIND_PROBE_ADDRESSES)
      if (veto == null) {
        Console.log("Port $port successfully released after ${System.currentTimeMillis() - startTime}ms")
        return true
      }
      if (veto != reportedVeto) {
        Console.log("Waiting for port $port: $veto")
        reportedVeto = veto
      }
      attempts++
      if (attempts % 10 == 0) {
        Console.log(
          "Still waiting for port $port to be released... " +
            "(${System.currentTimeMillis() - startTime}ms elapsed)",
        )
      }
      Thread.sleep(100)
    }
    Console.log("Warning: Port $port may still be in use after ${timeoutMs}ms timeout")
    return false
  }

  /**
   * Force-kills any local processes bound to the given port.
   * Uses `lsof` and `kill -9` — safe to call even if no process is using the port.
   */
  fun killProcessesUsingPort(port: Int) {
    try {
      val lsofProcess =
        ProcessBuilder(listOf("lsof", "-ti:$port")).redirectErrorStream(true).start()

      val lsofCompleted = lsofProcess.waitFor(5, TimeUnit.SECONDS)
      if (!lsofCompleted) {
        lsofProcess.destroyForcibly()
        return
      }

      val pids = lsofProcess.inputStream.bufferedReader().readText().trim()

      if (pids.isNotEmpty()) {
        pids.split("\n").filter { it.isNotBlank() }.forEach { pid ->
          try {
            ProcessBuilder(listOf("kill", "-9", pid.trim()))
              .start()
              .waitFor(2, TimeUnit.SECONDS)
          } catch (e: Exception) {
            // Ignore individual process kill failures
          }
        }
      }
    } catch (e: Exception) {
      // Ignore cleanup failures — don't prevent new connections
    }
  }

  /**
   * Probes whether a TCP listener is accepting connections at [host]:[port].
   *
   * Used to detect cached driver subprocesses that were reaped externally (SIGKILL,
   * OS reap, crash) — the in-process shutdown flag stays stale in that case, so we
   * confirm liveness with a short connect before reusing a cached client.
   *
   * Never throws; returns false on refused connection, timeout, or unknown host.
   */
  fun isPortReachable(host: String, port: Int, timeoutMs: Int = 500): Boolean {
    val socket = Socket()
    return try {
      socket.connect(InetSocketAddress(host, port), timeoutMs)
      true
    } catch (e: Exception) {
      false
    } finally {
      try {
        socket.close()
      } catch (e: Exception) {
        // Ignore close failures
      }
    }
  }

  /**
   * Removes a stale adb port forward for the given device and port.
   * Safe to call even if no forward exists.
   */
  fun removeStaleAdbPortForward(deviceInstanceId: String, port: Int) {
    try {
      AndroidHostAdbUtils.removePortForward(
        deviceId = TrailblazeDeviceId(deviceInstanceId, TrailblazeDevicePlatform.ANDROID),
        localPort = port,
      )
    } catch (e: Exception) {
      // Ignore cleanup failures
    }
  }
}
