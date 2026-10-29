package xyz.block.trailblaze.host.networkcapture

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.block.trailblaze.events.SessionCaptureHealth

class MitmproxyOutputRelayTest {

  @Test
  fun `a loss the proxy reports counts even when no file can be written`() {
    // A file where the session folder goes: neither the log nor the marker can be created.
    val notADir = Files.createTempFile("not-a-dir", "").toFile().apply { deleteOnExit() }
    val sessionDir = File(notADir, "session")
    val output = (
      "starting\n" +
        MitmproxyAndroidNetworkCaptureActivator.CAPTURE_INCOMPLETE_LINE_PREFIX +
        "proxy network capture: a write failed (No space left on device)\n" +
        "still running\n"
      ).byteInputStream()

    MitmproxyAndroidNetworkCaptureActivator.relayProxyOutput(output, File(sessionDir, "mitmdump.log"), sessionDir)

    assertEquals(
      "proxy network capture: a write failed (No space left on device)",
      SessionCaptureHealth.failure(sessionDir, MitmproxyAndroidNetworkCaptureActivator.NETWORK_STREAM),
    )
    SessionCaptureHealth.clear(sessionDir, MitmproxyAndroidNetworkCaptureActivator.NETWORK_STREAM)
  }

  @Test
  fun `the proxy's output still reaches its log`() {
    val sessionDir = Files.createTempDirectory("session").toFile().apply { deleteOnExit() }
    val log = File(sessionDir, "mitmdump.log")

    MitmproxyAndroidNetworkCaptureActivator.relayProxyOutput("one\ntwo\n".byteInputStream(), log, sessionDir)

    assertEquals(listOf("one", "two"), log.readLines())
    assertEquals(null, SessionCaptureHealth.failure(sessionDir, MitmproxyAndroidNetworkCaptureActivator.NETWORK_STREAM))
  }
}
