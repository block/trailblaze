package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the env-override parsing for the MCP request/socket timeout (Fix #4). The default stays long
 * (agent operations legitimately take minutes), but operators can lower it via
 * `TRAILBLAZE_MCP_REQUEST_TIMEOUT_MS` so a wedged device fast-fails instead of hanging 3 minutes.
 * Malformed / non-positive values must fall back to the default rather than silently disabling the
 * timeout or throwing.
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

  private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
    val map = pairs.toMap()
    return { name -> map[name] }
  }
}
