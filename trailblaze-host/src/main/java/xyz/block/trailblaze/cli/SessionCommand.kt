package xyz.block.trailblaze.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.util.Console
import java.util.concurrent.Callable

/**
 * Shared usage-error message printed by every `session …` subcommand that accepts the
 * session ID both positionally and via `--id`. Kept here so the wording stays uniform.
 */
internal const val SESSION_ID_CONFLICT_MESSAGE: String =
  "Positional <session-id> and --id are two ways to spell the same thing — pass only one."

/**
 * Manage the CLI session — save recordings, end the session.
 *
 * Examples:
 *   trailblaze session info                    - Show session status
 *   trailblaze session save --title login_flow - Save recording as a trail
 *   trailblaze session save --id abc123        - Save a specific session as a trail
 *   trailblaze session recording               - Print recording YAML to stdout
 *   trailblaze session recording --id abc123   - Print recording for a specific session
 *   trailblaze session stop                    - End session, release device
 *   trailblaze session stop --save             - Save and end in one step
 */
@Command(
  name = "session",
  mixinStandardHelpOptions = true,
  description = ["Manage the current device session — save it as a replayable trail, inspect steps, end it"],
  subcommands = [
    SessionStartCommand::class,
    SessionStopCommand::class,
    SessionSaveCommand::class,
    SessionRecordingCommand::class,
    SessionInfoCommand::class,
    SessionListCommand::class,
    SessionArtifactsCommand::class,
    SessionDeleteCommand::class,
    SessionEndCommand::class,
  ],
)
class SessionCommand : Callable<Int> {
  @CommandLine.ParentCommand
  internal lateinit var cliRoot: TrailblazeCliCommand

  override fun call(): Int {
    CommandLine(this).usage(System.out)
    return TrailblazeExitCode.SUCCESS.code
  }
}

/**
 * Start a new session.
 *
 * The session is bound to one device — the CLI is single-shot over MCP and does
 * not durably honor a daemon-side "active" session, so we resolve the device on
 * every invocation via `--device` flag → `TRAILBLAZE_DEVICE` env var → MISUSE.
 * The flag itself is optional at parse time: pin a per-shell ambient with
 * `eval $(trailblaze device connect <platform>)` once and subsequent commands
 * pick it up from the env var. `--target` and `--mode` may be omitted if
 * already in config.
 *
 * Examples:
 *   trailblaze session start --target myapp --mode trail --device android
 *   trailblaze session start --device ios      (target/mode already configured)
 *   trailblaze session start                   (TRAILBLAZE_DEVICE pinned in this shell)
 */
@Command(
  name = "start",
  mixinStandardHelpOptions = true,
  description = ["Start a new session with automatic video and log capture"],
)
class SessionStartCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: SessionCommand

  @Option(
    names = ["--target"],
    description = [TARGET_OPTION_DESCRIPTION_SESSION],
  )
  var target: String? = null

  @Option(
    names = ["--mode"],
    description = ["Working mode: trail or blaze. Saved to config for future commands."],
  )
  var mode: String? = null

  @Option(
    names = ["-d", "--device"],
    description = ["Device: platform (android, ios, web) or platform/id. Defaults to \$TRAILBLAZE_DEVICE."],
  )
  var device: String? = null

  @Option(
    names = ["--title"],
    description = ["Title for the session (used as trail name when saving)"],
  )
  var title: String? = null

  @Option(
    names = ["--no-video"],
    description = ["Disable video capture"],
  )
  var noVideo: Boolean = false

  @Option(
    names = ["--no-logs"],
    description = ["Disable device log capture"],
  )
  var noLogs: Boolean = false

  @Option(
    names = ["-v", "--verbose"],
    description = ["Enable verbose output"],
  )
  var verbose: Boolean = false

  @CommandLine.Mixin
  val headlessOption: HeadlessOption = HeadlessOption()

  override fun call(): Int {
    // Validate --mode early before touching config.
    val normalizedMode = mode?.lowercase()
    if (normalizedMode != null && normalizedMode !in setOf("trail", "blaze")) {
      Console.error("Error: --mode must be 'trail' or 'blaze' (got '$mode').")
      return TrailblazeExitCode.MISUSE.code
    }

    // Resolve --device → $TRAILBLAZE_DEVICE → null. The session lifecycle is per-device,
    // so we need a device explicitly: either the user named it on this invocation, or
    // they pinned it in the shell via `eval $(trailblaze device connect <platform>)`.
    val resolvedDevice = when (val r = requireSessionDevice(device, verb = "Session start")) {
      is DeviceResolution.Resolved -> r.deviceSpec
      else -> return r.exitCodeFallback()
    }

    if (normalizedMode != null) {
      CliConfigHelper.updateConfig { it.copy(cliMode = normalizedMode) }
    }
    val currentConfig = CliConfigHelper.getOrCreateConfig()

    if (!verbose) Console.enableQuietMode()
    val port = CliConfigHelper.resolveEffectiveHttpPort()
    // Session-scoped target: prefer the explicit `--target` flag, fall back
    // to `TRAILBLAZE_TARGET` env var (per-shell pin from `eval $(trailblaze
    // device connect ... --target X)`), else anchor on the daemon-wide
    // default so connectReusable can detect a toolset change and recreate
    // the per-device session when needed. Per-device target overrides live
    // in the daemon's in-memory map (set via setSessionTargetForBoundDevice
    // below) — never written to disk. `--target=clear` is the explicit
    // unset, flag-only (an env pin of `clear` is treated as unset, not as
    // a clear request — see [resolveCliTargetPin]).
    //
    // Shared helper keeps this in lockstep with [cliReusableWithDevice]; both
    // call sites reuse the same payload-shape resolution so a third action
    // command that wants the same semantics doesn't copy the logic a third
    // time.
    val daemonCall = resolveCliTargetDaemonCall(target)
    val targetAppId = daemonCall.pin ?: currentConfig.selectedTargetAppId

    return runBlocking {
      val client = connectOrStartDaemonReusable(port, targetAppId = targetAppId)
        ?: return@runBlocking TrailblazeExitCode.INFRA_FAILED.code

      client.use {
        // --device is resolved (flag → $TRAILBLAZE_DEVICE), so no config-default or
        // auto-detect fallback. The user tells us the device explicitly on every
        // invocation or via the shell-pinned env var; we honor it directly.
        val deviceError = it.ensureDevice(
          deviceSpec = resolvedDevice,
          webHeadless = headlessOption.resolve(),
        )
        if (deviceError != null) {
          Console.error(deviceError)
          return@runBlocking TrailblazeExitCode.INFRA_FAILED.code
        }
        // Save the platform to config so other commands (e.g., `trail`) that still
        // fall back to cliDevicePlatform when --device is omitted see the most recent
        // value. The session lifecycle no longer falls back to it itself.
        val platformStr = resolvedDevice.split("/", limit = 2)[0]
        if (TrailblazeDevicePlatform.fromString(platformStr) != null) {
          CliConfigHelper.updateConfig { cfg -> cfg.copy(cliDevicePlatform = platformStr.uppercase()) }
        }
        // Session-scope the target on the daemon for the bound device when
        // the user pinned one — either via explicit `--target` or via
        // `TRAILBLAZE_TARGET` in the calling shell. No-op when neither tier
        // supplies a value. `--target=clear` (flag-only) sends an empty
        // string to clear the override.
        if (daemonCall.payload != null) {
          val setError = it.setSessionTargetForBoundDevice(daemonCall.payload)
          if (setError != null) {
            Console.error(setError)
            // Mirror cliReusableWithDevice's env-source hint so a stale shell
            // pin surfaces with a one-liner recovery rather than a mystery.
            if (target == null && daemonCall.pin != null) {
              Console.error(
                "  hint: TRAILBLAZE_TARGET=${daemonCall.pin} is your shell pin; " +
                  "`unset TRAILBLAZE_TARGET` to drop it, or pass --target=clear",
              )
            }
            return@runBlocking TrailblazeExitCode.INFRA_FAILED.code
          }
        }

        val arguments = mutableMapOf<String, Any?>("action" to "START")
        if (title != null) arguments["title"] = title
        if (noVideo) arguments["noVideo"] = true
        if (noLogs) arguments["noLogs"] = true

        val result = it.callTool("session", arguments)
        if (result.isError) {
          Console.error("Error: ${extractErrorMessage(result.content)}")
          TrailblazeExitCode.INFRA_FAILED.code
        } else {
          try {
            val json = Json.parseToJsonElement(result.content).jsonObject
            val error = json["error"]?.jsonPrimitive?.content
            if (!error.isNullOrBlank()) {
              Console.error("Error: $error")
              return@use TrailblazeExitCode.INFRA_FAILED.code
            }
            val msg = json["message"]?.jsonPrimitive?.content
            val sessionId = json["sessionId"]?.jsonPrimitive?.content
            if (sessionId != null) Console.info("Session: trailblaze session info --id $sessionId")
            if (msg != null) Console.info(msg)
          } catch (_: Exception) {
            Console.info(result.content)
          }
          TrailblazeExitCode.SUCCESS.code
        }
      }
    }
  }
}

@Command(
  name = "stop",
  mixinStandardHelpOptions = true,
  description = ["Stop the current session and finalize captures"],
)
class SessionStopCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: SessionCommand

  @Option(
    names = ["-d", "--device"],
    description = ["Device: platform (android, ios, web) or platform/id. Defaults to \$TRAILBLAZE_DEVICE."],
  )
  var device: String? = null

  @Option(
    names = ["--save"],
    description = ["Save session as a trail before stopping"],
  )
  var save: Boolean = false

  @Option(
    names = ["--title", "-t"],
    description = ["Trail title when saving (overrides session title)"],
  )
  var title: String? = null

  override fun call(): Int {
    // Resolve --device → $TRAILBLAZE_DEVICE → null. `stop` is per-device, so we
    // need an explicit target — either passed on this invocation or pinned in
    // the shell. See DeviceDisconnectCommand for the multi-terminal safety note.
    val resolvedDevice = when (val r = requireSessionDevice(device, verb = "Session stop")) {
      is DeviceResolution.Resolved -> r.deviceSpec
      else -> return r.exitCodeFallback()
    }

    val port = CliConfigHelper.resolveEffectiveHttpPort()
    if (!DaemonClient(port = port).use { it.isRunningBlocking() }) {
      Console.log("No active session (daemon not running).")
      CliMcpClient.clearSession(port)
      return TrailblazeExitCode.SUCCESS.code
    }

    return runBlocking {
      val client = try {
        CliMcpClient.connectReusable(port)
      } catch (_: Exception) {
        Console.log("No active session.")
        CliMcpClient.clearSession(port)
        return@runBlocking TrailblazeExitCode.SUCCESS.code
      }

      var exitCode = TrailblazeExitCode.SUCCESS.code
      client.use {
        val extraArgs = buildMap<String, Any?> {
          if (save) put("save", true)
          if (title != null) put("title", title)
        }
        when (val outcome = stopBoundSessionIfMatches(it, resolvedDevice, extraArgs)) {
          is StopBoundSessionResult.NoActiveSession -> {
            Console.log("No active session for device $resolvedDevice.")
            // Fall through to clearSession + SUCCESS at the bottom.
          }
          is StopBoundSessionResult.DeviceMismatch -> {
            Console.error(
              "No active session for device $resolvedDevice — the daemon's current session is " +
                "bound to ${outcome.boundDevice.toFullyQualifiedDeviceId()}. Pass --device " +
                "${outcome.boundDevice.toFullyQualifiedDeviceId()} if you meant to stop that one.",
            )
            return@runBlocking TrailblazeExitCode.INFRA_FAILED.code
          }
          is StopBoundSessionResult.StopFailed -> {
            Console.error("Error: ${extractErrorMessage(outcome.error)}")
            exitCode = TrailblazeExitCode.INFRA_FAILED.code
          }
          is StopBoundSessionResult.Stopped -> {
            outcome.message?.let { msg -> Console.info(msg) }
          }
        }
      }

      CliMcpClient.clearSession(port)
      exitCode
    }
  }
}

@Command(
  name = "list",
  mixinStandardHelpOptions = true,
  description = ["List recent sessions"],
)
class SessionListCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: SessionCommand

  @Option(
    names = ["--limit", "-n"],
    description = ["Maximum number of sessions to show (default: 10)"],
  )
  var limit: Int = 10

  @Option(
    names = ["--all", "-a"],
    description = ["Show all sessions in a flat chronological list"],
  )
  var all: Boolean = false

  override fun call(): Int {
    if (limit < 0) {
      Console.error("Error: --limit must be non-negative (got $limit).")
      return TrailblazeExitCode.MISUSE.code
    }

    val port = CliConfigHelper.resolveEffectiveHttpPort()

    return runBlocking {
      val client = try {
        CliMcpClient.connectReusable(port)
      } catch (_: Exception) {
        reportDaemonUnreachable()
        return@runBlocking TrailblazeExitCode.INFRA_FAILED.code
      }

      client.use {
        val fetchLimit = if (all) limit else limit + 20 // fetch extra so we have enough completed
        val result = it.callTool("session", mapOf("action" to "LIST", "limit" to fetchLimit))
        if (result.isError) {
          Console.error("Error: ${extractErrorMessage(result.content)}")
          return@use TrailblazeExitCode.INFRA_FAILED.code
        }
        try {
          val json = Json.parseToJsonElement(result.content).jsonObject
          val sessions = json["sessions"] as? JsonArray
          if (sessions == null || sessions.isEmpty()) {
            Console.info("No sessions found.")
            return@use TrailblazeExitCode.SUCCESS.code
          }

          if (all) {
            printSessionTable(sessions)
          } else {
            printGroupedSessions(sessions, limit)
          }
        } catch (_: Exception) {
          Console.info(result.content)
        }
        TrailblazeExitCode.SUCCESS.code
      }
    }
  }

  private fun printSessionTable(sessions: JsonArray) {
    Console.info("%-40s  %-20s  %-20s  %s".format("ID", "STATUS", "STARTED", "TITLE"))
    Console.info("-".repeat(100))
    for (entry in sessions) {
      printSessionRow(entry.jsonObject)
    }
  }

  private fun printGroupedSessions(
    sessions: JsonArray,
    recentLimit: Int,
  ) {
    val inProgress = sessions.filter {
      val s = it.jsonObject["status"]?.jsonPrimitive?.content ?: ""
      s == "In Progress"
    }
    val completed = sessions.filter {
      val s = it.jsonObject["status"]?.jsonPrimitive?.content ?: ""
      s != "In Progress"
    }

    if (inProgress.isNotEmpty()) {
      Console.info("In Progress (${inProgress.size})")
      Console.info("-".repeat(100))
      for (entry in inProgress) {
        printSessionRow(entry.jsonObject)
      }
      if (completed.isNotEmpty()) Console.info("")
    }

    if (completed.isNotEmpty()) {
      Console.info("Recent (${completed.size.coerceAtMost(recentLimit)} of ${completed.size})")
      Console.info("-".repeat(100))
      for (entry in completed.take(recentLimit)) {
        printSessionRow(entry.jsonObject)
      }
    }

    if (inProgress.isEmpty() && completed.isEmpty()) {
      Console.info("No sessions found.")
    }
  }

  private fun printSessionRow(obj: JsonObject) {
    val id = obj["id"]?.jsonPrimitive?.content ?: "?"
    val status = obj["status"]?.jsonPrimitive?.content ?: "?"
    val startedAt = obj["startedAt"]?.jsonPrimitive?.content ?: ""
    val title = obj["title"]?.jsonPrimitive?.content ?: ""
    Console.info("  %-38s  %-20s  %-20s  %s".format(id, status, startedAt, title))
  }
}

@Command(
  name = "artifacts",
  mixinStandardHelpOptions = true,
  description = ["List artifacts in a session"],
)
class SessionArtifactsCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: SessionCommand

  @Parameters(
    index = "0",
    arity = "0..1",
    paramLabel = "<session-id>",
    description = [
      "Session ID (positional form of --id, defaults to current session). " +
        "Mutually exclusive with --id.",
    ],
  )
  var positionalId: String? = null

  @Option(
    names = ["--id"],
    description = ["Session ID (defaults to current session). Equivalent to the positional form."],
  )
  var id: String? = null

  override fun call(): Int {
    if (positionalId != null && id != null) {
      Console.error(SESSION_ID_CONFLICT_MESSAGE)
      return TrailblazeExitCode.MISUSE.code
    }
    val effectiveId = positionalId ?: id
    val port = CliConfigHelper.resolveEffectiveHttpPort()

    return runBlocking {
      val client = try {
        CliMcpClient.connectReusable(port)
      } catch (_: Exception) {
        reportDaemonUnreachable()
        return@runBlocking TrailblazeExitCode.INFRA_FAILED.code
      }

      client.use {
        val arguments = mutableMapOf<String, Any?>("action" to "ARTIFACTS")
        if (effectiveId != null) arguments["id"] = effectiveId
        val result = it.callTool("session", arguments)
        if (result.isError) {
          Console.error("Error: ${extractErrorMessage(result.content)}")
          return@use TrailblazeExitCode.INFRA_FAILED.code
        }
        try {
          val json = Json.parseToJsonElement(result.content).jsonObject
          val error = json["error"]?.jsonPrimitive?.content
          if (!error.isNullOrBlank()) {
            Console.error("Error: $error")
            return@use TrailblazeExitCode.INFRA_FAILED.code
          }
          val path = json["path"]?.jsonPrimitive?.content
          val artifacts = json["artifacts"] as? JsonArray
          if (path != null) Console.info("Session directory: $path")
          if (artifacts != null && artifacts.isNotEmpty()) {
            Console.info("")
            Console.info("%-30s  %-12s  %s".format("NAME", "TYPE", "SIZE"))
            Console.info(ITEM_DIVIDER)
            for (entry in artifacts) {
              val obj = entry.jsonObject
              val name = obj["name"]?.jsonPrimitive?.content ?: "?"
              val type = obj["type"]?.jsonPrimitive?.content ?: "?"
              val size = obj["sizeBytes"]?.jsonPrimitive?.content?.toLongOrNull()
              val sizeStr = if (size != null) "${size / 1024}KB" else "?"
              Console.info("%-30s  %-12s  %s".format(name, type, sizeStr))
            }
          } else {
            Console.info("No artifacts found.")
          }
        } catch (_: Exception) {
          Console.info(result.content)
        }
        TrailblazeExitCode.SUCCESS.code
      }
    }
  }
}

@Command(
  name = "delete",
  mixinStandardHelpOptions = true,
  description = ["Delete a session's logs and artifacts"],
)
class SessionDeleteCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: SessionCommand

  @Parameters(
    index = "0",
    arity = "0..1",
    paramLabel = "<session-id>",
    description = [
      "Session ID to delete (positional form of --id, supports prefix matching). " +
        "Mutually exclusive with --id; one of the two is required.",
    ],
  )
  var positionalId: String? = null

  @Option(
    names = ["--id"],
    description = ["Session ID to delete (supports prefix matching). Equivalent to the positional form."],
  )
  var id: String? = null

  override fun call(): Int {
    if (positionalId != null && id != null) {
      Console.error(SESSION_ID_CONFLICT_MESSAGE)
      return TrailblazeExitCode.MISUSE.code
    }
    val effectiveId = positionalId ?: id ?: run {
      Console.error("Missing required <session-id>. Pass it positionally or via --id.")
      return TrailblazeExitCode.MISUSE.code
    }
    val port = CliConfigHelper.resolveEffectiveHttpPort()

    return runBlocking {
      val client = try {
        CliMcpClient.connectReusable(port)
      } catch (_: Exception) {
        reportDaemonUnreachable()
        return@runBlocking TrailblazeExitCode.INFRA_FAILED.code
      }

      client.use {
        val result = it.callTool("session", mapOf("action" to "DELETE", "id" to effectiveId))
        if (result.isError) {
          Console.error("Error: ${extractErrorMessage(result.content)}")
          return@use TrailblazeExitCode.INFRA_FAILED.code
        }
        try {
          val json = Json.parseToJsonElement(result.content).jsonObject
          val error = json["error"]?.jsonPrimitive?.content
          if (!error.isNullOrBlank()) {
            Console.error("Error: $error")
            return@use TrailblazeExitCode.INFRA_FAILED.code
          }
          val msg = json["message"]?.jsonPrimitive?.content
          if (msg != null) Console.info(msg)
        } catch (_: Exception) {
          Console.info(result.content)
        }
        TrailblazeExitCode.SUCCESS.code
      }
    }
  }
}

@Command(
  name = "end",
  mixinStandardHelpOptions = true,
  description = ["End the CLI session and release the device (deprecated: use 'stop' instead)"],
)
class SessionEndCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: SessionCommand

  @Option(
    names = ["-d", "--device"],
    description = ["Device: platform (android, ios, web) or platform/id. Defaults to \$TRAILBLAZE_DEVICE."],
  )
  var device: String? = null

  @Option(
    names = ["--name", "-n"],
    description = ["Save the recording as a trail before ending"]
  )
  var name: String? = null

  override fun call(): Int {
    Console.error("Deprecated: use 'trailblaze session stop' instead.")

    // Resolve --device → $TRAILBLAZE_DEVICE → null. `end` is per-device with the same
    // multi-terminal safety contract as `session stop` — require an explicit target.
    val resolvedDevice = when (val r = requireSessionDevice(device, verb = "Session end")) {
      is DeviceResolution.Resolved -> r.deviceSpec
      else -> return r.exitCodeFallback()
    }

    // Find the root command to get the port
    val port = CliConfigHelper.resolveEffectiveHttpPort()

    if (!DaemonClient(port = port).use { it.isRunningBlocking() }) {
      Console.log("No active session (daemon not running).")
      CliMcpClient.clearSession(port)
      return TrailblazeExitCode.SUCCESS.code
    }

    return runBlocking {
      val client = try {
        CliMcpClient.connectReusable(port)
      } catch (e: Exception) {
        Console.log("No active session.")
        CliMcpClient.clearSession(port)
        return@runBlocking TrailblazeExitCode.SUCCESS.code
      }

      client.use {
        // Use --device as the session lookup key (same contract as `session stop`).
        // `return@runBlocking` from inside `use` is fine — `close()` runs in the
        // surrounding finally block.
        val boundDevice = it.getBoundDeviceId()
        when {
          boundDevice == null -> {
            Console.log("No active session for device $resolvedDevice.")
            CliMcpClient.clearSession(port)
            return@runBlocking TrailblazeExitCode.SUCCESS.code
          }
          !deviceArgMatches(resolvedDevice, boundDevice) -> {
            Console.error(
              "No active session for device $resolvedDevice — the daemon's current session is " +
                "bound to ${boundDevice.toFullyQualifiedDeviceId()}. Pass --device " +
                "${boundDevice.toFullyQualifiedDeviceId()} if you " +
                "meant to end that one.",
            )
            return@runBlocking TrailblazeExitCode.INFRA_FAILED.code
          }
          // else: match — proceed with end.
        }
        if (name != null) {
          val saveResult = it.callTool("trail", mapOf("action" to "SAVE", "name" to name!!))
          if (saveResult.isError) {
            Console.error("Error saving trail: ${extractErrorMessage(saveResult.content)}")
          } else {
            Console.info("Trail saved: $name")
          }
        }

        // End the session recording (deprecated trail tool uses END action)
        it.callTool("trail", mapOf("action" to "END"))
      }

      // Clear the session file
      CliMcpClient.clearSession(port)
      Console.log("Session ended.")
      TrailblazeExitCode.SUCCESS.code
    }
  }
}

@Command(
  name = "save",
  mixinStandardHelpOptions = true,
  description = ["Write the recorded steps to a *.trail.yaml file you can replay later (does not end the session)"],
)
class SessionSaveCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: SessionCommand

  @Option(
    names = ["--title", "-t"],
    description = ["Title for the saved trail (uses session title if not specified)"],
  )
  var title: String? = null

  @Option(
    names = ["--id"],
    description = ["Session ID to save (defaults to current session, supports prefix matching)"],
  )
  var id: String? = null

  @Option(
    names = ["--name", "-n"],
    hidden = true,
    description = ["Deprecated: use --title instead"],
  )
  var name: String? = null

  override fun call(): Int {
    if (name != null && title == null) {
      Console.error("Deprecated: --name is renamed to --title.")
    }
    val effectiveTitle = title ?: name
    val port = CliConfigHelper.resolveEffectiveHttpPort()

    if (!DaemonClient(port = port).use { it.isRunningBlocking() }) {
      reportDaemonUnreachable()
      return TrailblazeExitCode.INFRA_FAILED.code
    }

    return runBlocking {
      val client = try {
        CliMcpClient.connectReusable(port)
      } catch (e: Exception) {
        Console.error("Error: No active session. ${e.message}")
        return@runBlocking TrailblazeExitCode.INFRA_FAILED.code
      }

      client.use {
        val arguments = mutableMapOf<String, Any?>("action" to "SAVE")
        if (effectiveTitle != null) arguments["title"] = effectiveTitle
        if (id != null) arguments["id"] = id
        val result = it.callTool("session", arguments)
        if (result.isError) {
          Console.error("Error saving trail: ${extractErrorMessage(result.content)}")
          TrailblazeExitCode.INFRA_FAILED.code
        } else {
          try {
            val json = Json.parseToJsonElement(result.content).jsonObject
            val error = json["error"]?.jsonPrimitive?.content
            if (!error.isNullOrBlank()) {
              Console.error("Error: $error")
              return@use TrailblazeExitCode.INFRA_FAILED.code
            }
            val msg = json["message"]?.jsonPrimitive?.content
            if (msg != null) Console.info(msg)
          } catch (_: Exception) {
            Console.info(result.content)
          }
          TrailblazeExitCode.SUCCESS.code
        }
      }
    }
  }
}

@Command(
  name = "recording",
  mixinStandardHelpOptions = true,
  description = ["Output the recording YAML for a session"],
)
class SessionRecordingCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: SessionCommand

  @Parameters(
    index = "0",
    arity = "0..1",
    paramLabel = "<session-id>",
    description = [
      "Session ID (positional form of --id, defaults to current session, supports " +
        "prefix matching). Mutually exclusive with --id.",
    ],
  )
  var positionalId: String? = null

  @Option(
    names = ["--id"],
    description = [
      "Session ID (defaults to current session, supports prefix matching). " +
        "Equivalent to the positional form.",
    ],
  )
  var id: String? = null

  override fun call(): Int {
    if (positionalId != null && id != null) {
      Console.error(SESSION_ID_CONFLICT_MESSAGE)
      return TrailblazeExitCode.MISUSE.code
    }
    val effectiveId = positionalId ?: id
    val port = CliConfigHelper.resolveEffectiveHttpPort()

    if (!DaemonClient(port = port).use { it.isRunningBlocking() }) {
      reportDaemonUnreachable()
      return TrailblazeExitCode.INFRA_FAILED.code
    }

    return runBlocking {
      val client = try {
        CliMcpClient.connectReusable(port)
      } catch (_: Exception) {
        reportDaemonUnreachable()
        return@runBlocking TrailblazeExitCode.INFRA_FAILED.code
      }

      client.use {
        val arguments = mutableMapOf<String, Any?>("action" to "RECORDING")
        if (effectiveId != null) arguments["id"] = effectiveId
        val result = it.callTool("session", arguments)
        if (result.isError) {
          Console.error("Error: ${extractErrorMessage(result.content)}")
          return@use TrailblazeExitCode.INFRA_FAILED.code
        }
        try {
          val json = Json.parseToJsonElement(result.content).jsonObject
          val error = json["error"]?.jsonPrimitive?.content
          if (!error.isNullOrBlank()) {
            Console.error("Error: $error")
            return@use TrailblazeExitCode.INFRA_FAILED.code
          }
          val yaml = json["yaml"]?.jsonPrimitive?.content
          if (yaml != null) {
            // Print YAML directly to stdout for piping/redirection
            println(yaml)
          } else {
            Console.error("Error: No recording YAML returned.")
            return@use TrailblazeExitCode.INFRA_FAILED.code
          }
        } catch (_: Exception) {
          Console.info(result.content)
        }
        TrailblazeExitCode.SUCCESS.code
      }
    }
  }
}

@Command(
  name = "info",
  mixinStandardHelpOptions = true,
  description = ["Show information about a session"],
)
class SessionInfoCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: SessionCommand

  @Parameters(
    index = "0",
    arity = "0..1",
    paramLabel = "<session-id>",
    description = [
      "Session ID (positional form of --id, defaults to current session). " +
        "Mutually exclusive with --id.",
    ],
  )
  var positionalId: String? = null

  @Option(
    names = ["--id"],
    description = ["Session ID (defaults to current session). Equivalent to the positional form."],
  )
  var id: String? = null

  override fun call(): Int {
    if (positionalId != null && id != null) {
      Console.error(SESSION_ID_CONFLICT_MESSAGE)
      return TrailblazeExitCode.MISUSE.code
    }
    val effectiveId = positionalId ?: id
    val port = CliConfigHelper.resolveEffectiveHttpPort()

    if (!DaemonClient(port = port).use { it.isRunningBlocking() }) {
      Console.log("No active session (daemon not running).")
      return TrailblazeExitCode.SUCCESS.code
    }

    return runBlocking {
      val client = try {
        CliMcpClient.connectReusable(port)
      } catch (_: Exception) {
        Console.log("No active session.")
        return@runBlocking TrailblazeExitCode.SUCCESS.code
      }

      client.use {
        val arguments = mutableMapOf<String, Any?>("action" to "INFO")
        if (effectiveId != null) arguments["id"] = effectiveId

        val result = it.callTool("session", arguments)

        // Check for "no active session" — not an error, just informational
        val infoError = try {
          Json.parseToJsonElement(result.content).jsonObject["error"]?.jsonPrimitive?.content
        } catch (_: Exception) { null }
        if (infoError != null) {
          Console.info(infoError)
          return@use TrailblazeExitCode.SUCCESS.code
        }

        if (result.isError) {
          Console.error("Error: ${result.content}")
          return@use TrailblazeExitCode.INFRA_FAILED.code
        }
        try {
          val json = Json.parseToJsonElement(result.content).jsonObject
          val sessionId = json["sessionId"]?.jsonPrimitive?.content
          val sessionTitle = json["title"]?.jsonPrimitive?.content
          val status = json["status"]?.jsonPrimitive?.content
          val device = json["device"]?.jsonPrimitive?.content
          val platform = json["platform"]?.jsonPrimitive?.content
          val path = json["path"]?.jsonPrimitive?.content

          Console.info("Session:")
          if (sessionId != null) Console.info("  ID:       $sessionId")
          if (sessionTitle != null) Console.info("  Title:    $sessionTitle")
          if (status != null) Console.info("  Status:   $status")
          if (device != null && platform != null) Console.info("  Device:   $device ($platform)")
          else if (device != null) Console.info("  Device:   $device")
          if (path != null) Console.info("  Path:     file://$path")
        } catch (_: Exception) {
          Console.info(result.content)
        }
        TrailblazeExitCode.SUCCESS.code
      }
    }
  }
}
