package xyz.block.trailblaze.cli

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import xyz.block.trailblaze.logs.server.endpoints.CliExecRequest

/**
 * Tests for [TrailblazeCli.executeForDaemon]. Exercises the branches that
 * don't require a real [TrailblazeDesktopApp] / config to be wired:
 *  - subcommand not in allowlist → `forwarded = false`
 *  - empty argv → `forwarded = false`
 *  - allowlisted subcommand but providers never captured (run() not called) →
 *    `forwarded = true, exitCode = 1`, diagnostic on stderr
 *
 * The success and picocli-exception branches need real providers (and a live
 * device) to exercise meaningfully; covering them requires an integration
 * test harness that this unit suite does not set up.
 */
class TrailblazeCliExecuteForDaemonTest {

  @Test fun `empty args returns forwarded false`() {
    val response = TrailblazeCli.executeForDaemon(CliExecRequest(args = emptyList()))
    assertFalse(response.forwarded, "empty argv must not be forwarded")
    assertEquals(0, response.exitCode)
  }

  @Test fun `non-forwardable subcommand returns forwarded false`() {
    // `trail` resolves trail YAML against the caller's cwd, so it stays on the
    // JVM path — the shim falls back when forwarded=false. Use any other
    // non-allowlisted subcommand (`blaze`, `verify`, etc.) here if `trail`
    // ever joins the allowlist.
    val response = TrailblazeCli.executeForDaemon(CliExecRequest(args = listOf("trail", "some.trail.yaml")))
    assertFalse(response.forwarded)
    assertEquals(0, response.exitCode)
    // Response body should be empty — this path is a pure signal, no output.
    assertTrue(response.stdout.isEmpty())
    assertTrue(response.stderr.isEmpty())
  }

  @Test fun `allowlisted subcommand without providers returns exit 1 and diagnostic stderr`() {
    // `TrailblazeCli.run()` is never called in this unit test, so the
    // `appProviderRef` / `configProviderRef` remain null. `executeForDaemon`
    // must return `forwarded = true, exitCode = 1` with an explanatory
    // stderr rather than crashing or silently succeeding.
    //
    // NB: if any other test in this JVM does manage to call `run()`, this
    // test's invariant breaks. Keep `TrailblazeCli.run()` out of unit tests
    // or use separate JVMs per test class.
    val response = TrailblazeCli.executeForDaemon(CliExecRequest(args = listOf("snapshot")))
    assertTrue(response.forwarded, "allowlisted subcommand should be forwarded")
    assertEquals(1, response.exitCode, "missing-providers path must yield exitCode=1")
    assertTrue(
      response.stderr.contains("missing providers"),
      "stderr should indicate providers were never captured, got: '${response.stderr}'",
    )
  }
}
