package xyz.block.trailblaze.cli

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Headroom for a loaded CI box between the deadline firing and the coroutine unwinding. */
internal const val SCHEDULING_SLACK_MS = 10_000L

/**
 * How long a pre-flight step may take before the test itself gives up. One number for the
 * assertion and the guard below, so they cannot drift apart.
 */
internal const val PREFLIGHT_GUARD_MS = CliMcpClient.DEFAULT_PREFLIGHT_TIMEOUT_MS + SCHEDULING_SLACK_MS

/**
 * Runs [block] the way a pre-flight bound test needs it run.
 *
 * Two things, both about keeping these tests honest against the machine they run on:
 *
 * 1. Pins an EMPTY caller environment. The production resolver reads
 *    `TRAILBLAZE_MCP_PREFLIGHT_TIMEOUT_MS` (and the device vars) from the caller's environment,
 *    falling back to this JVM's. A developer or CI box with any of them exported would otherwise
 *    change the bound under the test and make it fail, or pass for the wrong reason. An
 *    authoritative empty map means every test here measures
 *    [CliMcpClient.DEFAULT_PREFLIGHT_TIMEOUT_MS].
 *
 * 2. Caps the whole thing at [PREFLIGHT_GUARD_MS]. These tests exist because an unbounded
 *    pre-flight step waits out the request deadline; without this guard, the regression they catch
 *    would not fail them, it would STALL them for that whole deadline and take the suite's wall
 *    clock with it. A fast, odd-looking `TimeoutCancellationException` is the better failure.
 */
internal fun <T> runUnderPreflightGuard(block: suspend () -> T): T = CliCallerContext.withCallerEnv(emptyMap()) {
  runBlocking { withTimeout(PREFLIGHT_GUARD_MS) { block() } }
}
