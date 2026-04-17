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
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.util.Console
import java.util.concurrent.Callable

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
  description = ["Every blaze records a session — save it as a replayable trail"],
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
  override fun call(): Int {
    CommandLine(this).usage(System.out)
    return CommandLine.ExitCode.OK
  }
}

/**
 * Start a new session.
 *
 * Accepts `--target`, `--mode`, and `--device` to configure and start in one step.
 * If target/mode are already configured, they can be omitted.
 * Device is per-session — two terminals can have different devices.
 *
 * Examples:
 *   trailblaze session start --target myapp --mode trail --device android
 *   trailblaze session start --device ios      (target/mode already configured)
 *   trailblaze session start                   (uses configured defaults)
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
    description = ["Target app ID. Saved to config for future commands."],
  )
  var target: String? = null

  @Option(
    names = ["--mode"],
    description = ["Working mode: trail or blaze. Saved to config for future commands."],
  )
  var mode: String? = null

  @Option(
    names = ["-d", "--device"],
    description = ["Device: platform (android, ios, web) or platform/id (e.g., ios/DEVICE-UUID). " +
      "Switches the daemon's active device for all clients. Required for multi-device workflows."],
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

  override fun call(): Int {
    // Apply --target and --mode to global config if provided.
    if (target != null || mode != null) {
      var currentConfig = CliConfigHelper.getOrCreateConfig()

      if (target != null) {
        currentConfig = currentConfig.copy(selectedTargetAppId = target!!.lowercase())
      }

      if (mode != null) {
        val normalizedMode = mode!!.lowercase()
        if (normalizedMode !in setOf("trail", "blaze")) {
          Console.error("Error: --mode must be 'trail' or 'blaze' (got '$mode').")
          return CommandLine.ExitCode.USAGE
        }
        currentConfig = currentConfig.copy(cliMode = normalizedMode)
      }

      CliConfigHelper.writeConfig(currentConfig)
    }

    // Gate: config must be complete (target/mode set) before starting a session.
    val setupError = checkSetupComplete()
    if (setupError != null) {
      Console.error(setupError)
      return CommandLine.ExitCode.SOFTWARE
    }

    if (!verbose) Console.enableQuietMode()
    val port = CliConfigHelper.resolveEffectiveHttpPort()

    return runBlocking {
      val client = connectOrStartDaemon(port) ?: return@runBlocking CommandLine.ExitCode.SOFTWARE

      client.use {
        // Connect device: per-command flag → config default → auto-detect
        val effectiveDevice = device ?: CliConfigHelper.readConfig()?.cliDevicePlatform
        if (effectiveDevice != null) {
          val deviceError = it.ensureDevice(effectiveDevice)
          if (deviceError != null) {
            Console.error(deviceError)
            return@runBlocking CommandLine.ExitCode.SOFTWARE
          }
        }
        // Save device to config so subsequent CLI invocations (blaze, ask, etc.) use it
        if (device != null) {
          val platformStr = device!!.split("/", limit = 2)[0]
          if (TrailblazeDevicePlatform.fromString(platformStr) != null) {
            CliConfigHelper.updateConfig { cfg -> cfg.copy(cliDevicePlatform = platformStr.uppercase()) }
          }
        }

        val arguments = mutableMapOf<String, Any?>("action" to "START")
        if (title != null) arguments["title"] = title
        if (noVideo) arguments["noVideo"] = true
        if (noLogs) arguments["noLogs"] = true

        val result = it.callTool("session", arguments)
        if (result.isError) {
          Console.error("Error: ${extractErrorMessage(result.content)}")
          CommandLine.ExitCode.SOFTWARE
        } else {
          try {
            val json = Json.parseToJsonElement(result.content).jsonObject
            val error = json["error"]?.jsonPrimitive?.content
            if (!error.isNullOrBlank()) {
              Console.error("Error: $error")
              return@use CommandLine.ExitCode.SOFTWARE
            }
            val msg = json["message"]?.jsonPrimitive?.content
            val sessionId = json["sessionId"]?.jsonPrimitive?.content
            if (sessionId != null) Console.info("Session: trailblaze session info --id $sessionId")
            if (msg != null) Console.info(msg)
          } catch (_: Exception) {
            Console.info(result.content)
          }
          CommandLine.ExitCode.OK
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
    val port = CliConfigHelper.resolveEffectiveHttpPort()
    if (!DaemonClient(port = port).use { it.isRunningBlocking() }) {
      Console.log("No active session (daemon not running).")
      CliMcpClient.clearSession(port)
      return CommandLine.ExitCode.OK
    }

    return runBlocking {
      val client = try {
        CliMcpClient.connectToDaemon(port)
      } catch (_: Exception) {
        Console.log("No active session.")
        CliMcpClient.clearSession(port)
        return@runBlocking CommandLine.ExitCode.OK
      }

      var exitCode = CommandLine.ExitCode.OK
      client.use {
        val arguments = mutableMapOf<String, Any?>("action" to "STOP")
        if (save) arguments["save"] = true
        if (title != null) arguments["title"] = title

        val result = it.callTool("session", arguments)
        if (result.isError) {
          Console.error("Error: ${extractErrorMessage(result.content)}")
          exitCode = CommandLine.ExitCode.SOFTWARE
        } else {
          try {
            val json = Json.parseToJsonElement(result.content).jsonObject
            val msg = json["message"]?.jsonPrimitive?.content
            if (msg != null) Console.info(msg)
          } catch (_: Exception) {
            Console.info(result.content)
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
      return CommandLine.ExitCode.USAGE
    }

    val port = CliConfigHelper.resolveEffectiveHttpPort()

    return runBlocking {
      val client = try {
        CliMcpClient.connectToDaemon(port)
      } catch (_: Exception) {
        Console.error(DAEMON_NOT_RUNNING_ERROR)
        return@runBlocking CommandLine.ExitCode.SOFTWARE
      }

      client.use {
        val fetchLimit = if (all) limit else limit + 20 // fetch extra so we have enough completed
        val result = it.callTool("session", mapOf("action" to "LIST", "limit" to fetchLimit))
        if (result.isError) {
          Console.error("Error: ${extractErrorMessage(result.content)}")
          return@use CommandLine.ExitCode.SOFTWARE
        }
        try {
          val json = Json.parseToJsonElement(result.content).jsonObject
          val sessions = json["sessions"] as? JsonArray
          if (sessions == null || sessions.isEmpty()) {
            Console.info("No sessions found.")
            return@use CommandLine.ExitCode.OK
          }

          if (all) {
            printSessionTable(sessions)
          } else {
            printGroupedSessions(sessions, limit)
          }
        } catch (_: Exception) {
          Console.info(result.content)
        }
        CommandLine.ExitCode.OK
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

  @Option(
    names = ["--id"],
    description = ["Session ID (defaults to current session)"],
  )
  var id: String? = null

  override fun call(): Int {
    val port = CliConfigHelper.resolveEffectiveHttpPort()

    return runBlocking {
      val client = try {
        CliMcpClient.connectToDaemon(port)
      } catch (_: Exception) {
        Console.error(DAEMON_NOT_RUNNING_ERROR)
        return@runBlocking CommandLine.ExitCode.SOFTWARE
      }

      client.use {
        val arguments = mutableMapOf<String, Any?>("action" to "ARTIFACTS")
        if (id != null) arguments["id"] = id
        val result = it.callTool("session", arguments)
        if (result.isError) {
          Console.error("Error: ${extractErrorMessage(result.content)}")
          return@use CommandLine.ExitCode.SOFTWARE
        }
        try {
          val json = Json.parseToJsonElement(result.content).jsonObject
          val error = json["error"]?.jsonPrimitive?.content
          if (!error.isNullOrBlank()) {
            Console.error("Error: $error")
            return@use CommandLine.ExitCode.SOFTWARE
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
        CommandLine.ExitCode.OK
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

  @Option(
    names = ["--id"],
    description = ["Session ID to delete (supports prefix matching)"],
    required = true,
  )
  lateinit var id: String

  override fun call(): Int {
    val port = CliConfigHelper.resolveEffectiveHttpPort()

    return runBlocking {
      val client = try {
        CliMcpClient.connectToDaemon(port)
      } catch (_: Exception) {
        Console.error(DAEMON_NOT_RUNNING_ERROR)
        return@runBlocking CommandLine.ExitCode.SOFTWARE
      }

      client.use {
        val result = it.callTool("session", mapOf("action" to "DELETE", "id" to id))
        if (result.isError) {
          Console.error("Error: ${extractErrorMessage(result.content)}")
          return@use CommandLine.ExitCode.SOFTWARE
        }
        try {
          val json = Json.parseToJsonElement(result.content).jsonObject
          val error = json["error"]?.jsonPrimitive?.content
          if (!error.isNullOrBlank()) {
            Console.error("Error: $error")
            return@use CommandLine.ExitCode.SOFTWARE
          }
          val msg = json["message"]?.jsonPrimitive?.content
          if (msg != null) Console.info(msg)
        } catch (_: Exception) {
          Console.info(result.content)
        }
        CommandLine.ExitCode.OK
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
    names = ["--name", "-n"],
    description = ["Save the recording as a trail before ending"]
  )
  var name: String? = null

  override fun call(): Int {
    Console.error("Deprecated: use 'trailblaze session stop' instead.")

    // Find the root command to get the port
    val port = CliConfigHelper.resolveEffectiveHttpPort()

    if (!DaemonClient(port = port).use { it.isRunningBlocking() }) {
      Console.log("No active session (daemon not running).")
      CliMcpClient.clearSession(port)
      return CommandLine.ExitCode.OK
    }

    return runBlocking {
      val client = try {
        CliMcpClient.connectToDaemon(port)
      } catch (e: Exception) {
        Console.log("No active session.")
        CliMcpClient.clearSession(port)
        return@runBlocking CommandLine.ExitCode.OK
      }

      client.use {
        // Save if name provided
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
      CommandLine.ExitCode.OK
    }
  }
}

@Command(
  name = "save",
  mixinStandardHelpOptions = true,
  description = ["Save the current recording as a trail without ending the session"],
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
      Console.error(DAEMON_NOT_RUNNING_ERROR)
      return CommandLine.ExitCode.SOFTWARE
    }

    return runBlocking {
      val client = try {
        CliMcpClient.connectToDaemon(port)
      } catch (e: Exception) {
        Console.error("Error: No active session. ${e.message}")
        return@runBlocking CommandLine.ExitCode.SOFTWARE
      }

      client.use {
        val arguments = mutableMapOf<String, Any?>("action" to "SAVE")
        if (effectiveTitle != null) arguments["title"] = effectiveTitle
        if (id != null) arguments["id"] = id
        val result = it.callTool("session", arguments)
        if (result.isError) {
          Console.error("Error saving trail: ${extractErrorMessage(result.content)}")
          CommandLine.ExitCode.SOFTWARE
        } else {
          try {
            val json = Json.parseToJsonElement(result.content).jsonObject
            val error = json["error"]?.jsonPrimitive?.content
            if (!error.isNullOrBlank()) {
              Console.error("Error: $error")
              return@use CommandLine.ExitCode.SOFTWARE
            }
            val msg = json["message"]?.jsonPrimitive?.content
            if (msg != null) Console.info(msg)
          } catch (_: Exception) {
            Console.info(result.content)
          }
          CommandLine.ExitCode.OK
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

  @Option(
    names = ["--id"],
    description = ["Session ID (defaults to current session, supports prefix matching)"],
  )
  var id: String? = null

  override fun call(): Int {
    val port = CliConfigHelper.resolveEffectiveHttpPort()

    if (!DaemonClient(port = port).use { it.isRunningBlocking() }) {
      Console.error(DAEMON_NOT_RUNNING_ERROR)
      return CommandLine.ExitCode.SOFTWARE
    }

    return runBlocking {
      val client = try {
        CliMcpClient.connectToDaemon(port)
      } catch (_: Exception) {
        Console.error(DAEMON_NOT_RUNNING_ERROR)
        return@runBlocking CommandLine.ExitCode.SOFTWARE
      }

      client.use {
        val arguments = mutableMapOf<String, Any?>("action" to "RECORDING")
        if (id != null) arguments["id"] = id
        val result = it.callTool("session", arguments)
        if (result.isError) {
          Console.error("Error: ${extractErrorMessage(result.content)}")
          return@use CommandLine.ExitCode.SOFTWARE
        }
        try {
          val json = Json.parseToJsonElement(result.content).jsonObject
          val error = json["error"]?.jsonPrimitive?.content
          if (!error.isNullOrBlank()) {
            Console.error("Error: $error")
            return@use CommandLine.ExitCode.SOFTWARE
          }
          val yaml = json["yaml"]?.jsonPrimitive?.content
          if (yaml != null) {
            // Print YAML directly to stdout for piping/redirection
            println(yaml)
          } else {
            Console.error("Error: No recording YAML returned.")
            return@use CommandLine.ExitCode.SOFTWARE
          }
        } catch (_: Exception) {
          Console.info(result.content)
        }
        CommandLine.ExitCode.OK
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

  @Option(
    names = ["--id"],
    description = ["Session ID (defaults to current session)"],
  )
  var id: String? = null

  override fun call(): Int {
    val port = CliConfigHelper.resolveEffectiveHttpPort()

    if (!DaemonClient(port = port).use { it.isRunningBlocking() }) {
      Console.log("No active session (daemon not running).")
      return CommandLine.ExitCode.OK
    }

    return runBlocking {
      val client = try {
        CliMcpClient.connectToDaemon(port)
      } catch (_: Exception) {
        Console.log("No active session.")
        return@runBlocking CommandLine.ExitCode.OK
      }

      client.use {
        val arguments = mutableMapOf<String, Any?>("action" to "INFO")
        if (id != null) arguments["id"] = id

        val result = it.callTool("session", arguments)

        // Check for "no active session" — not an error, just informational
        val infoError = try {
          Json.parseToJsonElement(result.content).jsonObject["error"]?.jsonPrimitive?.content
        } catch (_: Exception) { null }
        if (infoError != null) {
          Console.info(infoError)
          return@use CommandLine.ExitCode.OK
        }

        if (result.isError) {
          Console.error("Error: ${result.content}")
          return@use CommandLine.ExitCode.SOFTWARE
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
        CommandLine.ExitCode.OK
      }
    }
  }
}
