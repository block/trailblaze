package xyz.block.trailblaze.cli

import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.logs.server.endpoints.CliDaemonCapabilities
import xyz.block.trailblaze.trailrunner.CompanionConnectRequest
import xyz.block.trailblaze.trailrunner.CompanionConnectResponse
import xyz.block.trailblaze.trailrunner.CompanionDirectiveRequest
import xyz.block.trailblaze.trailrunner.CompanionDisconnectRequest
import xyz.block.trailblaze.trailrunner.CompanionEventRequest
import xyz.block.trailblaze.trailrunner.CompanionRespondRequest
import xyz.block.trailblaze.trailrunner.ExternalAgentType
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.runJsonOutput
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Callable

@Command(
  name = "companion",
  mixinStandardHelpOptions = true,
  description = ["Attach a coding agent to Trail Runner while it authors a trail."],
  subcommands = [
    CompanionStartCommand::class,
    CompanionEventCommand::class,
    CompanionSendCommand::class,
    CompanionListenCommand::class,
    CompanionDisconnectCommand::class,
    CompanionRespondCommand::class,
    CompanionAgentHelpCommand::class,
  ],
)
class CompanionCommand : Callable<Int> {
  @Option(
    names = ["--agent-help"],
    description = ["Print the complete coding-agent workflow and wire contract."],
  )
  var agentHelp: Boolean = false

  override fun call(): Int {
    if (agentHelp) {
      return printCompanionAgentHelp()
    }
    return companionFailure("choose start, event, send, listen, disconnect, respond, or agent-help")
  }
}

@Command(name = "agent-help", description = ["Print the complete coding-agent workflow and wire contract."])
internal class CompanionAgentHelpCommand : Callable<Int> {
  override fun call(): Int = printCompanionAgentHelp()
}

private fun printCompanionAgentHelp(): Int {
  println(CompanionCommand::class.java.getResource("/companion-agent-help.txt")!!.readText())
  return TrailblazeExitCode.SUCCESS.code
}

internal abstract class CompanionAction {
  protected fun client(): CompanionCliClient =
    CompanionCliClient(CliConfigHelper.resolveEffectiveHttpPort())
}

@Command(name = "start", mixinStandardHelpOptions = true, description = ["Start a companion authoring session."])
internal class CompanionStartCommand : CompanionAction(), Callable<Int> {
  @Option(names = ["--folder"], description = ["Trail folder relative to the workspace root."])
  var folder: String? = null

  @Option(names = ["--title"], description = ["Human-readable title shown in Trail Runner."])
  var title: String? = null

  @Option(names = ["--agent"], defaultValue = "claude", description = ["Agent type: claude or codex."])
  var agent: String = "claude"

  @Option(names = ["--label"], description = ["Agent label shown in Trail Runner."])
  var label: String? = null

  @Option(names = ["--trails-dir"], defaultValue = ".", description = ["Workspace root (default: current directory)."])
  var trailsDir: File = File(".")

  override fun call(): Int = Console.runJsonOutput {
    val root = runCatching { trailsDir.canonicalFile }.getOrNull()
      ?.takeIf { it.isDirectory }
      ?: return companionFailure("the trails dir does not exist: $trailsDir")
    val agentType = when (agent.lowercase()) {
      "claude" -> ExternalAgentType.CLAUDE
      "codex" -> ExternalAgentType.CODEX
      else -> return companionFailure("unknown --agent: $agent (use claude or codex)")
    }
    val port = CliConfigHelper.resolveEffectiveHttpPort()
    // A running daemon may already own live Companion sessions, which are intentionally not part
    // of the trail-run count used by version-mismatch auto-restart. Disable that restart inside the
    // ensure operation itself so a daemon that races our probe is also preserved; connect validates
    // its root below. Only the no-daemon case starts a process rooted at this workspace.
    if (!ensureDaemonServerRunning(
        port,
        childEnvironment = mapOf("TRAILBLAZE_TRAILS_DIR" to root.path),
        restartStaleDaemon = false,
      )) {
      return companionFailure("the Trailblaze daemon is not up", TrailblazeExitCode.INFRA_FAILED)
    }
    if (!daemonSupportsCompanion(port)) {
      return companionFailure(
        "the server on port $port does not support Companion; stop it and retry",
        TrailblazeExitCode.INFRA_FAILED,
      )
    }
    val api = client()
    val result = api.post(
      "/connect",
      CompanionConnectRequest(agentType = agentType, agentLabel = label, title = title, folder = folder),
      CompanionConnectRequest.serializer(),
    )
    if (!result.ok) return result.print()
    val response = runCatching {
      companionJson.decodeFromString(CompanionConnectResponse.serializer(), result.body)
    }.getOrElse { return companionFailure("could not parse the daemon connect response") }
    val runId = response.runId ?: return companionFailure("the daemon did not return a run id")
    val daemonRoot = response.primaryRoot?.let { reported ->
      runCatching { File(reported).canonicalFile }
        .getOrElse { return companionFailure("the daemon returned an invalid primary root") }
    }
    if (daemonRoot != null && daemonRoot != root) {
      api.post(
        "/$runId/disconnect",
        CompanionDisconnectRequest(note = "aborted: daemon rooted at a different workspace"),
        CompanionDisconnectRequest.serializer(),
      )
      return companionFailure("the running daemon is rooted at ${response.primaryRoot}, not ${root.path}; stop it and retry")
    }
    val base = api.baseUrl
    val ui = "http://localhost:$port/trailrunner/#companion/$runId"
    println(buildJsonObject {
      put("ok", true)
      put("runId", runId)
      put("port", port)
      put("apiBase", base)
      put("primaryRoot", response.primaryRoot ?: root.path)
      put("ui", ui)
    })
    if (!openCompanionWindow(ui, port)) {
      System.err.println("Open this URL in your browser: $ui")
    }
    return TrailblazeExitCode.SUCCESS.code
  }
}

internal fun daemonSupportsCompanion(port: Int): Boolean = DaemonClient(port = port).use { daemon ->
  CliDaemonCapabilities.COMPANION in daemon.getStatusBlocking()?.capabilities.orEmpty()
}

internal fun openCompanionWindow(ui: String, port: Int): Boolean {
  val launcher = findTrailblazeLauncher() ?: return false
  return runCatching {
    ProcessBuilder(launcher.absolutePath, "trailrunner")
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .redirectError(ProcessBuilder.Redirect.INHERIT)
      .apply {
        environment()["TRAILBLAZE_PORT"] = port.toString()
        environment()["TRAILBLAZE_TRAILRUNNER_URL"] = ui
      }
      .start()
      .waitFor() == 0
  }.getOrDefault(false)
}

@Command(name = "event", mixinStandardHelpOptions = true, description = ["Append narration to a companion session."])
internal class CompanionEventCommand : CompanionAction(), Callable<Int> {
  @Parameters(index = "0", description = ["Companion run id."])
  lateinit var runId: String

  @Option(names = ["--kind"], description = ["assistant_message, lifecycle, or error."])
  var kind: String? = null

  @Option(names = ["--title"], description = ["Optional event heading."])
  var title: String? = null

  @Option(names = ["--text"], description = ["Event body."])
  var text: String? = null

  override fun call(): Int {
    validateRunId(runId)?.let { return companionFailure(it) }
    if (title.isNullOrEmpty() && text.isNullOrEmpty()) return companionFailure("companion event needs --title or --text")
    return client().post(
      "/$runId/event",
      CompanionEventRequest(kind = kind, title = title, text = text),
      CompanionEventRequest.serializer(),
    ).print()
  }
}

@Command(name = "send", mixinStandardHelpOptions = true, description = ["Send or retract Trail Runner UI guidance."])
internal class CompanionSendCommand : CompanionAction(), Callable<Int> {
  @Parameters(index = "0", description = ["Companion run id."])
  lateinit var runId: String

  @Parameters(index = "1", description = ["Directive name."])
  lateinit var directive: String

  @Option(names = ["--text"]) var text: String? = null
  @Option(names = ["--route"]) var route: String? = null
  @Option(names = ["--variant"]) var variant: String? = null
  @Option(names = ["--platform"]) var platform: String? = null
  @Option(names = ["--app"]) var app: String? = null
  @Option(names = ["--title"]) var title: String? = null
  @Option(names = ["--item"], description = ["List item; repeat for multiple items."])
  var items: MutableList<String> = mutableListOf()
  @Option(names = ["--payload"], description = ["Additional directive fields as a JSON object."])
  var payload: String? = null

  override fun call(): Int {
    validateRunId(runId)?.let { return companionFailure(it) }
    items.firstOrNull { '\n' in it || '\r' in it }?.let {
      return companionFailure("--item must be a single line; pass one --item per entry")
    }
    val fields = payload?.takeIf { it.isNotBlank() }?.let { raw ->
      runCatching { companionJson.parseToJsonElement(raw).jsonObject.toMutableMap() }
        .getOrElse { return companionFailure("--payload must be a JSON object") }
    } ?: mutableMapOf()
    listOf(
      "text" to text,
      "route" to route,
      "variant" to variant,
      "platform" to platform,
      "app" to app,
      "title" to title,
    ).forEach { (key, value) -> value?.takeIf { it.isNotEmpty() }?.let { fields[key] = JsonPrimitive(it) } }
    if (items.isNotEmpty()) fields["items"] = JsonArray(items.map { JsonPrimitive(it.trim()) })
    val body = CompanionDirectiveRequest(
      directive = directive,
      payload = fields.takeIf { it.isNotEmpty() }?.let(::JsonObject),
    )
    return client().post("/$runId/directive", body, CompanionDirectiveRequest.serializer()).print()
  }
}

@Command(name = "listen", mixinStandardHelpOptions = true, description = ["Stream human and lifecycle events as JSON Lines."])
internal class CompanionListenCommand : CompanionAction(), Callable<Int> {
  @Parameters(index = "0", description = ["Companion run id."])
  lateinit var runId: String

  @Option(names = ["--after"], description = ["Resume after this event sequence number."])
  var after: String? = null

  override fun call(): Int {
    validateRunId(runId)?.let { return companionFailure(it) }
    val afterSeq = after?.toIntOrNull()?.takeIf { it >= 0 }
    if (after != null && afterSeq == null) return companionFailure("--after must be a non-negative integer")
    return client().listen(runId, afterSeq)
  }
}

@Command(name = "disconnect", mixinStandardHelpOptions = true, description = ["End a companion session."])
internal class CompanionDisconnectCommand : CompanionAction(), Callable<Int> {
  @Parameters(index = "0", description = ["Companion run id."])
  lateinit var runId: String

  @Option(names = ["--note"], description = ["Optional closing summary."])
  var note: String? = null

  override fun call(): Int {
    validateRunId(runId)?.let { return companionFailure(it) }
    return client().post(
      "/$runId/disconnect",
      CompanionDisconnectRequest(note),
      CompanionDisconnectRequest.serializer(),
    ).print()
  }
}

@Command(name = "respond", mixinStandardHelpOptions = true, description = ["Settle a request delegated by Trail Runner."])
internal class CompanionRespondCommand : CompanionAction(), Callable<Int> {
  @Parameters(index = "0", description = ["Companion run id."])
  lateinit var runId: String

  @Option(names = ["--request"], required = true, description = ["Request id from the listen stream."])
  lateinit var requestId: String

  @Option(names = ["--status"], required = true, description = ["done or error."])
  lateinit var status: String

  @Option(names = ["--note"], description = ["Optional result note."])
  var note: String? = null

  override fun call(): Int {
    validateRunId(runId)?.let { return companionFailure(it) }
    if (status !in setOf("done", "error")) return companionFailure("--status must be done or error")
    return client().post(
      "/$runId/respond",
      CompanionRespondRequest(requestId = requestId, status = status, note = note),
      CompanionRespondRequest.serializer(),
    ).print()
  }
}

internal data class CompanionApiResult(
  val body: String,
  val ok: Boolean,
  val failureExitCode: TrailblazeExitCode = TrailblazeExitCode.INFRA_FAILED,
) {
  fun print(): Int {
    println(body)
    return if (ok) TrailblazeExitCode.SUCCESS.code else failureExitCode.code
  }
}

internal class CompanionCliClient(
  port: Int,
  private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
) {
  internal val baseUrl = "http://localhost:$port/trailrunner/api/companion"

  fun <T> post(path: String, body: T, serializer: KSerializer<T>): CompanionApiResult = try {
    val request = HttpRequest.newBuilder(URI.create(baseUrl + path))
      .timeout(Duration.ofSeconds(20))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(companionJson.encodeToString(serializer, body)))
      .build()
    val response = http.send(request, HttpResponse.BodyHandlers.ofString())
    val text = response.body()
    val failureExitCode = if (response.statusCode() in 400..499) {
      TrailblazeExitCode.MISUSE
    } else {
      TrailblazeExitCode.INFRA_FAILED
    }
    if (responseIsJsonVerdict(text)) {
      CompanionApiResult(text, responseIsOk(text), failureExitCode)
    } else {
      CompanionApiResult(
        companionError("daemon request failed with HTTP ${response.statusCode()}"),
        false,
        failureExitCode,
      )
    }
  } catch (e: Exception) {
    CompanionApiResult(companionError("no response from the daemon: ${e.message ?: e.javaClass.simpleName}"), false)
  }

  fun listen(runId: String, after: Int?): Int = try {
    val query = buildString {
      append("?consumer=agent")
      if (after != null) append("&afterSeq=$after")
    }
    val request = HttpRequest.newBuilder(
      URI.create("http://localhost:${URI.create(baseUrl).port}/trailrunner/api/external-agent/$runId/stream$query")
    ).GET().build()
    val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
    if (response.statusCode() !in 200..299) {
      println(companionError("stream request failed with HTTP ${response.statusCode()}"))
      return TrailblazeExitCode.INFRA_FAILED.code
    }
    response.body().bufferedReader().use { reader ->
      var event = ""
      reader.lineSequence().forEach { raw ->
        val line = raw.removeSuffix("\r")
        when {
          line.startsWith("event:") -> event = line.substringAfter(':').trimStart()
          line.startsWith("data:") -> {
            val data = line.substringAfter(':').trimStart()
            when (event) {
              "agent-event" -> println(data)
              "done" -> return TrailblazeExitCode.SUCCESS.code
              "error" -> {
                println(data)
                return companionStreamErrorExitCode(data).code
              }
            }
          }
          line.isEmpty() -> event = ""
        }
      }
    }
    companionFailure(
      "stream ended without the done sentinel; resume with --after <lastSeq>",
      TrailblazeExitCode.INFRA_FAILED,
    )
  } catch (e: Exception) {
    companionFailure("stream failed: ${e.message ?: e.javaClass.simpleName}", TrailblazeExitCode.INFRA_FAILED)
  }
}

internal fun companionStreamErrorExitCode(data: String): TrailblazeExitCode {
  val error = runCatching {
    companionJson.parseToJsonElement(data).jsonObject["error"]?.jsonPrimitive?.content
  }.getOrNull()
  return if (error?.contains("run not found", ignoreCase = true) == true) {
    TrailblazeExitCode.MISUSE
  } else {
    TrailblazeExitCode.INFRA_FAILED
  }
}

private val companionJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
private val validRunId = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*")

private fun validateRunId(runId: String): String? =
  if (runId == "." || runId == ".." || !validRunId.matches(runId)) "invalid runId: $runId" else null

private fun responseIsOk(text: String): Boolean = runCatching {
  companionJson.parseToJsonElement(text).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull == true
}.getOrDefault(false)

private fun responseIsJsonVerdict(text: String): Boolean = runCatching {
  companionJson.parseToJsonElement(text).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull != null
}.getOrDefault(false)

internal fun companionFailure(
  message: String,
  exitCode: TrailblazeExitCode = TrailblazeExitCode.MISUSE,
): Int {
  println(companionError(message))
  return exitCode.code
}

private fun companionError(message: String): String = buildJsonObject {
  put("ok", false)
  put("error", message.filterNot { it.code in 0..31 }.take(500))
}.toString()
