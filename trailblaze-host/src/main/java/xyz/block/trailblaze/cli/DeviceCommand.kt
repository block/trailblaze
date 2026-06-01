package xyz.block.trailblaze.cli

import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.devices.WebInstanceIds
import xyz.block.trailblaze.util.Console
import java.util.concurrent.Callable
import kotlin.system.exitProcess

/**
 * Manage device connections — list available devices and connect to a session.
 *
 * Examples:
 *   trailblaze device                       - List available devices (default)
 *   trailblaze device list                  - List available devices
 *   trailblaze device connect ANDROID       - Connect an Android device to your session
 *   trailblaze device connect IOS           - Connect an iOS device to your session
 *   trailblaze device connect WEB           - Connect a web browser to your session
 */
@Command(
  name = "device",
  mixinStandardHelpOptions = true,
  description = ["List and connect devices (Android, iOS, Web)"],
  subcommands = [
    DeviceListCommand::class,
    DeviceConnectCommand::class,
    DeviceRebindCommand::class,
    DeviceDisconnectCommand::class,
    DeviceCreateCommand::class,
  ],
)
class DeviceCommand : Callable<Int> {

  @CommandLine.ParentCommand
  internal lateinit var parent: TrailblazeCliCommand

  override fun call(): Int {
    // Default action: list devices
    return DeviceListCommand.listDevices(parent)
  }
}

/**
 * List all available devices.
 */
@Command(
  name = "list",
  mixinStandardHelpOptions = true,
  description = ["List available devices"],
)
class DeviceListCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: DeviceCommand

  @CommandLine.Option(
    names = ["--all"],
    description = ["Include hidden platforms (e.g. the Compose desktop driver — `desktop/self`)."],
  )
  internal var showAll: Boolean = false

  override fun call(): Int {
    return listDevices(parent.parent, showAll = showAll)
  }

  companion object {
    fun listDevices(parent: TrailblazeCliCommand, showAll: Boolean = false): Int {
      Console.enableQuietMode()

      // Prefer the running daemon as the source of truth — it's the process that
      // actually owns the WebBrowserManager state across CLI invocations, so any
      // browser slots provisioned via `--device web/<id>` only show up there.
      // When no daemon is running, fall through to in-process discovery so the
      // command still works offline.
      val port = CliConfigHelper.resolveEffectiveHttpPort()
      val daemonEntries = fetchDevicesFromDaemonIfRunning(port)
      if (daemonEntries != null) {
        printEntries(daemonEntries)
        exitProcess(TrailblazeExitCode.SUCCESS.code)
      }

      val app = parent.appProvider()

      Console.info("Scanning for connected devices...")
      val allDevices = runBlocking {
        app.deviceManager.loadDevicesSuspend(applyDriverFilter = true)
      }

      // Filter out Revyl cloud devices — they require the revyl CLI which
      // may not be installed, and they clutter the output for local usage.
      // Filter platforms with `hidden = true` (Compose desktop) unless the user
      // passed `--all`, mirroring the top-level `trailblaze --help --all` escape hatch.
      val devices = allDevices.filter {
        it.trailblazeDriverType != TrailblazeDriverType.REVYL_ANDROID &&
          it.trailblazeDriverType != TrailblazeDriverType.REVYL_IOS &&
          (showAll || !it.platform.hidden)
      }.let { filtered ->
        // Re-include named web browser instances that the UI-level filter strips when
        // web mode is off. They're real, running browsers — users need them visible
        // here so they can reuse the same `--device web/<id>` across commands.
        val running = app.deviceManager.webBrowserManager.getAllRunningBrowserSummaries()
        val seen = filtered.map { it.instanceId to it.platform }.toMutableSet()
        val withRunning = filtered + running.filter { (it.instanceId to it.platform) !in seen }
          .also { added -> added.forEach { seen += it.instanceId to it.platform } }

        // Always include the playwright-native singleton — it's the always-available
        // virtual default that maps to bare `--device web`. Check by instanceId here
        // (not driver type), because named web instances also use PLAYWRIGHT_NATIVE
        // and would otherwise suppress the canonical default from the listing.
        if (withRunning.none { it.instanceId == WebInstanceIds.PLAYWRIGHT_NATIVE }) {
          withRunning + TrailblazeConnectedDeviceSummary(
            trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
            instanceId = WebInstanceIds.PLAYWRIGHT_NATIVE,
            description = "Playwright Browser (Native)",
          )
        } else {
          withRunning
        }
      }

      if (devices.isEmpty()) {
        Console.info("No devices found.")
        Console.info("")
        Console.info("To connect devices:")
        Console.info("  Android: Connect via USB or start an emulator")
        Console.info("  iOS:     Start an iOS simulator via Xcode")
        Console.info("  Web:     Always available (uses Playwright)")
        exitProcess(TrailblazeExitCode.SUCCESS.code)
      }

      Console.info("")
      val byPlatform = devices.groupBy { it.platform }
      Console.info("${devices.size} device(s) available. Pass --device on each device command:")
      Console.info("")
      // Warm the classifier cache for all devices in parallel before formatting. The
      // synchronous per-device formatDeviceType calls below then hit the cache instantly
      // instead of paying the ~200ms simctl-screenshot cost serially.
      DeviceClassifierResolver.warmCache(devices.map { it.platform to it.instanceId })
      byPlatform.forEach { (_, platformDevices) ->
        platformDevices.forEach { device ->
          val platformName = device.platform.name.lowercase()
          val deviceType = formatDeviceType(device.platform, device.instanceId)
          // Example uses `snapshot` (always-visible primitive) rather than `step` — the
          // AI commands are hidden behind `config ai-commands true` by default, and
          // pointing users at a command they can't see in `--help` defeats that gate.
          Console.info("  trailblaze snapshot --device $platformName/${device.instanceId}  ($deviceType)")
        }
      }

      // Force exit to terminate background services started by app initialization
      exitProcess(TrailblazeExitCode.SUCCESS.code)
    }

    /**
     * Returns the daemon's view of available devices, or null if no daemon is
     * running on [port] (in which case the caller should fall back to in-process
     * discovery). Never throws — connection failures and tool errors both yield
     * null so the user always gets *some* output.
     */
    private fun fetchDevicesFromDaemonIfRunning(port: Int): List<CliMcpClient.DeviceListEntry>? {
      return try {
        runBlocking {
          val running = DaemonClient(port = port).use { it.isRunning() }
          if (!running) return@runBlocking null
          CliMcpClient.connectOneShot(port = port).use { client ->
            val result = client.callTool("device", mapOf("action" to "LIST"))
            if (result.isError) null
            else CliMcpClient.parseDeviceList(result.content)
          }
        }
      } catch (_: Exception) {
        null
      }
    }

    private fun printEntries(entries: List<CliMcpClient.DeviceListEntry>) {
      if (entries.isEmpty()) {
        Console.info("No devices found.")
        Console.info("")
        Console.info("To connect devices:")
        Console.info("  Android: Connect via USB or start an emulator")
        Console.info("  iOS:     Start an iOS simulator via Xcode")
        Console.info("  Web:     Always available (uses Playwright)")
        return
      }
      Console.info("${entries.size} device(s) available. Pass --device on each device command:")
      Console.info("")
      // Warm the classifier cache for all entries in parallel before formatting. Same
      // rationale as the in-process path — keeps a multi-device listing from paying
      // the per-device probe cost serially.
      DeviceClassifierResolver.warmCache(entries.map { it.platform to it.instanceId })
      entries.forEach { entry ->
        val platformName = entry.platform.name.lowercase()
        val deviceType = formatDeviceType(entry.platform, entry.instanceId)
        val descSuffix = entry.description?.let { " — $it" } ?: ""
        // See in-process branch above — `snapshot` is the visible primitive that works
        // regardless of `ai-commands` opt-in. Keep these two example lines in sync.
        Console.info("  trailblaze snapshot --device $platformName/${entry.instanceId}  ($deviceType)$descSuffix")
      }
    }

    /**
     * Joins the per-device classifier list into the human-readable type label printed by
     * `device list` (e.g. `android-phone`, `ios-iphone`, `web`). The label comes from the
     * canonical [TrailblazeHostDeviceClassifier] — same classifier the runtime uses at
     * trail-run time, so the label here matches the filename convention recordings use on
     * disk (`<type>.trail.yaml`). For Android/iOS the resolver probes pixel dims to drive
     * the phone-vs-tablet branch; for Web/Compose only the platform classifier applies.
     *
     * `internal` so the join logic can be pinned by tests directly — promoting it from
     * `private` was the cheapest way to make the user-facing label shape testable without
     * shimming the entire `printEntries` call path.
     */
    internal fun formatDeviceType(
      platform: xyz.block.trailblaze.devices.TrailblazeDevicePlatform,
      instanceId: String,
    ): String = DeviceClassifierResolver.classifiersFor(platform, instanceId)
      .joinToString("-") { it.classifier }
  }
}

/**
 * Connect a device to the current CLI session.
 *
 * Examples:
 *   trailblaze device connect ANDROID
 *   trailblaze device connect IOS
 *   trailblaze device connect WEB
 */
@Command(
  name = "connect",
  mixinStandardHelpOptions = true,
  description = ["Connect a device + target to your session (use `eval $(...)` to pin TRAILBLAZE_DEVICE + TRAILBLAZE_TARGET to this shell)"],
)
class DeviceConnectCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: DeviceCommand

  @Parameters(
    index = "0",
    description = ["Device platform: ANDROID, IOS, or WEB (optionally with instance: android/emulator-5554)"],
  )
  lateinit var platform: String

  @CommandLine.Option(
    names = ["--target", "-t"],
    description = [
      "Target app to bind to this device's session (e.g. `default`, `sampleapp`). " +
        "Optional. When set, `eval \$(trailblaze device connect ... --target X)` also " +
        "exports \$TRAILBLAZE_TARGET so subsequent CLI calls in this shell re-apply " +
        "the binding automatically.",
    ],
  )
  var target: String? = null

  @CommandLine.Option(
    names = ["--mcp-session"],
    description = [
      "Explicit MCP session id to pin to this device (advanced). " +
        "Default: pin the most-recently-active unbound MCP client (Claude Desktop, " +
        "Cursor, Goose, …). No-op when no MCP clients are connected.",
    ],
  )
  var mcpSessionOverride: String? = null

  @CommandLine.Mixin
  val headlessOption: HeadlessOption = HeadlessOption()

  override fun call(): Int {
    return cliWithDaemon(verbose = false) { client ->
      // 1. Bind the device (existing daemon-side machinery via TrailblazeDeviceManager).
      val deviceError = client.ensureDevice(
        platform,
        webHeadless = headlessOption.resolve(),
      )
      if (deviceError != null) {
        Console.error(deviceError)
        return@cliWithDaemon TrailblazeExitCode.INFRA_FAILED.code
      }

      // 2. If --target was passed, bind it to the now-current device session.
      //    Reuses the existing session-scoped target override that `tool` / `step`
      //    use today, so the daemon-side machinery (SessionTargetRegistry) is the
      //    single source of truth for "what target is this device on."
      //
      //    [normalizeTargetId] keeps this surface in lockstep with the env-tier
      //    read ([envTrailblazeTarget]) and the pin resolver ([resolveCliTargetPin]):
      //    a `--target ' default '` invocation exports `TRAILBLAZE_TARGET=default`
      //    (not `' default '`), and the env pin survives a round-trip through
      //    the user's shell without the daemon-side lookup failing on the
      //    unintentional whitespace.
      //
      //    The "announcing" variant is the key piece. When the user re-runs
      //    `device connect <same-device> --target Y` after a prior connect
      //    with `--target X`, [connectReusable]'s file-tier target check
      //    matches (both reflect the config-tier value, NOT the --target arg
      //    — see [cliWithDaemon]'s `targetAppId = config.selectedTargetAppId`
      //    above) and the session is reused. The daemon then hot-swaps the
      //    per-device override silently, leaving the user with a "Reusing
      //    session …" line and no notice that their target changed. The
      //    announcing helper closes that gap by emitting `Target app changed
      //    (X -> Y) -- pinned on existing session.` to stderr when a prior
      //    session-override is being replaced.
      val resolvedTarget = normalizeTargetId(target)
      if (resolvedTarget != null) {
        val targetError = client.setSessionTargetForBoundDeviceAnnouncingChange(resolvedTarget)
        if (targetError != null) {
          Console.error("Failed to set target '$resolvedTarget': $targetError")
          return@cliWithDaemon TrailblazeExitCode.INFRA_FAILED.code
        }
      }

      // 3. Persist the platform choice for backwards-compatible "default platform"
      //    semantics on subsequent commands that haven't been migrated to read
      //    TRAILBLAZE_DEVICE yet. Safe to keep — the env-var resolver (added in a
      //    follow-on PR) wins over this when both are set.
      val platformStr = platform.split("/", limit = 2)[0]
      if (TrailblazeDevicePlatform.fromString(platformStr) != null) {
        CliConfigHelper.updateConfig { cfg -> cfg.copy(cliDevicePlatform = platformStr.uppercase()) }
      }

      // 4. Resolve the actual bound device id so the env var refers to a specific
      //    instance (e.g. `android/emulator-5554`) rather than the generic platform
      //    the user typed. Falls back to the user's spec if the lookup fails.
      val boundId = client.getBoundDeviceId()
      val deviceIdString = boundId?.toFullyQualifiedDeviceId() ?: platform

      // 4b. If a real MCP client (Claude Desktop, Cursor, Goose) is open and
      //    hasn't picked a device yet, adopt it into the same device + target
      //    we just bound. This gives MCP clients the same per-session OOBE
      //    that step 6's TRAILBLAZE_DEVICE export gives a shell.
      //
      //    No-op when no MCP clients are connected — `Pinned` is the only
      //    case that prints to stderr, the others (NoCandidates, Failed) stay
      //    quiet so users who aren't running MCP clients see clean output.
      try {
        val pinResult = client.pinMostRecentUnboundMcpSession(
          deviceSpec = deviceIdString,
          target = resolvedTarget,
          explicitSessionId = mcpSessionOverride,
        )
        when (pinResult) {
          is CliMcpClient.PinMcpSessionResult.Pinned ->
            Console.error("Also ${pinResult.message.replaceFirstChar { it.lowercase() }}.")
          CliMcpClient.PinMcpSessionResult.NoCandidates -> Unit // expected, silent
          is CliMcpClient.PinMcpSessionResult.Failed -> {
            // When the user passed --mcp-session explicitly, surface failures
            // on stderr (and stay non-fatal — the device bind itself worked,
            // the user's other shell tools will still function). A silent log
            // would hide typo'd ids since the user deliberately named a
            // session that didn't exist or was already bound.
            if (mcpSessionOverride != null) {
              Console.error("--mcp-session pin failed: ${pinResult.message}")
            } else {
              // No explicit override → silent best-effort, log only.
              Console.log("[device connect] MCP session pin skipped: ${pinResult.message}")
            }
          }
        }
      } catch (e: Exception) {
        // Same rationale as above: pinning is a best-effort affordance for
        // co-resident MCP clients, not load-bearing for the shell flow.
        Console.log("[device connect] MCP session pin threw: ${e.message}")
      }

      // 5. Status messages → stderr so `eval $(trailblaze device connect ...)` doesn't
      //    try to evaluate them as shell commands. Visible to interactive users either
      //    way (stderr renders to the terminal even when stdout is being captured).
      val targetSuffix = resolvedTarget?.let { " (target=$it)" }.orEmpty()
      Console.error("Connected $deviceIdString$targetSuffix.")
      Console.error("Pin to this shell: eval \$(trailblaze device connect $deviceIdString${resolvedTarget?.let { " --target $it" }.orEmpty()})")
      // First-time OOBE discovery hint. Surface the `-s` / require-steps trade-off
      // exactly once per connect so authors learn the durable-step contract self-
      // heal needs, without nagging on every tool call (which is the "paperwork"
      // feel Phase 1 of the OOBE fix is removing). Suppressed when the gate is
      // already on — the user has clearly opted in and the upsell is redundant.
      // [requireStepsEnabled] is the single read site shared with
      // [requireStepIfConfigured]; centralizing it keeps the two callsites from
      // drifting (and one Console.log appears on a corrupt-config read instead
      // of being silently swallowed in two places).
      if (!requireStepsEnabled()) {
        Console.error(
          "Tip: add `-s \"<what this step does>\"` to tool calls so the trail can self-heal " +
            "when the UI changes. To enforce on every call: `trailblaze config require-steps true`.",
        )
      }

      // 6. Shell-evaluable lines → stdout via the shared helper. This is what
      //    `eval $(...)` captures and runs in the parent shell to set
      //    TRAILBLAZE_DEVICE (and, when a target was passed, TRAILBLAZE_TARGET)
      //    for every subsequent CLI call in this terminal. The target export
      //    is the cross-invocation cousin of the daemon-side per-device
      //    override set in step 2: the daemon-side entry is wiped whenever a
      //    fresh MCP session re-claims the device (PR #3463 follow-up), but
      //    the env-pin survives because each new CLI invocation re-applies it
      //    via [resolveCliTargetPin] inside [cliReusableWithDevice].
      printShellExport("TRAILBLAZE_DEVICE", deviceIdString)
      if (resolvedTarget != null) {
        printShellExport("TRAILBLAZE_TARGET", resolvedTarget)
      }

      TrailblazeExitCode.SUCCESS.code
    }
  }
}

/**
 * Disconnect the current device and release its session.
 *
 * Pair with `device connect`: `eval $(trailblaze device disconnect)` prints
 * `unset TRAILBLAZE_DEVICE` so the shell stops routing to a device the daemon
 * no longer holds open. Without the `eval` wrapper, the daemon still releases
 * the session but the shell's env var is left dangling (harmless — subsequent
 * commands will fail at the daemon with "no active session" or auto-rebind
 * once that path lands).
 *
 * Symmetric clear: `device disconnect` ALWAYS emits both `unset TRAILBLAZE_DEVICE`
 * and `unset TRAILBLAZE_TARGET`, even when the prior `device connect` was bare
 * (no `--target`, so `TRAILBLAZE_TARGET` was not exported by that connect).
 * The contract is "the env-var lifecycle is owned by the connect/disconnect
 * pair as a unit" — leaving a stale TRAILBLAZE_TARGET behind after a
 * disconnect would silently contaminate the next `device connect` via
 * [resolveCliTargetPin]'s env-tier read. Users who manage `TRAILBLAZE_TARGET`
 * independently in their shell rc should set it AFTER any `device disconnect`
 * runs (or not use `eval $(...)` to consume the disconnect output).
 *
 * Examples:
 *   eval $(trailblaze device disconnect)               - Release the bound device + clear env vars
 *   trailblaze device disconnect                       - Release the bound device (env vars untouched)
 */
@Command(
  name = "disconnect",
  mixinStandardHelpOptions = true,
  description = ["Disconnect a device (use `eval $(...)` to also clear TRAILBLAZE_DEVICE + TRAILBLAZE_TARGET)"],
)
class DeviceDisconnectCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: DeviceCommand

  @CommandLine.Option(
    names = ["-d", "--device"],
    description = ["Device to disconnect. Defaults to \$TRAILBLAZE_DEVICE."],
  )
  var device: String? = null

  override fun call(): Int {
    // Require an explicit device target — either via --device or TRAILBLAZE_DEVICE.
    // Multi-terminal safety: in a shared-daemon world where any shell can stop the
    // daemon's currently-bound session, a bare `device disconnect` from a fresh
    // shell that never connected anything would happily terminate work belonging
    // to another shell. Force the caller to name the device they're disconnecting.
    val expectedDevice = resolveCliDevice(device)
    if (expectedDevice.isNullOrBlank()) {
      Console.error(
        "device disconnect needs a device to act on. Pass `--device <platform>` " +
          "or set TRAILBLAZE_DEVICE first (typically via `eval \$(trailblaze " +
          "device connect <platform>)`). This prevents accidentally stopping " +
          "another shell's session in the multi-terminal case.",
      )
      return TrailblazeExitCode.MISUSE.code
    }

    return cliWithDaemon(verbose = false) { client ->
      val port = CliConfigHelper.resolveEffectiveHttpPort()
      when (val outcome = stopBoundSessionIfMatches(client, expectedDevice)) {
        is StopBoundSessionResult.NoActiveSession -> {
          Console.error(
            "No device currently bound on the daemon. Clearing TRAILBLAZE_DEVICE " +
              "and TRAILBLAZE_TARGET in case they're stale.",
          )
          CliMcpClient.clearSession(port)
          // Symmetric with TRAILBLAZE_DEVICE: when `device connect --target X`
          // sets both env vars, `device disconnect` clears both. Leaving
          // TRAILBLAZE_TARGET set while TRAILBLAZE_DEVICE is unset would let
          // a stale target pin contaminate the next `device connect` in this
          // shell via `cliReusableWithDevice`'s [resolveCliTargetPin] read.
          printShellUnset("TRAILBLAZE_DEVICE")
          printShellUnset("TRAILBLAZE_TARGET")
          TrailblazeExitCode.SUCCESS.code
        }
        is StopBoundSessionResult.DeviceMismatch -> {
          Console.error(
            "Won't disconnect: you asked to release '$expectedDevice' but the " +
              "daemon's current session is bound to ${outcome.boundDevice.toFullyQualifiedDeviceId()}. " +
              "Either another shell connected after you did, or TRAILBLAZE_DEVICE is stale. " +
              "To release the other binding anyway: `trailblaze device disconnect " +
              "--device ${outcome.boundDevice.toFullyQualifiedDeviceId()}`. " +
              "To clear this shell's env vars only: `unset TRAILBLAZE_DEVICE TRAILBLAZE_TARGET`.",
          )
          TrailblazeExitCode.INFRA_FAILED.code
        }
        is StopBoundSessionResult.StopFailed -> {
          Console.error("Failed to stop session: ${outcome.error}")
          TrailblazeExitCode.INFRA_FAILED.code
        }
        is StopBoundSessionResult.Stopped -> {
          // Clear the local CLI session file — without this, the next call from
          // this shell would try to reattach to the now-closed session and fail
          // before auto-creating a new one. Mirrors `session stop`.
          CliMcpClient.clearSession(port)
          Console.error("Disconnected $expectedDevice.")
          printShellUnset("TRAILBLAZE_DEVICE")
          printShellUnset("TRAILBLAZE_TARGET")
          TrailblazeExitCode.SUCCESS.code
        }
      }
    }
  }
}

/**
 * Change the target app for the currently-bound device without forcing the
 * caller through a full `device disconnect` + `device connect --target X` cycle.
 *
 * The target is immutable mid-session — the same constraint that makes
 * `device connect --target` end-and-restart the session when re-binding the
 * same device — so this command stops the daemon-side session first, then
 * sets the new target. The daemon lazily starts a fresh session bound to the
 * new target on the next action command (`tool`, `step`, `snapshot`, etc.).
 *
 * TRAILBLAZE_DEVICE is unchanged: the device binding itself isn't moving, so
 * the shell env var that points at this device stays valid. No `eval $(...)`
 * wrapper is needed.
 *
 * Examples:
 *   trailblaze device rebind --target sampleapp
 *   trailblaze device rebind --device android/emulator-5554 -t default
 */
@Command(
  name = "rebind",
  mixinStandardHelpOptions = true,
  description = ["Change the target app for the currently-bound device"],
)
class DeviceRebindCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: DeviceCommand

  @CommandLine.Option(
    names = ["-d", "--device"],
    description = ["Device to rebind. Defaults to \$TRAILBLAZE_DEVICE."],
  )
  var device: String? = null

  @CommandLine.Option(
    names = ["--target", "-t"],
    description = ["New target app for the bound device (e.g. `default`, `sampleapp`)."],
    required = true,
  )
  var target: String? = null

  override fun call(): Int {
    // Same multi-terminal safety pin as `device disconnect`: refuse to operate
    // without an explicit device identifier (flag or env var), so a bare
    // `rebind` from a fresh shell can't accidentally tear down another
    // shell's session on the way to swapping its target.
    val expectedDevice = resolveCliDevice(device)
    if (expectedDevice.isNullOrBlank()) {
      Console.error(
        "device rebind needs a device to act on. Pass `--device <platform>` " +
          "or set TRAILBLAZE_DEVICE first (typically via `eval \$(trailblaze " +
          "device connect <platform>)`). This prevents accidentally rebinding " +
          "another shell's session in the multi-terminal case.",
      )
      return TrailblazeExitCode.MISUSE.code
    }

    // [normalizeTargetId] keeps this surface in lockstep with the env-tier
    // read and the pin resolver — see [DeviceConnectCommand.resolvedTarget]
    // for the round-trip story. A `--target ' default '` invocation should
    // never export trailing whitespace into `TRAILBLAZE_TARGET`; the next
    // action command's env-tier read would trim it anyway, but the daemon-
    // side `setSessionTargetForBoundDevice` lookup might not, so keeping the
    // export pre-normalized is the conservative contract.
    val newTarget = normalizeTargetId(target)
    if (newTarget == null) {
      Console.error("device rebind requires --target <app>.")
      return TrailblazeExitCode.MISUSE.code
    }

    return cliWithDaemon(verbose = false) { client ->
      val boundId = client.getBoundDeviceId()
      if (boundId == null) {
        Console.error(
          "No device currently bound; use `trailblaze device connect` first.",
        )
        return@cliWithDaemon TrailblazeExitCode.INFRA_FAILED.code
      }
      if (!deviceArgMatches(expectedDevice, boundId)) {
        Console.error(
          "Won't rebind: you asked to rebind '$expectedDevice' but the " +
            "daemon's current session is bound to ${boundId.toFullyQualifiedDeviceId()}. " +
            "Either another shell connected after you did, or TRAILBLAZE_DEVICE is stale. " +
            "To rebind the other binding anyway: `trailblaze device rebind " +
            "--device ${boundId.toFullyQualifiedDeviceId()} --target $newTarget`. " +
            "To clear this shell's env var only: `unset TRAILBLAZE_DEVICE`.",
        )
        return@cliWithDaemon TrailblazeExitCode.INFRA_FAILED.code
      }

      // Stop the current session first. Target is immutable mid-session, so
      // mirror the end-and-restart shape `device connect --target` uses when
      // re-binding the same device — the daemon will lazily start a fresh
      // session bound to the new target on the next action command.
      var sessionWasStopped = false
      when (val outcome = stopBoundSessionIfMatches(client, expectedDevice)) {
        is StopBoundSessionResult.NoActiveSession -> {
          // No session to stop — that's fine, we'll bind a new one when the
          // user runs their next action command against the new target.
        }
        is StopBoundSessionResult.DeviceMismatch -> {
          // The mismatch was already caught above, but `stopBoundSessionIfMatches`
          // re-checks atomically against the daemon's current view — if a third
          // shell raced in between our two reads, surface the same refusal shape.
          Console.error(
            "Won't rebind: the daemon's currently bound session is " +
              "${outcome.boundDevice.toFullyQualifiedDeviceId()}, not '$expectedDevice'.",
          )
          return@cliWithDaemon TrailblazeExitCode.INFRA_FAILED.code
        }
        is StopBoundSessionResult.StopFailed -> {
          Console.error("Failed to stop session before rebind: ${outcome.error}")
          return@cliWithDaemon TrailblazeExitCode.INFRA_FAILED.code
        }
        is StopBoundSessionResult.Stopped -> {
          // Continue — fall through to setSessionTargetForBoundDevice below.
          sessionWasStopped = true
        }
      }

      // Re-establish the per-session device binding before setting the target.
      // `SessionToolSet.handleStop` clears `sessionContext.associatedDeviceId`
      // as part of its cleanup, so `setSessionTargetForBoundDevice` below
      // would otherwise throw "No device is bound to this session" even
      // though the daemon still owns the underlying driver. The rebind is a
      // single `device(action=PLATFORM, deviceId=ID)` round-trip — for an
      // already-selected device the persistent driver is reused with no
      // re-launch cost. Skip the rebind on `NoActiveSession`: there was no
      // stop, so `associatedDeviceId` was never cleared and re-issuing the
      // device call would just be redundant work.
      if (sessionWasStopped) {
        val rebindError = client.rebindBoundDevice(boundId)
        if (rebindError != null) {
          Console.error("Failed to re-bind device before setting target: $rebindError")
          return@cliWithDaemon TrailblazeExitCode.INFRA_FAILED.code
        }
      }

      val setError = client.setSessionTargetForBoundDevice(newTarget)
      if (setError != null) {
        Console.error("Failed to set target '$newTarget': $setError")
        return@cliWithDaemon TrailblazeExitCode.INFRA_FAILED.code
      }

      // Status to stderr — the device binding itself hasn't moved, so
      // TRAILBLAZE_DEVICE is unchanged. The TARGET pin, however, has: emit a
      // shell-evaluable `export TRAILBLAZE_TARGET=<new>` on stdout so
      // `eval $(trailblaze device rebind --target X)` keeps the shell pin in
      // sync with the daemon-side override we just set. Without this, the
      // env var still says the OLD target and the next action command would
      // re-apply it via [resolveCliTargetPin], silently undoing the rebind.
      val deviceIdString = boundId.toFullyQualifiedDeviceId()
      Console.error("Rebound $deviceIdString to target=$newTarget.")
      Console.error("Pin to this shell: eval \$(trailblaze device rebind --device $deviceIdString --target $newTarget)")
      printShellExport("TRAILBLAZE_TARGET", newTarget)
      TrailblazeExitCode.SUCCESS.code
    }
  }
}
