package xyz.block.trailblaze.cli

import kotlin.system.exitProcess

/**
 * Entry point for the MCP stdio proxy when spawned straight from a running daemon's own classpath
 * (`java -cp <daemon classpath> xyz.block.trailblaze.cli.McpProxyMainKt`).
 *
 * Trail Runner's external-agent supervisor uses this instead of a PATH-resolved `trailblaze mcp`.
 * The `trailblaze` an agent's environment resolves can be a different build than the running
 * daemon (a Homebrew release next to a source-checkout daemon), and a source checkout's
 * `./trailblaze` wrapper rebuilds jars and stops running daemons as a side effect - fatal when the
 * daemon being stopped is the one that spawned it. Re-entering the daemon's own classpath
 * guarantees the proxy (including its `approval_prompt` interception) always matches the daemon
 * build and starts in plain JVM-startup time.
 *
 * Environment:
 * - `TRAILRUNNER_DAEMON_PORT` - the spawning daemon's actual bound HTTP port. A dedicated variable
 *   because the CLI's normal port resolution lets a persisted non-default `serverPort` outrank the
 *   environment, which could route the proxy at a daemon other than the one that spawned it.
 *   Absent (manual invocation), falls back to the same resolution `trailblaze mcp` uses.
 * - `TRAILRUNNER_PERMISSION_RUN_ID` - read by [McpProxy] itself; enables human-approvable
 *   permission mode for that Trail Runner run.
 *
 * Device/target pinning mirrors `trailblaze mcp` with no flags: `TRAILBLAZE_DEVICE` /
 * `TRAILBLAZE_TARGET` from the environment, else the proxy's single-device autodetect.
 */
fun main() {
  val port = System.getenv("TRAILRUNNER_DAEMON_PORT")?.trim()?.toIntOrNull()
    ?: CliConfigHelper.resolveEffectiveHttpPort()
  exitProcess(
    McpProxy(
      port = port,
      initialDeviceSpec = resolveCliDevice(null),
      initialTarget = resolveCliTargetPin(null),
    ).run(),
  )
}
