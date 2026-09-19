package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the env-override parsing for the MCP request/socket timeout (Fix #4). The default stays long
 * (agent operations legitimately take minutes), but operators can lower it via
 * `TRAILBLAZE_MCP_REQUEST_TIMEOUT_MS` so a wedged device fast-fails instead of hanging for the full default.
 * Malformed / non-positive values must fall back to the default rather than silently disabling the
 * timeout or throwing.
 *
 * Also pins the connect budget's relationship to that override: it has to stay inside the request
 * budget, because the exception type the CLI turns into a recovery hint depends on which of the
 * two timers fires first.
 */
class CliMcpClientTimeoutTest {

  @Test
  fun defaultsWhenEnvUnset() {
    val resolved = CliMcpClient.resolveRequestTimeoutMs { null }
    assertEquals(CliMcpClient.DEFAULT_REQUEST_TIMEOUT_MS, resolved)
  }

  @Test
  fun defaultsWhenEnvBlank() {
    val resolved = CliMcpClient.resolveRequestTimeoutMs(env(CliMcpClient.REQUEST_TIMEOUT_ENV to "   "))
    assertEquals(CliMcpClient.DEFAULT_REQUEST_TIMEOUT_MS, resolved)
  }

  @Test
  fun usesValidPositiveOverride() {
    val resolved = CliMcpClient.resolveRequestTimeoutMs(env(CliMcpClient.REQUEST_TIMEOUT_ENV to "15000"))
    assertEquals(15_000L, resolved)
  }

  @Test
  fun fallsBackOnNonNumericValue() {
    val resolved = CliMcpClient.resolveRequestTimeoutMs(env(CliMcpClient.REQUEST_TIMEOUT_ENV to "soon"))
    assertEquals(CliMcpClient.DEFAULT_REQUEST_TIMEOUT_MS, resolved)
  }

  @Test
  fun fallsBackOnZeroOrNegative() {
    assertEquals(
      CliMcpClient.DEFAULT_REQUEST_TIMEOUT_MS,
      CliMcpClient.resolveRequestTimeoutMs(env(CliMcpClient.REQUEST_TIMEOUT_ENV to "0")),
    )
    assertEquals(
      CliMcpClient.DEFAULT_REQUEST_TIMEOUT_MS,
      CliMcpClient.resolveRequestTimeoutMs(env(CliMcpClient.REQUEST_TIMEOUT_ENV to "-5000")),
    )
  }

  // ---------------------------------------------------------------------------
  // The connect budget has to stay inside the request budget that races it. Ktor's request timer
  // covers connection setup, so if it expires first the failure is an
  // HttpRequestTimeoutException - which ioFailureHint reads as "the daemon has your request and
  // is still working on it". For a command that never left the CLI that advice is inverted, and
  // it is reachable from a supported env override rather than from a code change.
  // ---------------------------------------------------------------------------

  @Test
  fun `the default request budget leaves the full connect timeout intact`() {
    assertEquals(
      CliMcpClient.CONNECT_TIMEOUT_MS,
      CliMcpClient.connectTimeoutMsFor(CliMcpClient.DEFAULT_REQUEST_TIMEOUT_MS),
    )
  }

  @Test
  fun `a request budget shorter than the connect timeout still finishes connecting first`() {
    listOf(1L, 2L, 500L, 1_000L, CliMcpClient.CLEANUP_TIMEOUT_MS, 20_000L).forEach { budget ->
      val connect = CliMcpClient.connectTimeoutMsFor(budget)
      assertTrue(connect >= 1L, "connect budget must stay positive for ${budget}ms, was $connect")
      assertTrue(connect <= budget, "connect ${connect}ms must fit inside the ${budget}ms request budget")
    }
  }

  @Test
  fun `the client installs the clamped connect budget, not the fixed constant`() {
    // Pins the wiring: a client built with a 1s override must not be left holding the 10s
    // connect timeout, or an unreachable daemon reports itself busy.
    CliMcpClient(requestTimeoutMs = 1_000L, origin = null).use { client ->
      assertTrue(
        client.connectTimeoutMs < 1_000L,
        "expected a connect budget under the 1000ms request budget, was ${client.connectTimeoutMs}",
      )
    }
  }

  // ---------------------------------------------------------------------------
  // The override has to reach the JVM that actually waits. On the daemon-forwarded path
  // (`/cli/exec`: snapshot, ask, tool, config) that JVM is the daemon, whose env was frozen at
  // `app start` — so reading System.getenv there ignores what the user exported, and the timeout
  // error's own advice to raise this var does nothing.
  // ---------------------------------------------------------------------------

  @Test
  fun `the override is read from the callers shell, not the daemons frozen env`() {
    val resolved = CliCallerContext.withCallerEnv(mapOf(CliMcpClient.REQUEST_TIMEOUT_ENV to "42000")) {
      CliMcpClient.resolveRequestTimeoutMs()
    }
    assertEquals(42_000L, resolved)
  }

  @Test
  fun `a forwarded command with no override keeps the default`() {
    // The forwarded env map is authoritative: an absent key means the user's shell has the var
    // unset, so resolution must not reach past it into the daemon's own environment.
    val resolved = CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_DEVICE" to "android")) {
      CliMcpClient.resolveRequestTimeoutMs()
    }
    assertEquals(CliMcpClient.DEFAULT_REQUEST_TIMEOUT_MS, resolved)
  }

  @Test
  fun `a client built on the forwarded path installs the callers budget`() {
    // End-to-end wiring, not just the resolver: the constructor default is what a forwarded
    // `snapshot`/`tool` gets. A 1s caller budget clamps the connect timeout below the 10s
    // constant, so this fails if the client resolved the timeout from anywhere else.
    CliCallerContext.withCallerEnv(mapOf(CliMcpClient.REQUEST_TIMEOUT_ENV to "1000")) {
      CliMcpClient(origin = null).use { client ->
        assertTrue(
          client.connectTimeoutMs < CliMcpClient.CONNECT_TIMEOUT_MS,
          "expected the caller's 1000ms budget to clamp connect, was ${client.connectTimeoutMs}",
        )
      }
    }
  }

  // The pre-flight bound follows the same contract: a positive override is honoured, anything
  // else keeps the default rather than disabling the bound.

  @Test
  fun preflightDefaultsWhenEnvUnset() {
    assertEquals(CliMcpClient.DEFAULT_PREFLIGHT_TIMEOUT_MS, CliMcpClient.resolvePreflightTimeoutMs { null })
  }

  @Test
  fun preflightUsesValidPositiveOverride() {
    val resolved = CliMcpClient.resolvePreflightTimeoutMs(env(CliMcpClient.PREFLIGHT_TIMEOUT_ENV to "20000"))
    assertEquals(20_000L, resolved)
  }

  @Test
  fun preflightFallsBackOnMalformedOrNonPositive() {
    for (raw in listOf("soon", "0", "-1", "  ")) {
      assertEquals(
        CliMcpClient.DEFAULT_PREFLIGHT_TIMEOUT_MS,
        CliMcpClient.resolvePreflightTimeoutMs(env(CliMcpClient.PREFLIGHT_TIMEOUT_ENV to raw)),
        "'$raw' must fall back to the default",
      )
    }
  }

  /**
   * The cases above all inject [env], which leaves the DEFAULT source untested — and the default
   * source is the whole point of forwarding this variable. A command the launcher hands to the
   * daemon runs inside the daemon process, so reading this JVM's environment would find whatever
   * `app start` froze and silently ignore what the user just exported. Switching the default to
   * `System::getenv` would break that and pass every other test in this file.
   */
  @Test
  fun preflightReadsTheCallersEnvironmentNotThisJvm() {
    val resolved = CliCallerContext.withCallerEnv(
      mapOf(CliMcpClient.PREFLIGHT_TIMEOUT_ENV to "31000"),
    ) {
      CliMcpClient.resolvePreflightTimeoutMs()
    }
    assertEquals(31_000L, resolved)
    assertEquals(
      null,
      System.getenv(CliMcpClient.PREFLIGHT_TIMEOUT_ENV),
      "the value above came from the pinned caller env, so this JVM must not also have it set",
    )
  }

  private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
    val map = pairs.toMap()
    return { name -> map[name] }
  }
}
