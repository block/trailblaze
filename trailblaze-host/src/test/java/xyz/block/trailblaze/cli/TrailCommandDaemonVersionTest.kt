package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertContains
import xyz.block.trailblaze.logs.server.endpoints.CliStatusResponse

class TrailCommandDaemonVersionTest {

  @Test
  fun `run identifies the daemon version and port it will delegate to`() {
    val line = TrailCommand.daemonVersionLine(
      status = status(version = "2026.09.09.1"),
      port = 52525,
    )

    assertContains(line, "Daemon version: 2026.09.09.1")
    assertContains(line, "port 52525")
  }

  @Test
  fun `run still calls out the daemon when an old status has no version`() {
    val line = TrailCommand.daemonVersionLine(status = status(version = null), port = 52525)

    assertContains(line, "Daemon version: unknown (not reported by daemon)")
    assertContains(line, "port 52525")
  }

  @Test
  fun `run still calls out the daemon when status cannot be read`() {
    val line = TrailCommand.daemonVersionLine(status = null, port = 52525)

    assertContains(line, "Daemon version: unknown (status unavailable)")
    assertContains(line, "port 52525")
  }

  private fun status(version: String?) = CliStatusResponse(
    running = true,
    port = 52525,
    connectedDevices = 0,
    uptimeSeconds = 1,
    version = version,
  )
}
