package xyz.block.trailblaze.capture.video

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.util.AdbPathResolver

/**
 * Builds and runs `adb exec-out` command lines for the capture recorders.
 *
 * `exec-out` is the transport both Android recorders need: it hands the device command's stdout
 * back as raw bytes with no line-ending translation, which a continuous H.264 stream and a PNG
 * screenshot both require. Shared so the server-endpoint flags are resolved in exactly one place —
 * a child `adb` that misses them talks to a different server than the rest of the daemon and finds
 * no devices at all.
 */
internal object AdbExecOut {

  /**
   * Mirror [xyz.block.trailblaze.util.AndroidHostAdbUtils.resolveAdbServerEndpoint] but emit
   * the *binary*-form flags (`-H` / `-P` / `-L`) so a child `adb` invocation hits the same
   * server as the rest of the daemon. Read once.
   */
  private val serverFlags: List<String> by lazy {
    val socket = System.getenv("ADB_SERVER_SOCKET")?.takeIf { it.isNotBlank() }
    if (socket != null) {
      // adb binary accepts `-L tcp:host:port` directly.
      return@lazy listOf("-L", socket)
    }
    val port = System.getenv("ANDROID_ADB_SERVER_PORT")?.takeIf { it.isNotBlank() }
    if (port != null && port.toIntOrNull() != null) {
      return@lazy listOf("-P", port)
    }
    emptyList()
  }

  /** The full argv for running [deviceArgs] on [deviceId] through `adb exec-out`. */
  fun command(deviceId: TrailblazeDeviceId, deviceArgs: List<String>): List<String> = buildList {
    add(AdbPathResolver.ADB_COMMAND)
    addAll(serverFlags)
    add("-s")
    add(deviceId.instanceId)
    add("exec-out")
    addAll(deviceArgs)
  }

  /**
   * Runs [deviceArgs] and returns its raw stdout, or null when the command could not run, exited
   * non-zero, timed out, or produced nothing.
   *
   * stderr is read on its own thread and discarded: draining it on this thread would let a child
   * that stops producing output hold the caller past [timeoutMs] forever, and the diagnostics it
   * carries are not worth that risk on a per-frame call. A command the device rejects prints its
   * complaint on **stdout** (that is how a missing binary reaches the caller as ASCII), so callers
   * that care must validate the bytes rather than trust the exit code.
   */
  fun bytes(deviceId: TrailblazeDeviceId, deviceArgs: List<String>, timeoutMs: Long): ByteArray? {
    val process = try {
      ProcessBuilder(command(deviceId, deviceArgs)).redirectErrorStream(false).start()
    } catch (_: Exception) {
      return null
    }
    val stdout = ByteArrayOutputStream()
    val reader = Thread {
      runCatching { process.inputStream.use { it.copyTo(stdout) } }
    }.apply { isDaemon = true; start() }
    val drainErr = Thread {
      runCatching { process.errorStream.use { it.readBytes() } }
    }.apply { isDaemon = true; start() }

    val exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    if (!exited) process.destroyForcibly()
    // Killing the process is what closes the pipes, so the readers are joined only after that.
    reader.join(READER_JOIN_MILLIS)
    drainErr.join(READER_JOIN_MILLIS)
    if (!exited || process.exitValue() != 0) return null
    val bytes = stdout.toByteArray()
    return bytes.takeIf { it.isNotEmpty() }
  }

  /** How long to wait for a reader thread once the child is gone; its pipe is already closed. */
  private const val READER_JOIN_MILLIS = 2_000L
}
