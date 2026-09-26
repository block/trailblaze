package xyz.block.trailblaze.host.devices

import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class HostDriverPortUtilsTest {

  @Test
  fun `isPortReachable returns true when a listener is bound`() {
    val serverSocket = ServerSocket(0)
    try {
      val port = serverSocket.localPort
      assertTrue(
        HostDriverPortUtils.isPortReachable("127.0.0.1", port, timeoutMs = 500),
        "Expected port $port to be reachable while ServerSocket is listening",
      )
    } finally {
      serverSocket.close()
    }
  }

  @Test
  fun `isPortReachable returns false when nothing is listening`() {
    // Probe port 1 (reserved, not bound on test machines) — localhost connect returns
    // ECONNREFUSED immediately. Avoids the bind-and-release race of grabbing an
    // ephemeral port and assuming nothing rebinds it before the probe runs.
    assertFalse(
      HostDriverPortUtils.isPortReachable("127.0.0.1", port = 1, timeoutMs = 500),
      "Expected port 1 (no listener) to not be reachable",
    )
  }

  @Test
  fun `isPortReachable returns false for an unreachable host without throwing`() {
    // Use a numeric address so DNS resolution cannot outlive the connect timeout. 0.0.0.0 is not
    // a routable remote destination and fails immediately without involving managed egress DNS.
    assertFalse(
      HostDriverPortUtils.isPortReachable("0.0.0.0", port = 22087, timeoutMs = 100),
    )
  }

  /**
   * `BindException` is not evidence that a port is taken: it is also what a bind to an address this
   * host cannot assign throws ("Can't assign requested address"). Left unhandled, the optional
   * `::1` probe reports EVERY port occupied on an IPv4-only machine, so `isPortBindable` never
   * returns true again and daemon auto-start stops working entirely.
   *
   * `192.0.2.1` is TEST-NET-1 (RFC 5737) — reserved for documentation and guaranteed not to be an
   * address of this machine, which is what makes this reproducible on a dual-stack developer Mac
   * instead of only on an IPv4-only host.
   */
  @Test
  fun `an address this host cannot assign does not make a free port look occupied`() {
    val freePort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    assertTrue(
      HostDriverPortUtils.isPortBindable(freePort, loopbackProbeAddresses = listOf("192.0.2.1")),
      "an unassignable probe address must not veto the port",
    )
  }

  @Test
  fun `an occupied loopback port is still vetoed`() {
    // The control for the test above: the assignability check must not defeat the veto it guards.
    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
      assertFalse(
        HostDriverPortUtils.isPortBindable(listener.localPort, loopbackProbeAddresses = listOf("127.0.0.1")),
        "a loopback listener has to keep vetoing its own port",
      )
    }
  }

  @Test
  fun `an unassignable probe address does not mask a real listener beside it`() {
    // The two above pass individually on a platform where `192.0.2.1` fails with something other
    // than BindException — the veto never fires, so there is nothing for the assignability check to
    // get wrong. This is the combination that pins it either way: whatever the unassignable address
    // does, the real one next to it still has to be heard.
    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
      assertFalse(
        HostDriverPortUtils.isPortBindable(
          listener.localPort,
          loopbackProbeAddresses = listOf("192.0.2.1", "127.0.0.1"),
        ),
        "an unassignable address must not swallow the veto from a genuine listener",
      )
    }
  }

  @Test
  fun `a port number no socket could name is not bindable`() {
    // `bind()` throws IllegalArgumentException for these rather than BindException, so without the
    // range guard a misconfigured TRAILBLAZE_PORT propagates an exception through every caller
    // instead of the "nothing can bind this" answer they all know how to report.
    assertFalse(HostDriverPortUtils.isPortBindable(0), "port 0 means 'pick one for me', not a port")
    assertFalse(HostDriverPortUtils.isPortBindable(65_536), "65536 is past the end of the TCP range")
    assertFalse(HostDriverPortUtils.isPortBindable(-1), "a negative port is not a port")
  }

  @Test
  fun `probing with no loopback addresses is rejected rather than answered`() {
    // An empty list would make the loopback search vacuously succeed, dropping the whole "is
    // something already there" half of the answer and reporting every occupied port bindable.
    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
      assertFailsWith<IllegalArgumentException> {
        HostDriverPortUtils.isPortBindable(listener.localPort, loopbackProbeAddresses = emptyList())
      }
    }
  }

  @Test
  fun `killing a port's processes kills its listener and spares a process only connected to it`() {
    // This JVM holding a client socket is the case the listener-only filter exists for: the daemon
    // connects to a runner's port, and killing everything on it would kill the daemon.
    val path = System.getenv("PATH").orEmpty().split(File.pathSeparator)
    assumeTrue(
      "needs lsof and nc on PATH",
      listOf("lsof", "nc").all { command -> path.any { File(it, command).canExecute() } },
    )
    val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
    // -k keeps the listening socket open while a connection is up, as an XCTest runner does.
    val listener = ProcessBuilder("nc", "-lk", "$port").start()
    try {
      val client = connectWithin(port, timeoutMs = 5_000)
      assumeTrue("nc -lk did not start listening on $port", client != null)
      client!!.use {
        val pids = HostDriverPortUtils.listeningPids(port)
        assertTrue(listener.pid().toString() in pids, "the listener must be found; got $pids")
        assertFalse(
          ProcessHandle.current().pid().toString() in pids,
          "a process only connected to $port must not be a kill target; got $pids",
        )

        HostDriverPortUtils.killProcessesUsingPort(port)

        assertTrue(listener.waitFor(5, TimeUnit.SECONDS), "the listener on $port must be killed")
      }
    } finally {
      listener.destroyForcibly()
    }
  }

  private fun connectWithin(port: Int, timeoutMs: Long): Socket? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val socket = Socket()
      try {
        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500)
        return socket
      } catch (e: Exception) {
        socket.close()
        Thread.sleep(100)
      }
    }
    return null
  }
}
