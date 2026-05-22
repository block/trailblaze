package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import xyz.block.trailblaze.devices.WebInstanceIds
import xyz.block.trailblaze.devices.WebViewportSpec
import xyz.block.trailblaze.util.Console
import java.util.concurrent.Callable

/**
 * Parent for platform-scoped `device create <platform>` subcommands.
 *
 * Today only `web` is registered. iOS and Android slot in as siblings the day they
 * need bespoke provisioning flags (e.g. `device create ios --runtime "iOS 17.0"
 * --device-type "iPhone 15 Pro"`, `device create android --avd-name Pixel_7_API_34`).
 * Keeping each platform in its own picocli leaf class — rather than collapsing onto
 * a single `--platform <p>` flag with conditional siblings — means each platform's
 * help text lists only its own flags and the type system enforces "flag X is
 * web-only" without runtime if-platform-then-validate gymnastics.
 *
 * Running the parent with no subcommand prints help (picocli default behavior
 * when no `@Parameters` / `@Option` are declared and `call()` is unreachable).
 */
@Command(
  name = "create",
  mixinStandardHelpOptions = true,
  description = ["Provision a device with a configured profile (web today; iOS / Android future)."],
  subcommands = [
    DeviceCreateWebCommand::class,
  ],
)
class DeviceCreateCommand : Callable<Int> {

  @CommandLine.ParentCommand
  internal lateinit var parent: DeviceCommand

  override fun call(): Int {
    // No subcommand passed — show help and exit USAGE so scripts that forget to
    // pass `web` (etc.) surface the right diagnostic rather than silently no-op.
    CommandLine.usage(this, System.err)
    return CommandLine.ExitCode.USAGE
  }
}

/**
 * Provisions a Playwright web browser slot with a viewport / device-emulation
 * profile baked in. Does NOT launch a browser — the slot's spec is consumed the
 * next time a connect or trail launches a browser for this instance ID.
 *
 * Examples:
 *
 *     trailblaze device create web --instance-id mobile-iphone --emulate "iPhone 15"
 *     trailblaze device create web --instance-id desktop-large --viewport 1920x1080
 *     trailblaze device create web --emulate "Pixel 7"          # singleton slot
 *
 * `--emulate` resolves against Playwright's bundled `devices` registry (sets
 * viewport, deviceScaleFactor, userAgent, isMobile, hasTouch). `--viewport` is
 * the raw-dimensions escape hatch (no UA / DPR change). Mutually exclusive.
 *
 * Routes through the daemon's `device(action=CREATE_WEB, …)` MCP tool so the
 * provisioned spec persists across CLI invocations targeting the same daemon.
 */
@Command(
  name = "web",
  mixinStandardHelpOptions = true,
  description = [
    "Provision a Playwright web browser slot with a viewport / emulation profile.",
    "",
    "Does NOT launch a browser — the spec is stored on the slot and applied at the next browser launch.",
    "",
    "Examples:",
    "  trailblaze device create web --instance-id mobile-iphone --emulate \"iPhone 15\"",
    "  trailblaze device create web --instance-id desktop-large --viewport 1920x1080",
    "  trailblaze device create web --emulate \"Pixel 7\"",
  ],
)
class DeviceCreateWebCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: DeviceCreateCommand

  @Option(
    names = ["--instance-id"],
    paramLabel = "<id>",
    description = [
      "Slot name. Subsequent commands address this slot as `--device web/<id>`. " +
        "Defaults to the singleton `playwright-native` when omitted (the same slot the " +
        "desktop app's Launch Browser button operates on).",
    ],
  )
  var instanceId: String? = null

  @Option(
    names = ["--emulate"],
    paramLabel = "<preset>",
    description = [
      "Playwright `devices` preset name. Applies full device emulation: viewport, " +
        "deviceScaleFactor, userAgent, isMobile, hasTouch. Examples: 'iPhone 14', " +
        "'Pixel 7', 'iPad Pro 11'. Mutually exclusive with --viewport.",
    ],
  )
  var emulate: String? = null

  @Option(
    names = ["--viewport"],
    paramLabel = "<WxH>",
    description = [
      "Raw viewport size, e.g. '375x812' or '1920x1080'. Sets ONLY the viewport box — " +
        "does not change User-Agent, deviceScaleFactor, isMobile, or hasTouch, so pages " +
        "that UA-sniff still serve their desktop variant. Use --emulate for a full " +
        "mobile/tablet emulation profile. Mutually exclusive with --emulate.",
    ],
  )
  var viewport: String? = null

  /**
   * Tri-state: `--headless` → true, `--no-headless` → false, omitted → null
   * (defer to the slot's stored preference, falling back to WebBrowserManager's
   * smart "headed when a display is available, headless otherwise" default).
   *
   * The picocli `negatable` flag generates the `--no-headless` variant automatically.
   * Field type is `Boolean?` so omitted stays null rather than collapsing to false.
   */
  @Option(
    names = ["--headless"],
    negatable = true,
    description = [
      "Launch the browser headless (--headless) or headed (--no-headless). " +
        "When omitted, defers to the slot's stored preference, falling back to " +
        "the headed-when-display-available default — so running this on a desktop " +
        "without --headless does NOT force a hidden window.",
    ],
  )
  var headless: Boolean? = null

  override fun call(): Int {
    if (emulate != null && viewport != null) {
      Console.error("Error: --emulate and --viewport are mutually exclusive. Pick one.")
      return CommandLine.ExitCode.USAGE
    }

    val spec = emulate?.takeIf { it.isNotBlank() } ?: viewport?.takeIf { it.isNotBlank() }
    if (spec != null) {
      // Eager shape validation so a typo (`--viewport 375x`) fails USAGE here rather
      // than crashing later in the daemon's CREATE_WEB handler. Preset *name* typos
      // are surfaced at browser launch with a clearer "Unknown Playwright device
      // preset" error than parser code could produce.
      try {
        WebViewportSpec.parse(spec)
      } catch (e: IllegalArgumentException) {
        Console.error("Error: ${e.message}")
        return CommandLine.ExitCode.USAGE
      }
    }

    val resolvedInstanceId = instanceId?.takeIf { it.isNotBlank() } ?: WebInstanceIds.PLAYWRIGHT_NATIVE

    return cliWithDaemon(verbose = false) { client ->
      val args = buildMap<String, Any> {
        put("action", "CREATE_WEB")
        put("deviceId", resolvedInstanceId)
        if (spec != null) put("viewport", spec)
        // Only forward `headless` when the user explicitly passed --headless / --no-headless.
        // Sending null/omitting lets the daemon's CREATE_WEB handler inherit the slot's
        // stored preference (or WebBrowserManager's headed-when-display-available default).
        headless?.let { put("headless", it) }
      }
      val result = client.callTool("device", args)
      if (result.isError) {
        Console.error(result.content.ifBlank { "Failed to create web device." })
        return@cliWithDaemon CommandLine.ExitCode.SOFTWARE
      }
      Console.info(result.content)
      CommandLine.ExitCode.OK
    }
  }
}
