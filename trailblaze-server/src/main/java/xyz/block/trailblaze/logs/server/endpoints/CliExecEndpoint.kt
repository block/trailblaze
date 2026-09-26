package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.util.Console

/**
 * Request to run a CLI subcommand in-process on the daemon.
 *
 * @property args the tokenized argv the CLI would have passed to picocli.
 * @property cwd the caller's interactive cwd (typically `$PWD` from the bash shim),
 *   plumbed through so commands that walk relative paths (`waypoint --target` resolving
 *   the workspace anchor at `<cwd>/trails/config/trailblaze.yaml`) anchor at the user's
 *   shell directory rather than the daemon's launch cwd. Optional for backward
 *   compatibility with shims that predate this field; when absent, the daemon falls
 *   back to `Paths.get("")` which is its own cwd. See [xyz.block.trailblaze.cli.CliCallerContext].
 * @property env the caller's interactive env vars relevant to CLI resolution
 *   (currently `TRAILBLAZE_DEVICE`). The daemon's own JVM env was captured when
 *   `app start` ran — any `export TRAILBLAZE_DEVICE=…` the user did afterward
 *   never reaches `System.getenv` on the daemon side. Forwarding the caller's
 *   shell env through this field is how the in-process forwarded subcommands
 *   (`snapshot`, `ask`, `config`) see the shell pin that `eval $(trailblaze
 *   device connect …)` set.
 *
 *   When non-null, this map is **authoritative**: present keys hold the
 *   user's shell values, absent keys mean the user has the var unset.
 *   The daemon-side resolver does NOT fall back to `System.getenv` when the
 *   map is non-null — otherwise an `eval $(trailblaze device disconnect)`
 *   that sends `env: {}` would silently resurrect the daemon's frozen
 *   `TRAILBLAZE_DEVICE` from `app start` time, the inverse of the fix.
 *
 *   Null (field absent in the JSON body) means an older bash shim that
 *   predates this field — the daemon falls back to `System.getenv` so
 *   shipping a newer daemon doesn't regress older shims. See
 *   [xyz.block.trailblaze.cli.CliCallerContext] for the read-side contract.
 */
@Serializable
data class CliExecRequest(
  val args: List<String>,
  val cwd: String? = null,
  val env: Map<String, String>? = null,
)

/** Which of the two standard streams a [CliExecChunk] was written to. */
@Serializable
enum class CliExecStream {
  @SerialName("stdout")
  STDOUT,

  @SerialName("stderr")
  STDERR,
}

/**
 * One run of bytes the command wrote to a single stream, in the order it wrote them.
 *
 * @property stream where the command wrote these bytes.
 * @property text the bytes, decoded as UTF-8.
 */
@Serializable
data class CliExecChunk(
  val stream: CliExecStream,
  val text: String,
)

/**
 * Result of an in-process CLI execution. The shim in `./trailblaze` replays the
 * output on its own stdout and stderr and exits with [exitCode] to preserve the
 * user-visible contract of a local invocation.
 *
 * [transcript] is the authoritative output: both streams in the single order the
 * command actually wrote them. [stdout] and [stderr] are that same output flattened
 * per stream, kept for shims that predate the transcript — those replay all of one
 * stream and then all of the other, which reorders a command's output against
 * itself. A command that prints a status line, then an error, then a tip about that
 * error comes out with the status line wedged between the error and its tip.
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
  val transcript: List<CliExecChunk> = emptyList(),
)

/**
 * A forwarded [CliExecResponse] in a framing the bash shim replays with builtins alone, so the
 * forwarded path needs no JSON decoder process. The shim asks for it with an `Accept` header of
 * [CONTENT_TYPE] and keeps its JSON decoder for daemons that predate it.
 *
 * UTF-8 bytes, in this order:
 * ```
 * trailblaze-replay 1\n
 * exit <0-255>\n
 * <1|2> <byte count>\n<bytes>      one per run of output: 1 = stdout, 2 = stderr, in write order
 * end\n
 * ```
 * Byte counts rather than delimiters, because output can contain any delimiter. NUL characters are
 * dropped: bash cannot hold them in a variable, so a count that included them would never match.
 * The trailing `end` is what tells a complete body from a truncated one.
 */
object CliExecReplayFormat {

  const val CONTENT_TYPE: String = "application/vnd.trailblaze.cli-replay"

  private const val HEADER = "trailblaze-replay 1\n"

  fun encode(response: CliExecResponse): ByteArray {
    val chunks = response.transcript.ifEmpty {
      listOf(
        CliExecChunk(CliExecStream.STDOUT, response.stdout),
        CliExecChunk(CliExecStream.STDERR, response.stderr),
      )
    }
    val out = java.io.ByteArrayOutputStream()
    out.write(HEADER.encodeToByteArray())
    out.write("exit ${Math.floorMod(response.exitCode, 256)}\n".encodeToByteArray())
    var pendingStream: CliExecStream? = null
    val pending = StringBuilder()
    fun flush() {
      val stream = pendingStream ?: return
      val bytes = pending.toString().encodeToByteArray()
      if (bytes.isNotEmpty()) {
        val tag = if (stream == CliExecStream.STDERR) 2 else 1
        out.write("$tag ${bytes.size}\n".encodeToByteArray())
        out.write(bytes)
      }
      pending.setLength(0)
    }
    for (chunk in chunks) {
      // Consecutive writes to one stream replay as one: the shim slices each run out of the body
      // separately, so fewer runs is less work there.
      if (chunk.stream != pendingStream) {
        flush()
        pendingStream = chunk.stream
      }
      pending.append(chunk.text.replace("\u0000", ""))
    }
    flush()
    out.write("end\n".encodeToByteArray())
    return out.toByteArray()
  }
}

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

      val wantsReplay = call.request.headers.getAll(HttpHeaders.Accept).orEmpty()
        .any { it.contains(CliExecReplayFormat.CONTENT_TYPE) }
      try {
        val response = onExec(request)
        // Only a forwarded result is framed. Anything else sends the shim back to its own JVM,
        // which it decides from the JSON as it always has.
        if (wantsReplay && response.forwarded) {
          call.respondBytes(
            CliExecReplayFormat.encode(response),
            ContentType.parse(CliExecReplayFormat.CONTENT_TYPE),
            HttpStatusCode.OK,
          )
        } else {
          respondJson(HttpStatusCode.OK, response)
        }
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
