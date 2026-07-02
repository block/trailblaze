package xyz.block.trailblaze.trailrunner

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentType
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.util.Console
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

// Default compile task for the rebuild-and-restart route: this module's own sources. A downstream
// desktop app overrides it via [TrailRunnerEndpoint.register]'s `rebuildGradleTask`.
internal const val DEFAULT_REBUILD_GRADLE_TASK: String = ":trailblaze-host:compileKotlin"

internal fun Route.daemonRoutes(deps: TrailRunnerDeps) {
  post("$PATH_BASE/api/daemon/rebuild") {
    // Require a JSON content-type so a cross-origin page can't drive-by trigger an
    // expensive rebuild + restart: this makes the request non-"simple", forcing a
    // CORS preflight the daemon never approves. The in-app client sends it explicitly.
    // (RPC callers always send JSON, so the typed RPC handler skips this transport guard.)
    if (!call.request.contentType().match(ContentType.Application.Json)) {
      call.respond(HttpStatusCode.UnsupportedMediaType, RebuildDaemonResponse(ok = false, error = "rebuild requires Content-Type: application/json"))
      return@post
    }
    call.respond(buildRebuildDaemonResponse(deps))
  }
}

/**
 * Recompiles the daemon's Gradle module and, on success, schedules a detached restart — the
 * shared source for both the REST `POST /api/daemon/rebuild` route and the `RebuildDaemonRequest`
 * RPC handler. The compile task comes from the registration seam ([TrailRunnerDeps.rebuildGradleTask],
 * supplied by the desktop app so it covers every daemon launch path); the
 * `TRAILBLAZE_REBUILD_GRADLE_TASK` env var remains as a per-launch emergency override. On success
 * the process exits ~800ms after this returns (giving the caller's response
 * time to flush); a compile or restart-scheduling failure rides in `RebuildDaemonResponse.ok=false`.
 */
internal suspend fun buildRebuildDaemonResponse(deps: TrailRunnerDeps): RebuildDaemonResponse {
  val root = File(System.getProperty("user.dir"))
  val compile = withContext(Dispatchers.IO) {
    runCatching {
      val rebuildTask = System.getenv("TRAILBLAZE_REBUILD_GRADLE_TASK")?.takeIf { it.isNotBlank() }
        ?: deps.rebuildGradleTask
      val proc = ProcessBuilder(File(root, "gradlew").absolutePath, rebuildTask, "--console=plain")
        .directory(root)
        .redirectErrorStream(true)
        .start()
      // Drain stdout on a background thread: readText() blocks until the pipe hits
      // EOF (only when gradle exits), so a wedged gradle holding the pipe open would
      // hang forever and waitFor's timeout would never be reached.
      val outBuf = StringBuilder()
      val reader = thread { proc.inputStream.bufferedReader().forEachLine { synchronized(outBuf) { outBuf.appendLine(it) } } }
      val finished = proc.waitFor(10, TimeUnit.MINUTES)
      if (!finished) proc.destroyForcibly()
      reader.join(2000)
      val out = synchronized(outBuf) { outBuf.toString() }
      if (!finished) {
        RebuildDaemonResponse(ok = false, error = "compile timed out after 10 minutes")
      } else if (proc.exitValue() != 0) {
        RebuildDaemonResponse(ok = false, error = out.takeLast(4000))
      } else {
        RebuildDaemonResponse(ok = true)
      }
    }.getOrElse { e ->
      RebuildDaemonResponse(ok = false, error = e.message ?: "could not run gradle compile")
    }
  }
  if (!compile.ok) {
    return compile
  }
  val port = System.getenv("TRAILBLAZE_PORT")?.takeIf { it.isNotBlank() } ?: "52525"
  val launcher = System.getenv("TRAILBLAZE_LAUNCHER")?.takeIf { it.isNotBlank() }
    ?: File(root, "trailblaze").absolutePath
  val d = "$"
  val script = File.createTempFile("trailrunner-restart", ".sh")
  // The restart outlives this process, so we can't delete the script inline;
  // deleteOnExit covers the failure path where it never gets exec'd, and the
  // detached bash reads it once at startup before we exit.
  script.deleteOnExit()
  script.writeText(
    """
    #!/bin/bash
    exec >> /tmp/trailrunner-daemon-restart.log 2>&1
    printf '=== restart %s ===\n' "$d(date)"
    for i in $d(seq 1 120); do
      curl -s --connect-timeout 1 "http://localhost:$port/ping" >/dev/null 2>&1 || break
      sleep 0.5
    done
    cd "${root.absolutePath}"
    export BLAZE_JAR=0
    exec "$launcher" app --foreground --headless
    """.trimIndent(),
  )
  script.setExecutable(true)
  val started = runCatching { ProcessBuilder("bash", script.absolutePath).start() }
  if (started.isFailure) {
    return RebuildDaemonResponse(ok = false, error = "compiled, but could not schedule the restart: ${started.exceptionOrNull()?.message}")
  }
  Console.log("[TrailRunnerEndpoint] rebuild OK — daemon restarting via ${script.absolutePath}")
  // Exit after a short delay so the caller's response (REST body or RPC result) flushes first.
  thread {
    Thread.sleep(800)
    exitProcess(0)
  }
  return RebuildDaemonResponse(ok = true)
}
