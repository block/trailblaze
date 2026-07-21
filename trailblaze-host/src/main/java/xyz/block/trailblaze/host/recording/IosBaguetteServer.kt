package xyz.block.trailblaze.host.recording

import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils.isCommandAvailable

/**
 * Owns the machine-local `baguette serve` process that backs iOS Simulator live streaming for the
 * `/devices` viewer — the iOS analogue of Android's shared `screenrecord` H.264 producer.
 *
 * The iOS Simulator has no stock live-H.264 primitive: `simctl io recordVideo` only writes a
 * seekable file (it refuses a pipe / `/dev/stdout`), and baguette's own `stream`-to-stdout mode is
 * broken in the shipping release (it writes an HTTP header and no frames). The working transport is
 * [baguette](https://github.com/tddworks/baguette)'s `serve` HTTP/WebSocket server: it captures the
 * simulator framebuffer via private SimulatorKit frameworks, hardware-encodes H.264 with
 * VideoToolbox, and streams avcc records over `ws://<host>/simulators/<udid>/stream?format=avcc`
 * (see [streamIosLiveH264AccessUnits]).
 *
 * **One shared server, fanned out.** A single `baguette serve` handles every booted simulator and
 * every concurrent client — unlike a subprocess-per-connection model, one server multiplexes all
 * viewers. [ensureServing] starts it lazily on first use and, if a server is already listening
 * (started by this daemon or another), reuses it rather than spawning a second. So multiple
 * Trailblaze daemons on one machine share the same video source, and one simulator can be watched
 * from several viewers at once. Only a server this JVM started is torn down on shutdown; a reused
 * one is left alone.
 *
 * **Optional dependency.** baguette is macOS + Apple-Silicon only and is not bundled. When it isn't
 * installed ([isAvailable] is false) the `/devices/api/stream` endpoint declines the iOS H.264 path
 * and the browser falls back to the JPEG poll — so the viewer keeps working everywhere, just at
 * screenshot cadence. The binary is resolved once (lazily); install baguette before starting the
 * daemon.
 */
internal object IosBaguetteServer {

  /** Env override pointing directly at a `baguette` binary (skips PATH / Homebrew resolution). */
  private const val ENV_BAGUETTE = "TRAILBLAZE_BAGUETTE"

  /** Env override for the `baguette serve` port (default [DEFAULT_SERVE_PORT]). */
  private const val ENV_SERVE_PORT = "TRAILBLAZE_BAGUETTE_SERVE_PORT"

  /** Homebrew's native (arm64) install location — baguette only ships for Apple Silicon. */
  private const val HOMEBREW_BAGUETTE = "/opt/homebrew/bin/baguette"

  /** baguette's own default `serve` port; matches its web UI so a manually-started server is reused. */
  private const val DEFAULT_SERVE_PORT = 8421

  /** Loopback only — the server is a local video source, never exposed off-host. */
  private const val SERVE_HOST = "127.0.0.1"

  /** Max wait for a freshly-spawned server to answer before giving up (generous for loaded CI). */
  private const val SERVE_READY_TIMEOUT_MS = 15_000L

  /** Resolved `baguette` command (or null when not installed). Read once. */
  private val command: String? by lazy { resolveBaguette() }

  /** Port `baguette serve` listens on. Read once. */
  private val servePort: Int by lazy { resolveServePort() }

  /** Short-timeout client for the readiness probe; the WS stream uses its own client. */
  private val probeClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(500, TimeUnit.MILLISECONDS)
      .readTimeout(500, TimeUnit.MILLISECONDS)
      .callTimeout(1, TimeUnit.SECONDS)
      .build()
  }

  private val startLock = Any()
  @Volatile private var spawnedProcess: Process? = null

  /** True when a `baguette` binary was found; the iOS H.264 stream path is only offered when true. */
  fun isAvailable(): Boolean = command != null

  /**
   * Guarantees a `baguette serve` is reachable and returns its `host:port` authority (e.g.
   * `127.0.0.1:8421`), or null when baguette isn't installed or the server never became ready.
   * Idempotent: reuses an already-listening server and only spawns one when none is up.
   */
  fun ensureServing(): String? {
    command ?: return null
    if (isServeHealthy()) return authority()
    synchronized(startLock) {
      if (isServeHealthy()) return authority()
      if (!startServe()) return null
    }
    return authority()
  }

  private fun authority(): String = "$SERVE_HOST:$servePort"

  /** GET `/simulators`; a 200 means a baguette serve (ours or another daemon's) is already up. */
  private fun isServeHealthy(): Boolean =
    runCatching {
        probeClient
          .newCall(Request.Builder().url("http://${authority()}/simulators").get().build())
          .execute()
          .use { it.isSuccessful }
      }
      .getOrDefault(false)

  /** Spawns `baguette serve` and waits until it answers. Returns false if it never comes up. */
  private fun startServe(): Boolean {
    val baguette = command ?: return false
    Console.log("[IosBaguetteServer] starting baguette serve on ${authority()}")
    val process =
      ProcessBuilder(baguette, "serve", "--port", servePort.toString(), "--host", SERVE_HOST)
        // Keep stderr off stdout; drain it to the daemon log so serve diagnostics are greppable.
        .redirectErrorStream(false)
        .start()
    spawnedProcess = process
    drainStderr(process)
    // Tear down only the server this JVM spawned — a reused one belongs to another daemon.
    Runtime.getRuntime()
      .addShutdownHook(
        Thread {
          runCatching {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
          }
        },
      )

    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SERVE_READY_TIMEOUT_MS)
    while (System.nanoTime() < deadline) {
      if (!process.isAlive) {
        Console.log("[IosBaguetteServer] baguette serve exited before becoming ready")
        return false
      }
      if (isServeHealthy()) {
        Console.log("[IosBaguetteServer] baguette serve ready on ${authority()} (pid ${process.pid()})")
        return true
      }
      runCatching { Thread.sleep(200) }
    }
    Console.log(
      "[IosBaguetteServer] baguette serve did not become ready within ${SERVE_READY_TIMEOUT_MS}ms; " +
        "iOS live stream will fall back to JPEG polling",
    )
    return false
  }

  private fun drainStderr(process: Process) {
    Thread(
        {
          runCatching {
            process.errorStream.bufferedReader().useLines { lines ->
              lines.forEach { Console.log("[IosBaguetteServer/baguette] $it") }
            }
          }
        },
        "ios-baguette-serve-stderr",
      )
      .apply {
        isDaemon = true
        start()
      }
  }

  private fun resolveServePort(): Int {
    val raw = System.getenv(ENV_SERVE_PORT)?.trim().orEmpty()
    if (raw.isEmpty()) return DEFAULT_SERVE_PORT
    val parsed = raw.toIntOrNull()?.takeIf { it in 1..65535 }
    if (parsed == null) {
      Console.log("[IosBaguetteServer] $ENV_SERVE_PORT='$raw' is not a valid port; using $DEFAULT_SERVE_PORT")
      return DEFAULT_SERVE_PORT
    }
    return parsed
  }

  /**
   * Resolves the `baguette` binary against the real environment: `TRAILBLAZE_BAGUETTE` override,
   * then PATH, then the native Homebrew location. Delegates the decision to the pure
   * [resolveBaguettePath] so the resolution order is unit-testable without touching the filesystem.
   */
  private fun resolveBaguette(): String? =
    resolveBaguettePath(
      envOverride = System.getenv(ENV_BAGUETTE),
      isExecutable = { File(it).canExecute() },
      isOnPath = { isCommandAvailable(it) },
    )

  /**
   * Pure resolution of which `baguette` command to run, given injected environment lookups. Returns
   * null (not a bare `"baguette"`) when none resolves, so the caller can cleanly decline the H.264
   * path instead of failing later with an opaque "cannot run program". Order: an executable
   * `TRAILBLAZE_BAGUETTE` override wins; otherwise a `baguette` on PATH; otherwise the executable
   * native Homebrew binary; otherwise null.
   */
  internal fun resolveBaguettePath(
    envOverride: String?,
    isExecutable: (String) -> Boolean,
    isOnPath: (String) -> Boolean,
  ): String? {
    envOverride?.takeIf { it.isNotBlank() }?.let { path ->
      if (isExecutable(path)) return path
      Console.log("[IosBaguetteServer] $ENV_BAGUETTE=$path is not executable; falling back")
    }
    if (isOnPath("baguette")) return "baguette"
    if (isExecutable(HOMEBREW_BAGUETTE)) return HOMEBREW_BAGUETTE
    Console.log(
      "[IosBaguetteServer] baguette not found on PATH; iOS H.264 streaming unavailable " +
        "(install with `brew install baguette`). Falling back to JPEG polling.",
    )
    return null
  }
}
