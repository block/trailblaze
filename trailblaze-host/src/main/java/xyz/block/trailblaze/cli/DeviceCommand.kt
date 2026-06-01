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
      val availableSpecs = devices.map { "${it.platform.name.lowercase()}/${it.instanceId}" }.toSet()
      printPinHeaderIfPresent(availableSpecs)
      val byPlatform = devices.groupBy { it.platform }
      Console.info(
        "${devices.size} device(s) available. " +
          "In an interactive terminal, pin one with `trailblaze device connect <spec>` " +
          "and subsequent commands inherit it. " +
          "In a fresh-shell harness (Claude Code, CI), pass `--device <spec>` per call:",
      )
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

    /**
     * Print this terminal's pinned device at the top of `device list` so the user
     * can answer "what am I connected to?" without cross-referencing the list
     * against memory. Shared by the daemon-running and in-process branches so
     * the user sees the pin in both modes. No-op when no pin is set for this
     * terminal.
     *
     * Reads the pin from [readShellPinDevice], then delegates the wording to
     * [buildPinHeaderLines] — keeping that builder pure means tests can pin the
     * user-facing strings without spinning up a `Console` capture harness (the
     * JVM `Console` impl caches `System.out` at class load so `System.setOut`
     * doesn't redirect `info`-level output).
     */
    internal fun printPinHeaderIfPresent(availableSpecs: Set<String>) {
      val port = CliConfigHelper.resolveEffectiveHttpPort()
      val pinned = readShellPinDevice(port) ?: return
      buildPinHeaderLines(pinned, availableSpecs).forEach { Console.info(it) }
    }

    /**
     * Pure builder for the lines printed by [printPinHeaderIfPresent]. Returns
     * the lines in print order, including the trailing blank-line separator
     * that visually divides the pin header from the "N device(s) available"
     * block below.
     *
     * Matches-available case: brief confirmation + unpin hint.
     * Stale-pin case: tells the user the pin's device is gone and points them
     * at the list below as the recovery path. The stale pin self-evicts on
     * the next action command's `ensureDevice` failure — see
     * [evictShellPinIfMatches].
     */
    internal fun buildPinHeaderLines(pinned: String, availableSpecs: Set<String>): List<String> {
      // Case-insensitive match: `device connect` writes lowercase FQDNs via
      // `toFullyQualifiedDeviceId()` and `availableSpecs` is built with
      // `name.lowercase()`, so a strict `pinned in availableSpecs` works for
      // every production code path today. But there's a fallback in
      // `DeviceConnectCommand` (`boundId?.toFullyQualifiedDeviceId() ?: platform`)
      // that persists the raw user-typed `platform` string when the daemon's
      // `getBoundDeviceId` returns null — `Android` (mixed case) instead of
      // `android`. The fallback is rare but real, and a case-insensitive
      // match removes a class of "stale pin" false positives that would
      // mis-route through the recovery branch.
      val match = availableSpecs.any { it.equals(pinned, ignoreCase = true) }
      return if (match) {
        listOf(
          "Pinned for this terminal: $pinned",
          "  (run a device command without --device to use this; " +
            "`trailblaze device disconnect` to unpin)",
          "",
        )
      } else {
        listOf(
          "Pinned for this terminal: $pinned — but it's no longer connected. " +
            "Pick one from the list below with `trailblaze device connect <spec>`.",
          "",
        )
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
      val availableSpecs = entries.map { "${it.platform.name.lowercase()}/${it.instanceId}" }.toSet()
      printPinHeaderIfPresent(availableSpecs)
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
  description = ["Connect a device + target and pin them for this terminal so subsequent commands inherit the binding"],
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
        "Optional. When set, the target is recorded alongside the device in this " +
        "terminal's pin so subsequent CLI calls re-apply the binding automatically " +
        "until you `device disconnect` or pin a different target.",
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

      // 5. Pin the device for this terminal so subsequent commands default to
      //    it without flags or env vars. The pin survives daemon restart
      //    (it's a file under ~/.trailblaze keyed by the shell PID forwarded
      //    by the `./trailblaze` wrapper as TRAILBLAZE_SHELL_PID). Older
      //    wrappers that don't forward the PID skip this silently — the
      //    daemon-side bind still happened in step 1, so a follow-up call
      //    in the same shell still works via the `--device` flag or the
      //    TRAILBLAZE_DEVICE env tier.
      // Pass the resolved target through to the pin so a `device connect
      // --target X` survives a daemon restart — the per-device override
      // lives only in the daemon's in-memory SessionTargetRegistry, and
      // without persisting the target alongside the device id every action
      // command after a restart would degrade to workspace config.
      writeShellDevicePinIfPossible(deviceIdString, resolvedTarget)

      // 6. Status messages → stderr. Structured-success envelope that mirrors
      //    [reportCliError]'s shape (a `✓` header + indented body lines) so
      //    the success readout closes the loop on the error envelope that
      //    likely sent the user here — `✗ Snapshot failed / hint: pin one
      //    (subsequent commands inherit it)` flows naturally into
      //    `✓ Pinned X / next: re-run your command without --device`.
      //    Plain `Pinned X for this terminal.` got the job done but didn't
      //    tell the user what they could do next, which is the missing
      //    half of the error envelope's promise.
      val targetSuffix = resolvedTarget?.let { " (target=$it)" }.orEmpty()
      Console.error("✓ Pinned $deviceIdString$targetSuffix for this terminal")
      Console.error(
        "  next:  re-run your command without --device — every device command " +
          "in this terminal inherits the pin",
      )
      Console.error(
        "  scope: other terminals are independent. " +
          "Unpin with `trailblaze device disconnect`.",
      )
      // Catch the agent-harness case: the wrapper passed TRAILBLAZE_INTERACTIVE=0
      // because stdin isn't a tty. Claude Code's Bash tool, Cursor's shell, Codex,
      // and CI all qualify. In those environments each subsequent CLI invocation
      // is a fresh shell with a different PID, so the pin we just wrote is
      // invisible to the next call — directing the agent at `--device` per call
      // avoids the "I pinned it but it's not working" loop. Real humans in a
      // real terminal have a tty and never see this notice.
      if (!isInteractiveCaller()) {
        Console.error(
          "Note: this terminal looks non-interactive (AI agent, CI, or piped). " +
            "The pin won't survive into fresh shells — pass " +
            "`--device $deviceIdString` on each command instead.",
        )
      }
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

      // Stdout is intentionally empty for this command now that the file-pin
      // carries the binding. We used to print `export TRAILBLAZE_DEVICE=…`
      // here so `eval $(...)` could lift it into the parent shell — that
      // mechanism is obsolete with terminal-scoped pinning. Anyone who still
      // wants the env var as a manual override can `export TRAILBLAZE_DEVICE=…`
      // themselves; the resolver tier that reads it is unchanged.

      TrailblazeExitCode.SUCCESS.code
    }
  }
}

/**
 * Disconnect the current device and clear this terminal's pin.
 *
 * Pair with `device connect`. After this runs, the resolver falls through to
 * autodetect on subsequent CLI calls until a new `device connect` is issued.
 *
 * Stdout is intentionally empty. The historical `unset TRAILBLAZE_DEVICE` /
 * `unset TRAILBLAZE_TARGET` lines were emitted for users wiring
 * `eval $(trailblaze device disconnect)` in their shell, but `device connect`
 * stopped emitting `export TRAILBLAZE_DEVICE=…` once the file-pin took over
 * — leaving `disconnect` to emit unsets for env vars `connect` never set.
 * They surfaced as stray lines in normal terminal use and have been removed.
 * Users who explicitly `export TRAILBLAZE_DEVICE=…` as a manual override can
 * `unset` it themselves.
 *
 * Examples:
 *   trailblaze device disconnect                       - Release the device + clear this terminal's pin
 *   trailblaze device disconnect --device android/...  - Release a specific bound device by id
 */
@Command(
  name = "disconnect",
  mixinStandardHelpOptions = true,
  description = ["Disconnect a device and clear this terminal's pin"],
)
class DeviceDisconnectCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: DeviceCommand

  @CommandLine.Option(
    names = ["-d", "--device"],
    description = [
      "Device to disconnect. Defaults to `\$TRAILBLAZE_DEVICE` if set " +
        "manually, otherwise this terminal's pin (set by " +
        "`trailblaze device connect`).",
    ],
  )
  var device: String? = null

  override fun call(): Int {
    // Require an explicit device target — either via --device or TRAILBLAZE_DEVICE.
    // Multi-terminal safety: in a shared-daemon world where any shell can stop the
    // daemon's currently-bound session, a bare `device disconnect` from a fresh
    // shell that never connected anything would happily terminate work belonging
    // to another shell. Force the caller to name the device they're disconnecting.
    // Resolve the device the caller wants to release. `resolveCliDevice` only
    // covers --device and TRAILBLAZE_DEVICE; the file-pin written by
    // `device connect` is the primary mechanism now, so we also consult it
    // here. Without this, a user who connected via the file-pin and then
    // typed `trailblaze device disconnect` (no flag, no env) would get the
    // "needs a device" error instead of the obvious release.
    val port = CliConfigHelper.resolveEffectiveHttpPort()
    val expectedDevice = resolveCliDevice(device)
      ?: run {
        val pidStr = CliCallerContext.callerEnv("TRAILBLAZE_SHELL_PID")?.takeIf { it.isNotBlank() }
        val pid = pidStr?.toLongOrNull()?.takeIf { it > 0 }
        pid?.let {
          (ShellDevicePinStore.resolvePin(ShellDevicePinStore.pinFileFor(port), it)
            as? ShellDevicePinStore.PinLookup.Found)?.device
        }
      }
    if (expectedDevice.isNullOrBlank()) {
      Console.error(
        "device disconnect needs a device to act on. This terminal has no pin " +
          "(each terminal has its own — pinning in one doesn't show up in " +
          "another). Pass `--device <platform>/<id>` to name a specific device " +
          "to release, or run `trailblaze device list` to see what's currently " +
          "bound and try `trailblaze device disconnect --device <id>`.",
      )
      return TrailblazeExitCode.MISUSE.code
    }

    return cliWithDaemon(verbose = false) { client ->
      when (val outcome = stopBoundSessionIfMatches(client, expectedDevice)) {
        is StopBoundSessionResult.NoActiveSession -> {
          Console.error(
            "No device currently bound on the daemon. Clearing this terminal's pin " +
              "and any stale TRAILBLAZE_DEVICE / TRAILBLAZE_TARGET env vars.",
          )
          CliMcpClient.clearSession(port)
          // Clear the per-terminal file pin so the resolver doesn't keep
          // returning a now-stale entry on subsequent calls. Stdout stays
          // empty — see the class kdoc for why the legacy `unset …` lines
          // were dropped.
          clearShellDevicePinIfPossible()
          TrailblazeExitCode.SUCCESS.code
        }
        is StopBoundSessionResult.DeviceMismatch -> {
          Console.error(
            "Won't disconnect: you asked to release '$expectedDevice' but the " +
              "daemon's current session is bound to ${outcome.boundDevice.toFullyQualifiedDeviceId()}. " +
              "Either another terminal connected after you did, or this terminal's " +
              "pin is stale. To release the other binding anyway: `trailblaze device " +
              "disconnect --device ${outcome.boundDevice.toFullyQualifiedDeviceId()}`.",
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
          // Clear the per-terminal file pin so subsequent commands fall back
          // to autodetect (or a different terminal's pin) instead of trying
          // to use this just-disconnected device. Stdout stays empty — see
          // the class kdoc for why the legacy `unset …` lines were dropped.
          clearShellDevicePinIfPossible()
          Console.error("Disconnected $expectedDevice.")
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
 * The per-terminal pin's *device* doesn't move — only the bound target does
 * — so rebind doesn't touch `~/.trailblaze/shell-device-pins-<port>.json`.
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
    description = [
      "Device to rebind. Defaults to `\$TRAILBLAZE_DEVICE` if set " +
        "manually, otherwise this terminal's pin (set by " +
        "`trailblaze device connect`).",
    ],
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
    // without an explicit device identifier (flag or env var OR file-pin), so a
    // bare `rebind` from a fresh shell with no pin can't accidentally tear down
    // another shell's session on the way to swapping its target.
    val port = CliConfigHelper.resolveEffectiveHttpPort()
    val expectedDevice = resolveCliDevice(device)
      ?: run {
        val pidStr = CliCallerContext.callerEnv("TRAILBLAZE_SHELL_PID")?.takeIf { it.isNotBlank() }
        val pid = pidStr?.toLongOrNull()?.takeIf { it > 0 }
        pid?.let {
          (ShellDevicePinStore.resolvePin(ShellDevicePinStore.pinFileFor(port), it)
            as? ShellDevicePinStore.PinLookup.Found)?.device
        }
      }
    if (expectedDevice.isNullOrBlank()) {
      Console.error(
        "device rebind needs a device to act on. Pass `--device <platform>` " +
          "or `trailblaze device connect <platform>` first so this terminal " +
          "knows what to rebind.",
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
            "Either another terminal connected after you did, or this terminal's " +
            "pin is stale. To rebind the other binding anyway: `trailblaze device " +
            "rebind --device ${boundId.toFullyQualifiedDeviceId()} --target $newTarget`.",
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

      // Plain stderr confirmation. The device-side binding hasn't moved, so
      // this terminal's pin (which keys on device, not target) stays valid;
      // the new target is recorded daemon-side as a per-session override
      // that the resolver re-applies on every subsequent CLI call. Legacy
      // `eval $(trailblaze device rebind ...)` callers who relied on the
      // stdout `export TRAILBLAZE_TARGET=…` line will need to update — the
      // file-pin makes that mechanism redundant for the typical flow, and
      // anyone who wants the env var as a manual override can `export` it.
      val deviceIdString = boundId.toFullyQualifiedDeviceId()
      // Update the pin's target field. Without this, the next CLI call in
      // this terminal would read the pin's OLD target (or null), call
      // `setSessionTargetForBoundDevice` with the stale value, and silently
      // undo the rebind. Note: this also writes a fresh pin for terminals
      // that didn't have one (rebind via explicit --device from a shell
      // that never ran connect) — which matches user intent: they just
      // expressed a (device, target) binding for this shell.
      writeShellDevicePinIfPossible(deviceIdString, newTarget)
      Console.error("Rebound $deviceIdString to target=$newTarget.")
      TrailblazeExitCode.SUCCESS.code
    }
  }
}
