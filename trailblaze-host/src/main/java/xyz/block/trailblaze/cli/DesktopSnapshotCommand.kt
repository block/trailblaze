package xyz.block.trailblaze.cli

import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import xyz.block.trailblaze.compose.driver.rpc.ComposeRpcClient
import xyz.block.trailblaze.compose.driver.rpc.ComposeRpcServer
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.util.Console
import java.io.File
import java.util.Base64
import java.util.concurrent.Callable

/**
 * Hidden CLI command that captures a screen-state snapshot from the **running Trailblaze
 * desktop app's own UI** via the Compose RPC server it exposes on `127.0.0.1:52600` (see
 * [ComposeRpcServer.COMPOSE_DEFAULT_PORT]).
 *
 * Demo path:
 *   1. `./trailblaze app` — launches the desktop window with the self-test server enabled.
 *   2. `./trailblaze desktop snapshot` — captures the live window, prints summary +
 *      optionally writes the screenshot to disk via `--out`.
 *
 * This command is hidden (see [DesktopCommand]) — promote to visible when the Compose
 * driver becomes a first-class device platform with `TrailblazeDevicePlatform.DESKTOP`.
 */
@Command(
  name = "snapshot",
  // Not hidden at this level: the parent [DesktopCommand] is hidden, so the entire
  // `desktop *` tree is suppressed from `trailblaze --help`. Keeping this subcommand
  // visible inside `trailblaze desktop --help` lets a user who already knows `desktop`
  // exists discover what it can do without grepping source.
  mixinStandardHelpOptions = true,
  description = [
    "Capture a screen-state snapshot of the running Trailblaze desktop window via the Compose RPC server.",
  ],
)
class DesktopSnapshotCommand : Callable<Int> {

  @Option(
    names = ["--port"],
    description = ["Port the desktop app's Compose RPC server is listening on (default: 52600)"],
  )
  var port: Int = TrailblazeDevicePort.COMPOSE_DEFAULT_RPC_PORT

  @Option(
    names = ["--out"],
    description = [
      "When set, decode the response's base64 screenshot and write the PNG to this path. " +
        "Skips the file write if the response has no screenshot.",
    ],
  )
  var out: File? = null

  @Option(
    names = ["--json"],
    description = ["Print the full GetScreenStateResponse as JSON instead of the human-readable summary."],
  )
  var jsonOutput: Boolean = false

  override fun call(): Int = runBlocking {
    val baseUrl = "http://127.0.0.1:$port"
    val client = ComposeRpcClient(baseUrl)
    try {
      // Fail fast with a clear hint when the user forgot to launch the desktop app —
      // RPC errors otherwise come back as a generic IOException stack tail.
      if (!client.waitForServer(maxAttempts = 3, delayMs = 200)) {
        Console.error(
          "No Compose RPC server reachable at $baseUrl. " +
            "Is the Trailblaze desktop app running? Start it with `trailblaze app`.",
        )
        return@runBlocking CommandLine.ExitCode.SOFTWARE
      }
      when (val result = client.getScreenState()) {
        is RpcResult.Success -> {
          val response = result.data
          if (jsonOutput) {
            // The response is large (full view hierarchy + base64 screenshot) — when JSON
            // is requested, emit it raw so the caller can `jq` against it. The summary is
            // for interactive use.
            Console.log(
              xyz.block.trailblaze.logs.client.TrailblazeJsonInstance.encodeToString(
                xyz.block.trailblaze.compose.driver.rpc.GetScreenStateResponse.serializer(),
                response,
              ),
            )
          } else {
            Console.log("Compose RPC: $baseUrl")
            Console.log("Window: ${response.width}x${response.height}")
            Console.log(
              "View hierarchy: ${response.viewHierarchy.children?.size ?: 0} top-level child(ren)",
            )
            response.trailblazeNodeTree?.let {
              Console.log("TrailblazeNode tree: ${it.aggregate().size} node(s)")
            }
            Console.log("Element refs: ${response.elementIdMapping.size}")
            Console.log("Screenshot: ${if (response.screenshotBase64 != null) "captured" else "(none)"}")
          }
          // Decode and write the screenshot if --out was passed and one is present.
          // Done after the summary print so the user gets the diagnostic even when the
          // file write is the actionable part.
          val outFile = out
          val screenshotBase64 = response.screenshotBase64
          if (outFile != null) {
            if (screenshotBase64 == null) {
              Console.error("--out specified but the response had no screenshot; nothing written.")
              return@runBlocking CommandLine.ExitCode.SOFTWARE
            }
            outFile.parentFile?.mkdirs()
            outFile.writeBytes(Base64.getDecoder().decode(screenshotBase64))
            Console.log("Screenshot written: ${outFile.absolutePath}")
          }
          CommandLine.ExitCode.OK
        }
        is RpcResult.Failure -> {
          Console.error(
            "Compose RPC call failed: ${result.errorType} ${result.message}" +
              (result.details?.let { " — $it" } ?: ""),
          )
          CommandLine.ExitCode.SOFTWARE
        }
      }
    } finally {
      client.close()
    }
  }
}
