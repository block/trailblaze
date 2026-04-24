package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.util.Console

/**
 * Request to run a CLI subcommand in-process on the daemon.
 * [args] is the tokenized argv the CLI would have passed to picocli.
 */
@Serializable
data class CliExecRequest(val args: List<String>)

/**
 * Result of an in-process CLI execution. The shim in `./trailblaze` prints
 * [stdout] on its own stdout, [stderr] on its own stderr, and exits with
 * [exitCode] to preserve the user-visible contract of a local invocation.
 *
 * [forwarded] is false when the daemon declines to run the command locally
 * (e.g. not in the forwardable allowlist); the shim should fall through to
 * the normal JVM path in that case.
 */
@Serializable
data class CliExecResponse(
  val stdout: String,
  val stderr: String,
  val exitCode: Int,
  val forwarded: Boolean = true,
)

/**
 * Endpoint for the CLI-via-daemon IPC fast path.
 *
 * POST [CliEndpoints.EXEC] with `{"args": [...]}`. The daemon runs the
 * picocli CLI in-process with stdout/stderr captured via
 * [xyz.block.trailblaze.cli.CliOutCapture], eliminating the ~1.1s cold JVM
 * startup the CLI would otherwise pay.
 *
 * The handler decides which subcommands are safe to forward — the endpoint
 * itself just relays the call.
 */
object CliExecEndpoint {

  /**
   * Explicit Json so we can (de)serialize without relying on a ContentNegotiation install.
   *
   * `encodeDefaults = true` is critical: [CliExecResponse.forwarded] defaults to `true`,
   * and the bash shim reads `.forwarded // false` — so if the field is omitted (kotlinx's
   * default), the shim falls back to the JVM path on every successful forward, silently
   * regressing the fast path to "IPC round-trip + JVM startup" (worst of both worlds).
   */
  private val json: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  /**
   * Loopback addresses accepted by this endpoint. Mirrors
   * [ScriptingCallbackEndpoint] — the daemon binds to `::` (all interfaces)
   * via [xyz.block.trailblaze.logs.server.SslConfig], so without this gate a
   * remote caller on the same network could POST `/cli/exec` and drive the
   * connected device via `snapshot`/`ask` (reading screenshots, LLM output).
   * Legitimate callers are always the local shell shim on loopback.
   */
  private val LOOPBACK_ADDRESSES = setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "localhost")

  private fun isLoopback(address: String): Boolean =
    address in LOOPBACK_ADDRESSES ||
      address.startsWith("127.") ||
      // Dual-stack hosts sometimes report IPv4-mapped IPv6 (`::ffff:127.0.0.1`).
      address.startsWith("::ffff:127.")

  /**
   * Hard cap on the incoming request body — argv for any forwardable command
   * fits comfortably below this; anything larger is almost certainly a buggy
   * or malicious local caller trying to OOM the daemon.
   */
  private const val MAX_REQUEST_BYTES: Long = 1L * 1024 * 1024 // 1 MiB

  fun register(
    routing: Routing,
    onExec: suspend (CliExecRequest) -> CliExecResponse,
  ) = with(routing) {
    post(CliEndpoints.EXEC) {
      val remoteAddress = call.request.local.remoteAddress
      if (!isLoopback(remoteAddress)) {
        Console.log("[cli/exec] BLOCKED non-loopback request from $remoteAddress")
        call.respond(
          HttpStatusCode.Forbidden,
          "cli/exec is only available from loopback (got $remoteAddress)",
        )
        return@post
      }

      val declaredLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L
      if (declaredLength > MAX_REQUEST_BYTES) {
        respondJson(
          HttpStatusCode.PayloadTooLarge,
          CliExecResponse(
            stdout = "",
            stderr = "cli/exec: request body too large ($declaredLength bytes > $MAX_REQUEST_BYTES limit)\n",
            exitCode = 1,
            forwarded = false,
          ),
        )
        return@post
      }

      val bodyText = try {
        call.receive<String>()
      } catch (e: Exception) {
        respondJson(
          HttpStatusCode.BadRequest,
          CliExecResponse(
            stdout = "",
            stderr = "cli/exec: failed to read body: ${e.message ?: e::class.simpleName}\n",
            exitCode = 1,
            forwarded = false,
          ),
        )
        return@post
      }

      val request = try {
        json.decodeFromString(CliExecRequest.serializer(), bodyText)
      } catch (e: SerializationException) {
        respondJson(
          HttpStatusCode.BadRequest,
          CliExecResponse(
            stdout = "",
            stderr = "cli/exec: malformed request: ${e.message ?: e::class.simpleName}\n",
            exitCode = 1,
            forwarded = false,
          ),
        )
        return@post
      }

      try {
        respondJson(HttpStatusCode.OK, onExec(request))
      } catch (e: CancellationException) {
        // Structured concurrency: cancellation must propagate, not be swallowed
        // into a 500 response.
        throw e
      } catch (e: Exception) {
        // The CLI shim falls back silently on any failure, so we must log here
        // — otherwise real daemon problems disappear from daemon logs too.
        Console.error("[cli/exec] handler failed for argv=${request.args}: $e")
        e.printStackTrace()
        respondJson(
          HttpStatusCode.InternalServerError,
          CliExecResponse(
            stdout = "",
            stderr = "cli/exec failed: ${e.message ?: e::class.simpleName}\n",
            exitCode = 1,
            forwarded = false,
          ),
        )
      }
    }
  }

  private suspend fun io.ktor.server.routing.RoutingContext.respondJson(
    status: HttpStatusCode,
    body: CliExecResponse,
  ) {
    call.respondText(
      json.encodeToString(CliExecResponse.serializer(), body),
      ContentType.Application.Json,
      status,
    )
  }
}
