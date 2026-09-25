package xyz.block.trailblaze.util

import dadb.AdbShellPacket
import dadb.Dadb
import xyz.block.trailblaze.device.AndroidShellBounds
import dadb.adbserver.AdbServer
import xyz.block.trailblaze.android.tools.shellEscape
import xyz.block.trailblaze.device.InstalledApp
import xyz.block.trailblaze.device.parseInstalledAppsFromDumpsys
import xyz.block.trailblaze.device.parsePmListPackages
import xyz.block.trailblaze.device.redactBulkPayloadsForLog
import xyz.block.trailblaze.device.PM_LIST_PACKAGES_ARGV
import xyz.block.trailblaze.device.PmClearOutcome
import xyz.block.trailblaze.device.validateClearAppDataAppId
import xyz.block.trailblaze.device.verifyPmClearSucceeded
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.model.AppVersionInfo
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils.runProcess
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Removes [client] from [clients] only if it is still the value mapped to [key], and closes it
 * only when this call is the one that removed it.
 *
 * A concurrent caller sharing the same key can have already evicted-and-reconnected by the time
 * this runs: the client this call knew about may no longer be cached at all, replaced by a fresh,
 * healthy one a different caller now depends on. Removing "whatever is currently cached" would
 * tear that unrelated connection down instead of the one this call actually used; comparing
 * against the exact instance first means a caller can only ever evict its own client.
 *
 * Generic and free of [dadb.Dadb] so it is testable with a fake key/value pair, no device or real
 * transport required.
 */
internal fun <K, T : AutoCloseable> evictExactClient(clients: ConcurrentHashMap<K, T>, key: K, client: T?) {
  if (client != null && clients.remove(key, client)) {
    runCatching { client.close() }
  }
}

/**
 * Resolves a client and runs [block] against it, handing a transport-level [IOException] to
 * [onTransportFailure] together with the exact client the attempt used.
 *
 * [resolve] runs inside the `try` on purpose: creating a client opens a socket, so a cold connect
 * can fail with the same transient [IOException] as the call itself and must reach the same
 * recovery. The used client is `null` in that case, so the eviction that follows is a no-op
 * rather than a removal of some other caller's client.
 *
 * A device-side sync FAIL (pulling/pushing an unreadable or missing remote path) also arrives as
 * an [IOException], but the transport is healthy - the device answered, the answer was "no".
 * Evicting and retrying would run every denied transfer twice and churn the shared client under
 * concurrent callers (observed: the installed-apps badge fetcher logging an eviction per system
 * APK it isn't allowed to pull). It propagates as the terminal failure it is.
 *
 * Generic and free of [dadb.Dadb] so the retry boundary is testable without a device.
 */
internal fun <C, T> runOnResolvedClient(
  resolve: () -> C,
  onClientResolved: (C) -> Unit,
  onTransportFailure: (e: IOException, usedClient: C?) -> T,
  block: (C) -> T,
): T {
  var usedClient: C? = null
  return try {
    val client = resolve()
    usedClient = client
    onClientResolved(client)
    block(client)
  } catch (e: IOException) {
    if (!isTransportFailure(e)) throw e
    onTransportFailure(e, usedClient)
  }
}

/**
 * [runOnResolvedClient] that never re-runs [block]: [onTransportFailure] observes the failure (to
 * evict the client), then the failure propagates.
 *
 * This is the transport every bounded worker uses. A worker whose caller timed out is abandoned,
 * not stopped, so a retry inside it would fire whenever the adb server finally drops its socket —
 * minutes later, against a device that has moved on to another trail. Named and tested on its own
 * because "the worker cannot re-run a side effect" rests entirely on it.
 */
internal fun <C, T> runOnResolvedClientOnce(
  resolve: () -> C,
  onClientResolved: (C) -> Unit,
  onTransportFailure: (e: IOException, usedClient: C?) -> Unit,
  block: (C) -> T,
): T = runOnResolvedClient(
  resolve = resolve,
  onClientResolved = onClientResolved,
  onTransportFailure = { e, usedClient ->
    onTransportFailure(e, usedClient)
    throw e
  },
  block = block,
)

/**
 * Whether [e] means the transport broke, as opposed to the device answering "no". A sync FAIL is
 * the device refusing a pull/push of an unreadable path over a healthy transport.
 */
internal fun isTransportFailure(e: Throwable?): Boolean =
  e is IOException && !e.message.orEmpty().startsWith("Sync failed")

object AndroidHostAdbUtils {

  // One Dadb client per device serial. Cached for the JVM lifetime; the underlying ADB connection
  // is reused across calls (handshake amortized). If a device disconnects mid-run the cached client
  // throws on next use; we evict and reconnect lazily.
  private val dadbClients = ConcurrentHashMap<String, Dadb>()

  // Tracks active port forwards (host port → AutoCloseable) so removePortForward can tear them
  // down. The AutoCloseable owns the forward's lifetime (removes it via the adb binary on close).
  private val activeForwards = ConcurrentHashMap<Pair<String, Int>, AutoCloseable>()

  // Default per-shell-call timeout for command paths that need a bounded wait (`logcat -d`,
  // `getprop`, port-forward removal). Long enough to absorb a slow device, short enough to fail
  // fast when adbd or a transport is wedged. Streaming paths (`logcat -f`, `screenrecord`,
  // `am instrument`) intentionally bypass this and run indefinitely.
  //
  // Tunable via `TRAILBLAZE_ADB_TIMEOUT_MS` for slow CI emulators. See `CLAUDE.md` for the full
  // list of ADB-related env vars supported here.
  private val DEFAULT_SHORT_CALL_TIMEOUT_MS: Long =
    System.getenv("TRAILBLAZE_ADB_TIMEOUT_MS")?.toLongOrNull()?.also {
      Console.log("[AndroidHostAdbUtils] short-call timeout overridden via TRAILBLAZE_ADB_TIMEOUT_MS=$it")
    } ?: 10_000L

  // LLM provider tokens reach the device as `trailblaze.llm.auth.token.<provider>` args; redact
  // their values so CI shell-command log artifacts don't leak live credentials.
  private val AUTH_TOKEN_ARG_REGEX =
    Regex("""('?trailblaze\.llm\.auth\.token\.[^'\s]+'?\s+)((?:'[^']*'|\\'|[^\s'])+)""")

  // Also strip base64 file bodies: `writeFileAs` carries a file's bytes inside the command line,
  // and those bodies (seeded auth/session files) are the ones tools already mask in session logs.
  internal fun redactSecretsForLog(command: String): String =
    redactBulkPayloadsForLog(AUTH_TOKEN_ARG_REGEX.replace(command) { "${it.groupValues[1]}<redacted>" })

  // Drains the Dadb client cache and any active port forwards on JVM exit. Without this, a
  // long-running daemon (`./trailblaze app start`) leaves dadb sockets and host-side forward
  // listeners open until the OS reclaims them on process death. The legacy ProcessBuilder model
  // didn't need this because every call was an ephemeral subprocess.
  //
  // Snapshot the maps before iterating: ConcurrentHashMap.values() is weakly consistent so a
  // racing daemon thread mid-stream wouldn't throw CME, but it could see a half-closed client
  // and surface a confusing IOException right at JVM exit. Snapshotting (then clearing, then
  // closing the snapshot) means concurrent lookups during shutdown either find the entry
  // (still valid, will be closed in a moment) or miss it cleanly (cleared) — never a half-state.
  init {
    Runtime.getRuntime().addShutdownHook(
      Thread(
        {
          val forwards = activeForwards.values.toList()
          val clients = dadbClients.values.toList()
          activeForwards.clear()
          dadbClients.clear()
          forwards.forEach { runCatching { it.close() } }
          clients.forEach { runCatching { it.close() } }
        },
        "AndroidHostAdbUtils-shutdown",
      ),
    )
  }

  private fun dadbFor(deviceId: TrailblazeDeviceId): Dadb {
    val serial = deviceId.instanceId
    while (true) {
      val cached = dadbClients[serial]
      if (cached != null) return cached
      val created = createDadb(serial)
      val existing = dadbClients.putIfAbsent(serial, created)
      if (existing == null) return created
      // Lost a race; close the duplicate and use the existing one.
      runCatching { created.close() }
    }
  }

  private fun createDadb(serial: String): Dadb {
    val (host, port) = resolveAdbServerEndpoint()
    return AdbServer.createDadb(
      adbServerHost = host,
      adbServerPort = port,
      deviceQuery = "host:transport:$serial",
    )
  }

  /**
   * Resolves the local adb server endpoint, honoring the same env vars that the `adb` binary does:
   * `ADB_SERVER_SOCKET=tcp:host:port` (host:port composite) takes precedence, then
   * `ANDROID_ADB_SERVER_PORT` (port only, host stays `localhost`). Falls back to `localhost:5037`.
   *
   * Logs a warning when an env var is set but unparseable so operators get a signal that their
   * configuration was rejected (vs silently falling back and seeing "device not found"
   * downstream).
   */
  internal fun resolveAdbServerEndpoint(): Pair<String, Int> = resolveAdbServerEndpoint(System::getenv)

  /**
   * Test seam: same as [resolveAdbServerEndpoint] but sources env vars via [getenv]. Pure logic —
   * no system access — so unit tests can drive every malformed-input branch without
   * `withEnvironmentVariable` shenanigans.
   */
  internal fun resolveAdbServerEndpoint(getenv: (String) -> String?): Pair<String, Int> {
    val socket = getenv("ADB_SERVER_SOCKET")?.takeIf { it.isNotBlank() }
    if (socket != null) {
      val parsed = parseTcpEndpoint(socket)
      if (parsed != null) return parsed
      Console.log("[AndroidHostAdbUtils] ADB_SERVER_SOCKET='$socket' is malformed (expected 'tcp:<host>:<port>'); falling back to defaults")
    }
    val portRaw = getenv("ANDROID_ADB_SERVER_PORT")?.takeIf { it.isNotBlank() }
    val port = portRaw?.toIntOrNull()
    if (portRaw != null && port == null) {
      Console.log("[AndroidHostAdbUtils] ANDROID_ADB_SERVER_PORT='$portRaw' is not a valid port; falling back to 5037")
    }
    return "localhost" to (port ?: 5037)
  }

  /**
   * Parses an `ADB_SERVER_SOCKET`-style endpoint of the form `tcp:<host>:<port>` into a
   * `(host, port)` pair, or returns null if the input doesn't match that grammar (empty host,
   * non-numeric port, missing colon, etc.).
   */
  internal fun parseTcpEndpoint(value: String): Pair<String, Int>? {
    if (!value.startsWith("tcp:")) return null
    val rest = value.removePrefix("tcp:")
    val lastColon = rest.lastIndexOf(':')
    if (lastColon <= 0 || lastColon == rest.lastIndex) return null
    val host = rest.substring(0, lastColon).takeIf { it.isNotBlank() } ?: return null
    val port = rest.substring(lastColon + 1).toIntOrNull() ?: return null
    return host to port
  }

  /**
   * Runs [block] against the device's [Dadb] client, evicting and retrying once on a transport-level
   * [IOException] (the typical "device reconnected with a new transport id" symptom). Other
   * exceptions — failed commands, install errors, etc. — propagate without retry so non-idempotent
   * operations (install/uninstall) do not silently execute twice.
   *
   * The eviction-and-retry path is logged: a device that briefly disconnects and reconnects with a
   * new transport id is the most useful diagnostic point in the whole abstraction, so operators
   * debugging "why did this call slow down" should see exactly what happened.
   *
   * This handles a transport that *throws* (IOException). A transport that *hangs* — a wedged device,
   * or a stale transport that never errors — is recovered separately, and only for short, idempotent,
   * time-bounded calls, in [execAdbShellCommandWithTimeout]'s reconnect-on-timeout. The bulk/streaming
   * paths (pull/push/streamingShell) deliberately don't reconnect-on-timeout: they have no bounded
   * deadline that could distinguish "slow" from "hung".
   */
  private fun <T> withDadb(deviceId: TrailblazeDeviceId, block: (Dadb) -> T): T {
    val serial = deviceId.instanceId
    return runOnResolvedClient(
      resolve = { dadbFor(deviceId) },
      onClientResolved = {},
      onTransportFailure = { e, usedClient ->
        Console.log(
          "[AndroidHostAdbUtils] withDadb evicting cached client for $serial after IOException " +
            "(${e.javaClass.simpleName}: ${e.message}); retrying once",
        )
        evictExactClient(dadbClients, serial, usedClient)
        block(dadbFor(deviceId))
      },
      block = block,
    )
  }

  /**
   * [withDadb] without the re-run.
   *
   * The retry above is safe for a read, and wrong for anything with a side effect: dadb cannot say
   * whether an `IOException` arrived before the command reached the device or after it already ran,
   * so re-running the block can execute an `am broadcast`, a `pm clear` or whatever a trail handed
   * `android_adbShell` a second time. Paths documented as single-attempt use this instead.
   *
   * The eviction is kept. Recovery still happens, one call later: the failure is reported to this
   * caller, and the NEXT call reconnects rather than inheriting the broken client.
   */
  private fun <T> withDadbNoRetry(
    deviceId: TrailblazeDeviceId,
    onClientResolved: (Dadb) -> Unit = {},
    block: (Dadb) -> T,
  ): T {
    val serial = deviceId.instanceId
    return runOnResolvedClientOnce(
      resolve = { dadbFor(deviceId) },
      onClientResolved = onClientResolved,
      onTransportFailure = { e, usedClient ->
        Console.log(
          "[AndroidHostAdbUtils] withDadbNoRetry evicting cached client for $serial after " +
            "IOException (${e.javaClass.simpleName}: ${e.message}); NOT retrying here",
        )
        evictExactClient(dadbClients, serial, usedClient)
      },
      block = block,
    )
  }

  /**
   * Builds the argv list for `adb shell am broadcast ...`. Every user-supplied arg is
   * wrapped in single quotes via [shellEscape] because `adb shell` joins argv with
   * spaces and hands the result to the device's `sh`, which would otherwise split
   * on whitespace or interpret metacharacters (`;`, `$`, backtick, etc.) inside
   * action, component, extra keys, or extra values.
   */
  fun intentToAdbBroadcastCommandArgs(
    action: String,
    component: String,
    extras: Map<String, Any>,
  ): List<String> {
    val args = buildList<String> {
      add("am")
      add("broadcast")
      if (action.isNotEmpty()) {
        add("-a")
        add(action.shellEscape())
      }
      if (component.isNotEmpty()) {
        add("-n")
        add(component.shellEscape())
      }
      extras.forEach { (key, value) ->
        val flag = when (value) {
          is Boolean -> "--ez"
          is Int -> "--ei"
          is Long -> "--el"
          is Float -> "--ef"
          else -> "--es"
        }
        add(flag)
        add(key.shellEscape())
        add(value.toString().shellEscape())
      }
    }
    return args
  }

  fun uninstallApp(
    deviceId: TrailblazeDeviceId,
    appPackageId: String,
  ) {
    try {
      withDadb(deviceId) { it.uninstall(appPackageId) }
    } catch (e: Exception) {
      // Match the previous behavior: errors during uninstall are non-fatal and surface via logs.
      Console.log("uninstall($appPackageId) failed: ${e.message}")
    }
  }

  /**
   * Builds an `adb` ProcessBuilder. Retained as a private fallback for the few host operations
   * that dadb does not cover (currently `adb reverse` and `adb forward tcp:X localabstract:Y`).
   * External callers should use [execAdbShellCommand], [pullFile], [pushFile], [adbPortForward],
   * [adbPortForwardLocalAbstract], [adbPortReverse], etc.
   */
  private fun createAdbCommandProcessBuilder(
    args: List<String>,
    deviceId: TrailblazeDeviceId?,
  ): ProcessBuilder {
    val args = mutableListOf<String>().apply {
      add(AdbPathResolver.ADB_COMMAND)
      if (deviceId != null) {
        add("-s")
        add(deviceId.instanceId)
      }
      this.addAll(args)
    }
    return TrailblazeProcessBuilderUtils.createProcessBuilder(args)
  }

  /**
   * Runs an `adb` ProcessBuilder with a timeout, force-killing it if it does not exit in time.
   * Matches the legacy `process.waitFor(timeout, SECONDS) -> destroyForcibly` pattern that protected
   * device discovery and port-forward cleanup from a wedged adb/adbd. Returns true only if the
   * process exited within the deadline with a zero exit code.
   */
  private fun runProcessBuilderWithTimeout(
    pb: ProcessBuilder,
    timeoutMs: Long,
  ): Boolean = try {
    val process = pb.start()
    val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    if (!finished) {
      process.destroyForcibly()
      process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
    }
    finished && process.exitValue() == 0
  } catch (_: Exception) {
    false
  }

  suspend fun isAppInstalled(appId: String, deviceId: TrailblazeDeviceId): Boolean =
    listInstalledPackages(deviceId).any { it == appId }

  /**
   * Installs a host→device `adb forward tcp:$localPort tcp:$remotePort` via the real adb binary
   * (NOT dadb's [Dadb.tcpForward], which drops the connection immediately on some devices —
   * e.g. the Galaxy Tab S8 — flooding "tcp:<port>: closed"). Returns an [AutoCloseable] that tears
   * the forward down via `adb forward --remove tcp:$localPort`. Mirrors [adbPortForwardLocalAbstract]
   * and [adbPortReverse], which already shell out to the binary.
   *
   * @throws IOException if the `adb forward` command times out or exits non-zero.
   */
  private fun installAdbForwardViaBinary(
    deviceId: TrailblazeDeviceId,
    localPort: Int,
    remotePort: Int,
  ): AutoCloseable {
    val installed = runProcessBuilderWithTimeout(
      createAdbCommandProcessBuilder(
        deviceId = deviceId,
        args = listOf("forward", "tcp:$localPort", "tcp:$remotePort"),
      ),
      timeoutMs = DEFAULT_SHORT_CALL_TIMEOUT_MS,
    )
    if (!installed) {
      throw IOException("adb forward tcp:$localPort tcp:$remotePort failed or timed out")
    }
    return AutoCloseable {
      runCatching {
        runProcessBuilderWithTimeout(
          createAdbCommandProcessBuilder(
            deviceId = deviceId,
            args = listOf("forward", "--remove", "tcp:$localPort"),
          ),
          timeoutMs = DEFAULT_SHORT_CALL_TIMEOUT_MS,
        )
      }
    }
  }

  /**
   * Sets up a host→device TCP forward, equivalent to `adb forward tcp:local tcp:remote`. Idempotent
   * per (deviceId, localPort): a no-op if the forward is already active. Forwards are owned by the
   * JVM process and torn down via [removePortForward] or when the JVM exits.
   */
  fun adbPortForward(
    deviceId: TrailblazeDeviceId,
    localPort: Int,
    remotePort: Int = localPort,
  ) {
    val key = deviceId.instanceId to localPort
    if (activeForwards.containsKey(key)) {
      Console.log("Port forward tcp:$localPort -> tcp:$remotePort already exists")
      return
    }
    try {
      Console.log("Setting up port forward tcp:$localPort -> tcp:$remotePort")
      // Real `adb forward` binary instead of dadb.tcpForward (which fails on some devices).
      val forwarder = installAdbForwardViaBinary(deviceId, localPort, remotePort)
      // If we lost a race, the winner governs the same underlying `adb forward` (idempotent by
      // localPort), so just drop our duplicate AutoCloseable WITHOUT running `--remove`.
      activeForwards.putIfAbsent(key, forwarder)
    } catch (e: Exception) {
      // Fall back to the class name when message is blank, so the error isn't just "null".
      val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
      throw RuntimeException(
        "Failed to start port forwarding tcp:$localPort -> tcp:$remotePort on " +
          "${deviceId.instanceId}: $detail",
        e,
      )
    }
  }

  /**
   * Recovery primitive for transient ADB-forward drops on Android. Distinct from [adbPortForward],
   * which is the first-time-setup helper and short-circuits when this JVM thinks the forward
   * is already active (which can be stale if the underlying socket died after a host adb-server
   * restart, version-skew kill-server, or emulator hiccup).
   *
   * Diagnoses whether `tcp:LOCAL`→`tcp:REMOTE` is currently in the host adb server's forward
   * table — queried directly via the `host:list-forward` host-service (the authoritative source,
   * not the in-process [activeForwards] map which can be stale). Then unconditionally tears down
   * any tracked forwarder this JVM held for that (deviceId, localPort) pair and re-establishes a
   * fresh one via the real adb binary (see [installAdbForwardViaBinary]). Returns the pre-recovery
   * presence state for callers that want to structure failure messages or telemetry: `true` =
   * entry was PRESENT in the adb server's table, `false` = ABSENT, `null` = could not determine
   * (host-services query failed).
   */
  fun diagnoseAndReAdbPortForward(
    deviceId: TrailblazeDeviceId,
    localPort: Int,
    remotePort: Int = localPort,
  ): Boolean? {
    val wasActive = try {
      val payload = queryAdbHostService("host:list-forward")
      payload.lines().any { line ->
        // Lines have the shape `<serial> tcp:<localPort> tcp:<remotePort>`. We require both
        // ports to appear so a different forward sharing one of the port numbers can't false-
        // positive this match.
        line.contains("tcp:$localPort") && line.contains("tcp:$remotePort")
      }
    } catch (e: Exception) {
      Console.log("[AndroidHostAdbUtils] host:list-forward query failed: ${e.message}")
      null
    }
    val key = deviceId.instanceId to localPort
    activeForwards.remove(key)?.let { runCatching { it.close() } }
    try {
      val forwarder = installAdbForwardViaBinary(deviceId, localPort, remotePort)
      activeForwards.putIfAbsent(key, forwarder)
    } catch (e: Exception) {
      Console.log("[AndroidHostAdbUtils] adb forward (recovery) failed: ${e.message}")
    }
    return wasActive
  }

  /**
   * Sets up a host→device TCP forward whose target is a Unix abstract socket on the device, e.g.
   * `adb forward tcp:$localPort localabstract:$socketName`. dadb's [Dadb.tcpForward] only supports
   * TCP-to-TCP, so this routes through the `adb` binary. The forward is removable via
   * [removePortForward] which falls back to the binary for forwards it didn't track.
   *
   * @throws IOException if the `adb forward` command times out or exits non-zero (e.g. the adb
   *   binary is missing or the local port cannot be bound). A silent failure here would surface
   *   later as an unrelated connection-refused error on the local port.
   */
  fun adbPortForwardLocalAbstract(
    deviceId: TrailblazeDeviceId,
    localPort: Int,
    socketName: String,
  ) {
    val installed = runProcessBuilderWithTimeout(
      createAdbCommandProcessBuilder(
        deviceId = deviceId,
        args = listOf("forward", "tcp:$localPort", "localabstract:$socketName"),
      ),
      timeoutMs = DEFAULT_SHORT_CALL_TIMEOUT_MS,
    )
    if (!installed) {
      throw IOException("adb forward tcp:$localPort localabstract:$socketName failed or timed out")
    }
  }

  fun removePortForward(deviceId: TrailblazeDeviceId, localPort: Int) {
    val key = deviceId.instanceId to localPort
    val tracked = activeForwards.remove(key)
    if (tracked != null) {
      runCatching { tracked.close() }
    } else {
      // Forward wasn't set up via this object (e.g. configured by legacy code that called
      // createAdbCommandProcessBuilder directly with a `localabstract:` target). Fall back to the
      // binary so removal still works — bounded by a timeout so a wedged adb/adbd cannot block
      // device discovery / connection cleanup indefinitely.
      runCatching {
        runProcessBuilderWithTimeout(
          createAdbCommandProcessBuilder(
            deviceId = deviceId,
            args = listOf("forward", "--remove", "tcp:$localPort"),
          ),
          timeoutMs = DEFAULT_SHORT_CALL_TIMEOUT_MS,
        )
      }
    }
  }

  /**
   * Sets up a device→host reverse TCP forward, equivalent to `adb reverse tcp:remote tcp:local`.
   *
   * dadb does not implement reverse forwarding, so this still goes through the `adb` binary
   * via [createAdbCommandProcessBuilder]. The forward persists in the adb server even after the
   * calling JVM exits (different lifetime semantics from [adbPortForward]).
   */
  fun adbPortReverse(
    deviceId: TrailblazeDeviceId,
    localPort: Int,
    remotePort: Int = localPort,
  ) = try {
    if (isPortReverseAlreadyActive(deviceId, localPort, remotePort)) {
      Console.log("Port reverse tcp:$localPort -> tcp:$remotePort already exists")
    } else {
      Console.log("Setting up port reverse tcp:$localPort -> tcp:$remotePort")
      runProcessBuilderWithTimeout(
        createAdbCommandProcessBuilder(
          deviceId = deviceId,
          args = listOf("reverse", "tcp:$localPort", "tcp:$remotePort"),
        ),
        timeoutMs = DEFAULT_SHORT_CALL_TIMEOUT_MS,
      )
    }
  } catch (e: Exception) {
    throw RuntimeException("Failed to start reverse port forwarding: ${e.message}", e)
  }

  fun removePortReverse(deviceId: TrailblazeDeviceId, localPort: Int) {
    runCatching {
      runProcessBuilderWithTimeout(
        createAdbCommandProcessBuilder(
          deviceId = deviceId,
          args = listOf("reverse", "--remove", "tcp:$localPort"),
        ),
        timeoutMs = DEFAULT_SHORT_CALL_TIMEOUT_MS,
      )
    }
  }

  private fun isPortReverseAlreadyActive(
    deviceId: TrailblazeDeviceId,
    localPort: Int,
    remotePort: Int,
  ): Boolean = try {
    val result = createAdbCommandProcessBuilder(
      deviceId = deviceId,
      args = listOf("reverse", "--list"),
    ).runProcess({})
    result.outputLines.any { line ->
      line.contains("tcp:$localPort") && line.contains("tcp:$remotePort")
    }
  } catch (e: Exception) {
    false
  }

  /**
   * Runs `adb shell <args>` on the device and returns combined stdout + stderr. Args are joined
   * with spaces and handed to the device's `sh`, matching the legacy `adb shell` argv-joining
   * behavior (which used `redirectErrorStream(true)` so callers saw merged output via
   * `fullOutput`); callers that need shell metacharacters in arg values must [shellEscape] them.
   *
   * Bounded at [AndroidShellBounds.HOST_SHELL_TIMEOUT_MS]. The bound lives HERE, on the primitive,
   * rather than on each caller: this one function backs `pm clear`, `force-stop`, `isAppRunning`,
   * the package listings and the device clock read, and bounding them one at a time leaves the next
   * one added unbounded. `pm clear` is the 154s command [AndroidShellBounds] cites as the slowest
   * on record, so it is exactly the call most likely to wedge.
   *
   * A timeout throws. Returning `""` would turn a wedged device into "no packages installed" or
   * "the app is not running" — a wrong answer delivered as a right one, and worse than the hang.
   *
   * A transport `IOException` is still retried once, as it was before the bound, but on THIS
   * thread and only within what is left of the bound ([execWithOneTransportRetry]). Never inside
   * the worker: a timed-out worker is abandoned, not stopped, and a retry there would re-run a
   * `pm clear` or force-stop whenever the adb server finally drops its socket — after this call
   * has thrown and the device has moved on to the next trail.
   */
  fun execAdbShellCommand(deviceId: TrailblazeDeviceId, args: List<String>): String {
    val command = args.joinToString(" ")
    val redactedCommand = redactSecretsForLog(command)
    Console.log("adb shell $redactedCommand")
    val attempt = execWithOneTransportRetry(AndroidShellBounds.HOST_SHELL_TIMEOUT_MS) { budgetMs ->
      runSingleAdbShellAttempt(deviceId, command, redactedCommand, budgetMs)
    }
    return hostShellOutputOrThrow(
      attempt = attempt,
      deviceLabel = deviceId.instanceId,
      command = command,
      timeoutMs = AndroidShellBounds.HOST_SHELL_TIMEOUT_MS,
    )
  }

  /**
   * Runs [attempt] with the whole [timeoutMs] budget and, if it failed on a transport
   * `IOException`, once more with whatever budget is left. A timeout is not retried: the budget is
   * already spent. Neither is a non-transport failure, which the device itself reported.
   *
   * The retry runs on the calling thread, after the first attempt's worker has finished (it
   * threw), so it cannot overlap that worker or outlive the caller. Pure control flow over an
   * injectable clock, so it is testable without threads or a device.
   */
  internal fun execWithOneTransportRetry(
    timeoutMs: Long,
    nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    attempt: (budgetMs: Long) -> ShellAttemptResult,
  ): ShellAttemptResult {
    val deadline = nowMs() + timeoutMs
    val first = attempt(timeoutMs)
    if (first.outcome != ShellAttemptOutcome.FAILED || !isTransportFailure(first.error)) return first
    val remainingMs = deadline - nowMs()
    if (remainingMs <= 0) return first
    Console.log(
      "[AndroidHostAdbUtils] adb shell transport failed (${first.error?.message}) — retrying once " +
        "with ${remainingMs}ms of the bound left",
    )
    return attempt(remainingMs)
  }

  /** dadb's combined-output convention. */
  private fun shellOutputOf(dadb: Dadb, command: String): String {
    val response = dadb.shell(command)
    return if (response.errorOutput.isEmpty()) response.output else response.allOutput
  }

  /**
   * Outcome of a single bounded shell attempt. [TIMED_OUT] is deliberately distinct from [FAILED]:
   * only a timeout — the symptom of a stale/wedged transport that *hangs* instead of throwing — is
   * safe and worthwhile to retry against a freshly-reconnected client. A [FAILED] attempt threw,
   * which for a possibly-non-idempotent command we must not silently re-run.
   */
  internal enum class ShellAttemptOutcome { SUCCESS, FAILED, TIMED_OUT }

  internal data class ShellAttemptResult(
    val value: String?,
    val outcome: ShellAttemptOutcome,
    /**
     * What the worker threw, on a [ShellAttemptOutcome.FAILED] it caught. Carried rather than only
     * logged so a caller that rethrows can attach the real cause; a bare "adb shell failed" with no
     * stack is a much worse thing to find in a session log than the IOException underneath it.
     * Null on the interrupted path, which has no exception of its own to report.
     */
    val error: Throwable? = null,
  )

  /**
   * Like [execAdbShellCommand] but bounded by [timeoutMs]. On timeout the cached dadb client is
   * evicted and the call is retried ONCE against a freshly-reconnected client before giving up.
   *
   * The retry is the dadb analog of what the `adb` binary does transparently: reconnect a stale
   * transport. When an emulator dies and a new one boots on the same serial (new transport id), or
   * adbd briefly wedges, the cached dadb transport *hangs* on the next read rather than throwing an
   * [IOException] — so [withDadb]'s IOException-only retry never fires and the call would otherwise
   * just time out and return null. Evicting on timeout and reconnecting once recovers that case
   * (the "device disappeared from discovery / a single tap hung for minutes" failure mode).
   *
   * If the retry also times out (or the command genuinely failed), `null` is returned so the caller
   * can decide whether the missing data is fatal or recoverable. Use only for short-lived,
   * idempotent shell calls that previously had explicit timeouts (`getprop` during device
   * discovery, `logcat -d` for the MCP logcat tool, port-forward removal) — a retry must not be
   * able to double-execute a side effect.
   *
   * [quiet] skips the per-call "adb shell …" debug line. For a caller that polls on a timer (the
   * memory sampler runs two of these every couple of seconds) the line is pure noise; failures and
   * timeouts are still logged.
   *
   * [evictClientOnTimeout] is the recovery above, and every trail-driving caller wants it. Pass
   * `false` from a BACKGROUND poller: the cached client is shared per device, so evicting it tears
   * down the transport the running trail is using mid-step — a heavy price for a sample nobody is
   * waiting for. With it off a timeout is terminal (there is no fresh transport to retry against)
   * and the caller simply gets `null` for that reading.
   */
  fun execAdbShellCommandWithTimeout(
    deviceId: TrailblazeDeviceId,
    args: List<String>,
    timeoutMs: Long = DEFAULT_SHORT_CALL_TIMEOUT_MS,
    quiet: Boolean = false,
    evictClientOnTimeout: Boolean = true,
  ): String? {
    val command = args.joinToString(" ")
    val redactedCommand = redactSecretsForLog(command)
    if (!quiet) Console.log("adb shell ($timeoutMs ms timeout) $redactedCommand")
    if (!evictClientOnTimeout) {
      return runSingleAdbShellAttempt(deviceId, command, redactedCommand, timeoutMs, evictClientOnTimeout = false).value
    }
    return execWithReconnectOnTimeout {
      runSingleAdbShellAttempt(deviceId, command, redactedCommand, timeoutMs)
    }
  }

  /**
   * Like [execAdbShellCommandWithTimeout] but exactly one bounded attempt, never retried. A
   * timeout there only evicts the *cached client*; the worker thread it started can outlive that
   * eviction (`interrupt()` does not unblock a thread parked in a native socket read), so the
   * retry's fresh attempt can end up running alongside the first one instead of after it. That is
   * merely wasted work for a short idempotent read, but not for a command that is already bounded
   * at minutes and whose caller is relying on that bound — `pm compile`, which callers already wait
   * up to [xyz.block.trailblaze.device.EnsureAppCompiled.COMPILE_TIMEOUT_MS] for once; a silent
   * retry would double that wait rather than reconnecting a stale transport.
   */
  fun execAdbShellCommandBoundedOnce(
    deviceId: TrailblazeDeviceId,
    args: List<String>,
    timeoutMs: Long,
  ): String? = execAdbShellCommandBoundedOnceDetailed(deviceId, args, timeoutMs).value

  /**
   * [execAdbShellCommandBoundedOnce] with the attempt's outcome kept instead of flattened to
   * `null`.
   *
   * Three unrelated conditions all produce a null value — the read timed out, the call threw, and
   * the block legitimately returned nothing — and a caller that turns "no value" into one error
   * message necessarily reports two of them wrongly. A disconnected device described as a hang
   * sends whoever reads the log hunting for a wedge that never happened.
   */
  internal fun execAdbShellCommandBoundedOnceDetailed(
    deviceId: TrailblazeDeviceId,
    args: List<String>,
    timeoutMs: Long,
  ): ShellAttemptResult {
    val command = args.joinToString(" ")
    val redactedCommand = redactSecretsForLog(command)
    Console.log("adb shell ($timeoutMs ms timeout, no retry) $redactedCommand")
    return runSingleAdbShellAttempt(deviceId, command, redactedCommand, timeoutMs)
  }

  /**
   * Retry policy for [execAdbShellCommandWithTimeout], extracted as pure control flow so it is
   * unit-testable without real threads or a live device. Runs [attempt] up to [maxAttempts] times,
   * returning early on any non-[ShellAttemptOutcome.TIMED_OUT] outcome (a success, or a thrown
   * command error — both terminal). Only a timeout retries, because only a wedged/stale transport
   * benefits from reconnecting; the per-attempt eviction that makes the next attempt reconnect with
   * a fresh transport happens inside [attempt]. Returns the last attempt's value.
   */
  internal fun execWithReconnectOnTimeout(
    maxAttempts: Int = 2,
    attempt: () -> ShellAttemptResult,
  ): String? {
    var last: ShellAttemptResult? = null
    repeat(maxAttempts) { i ->
      val result = attempt()
      last = result
      if (result.outcome != ShellAttemptOutcome.TIMED_OUT) return result.value
      if (i < maxAttempts - 1) {
        Console.log(
          "[AndroidHostAdbUtils] adb shell timed out — reconnecting and retrying " +
            "(attempt ${i + 2} of $maxAttempts)",
        )
      }
    }
    return last?.value
  }

  /**
   * Runs one bounded `adb shell` attempt on a daemon worker thread. On a wall-clock timeout the
   * cached dadb client is evicted (so the *next* attempt reconnects with a fresh transport) and the
   * worker is interrupted; the outcome is reported so [execWithReconnectOnTimeout] can decide
   * whether to retry.
   *
   * [evictClientOnTimeout] `false` leaves the shared client alone — for a background poller whose
   * timeout says nothing about the transport a running trail is using. The worker is still
   * abandoned and the outcome still reported.
   *
   * [shellCall] is the work the worker does. It defaults to [withDadbNoRetry], and no production
   * caller overrides it: a timed-out worker is abandoned rather than stopped, so any retry inside it
   * could re-run a side effect long after the caller gave up. A caller that wants a retry does it
   * on its own thread ([execWithOneTransportRetry]). It is also the seam a test uses to pin the
   * three outcomes — returns, throws, never comes back — without a device.
   *
   * [shellCall] reports the client it resolved through the callback it is handed, so a timeout or
   * interruption below can evict that exact instance ([evictExactClient]) instead of whatever
   * happens to be cached when cleanup runs — a concurrent caller on the same device can have
   * already reconnected in the meantime, and evicting "whatever is cached" would tear down that
   * unrelated, healthy client instead of the one this worker actually used.
   */
  internal fun runSingleAdbShellAttempt(
    deviceId: TrailblazeDeviceId,
    command: String,
    redactedCommand: String,
    timeoutMs: Long,
    evictClientOnTimeout: Boolean = true,
    shellCall: (onClientResolved: (Dadb) -> Unit) -> String? = { onClientResolved ->
      withDadbNoRetry(deviceId, onClientResolved) { dadb -> shellOutputOf(dadb, command) }
    },
  ): ShellAttemptResult {
    val resultRef = AtomicReference<String?>()
    val errorRef = AtomicReference<Throwable?>()
    val usedClientRef = AtomicReference<Dadb?>()
    val worker = thread(name = "dadb-shell-timed", isDaemon = true) {
      try {
        resultRef.set(shellCall { usedClientRef.set(it) })
      } catch (t: Throwable) {
        errorRef.set(t)
      }
    }
    try {
      worker.join(timeoutMs)
    } catch (e: InterruptedException) {
      // The calling thread was interrupted while waiting (e.g. discovery's name-resolution pool was
      // shut down after its deadline). Restore the interrupt flag, abandon the wedged worker and its
      // possibly-stale client, and report a terminal failure — interruption means "stop", not "retry".
      Thread.currentThread().interrupt()
      evictExactClient(dadbClients, deviceId.instanceId, usedClientRef.get())
      worker.interrupt()
      return ShellAttemptResult(null, ShellAttemptOutcome.FAILED)
    }
    // If the worker finished while we were waking up — even if it raced with the deadline —
    // prefer the result it produced over evicting the client. The thin window between
    // `join(timeoutMs)` returning and `isAlive` being checked is real; checking the result
    // first means we never throw away a successful client just because it cut it close.
    val result = resultRef.get()
    if (result != null) return ShellAttemptResult(result, ShellAttemptOutcome.SUCCESS)
    val error = errorRef.get()
    if (error != null) {
      // Worker completed with an exception (the underlying shell call threw). Distinguish
      // this from a wall-clock timeout in the logs so operators can tell "command failed"
      // from "device wedged" instead of seeing "timed out" for both. Caller still gets `null`
      // — the contract is "data unavailable" — but the diagnostic is now accurate.
      Console.log(
        "[AndroidHostAdbUtils] adb shell failed (${error.javaClass.simpleName}: " +
          "${error.message}) — command: $redactedCommand",
      )
      return ShellAttemptResult(null, ShellAttemptOutcome.FAILED, error)
    }
    if (worker.isAlive) {
      Console.log(
        "[AndroidHostAdbUtils] adb shell timed out after ${timeoutMs}ms — " +
          (if (evictClientOnTimeout) "evicting dadb client" else "leaving the shared dadb client alone") +
          ": $redactedCommand",
      )
      if (evictClientOnTimeout) evictExactClient(dadbClients, deviceId.instanceId, usedClientRef.get())
      // interrupt() may not unblock a worker parked in a dadb socket read (native I/O ignores the
      // interrupt flag); the daemon thread then lingers until that read returns or errors. Bounded in
      // practice — the device either responds or the socket eventually faults — and the thread is a
      // daemon, so it never blocks JVM exit. A hard cap would require a dadb-level socket-read timeout.
      worker.interrupt()
      return ShellAttemptResult(null, ShellAttemptOutcome.TIMED_OUT)
    }
    // Worker finished with neither a result nor an exception (the block legitimately returned null).
    // Terminal "no data" — not a timeout — so we don't retry a call that already completed.
    return ShellAttemptResult(null, ShellAttemptOutcome.SUCCESS)
  }

  /**
   * Pulls a file from the device. Returns true on success.
   */
  fun pullFile(deviceId: TrailblazeDeviceId, remotePath: String, localFile: File): Boolean = try {
    withDadb(deviceId) { it.pull(localFile, remotePath) }
    true
  } catch (e: Exception) {
    Console.log("pull $remotePath -> ${localFile.absolutePath} failed: ${e.message}")
    false
  }

  /**
   * Pushes a file to the device. Returns true on success.
   */
  fun pushFile(deviceId: TrailblazeDeviceId, localFile: File, remotePath: String): Boolean = try {
    withDadb(deviceId) { it.push(localFile, remotePath) }
    true
  } catch (e: Exception) {
    Console.log("push ${localFile.absolutePath} -> $remotePath failed: ${e.message}")
    false
  }

  /**
   * Streams `adb shell <command>` and emits stdout line-by-line via [onLine]. Returns an
   * [AutoCloseable] that, when closed, terminates the underlying ADB stream and the reader thread.
   * [onExit] is invoked once with the device-side exit code when the command exits naturally; any
   * trailing partial line (output without a final `\n`) is flushed to [onLine] before [onExit].
   *
   * Used for long-lived shell commands like `am instrument`, `screenrecord`, and `logcat -f`
   * where the host needs to consume output incrementally and cancel the command on demand. The
   * line buffer persists across packets — ADB packet boundaries are arbitrary, so a single line
   * can be split across multiple packets and a previous bug that re-allocated the buffer per
   * packet caused such lines to be silently truncated/dropped (instrumentation startup detection
   * via `INSTRUMENTATION_STATUS_CODE` was the canonical victim).
   *
   * Lines are decoded as UTF-8 at byte-level newline boundaries, so multi-byte codepoints split
   * across packet boundaries are reassembled correctly (a packet boundary in the middle of a
   * codepoint stays in the byte buffer until the next packet completes the line).
   */
  fun streamingShell(
    deviceId: TrailblazeDeviceId,
    command: String,
    onLine: (String) -> Unit,
    onExit: ((exitCode: Int) -> Unit)? = null,
  ): AutoCloseable {
    Console.log("adb shell (streaming) ${redactSecretsForLog(command)}")
    val stream = withDadb(deviceId) { it.openShell(command) }
    val decoder = StreamingLineDecoder(onLine)
    val readerThread = thread(name = "dadb-shell-stream", isDaemon = true) {
      try {
        while (true) {
          when (val packet = stream.read()) {
            is AdbShellPacket.StdOut -> decoder.feed(packet.payload)
            is AdbShellPacket.StdError -> decoder.feed(packet.payload)
            is AdbShellPacket.Exit -> {
              decoder.flushTrailingLine()
              onExit?.invoke(packet.payload[0].toInt())
              return@thread
            }
          }
        }
      } catch (e: Exception) {
        // Most exits here are benign — the caller closed the stream or the device disconnected.
        // But if a parse error or unexpected packet sneaks in, log so the downstream symptom
        // (e.g. `connectToInstrumentation` timing out without ever seeing
        // `INSTRUMENTATION_STATUS_CODE`) has a breadcrumb back to the actual cause.
        Console.log("[AndroidHostAdbUtils] dadb-shell-stream reader exited: ${e.javaClass.simpleName}: ${e.message}")
      }
    }
    return AutoCloseable {
      runCatching { stream.close() }
      readerThread.interrupt()
    }
  }

  /**
   * Buffer-and-emit decoder for ADB shell output: takes raw bytes (which may arrive in arbitrary
   * packet boundaries), reassembles them into UTF-8 lines, and emits each completed line via
   * [onLine]. Splitting on the literal `0x0A` byte means a multi-byte UTF-8 codepoint can't be
   * sliced mid-codepoint (continuation bytes have the high bit set, so they never match `\n` or
   * `\r`); a `\r\n` straddling a packet boundary still strips correctly because the previous
   * packet's `\r` stays in the buffer until the next packet's `\n` arrives.
   *
   * Extracted from [streamingShell] so the pure-logic decoder can be exercised by unit tests
   * without spinning up a real `Dadb` — this is the same code path that broke production CI as a
   * P1 (the previous implementation re-allocated the buffer per packet, dropping any line that
   * spanned a packet boundary).
   */
  internal class StreamingLineDecoder(private val onLine: (String) -> Unit) {
    private val byteBuffer = ByteArrayOutputStream()

    /** Append raw bytes from a single packet and emit any newly-completed lines. */
    fun feed(bytes: ByteArray) {
      byteBuffer.write(bytes)
      emitCompleteLines()
    }

    /**
     * Flush any trailing bytes in the buffer (output without a final `\n`) as a final line. Call
     * exactly once at end-of-stream so the caller still sees the partial line.
     */
    fun flushTrailingLine() {
      if (byteBuffer.size() > 0) {
        onLine(String(byteBuffer.toByteArray(), Charsets.UTF_8).trimEnd('\r'))
        byteBuffer.reset()
      }
    }

    private fun emitCompleteLines() {
      val bytes = byteBuffer.toByteArray()
      var lineStart = 0
      var i = 0
      while (i < bytes.size) {
        if (bytes[i] == NEWLINE_BYTE) {
          val lineEnd = if (i > lineStart && bytes[i - 1] == CARRIAGE_RETURN_BYTE) i - 1 else i
          onLine(String(bytes, lineStart, lineEnd - lineStart, Charsets.UTF_8))
          lineStart = i + 1
        }
        i++
      }
      if (lineStart > 0) {
        byteBuffer.reset()
        if (lineStart < bytes.size) byteBuffer.write(bytes, lineStart, bytes.size - lineStart)
      }
    }
  }

  /**
   * Streams `adb shell <command>` and writes raw stdout/stderr bytes to [outputFile]. Returns an
   * [AutoCloseable] that closes the underlying stream and waits (briefly) for the reader thread to
   * flush and close the file before returning, so callers observe a fully-flushed file
   * immediately after `close()`. Equivalent to `adb shell <command> > outputFile` but routed
   * through the adb wire protocol.
   */
  fun streamingShellToFile(
    deviceId: TrailblazeDeviceId,
    command: String,
    outputFile: File,
  ): AutoCloseable {
    Console.log("adb shell (streaming -> ${outputFile.name}) ${redactSecretsForLog(command)}")
    val stream = withDadb(deviceId) { it.openShell(command) }
    // Open the file *after* the stream and close the stream if we can't open the file —
    // otherwise an open shell stream leaks any time the file system rejects us
    // (permission denied, parent dir gone, full disk).
    val out: OutputStream = try {
      FileOutputStream(outputFile)
    } catch (e: Exception) {
      runCatching { stream.close() }
      throw e
    }
    val readerThread = thread(name = "dadb-shell-stream", isDaemon = true) {
      try {
        while (true) {
          when (val packet = stream.read()) {
            is AdbShellPacket.StdOut -> out.write(packet.payload)
            is AdbShellPacket.StdError -> out.write(packet.payload)
            is AdbShellPacket.Exit -> return@thread
          }
        }
      } catch (e: Exception) {
        // Most exits here are benign — the caller closed the stream or the device disconnected.
        // But if a parse error or unexpected packet sneaks in, log so debugging the symptom
        // (empty/short capture file) doesn't dead-end at "the reader thread vanished silently."
        Console.log(
          "[AndroidHostAdbUtils] dadb-shell-stream reader exited (${outputFile.name}): " +
            "${e.javaClass.simpleName}: ${e.message}",
        )
      } finally {
        runCatching { out.flush() }
        runCatching { out.close() }
      }
    }
    return AutoCloseable {
      runCatching { stream.close() }
      readerThread.interrupt()
      // Give the reader's `finally` block a brief window to flush and close the file before we
      // return so callers observe a fully-flushed artifact (e.g. logcat capture stop()).
      try {
        readerThread.join(1_000)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
      }
    }
  }

  /**
   * Returns the list of `device`-state serials currently known to the local adb server. Talks to
   * the local adb server endpoint (honoring `ADB_SERVER_SOCKET` / `ANDROID_ADB_SERVER_PORT`)
   * directly using the adb host-services wire protocol, equivalent to running `adb devices` but
   * without spawning the binary. Devices in `unauthorized`, `offline`, or other non-`device`
   * states are filtered out.
   */
  fun listConnectedAdbDevices(): List<TrailblazeDeviceId> = try {
    parseHostDevicesPayload(queryAdbHostService("host:devices"))
  } catch (e: Exception) {
    Console.log("[AndroidHostAdbUtils] listConnectedAdbDevices failed: ${e.message}")
    emptyList()
  }

  /**
   * Parses the response payload from the `host:devices` service. Each line is `<serial>\t<state>`.
   * Only `device`-state entries become [TrailblazeDeviceId]s; `offline`, `unauthorized`,
   * `recovery`, etc. are filtered out (they can't accept commands anyway). Blank lines and
   * malformed rows (missing tab, extra columns) are dropped silently — the upstream protocol
   * doesn't define them, and the next refresh will pick up any genuinely-connected device.
   */
  internal fun parseHostDevicesPayload(payload: String): List<TrailblazeDeviceId> =
    payload.lines()
      .filter { it.isNotBlank() }
      .mapNotNull { line ->
        val parts = line.split("\t")
        if (parts.size != 2 || parts[1].trim() != "device") return@mapNotNull null
        TrailblazeDeviceId(
          instanceId = parts[0],
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        )
      }

  /**
   * Sends a single host service request (e.g. `host:devices`) to the local adb server and returns
   * the response payload. Implements the length-prefixed protocol described in the upstream adb
   * SERVICES.TXT.
   */
  private fun queryAdbHostService(service: String): String {
    val (host, port) = resolveAdbServerEndpoint()
    return java.net.Socket(host, port).use { socket ->
      socket.soTimeout = 5_000
      val out = java.io.DataOutputStream(socket.getOutputStream())
      out.writeBytes(String.format("%04x", service.length))
      out.writeBytes(service)
      out.flush()
      val input = java.io.DataInputStream(socket.getInputStream())
      val status = ByteArray(4).also { input.readFully(it) }
      if (String(status) != "OKAY") {
        // Read the failure reason if present so the exception is debuggable.
        val reason = runCatching { readLengthPrefixedString(input) }.getOrDefault("")
        throw IOException("adb host service '$service' failed: $reason")
      }
      readLengthPrefixedString(input)
    }
  }

  private fun readLengthPrefixedString(input: java.io.DataInputStream): String {
    val lengthHex = ByteArray(4).also { input.readFully(it) }
    val length = String(lengthHex).toInt(16)
    val data = ByteArray(length).also { input.readFully(it) }
    return String(data)
  }

  /**
   * Reads the device's current epoch in milliseconds via `date +%s%3N`. Used to align host-side
   * timestamps with on-device logcat output.
   */
  fun getDeviceEpochMs(deviceId: TrailblazeDeviceId): Long {
    val output = execAdbShellCommand(deviceId, listOf("date", "+%s%3N")).trim()
    return output.toLongOrNull() ?: System.currentTimeMillis()
  }

  fun isAppRunning(deviceId: TrailblazeDeviceId, appId: String): Boolean {
    val output = execAdbShellCommand(
      deviceId = deviceId,
      args = listOf("pidof", appId),
    )
    Console.log("pidof $appId: $output")
    return output.trim().isNotEmpty()
  }

  fun launchAppWithAdbMonkey(
    deviceId: TrailblazeDeviceId,
    appId: String,
  ) {
    execAdbShellCommand(
      deviceId = deviceId,
      args = listOf("monkey", "-p", appId, "1"),
    )
  }

  fun clearAppData(deviceId: TrailblazeDeviceId, appId: String): PmClearOutcome {
    // First, before the id reaches the single string execAdbShellCommand hands to the device's
    // `sh` — see `validateClearAppDataAppId`.
    validateClearAppDataAppId(appId)
    // `pm clear` writes 'Failed' to stdout, never stderr, so execAdbShellCommand's stderr check
    // reads a failed clear as ordinary output. See `verifyPmClearSucceeded`.
    return verifyPmClearSucceeded(
      appId = appId,
      output = execAdbShellCommand(
        deviceId = deviceId,
        args = listOf("pm", "clear", appId),
      ),
      // Unfiltered, and parsed by the shared helper — see `installedAccordingToPmList` for why a
      // `pm list packages <filter>` cannot distinguish "absent" from "probe failed".
      // (Non-suspend on purpose: `isAppInstalled` is suspend and `clearAppData` is not.)
      //
      // Bounded: this probe runs precisely when the transport is most suspect, and the unbounded
      // variant would hang the launch instead of reporting. A timeout returns null, which reads as
      // "could not establish" — an empty listing, which the shared helper refuses to call absence.
      pmListPackagesOutput = {
        execAdbShellCommandWithTimeout(deviceId = deviceId, args = PM_LIST_PACKAGES_ARGV)
          ?: error("`pm list packages` timed out on $deviceId")
      },
    )
  }

  fun forceStopApp(
    deviceId: TrailblazeDeviceId,
    appId: String,
  ) {
    if (isAppRunning(deviceId = deviceId, appId)) {
      execAdbShellCommand(
        deviceId = deviceId,
        args = listOf("am", "force-stop", appId),
      )
      val conditionDescription = "App $appId should be force stopped"
      // PollingUtils counts a throwing attempt as "not yet met", which is right for a device that
      // answered "no" and wrong for one that never answered: left alone, a wedged transport would
      // surface here as a plain 30-second poll timeout instead of the ~330-second host shell bound
      // that actually elapsed. So the transport failure is held aside and rethrown if the poll
      // never succeeded — see waitUntilAppInForeground for the same fix on the foreground poll.
      val transport = TransportFailureTracker()
      val stopped = PollingUtils.tryUntilSuccessOrTimeout(
        maxWaitMs = 30_000,
        intervalMs = 200,
        conditionDescription = conditionDescription,
      ) {
        transport.record {
          execAdbShellCommand(
            deviceId = deviceId,
            args = listOf("dumpsys", "package", appId, "|", "grep", "stopped=true"),
          )
        }.contains("stopped=true")
      }
      transport.rethrowIfUnresolved(stopped)
      if (!stopped) {
        error("Timed out (30000ms limit) met [$conditionDescription]")
      }
    } else {
      Console.log("App $appId does not have an active process, no need to force stop")
    }
  }

  fun grantPermission(
    deviceId: TrailblazeDeviceId,
    targetAppPackageName: String,
    permission: String,
  ) {
    execAdbShellCommand(
      deviceId = deviceId,
      args = listOf(
        "pm",
        "grant",
        targetAppPackageName,
        permission,
      ),
    )
  }

  /**
   * `pm list packages` via [execAdbShellCommand], which now throws — including on a bounded
   * timeout — rather than hanging or answering a wedged device with "no packages installed". A
   * caller that wants the old graceful-degrade-to-empty behavior catches at its own call site and
   * says so ([xyz.block.trailblaze.util.HostAndroidDeviceConnectUtils]'s force-stop gate does),
   * rather than this shared primitive silently mis-reporting for everyone — including
   * `mobile_listInstalledApps`, which used to report an empty inventory for a hung device.
   *
   * Run setup's force-stop (`MobileDeviceUtils.ensureAppsAreForceStopped`) deliberately does not
   * catch: it used to skip the force-stop on a failed probe and start the trail anyway, and now
   * fails setup instead. A device that cannot list its packages cannot run the trail, and failing
   * at setup names the cause rather than the step that trips over it later.
   */
  fun listInstalledPackages(deviceId: TrailblazeDeviceId): List<String> =
    parsePmListPackages(execAdbShellCommand(deviceId, PM_LIST_PACKAGES_ARGV))

  /**
   * The device's Android API level via `getprop ro.build.version.sdk`, or null when it can't be read
   * or parsed.
   *
   * Bounded and retryable ([execAdbShellCommandWithTimeout]) because it is a pure read taken on the
   * connect path, where a wedged transport must not stall the launch. Callers get null rather than a
   * guess, so a version-gated decision can fail towards whatever it did before the gate existed.
   */
  fun deviceApiLevel(deviceId: TrailblazeDeviceId): Int? =
    execAdbShellCommandWithTimeout(
      deviceId = deviceId,
      args = listOf("getprop", "ro.build.version.sdk"),
    )?.trim()?.toIntOrNull()

  /**
   * Host-JVM (adb) backing for [xyz.block.trailblaze.device.AndroidDeviceCommandExecutor.listInstalledAppsDetailed].
   *
   * One `dumpsys package packages` call yields almost the whole record — `isSystemApp`, version,
   * build number (`versionCode`), and install path (`codePath`) — which the pure
   * [xyz.block.trailblaze.device.parseInstalledAppsFromDumpsys] extracts. A single round-trip, no
   * per-app fan-out, and faster than the two `pm list packages -s`/`-3` calls it replaced. (We
   * already trust this `dumpsys package` format: [getAppVersionInfo] parses it per-app for version.)
   *
   * Only the human display name is left `null`: dumpsys doesn't carry it (resolving a label needs
   * `aapt dump badging` on a pulled APK, or the on-device `PackageManager` path). Throws on adb
   * failure (including a bounded timeout) rather than answering a wedged device with an empty
   * inventory — see [listInstalledPackages].
   */
  fun listInstalledAppsDetailed(deviceId: TrailblazeDeviceId): List<InstalledApp> {
    val output = execAdbShellCommand(deviceId, listOf("dumpsys", "package", "packages"))
    return parseInstalledAppsFromDumpsys(output)
  }

  /**
   * Gets version information for an installed Android app.
   *
   * Uses `adb shell dumpsys package <packageName>` to retrieve version details.
   * Parses output like: `versionCode=67500009 minSdk=28 targetSdk=34`
   *
   * @param deviceId The device to query
   * @param packageName The package name of the app (e.g., "com.example.app")
   * @return AppVersionInfo with version details, or null if the app is not installed or parsing fails
   */
  fun getAppVersionInfo(deviceId: TrailblazeDeviceId, packageName: String): AppVersionInfo? = try {
    val output = execAdbShellCommand(
      deviceId = deviceId,
      args = listOf("dumpsys", "package", packageName),
    )

    // Find the versionCode line that appears after a codePath (to get the installed version).
    // The output contains multiple versionCode entries; we want the one for the installed app.
    // Match both user-installed apps (/data/app) and system apps (/system/, /product/).
    val lines = output.lines()
    var foundCodePath = false
    var versionCode: String? = null
    var minSdk: Int? = null
    var versionName: String? = null

    for (line in lines) {
      if (line.trimStart().startsWith("codePath=")) {
        foundCodePath = true
      }

      if (foundCodePath && line.contains("versionCode=")) {
        val versionCodeMatch = Regex("versionCode=(\\d+)").find(line)
        val minSdkMatch = Regex("minSdk=(\\d+)").find(line)

        versionCode = versionCodeMatch?.groupValues?.get(1)
        minSdk = minSdkMatch?.groupValues?.get(1)?.toIntOrNull()
      }

      if (foundCodePath && line.trim().startsWith("versionName=")) {
        versionName = line.trim().substringAfter("versionName=")
        if (versionCode != null) break
      }
    }

    if (versionCode != null) {
      AppVersionInfo(
        trailblazeDeviceId = deviceId,
        versionCode = versionCode,
        versionName = versionName,
        minOsVersion = minSdk,
      )
    } else {
      null
    }
  } catch (e: Exception) {
    Console.log("Failed to get version info for $packageName: ${e.message}")
    null
  }

  /**
   * Parses the conflicting package name out of an `adb install` failure caused by a signing-key
   * change. Android rejects a same-package upgrade across signing identities with
   * `INSTALL_FAILED_UPDATE_INCOMPATIBLE` and a message like
   * `Existing package <pkg> signatures do not match newer version; ignoring!`
   * or (on some Android builds):
   * `Package <pkg> signatures do not match previously installed version; ignoring!`.
   *
   * Returns the package to uninstall-then-reinstall, or null when the failure is NOT a signature
   * mismatch (or the message doesn't name a package) — so genuine install failures still surface
   * unchanged instead of triggering a spurious uninstall.
   */
  internal fun mismatchedPackageFromInstallError(errorMessage: String?): String? {
    val message = errorMessage ?: return null
    val isSignatureMismatch = message.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") ||
      message.contains("signatures do not match")
    if (!isSignatureMismatch) return null
    // Match both "Existing package <pkg> signatures ..." (most Android builds) and
    // "Package <pkg> signatures ..." (some older/variant builds, e.g. API 28/29).
    return Regex("""(?:Existing )?[Pp]ackage (\S+) signatures do not match""")
      .find(message)?.groupValues?.getOrNull(1)
  }

  /**
   * Installs an APK file via dadb (`pm install` over the adb wire protocol). Returns true on
   * success, false on failure (with the failure reason logged).
   *
   * If the install is rejected because the package is already installed with a *different signing
   * key* (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), a plain `-r` upgrade can never succeed and would
   * loop forever — the only recovery is to uninstall the stale package and install clean. The
   * failure names the conflicting package, so this self-heals: uninstall that package, then retry
   * the install once. (This happens when a rebuilt runner APK is signed with a new key while an
   * older-key copy is still on the device.)
   */
  fun installApkFile(apkFile: File, trailblazeDeviceId: TrailblazeDeviceId): Boolean = try {
    Console.log("adb install ${apkFile.absolutePath}")
    withDadb(trailblazeDeviceId) { it.install(apkFile, "-r", "-t") }
    true
  } catch (e: Exception) {
    val stalelySignedPackage = mismatchedPackageFromInstallError(e.message)
    if (stalelySignedPackage != null) {
      Console.log(
        "APK install rejected: '$stalelySignedPackage' is already installed with a different " +
          "signing key — uninstalling the stale package and reinstalling clean",
      )
      uninstallApp(trailblazeDeviceId, stalelySignedPackage)
      try {
        withDadb(trailblazeDeviceId) { it.install(apkFile, "-r", "-t") }
        true
      } catch (retry: Exception) {
        Console.log("APK reinstall after uninstalling the stale package still failed: ${retry.message}")
        false
      }
    } else {
      Console.log("APK installation failed: ${e.message}")
      false
    }
  }

  // Single-byte boundaries used by the streaming line-decoder. Newline (`\n`) and the optional
  // preceding carriage return (`\r`) for CRLF sequences. Single bytes can't appear inside a
  // multi-byte UTF-8 codepoint (continuation bytes have the high bit set), so splitting at these
  // bytes never severs a codepoint.
  private const val NEWLINE_BYTE: Byte = 0x0A
  private const val CARRIAGE_RETURN_BYTE: Byte = 0x0D
}
