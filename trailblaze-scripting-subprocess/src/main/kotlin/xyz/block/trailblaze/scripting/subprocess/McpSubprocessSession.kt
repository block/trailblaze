package xyz.block.trailblaze.scripting.subprocess

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.util.concurrent.TimeUnit

/**
 * One running subprocess MCP session: the spawned `bun` / `tsx` [Process], the
 * `io.modelcontextprotocol.kotlin.sdk.client.Client` wired to it, and the stdio transport
 * connecting the two.
 *
 * Authored classes aren't meant to construct this directly — call [connect] to spawn and
 * finish the MCP `initialize` handshake in one step. The live [client] is exposed so later
 * commits can drive `tools/list` and `tools/call` against it.
 *
 * Shutdown is intentionally minimal for this landing: close the client (which tears down the
 * transport and sends SIGPIPE via stdin close), wait up to 5 s for the subprocess to exit,
 * then escalate to [Process.destroy] → [Process.destroyForcibly]. The graceful-shutdown
 * polish (MCP `shutdown` notification, structured log capture of tail stderr) lives in the
 * lifecycle commit.
 */
class McpSubprocessSession internal constructor(
  val spawnedProcess: SpawnedProcess,
  val transport: StdioClientTransport,
  val client: Client,
  val stderrCapture: StderrCapture,
) {

  /** True while the subprocess is still alive. Flips to false on exit / shutdown. */
  val isAlive: Boolean get() = spawnedProcess.process.isAlive

  /**
   * Closes the MCP client (which closes the stdio transport + the process's stdin), then
   * waits briefly for the subprocess to exit on its own. Escalates to SIGTERM / SIGKILL if
   * it doesn't honor the EOF signal inside [exitWait]. Flushes + closes the stderr capture
   * last so the on-disk log ends up complete regardless of which escalation step terminated
   * the process.
   *
   * Blocking `Process.waitFor` calls run under [Dispatchers.IO] so the caller's coroutine
   * dispatcher (often `Default`) isn't pinned while we wait up to ~9 s on a stuck child.
   * After `destroyForcibly` we still wait `afterSigkillSeconds` so the function doesn't
   * return until the subprocess is actually gone — callers can safely re-spawn immediately.
   */
  suspend fun shutdown(exitWait: Duration = Duration.DEFAULT) = withContext(Dispatchers.IO) {
    runCatching { client.close() }
    destroyWithEscalation(spawnedProcess.process, exitWait)
    stderrCapture.close()
  }

  /** Shutdown timing knobs — exposed so tests can hurry the escalation. */
  data class Duration(
    val afterCloseSeconds: Long,
    val afterSigtermSeconds: Long,
    /** Bounded wait after [Process.destroyForcibly] so [shutdown] only returns once the OS
     *  has actually reaped the subprocess. Without this, callers racing a re-spawn can land
     *  on a zombie still holding file descriptors. */
    val afterSigkillSeconds: Long,
  ) {
    companion object {
      /** Scope-devlog defaults: 5 s for graceful exit, 2 s after SIGTERM, 2 s after SIGKILL. */
      val DEFAULT = Duration(
        afterCloseSeconds = 5,
        afterSigtermSeconds = 2,
        afterSigkillSeconds = 2,
      )
    }
  }

  companion object {

    /** What Trailblaze advertises to MCP servers as the connecting client. */
    val DEFAULT_CLIENT_INFO: Implementation = Implementation(
      name = "trailblaze",
      version = "0.1.0",
    )

    /**
     * Default stderr severity classifier. Lines mentioning "error" surface as WARNING so they
     * show up in session logs; everything else is DEBUG. Authors wanting richer classification
     * pass their own classifier to [connect].
     */
    val DEFAULT_STDERR_CLASSIFIER: (String) -> StdioClientTransport.StderrSeverity = { line ->
      if (line.contains("error", ignoreCase = true)) {
        StdioClientTransport.StderrSeverity.WARNING
      } else {
        StdioClientTransport.StderrSeverity.DEBUG
      }
    }

    /**
     * Wires a [StdioClientTransport] to [spawnedProcess]'s stdio, constructs a [Client] with
     * [clientInfo], and performs the MCP `initialize` handshake before returning.
     *
     * Does **not** populate `_meta.trailblaze` on the initialize request. The scope devlog
     * (§ MCP handshake flow) documents why: neither the Kotlin client SDK nor the TypeScript
     * server SDK exposes the `_meta` channel on `initialize` ergonomically, so the
     * `TRAILBLAZE_*` env vars from [McpSubprocessSpawner.envVars] are the authoritative
     * handshake snapshot. A structured handshake payload via `ClientCapabilities.extensions`
     * is the more natural future path; additive follow-up.
     */
    suspend fun connect(
      spawnedProcess: SpawnedProcess,
      clientInfo: Implementation = DEFAULT_CLIENT_INFO,
      stderrCapture: StderrCapture = StderrCapture(),
      stderrClassifier: (String) -> StdioClientTransport.StderrSeverity = DEFAULT_STDERR_CLASSIFIER,
    ): McpSubprocessSession {
      val process = spawnedProcess.process
      val transport = StdioClientTransport(
        input = process.inputStream.asSource().buffered(),
        output = process.outputStream.asSink().buffered(),
        error = process.errorStream.asSource().buffered(),
        classifyStderr = { line ->
          stderrCapture.accept(line)
          stderrClassifier(line)
        },
      )
      val client = Client(clientInfo, ClientOptions())
      try {
        client.connect(transport)
      } catch (t: Throwable) {
        // Handshake failed (server crashed during init, bad protocol version, streams closed,
        // etc.). Without this path the caller has no [McpSubprocessSession] to shutdown, so
        // the subprocess would orphan itself and leak stdin/stdout/stderr pipes. Shares the
        // same escalation knobs as [shutdown] so both cleanup paths scale together.
        runCatching { client.close() }
        withContext(Dispatchers.IO) {
          destroyWithEscalation(process, Duration.DEFAULT)
        }
        runCatching { stderrCapture.close() }
        throw t
      }
      return McpSubprocessSession(spawnedProcess, transport, client, stderrCapture)
    }
  }
}

/**
 * Escalates [process] teardown under one [exitWait] knob: SIGTERM → wait → SIGKILL → wait.
 * Assumes the caller has already signalled the subprocess (e.g. by closing stdin via
 * `client.close()`) and is just waiting for it to exit before escalating. Shared between
 * the public `shutdown` path and the initialize-failure cleanup inside `connect` so both
 * honour the same [Duration] configuration.
 *
 * Must run under [Dispatchers.IO] — uses blocking [Process.waitFor].
 */
private fun destroyWithEscalation(process: Process, exitWait: McpSubprocessSession.Duration) {
  if (!process.waitFor(exitWait.afterCloseSeconds, TimeUnit.SECONDS)) {
    process.destroy()
    if (!process.waitFor(exitWait.afterSigtermSeconds, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      process.waitFor(exitWait.afterSigkillSeconds, TimeUnit.SECONDS)
    }
  }
}
