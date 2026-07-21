package xyz.block.trailblaze.host.devices

import java.net.ServerSocket
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
}
