package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The proxy retries a failed POST for up to `maxRetryMs`, which is right for a daemon that is
 * down and wrong for one that already has the request: `tools/call` is not idempotent, so
 * resending a call the daemon is still executing can tap twice or take a payment twice.
 *
 * This was unreachable while the deadline was a fixed 630 s — the retry window (120 s) always
 * closed before the timeout could fire — and became reachable when the deadline started honouring
 * `TRAILBLAZE_MCP_REQUEST_TIMEOUT_MS`, because any value under the retry window lets the timeout
 * land while the loop is still willing to resend.
 */
class McpProxyTimeoutDoesNotResendTest {

  @Test
  fun `a request the daemon never answers is not resent`() {
    StarvedDaemonStub.start().use { daemon ->
      // A deadline far below the retry window, so absent the guard the loop has ~28 s of room to
      // resend. The stub accepts the POST and never answers it, which is the whole point: the
      // daemon HAS the request.
      val response = CliCallerContext.withCallerEnv(
        mapOf(CliMcpClient.REQUEST_TIMEOUT_ENV to "2000"),
      ) {
        val proxy = McpProxy(
          port = daemon.port,
          retryIntervalMs = 10,
          maxRetryMs = 30_000,
          daemonProbeOverride = { DaemonProbe.REACHABLE },
        )
        proxy.forwardRequest(
          """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"device","arguments":{}}}""",
        ) { }
      }

      assertEquals(
        1,
        daemon.deviceCalls.get(),
        "the daemon already had the call; a second POST would run a non-idempotent tool twice",
      )
      val body = response.orEmpty()
      assertTrue("did not answer" in body, "the answer must name the timeout: $body")
      assertTrue(
        "may still be running" in body,
        "the caller has to be told the tool was not cancelled, only abandoned: $body",
      )
      assertTrue(
        CliMcpClient.REQUEST_TIMEOUT_ENV in body,
        "and which knob moves the deadline: $body",
      )
      assertTrue(
        "daemon unavailable" !in body,
        "a daemon that accepted the POST is not unavailable, which is the retry loop's wording: $body",
      )
    }
  }

  @Test
  fun `the deadline still stops the request, rather than the retry window outliving it`() {
    // Guards the setup above as much as the behaviour: if the resolved deadline were ignored, this
    // would sit for the full 30 s retry window and the assertion on one POST would pass for the
    // wrong reason.
    StarvedDaemonStub.start().use { daemon ->
      val startedAt = System.nanoTime()
      CliCallerContext.withCallerEnv(mapOf(CliMcpClient.REQUEST_TIMEOUT_ENV to "2000")) {
        McpProxy(
          port = daemon.port,
          retryIntervalMs = 10,
          maxRetryMs = 30_000,
          daemonProbeOverride = { DaemonProbe.REACHABLE },
        ).forwardRequest(
          """{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"device","arguments":{}}}""",
        ) { }
      }
      val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
      assertTrue(elapsedMs < 20_000, "answered after ${elapsedMs}ms; the 2000ms deadline should have ended it")
      assertEquals(1, daemon.deviceCalls.get(), "and it reached the daemon exactly once")
    }
  }
}
