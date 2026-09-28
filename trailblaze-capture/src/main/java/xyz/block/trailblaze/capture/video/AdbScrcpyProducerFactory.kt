package xyz.block.trailblaze.capture.video

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.util.Console

/**
 * Streams the device screen through scrcpy's on-device server instead of `screenrecord`, because
 * scrcpy tells us when the device drew each frame and `screenrecord` does not.
 *
 * `screenrecord` writes the encoder's output with its timestamps dropped, so a recording can only
 * stamp a frame when it reaches the host, after an encode and an adb hop whose delay swings with
 * load (a median 26 ms and a 99th percentile 578 ms behind the fastest frame, measured on a loaded
 * emulator). scrcpy's server encodes the same display with the same `MediaCodec`, and heads every
 * packet with the encoder's own presentation time. [ScrcpyAnnexBInputStream] turns that into the
 * Annex-B stream the rest of the tee already speaks, carrying each frame's device time in-band.
 *
 * ### The server
 * A pinned, unmodified scrcpy server release ships in this module's resources (see the README
 * beside it for provenance and license). It is pushed to the device when the copy there is missing
 * or differs, and started with `app_process`, as the scrcpy client does. Its protocol is internal to scrcpy and changes
 * between versions, so [VERSION] must match the bundled file exactly; the server refuses a mismatch.
 *
 * Launch options, and why:
 *  - `tunnel_forward=true`: the device listens and the host connects through `adb forward`, so
 *    nothing on the host has to accept connections.
 *  - `audio=false control=false`: video only. Nothing is injected into the device.
 *  - `capture_orientation=@`: locked to the orientation the session starts in, so a rotation does
 *    not change the video's size mid-recording — `screenrecord`'s behavior too.
 *  - `cleanup=false`: keeps the pushed server on the device between sessions.
 *  - `power_on=false`: leaves the screen's power state alone, as `screenrecord` does.
 *
 * Any failure to start throws, and [AndroidScreenProducerFactory] falls back to `screenrecord`.
 */
internal object AdbScrcpyProducerFactory : H264Tee.ProducerFactory {

  /** The bundled server release. Must match the file exactly; the server rejects any other. */
  internal const val VERSION = "4.1"

  /** SHA-256 of the bundled server, as published on the upstream release. */
  internal const val SHA256 = "deacb991ed2509715160ffdc7907e47b4160eb30d1566217e9047fd5b8850cae"

  internal const val RESOURCE = "/xyz/block/trailblaze/capture/video/scrcpy/scrcpy-server-v$VERSION"

  private const val DEVICE_PATH = "/data/local/tmp/trailblaze-scrcpy-server-v$VERSION.jar"

  /** scrcpy's codec id for H.264 (`"h264"`), the first four bytes on the video socket. */
  private const val CODEC_H264 = 0x68323634

  /** How long the server may take to start listening. A loaded CI emulator takes a few seconds. */
  private const val CONNECT_TIMEOUT_MS = 15_000L

  private val bundledServer: File by lazy {
    val stream = javaClass.getResourceAsStream(RESOURCE)
      ?: throw IOException("bundled scrcpy server $RESOURCE is missing from the classpath")
    File.createTempFile("trailblaze-scrcpy-server-", ".jar").apply {
      deleteOnExit()
      stream.use { input -> outputStream().use { input.copyTo(it) } }
    }
  }

  /** The device command that starts the server. Split out so its options can be asserted. */
  internal fun serverCommand(scid: String, videoSize: String, bitRate: String): List<String> = listOf(
    "CLASSPATH=$DEVICE_PATH",
    "app_process",
    "/",
    "com.genymobile.scrcpy.Server",
    VERSION,
    "scid=$scid",
    "log_level=info",
    "tunnel_forward=true",
    "audio=false",
    "control=false",
    "cleanup=false",
    "power_on=false",
    "send_device_meta=false",
    "send_dummy_byte=false",
    "send_frame_meta=true",
    "video_codec=h264",
    "video_bit_rate=$bitRate",
    "max_size=${maxSide(videoSize)}",
    "capture_orientation=@",
  )

  /** scrcpy sizes by the long side only; the recorder asks for `WxH`. 0 means "the display's own". */
  internal fun maxSide(videoSize: String): Int =
    videoSize.split('x').mapNotNull { it.trim().toIntOrNull() }.maxOrNull() ?: 0

  override fun spawn(
    deviceId: TrailblazeDeviceId,
    videoSize: String,
    bitRate: String,
    unlimited: Boolean,
  ): H264Tee.ProducerHandle {
    deploy(deviceId)
    val scid = "%08x".format(Random.nextInt(0, Int.MAX_VALUE))
    val port = forward(deviceId, scid)
    val server = try {
      ProcessBuilder(AdbExecOut.adb(deviceId, listOf("shell") + serverCommand(scid, videoSize, bitRate)))
        .redirectErrorStream(true)
        .start()
    } catch (e: Exception) {
      removeForward(deviceId, port)
      throw e
    }
    logServerOutput(deviceId, server.inputStream)
    val socket = try {
      connect(port, server)
    } catch (e: Exception) {
      stop(deviceId, server, port, scid)
      throw e
    }
    Console.log("[scrcpy] streaming ${deviceId.instanceId} (scid=$scid, max_size=${maxSide(videoSize)})")
    return object : H264Tee.ProducerHandle {
      override val input: InputStream = ScrcpyAnnexBInputStream(socket.getInputStream())
      override val carriesDeviceFrameTimes: Boolean = true
      override fun close() {
        runCatching { socket.close() }
        stop(deviceId, server, port, scid)
      }
    }
  }

  /**
   * Pushes the bundled server unless the device already holds it. Checked on every spawn, not
   * remembered: a device replaced at the same serial (a recreated emulator) starts without it.
   */
  private fun deploy(deviceId: TrailblazeDeviceId) {
    val expectedSize = bundledServer.length().toString()
    val onDevice = run(deviceId, listOf("shell", "stat", "-c", "%s", DEVICE_PATH), 10_000)?.trim()
    if (onDevice != expectedSize) {
      run(deviceId, listOf("push", bundledServer.absolutePath, DEVICE_PATH), 60_000)
        ?: throw IOException("could not push the scrcpy server to ${deviceId.instanceId}")
    }
  }

  /** `adb forward tcp:0 …` picks a free host port and prints it. */
  private fun forward(deviceId: TrailblazeDeviceId, scid: String): Int =
    run(deviceId, listOf("forward", "tcp:0", "localabstract:scrcpy_$scid"), 10_000)
      ?.trim()?.toIntOrNull()
      ?: throw IOException("adb forward for scrcpy failed on ${deviceId.instanceId}")

  /**
   * Connects to the forwarded port once the server is listening. Until it is, adb accepts the
   * connection and closes it straight away, so a connection counts only once the codec id arrives.
   */
  private fun connect(port: Int, server: Process): Socket {
    val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
      if (!server.isAlive) throw IOException("scrcpy server exited (${server.exitValue()}) before streaming")
      val socket = Socket()
      try {
        socket.connect(InetSocketAddress(AdbExecOut.serverHost, port), 2_000)
        val codec = readCodec(socket, server, deadline)
        if (codec != null) {
          if (codec != CODEC_H264) {
            runCatching { socket.close() }
            throw IllegalStateException("scrcpy sent codec 0x${codec.toString(16)}, not h264")
          }
          socket.soTimeout = 0 // the tee's reader blocks between frames; a still screen sends nothing
          return socket
        }
      } catch (_: IOException) {
        // Fall through and try a fresh connection; the loop ends on the deadline or a dead server.
      }
      runCatching { socket.close() }
      Thread.sleep(100)
    }
    throw IOException("scrcpy server did not start streaming within ${CONNECT_TIMEOUT_MS}ms")
  }

  /**
   * Reads the codec id off [socket]; null when the connection closed at once, meaning nothing was
   * listening yet. A connection that stays open is already the server's one video socket, still
   * setting up its encoder, so it is waited on to the deadline: closing it would end the server.
   */
  private fun readCodec(socket: Socket, server: Process, deadline: Long): Int? {
    socket.soTimeout = 250
    val input = socket.getInputStream()
    val bytes = ByteArray(4)
    var n = 0
    while (n < bytes.size) {
      val read = try {
        input.read(bytes, n, bytes.size - n)
      } catch (e: SocketTimeoutException) {
        if (!server.isAlive || System.currentTimeMillis() >= deadline) throw e
        continue
      }
      if (read < 0) {
        if (n == 0) return null
        throw IOException("scrcpy closed the video socket mid-header")
      }
      n += read
    }
    return ByteBuffer.wrap(bytes).int
  }

  /**
   * Ends the server. Killing the `adb shell` that runs it ends the device process; the `pkill` is
   * for a transport that does not pass that on. Cleanup runs off the caller's thread — the tee
   * calls this under its lock.
   */
  private fun stop(deviceId: TrailblazeDeviceId, server: Process, port: Int, scid: String) {
    runCatching { server.destroy() }
    Thread(
      {
        if (!server.waitFor(2, TimeUnit.SECONDS)) server.destroyForcibly()
        run(deviceId, listOf("shell", "pkill", "-f", "scid=$scid"), 5_000)
        removeForward(deviceId, port)
      },
      "scrcpy-cleanup-${deviceId.instanceId}",
    ).apply { isDaemon = true; start() }
  }

  private fun removeForward(deviceId: TrailblazeDeviceId, port: Int) {
    run(deviceId, listOf("forward", "--remove", "tcp:$port"), 5_000)
  }

  private fun logServerOutput(deviceId: TrailblazeDeviceId, output: InputStream) {
    Thread(
      {
        runCatching {
          output.bufferedReader().useLines { lines ->
            lines.forEach { Console.log("[scrcpy ${deviceId.instanceId}] $it") }
          }
        }
      },
      "scrcpy-log-${deviceId.instanceId}",
    ).apply { isDaemon = true; start() }
  }

  /** Runs one adb command and returns its stdout, or null when it failed or timed out. */
  private fun run(deviceId: TrailblazeDeviceId, args: List<String>, timeoutMs: Long): String? = try {
    val process = ProcessBuilder(AdbExecOut.adb(deviceId, args)).redirectErrorStream(true).start()
    val output = StringBuilder()
    val reader = Thread { runCatching { output.append(process.inputStream.bufferedReader().readText()) } }
      .apply { isDaemon = true; start() }
    val exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    if (!exited) process.destroyForcibly()
    reader.join(2_000)
    output.toString().takeIf { exited && process.exitValue() == 0 }
  } catch (_: Exception) {
    null
  }
}

/**
 * Where an Android device's screen stream comes from: scrcpy's server when it starts, `screenrecord`
 * otherwise. Set `TRAILBLAZE_ANDROID_VIDEO_SOURCE=screenrecord` to skip scrcpy entirely.
 *
 * A device on which scrcpy failed to start is sent straight to `screenrecord` for the next
 * [RETRY_AFTER_MS], so a device that cannot run it does not pay the startup timeout every session.
 * On a device with no `screenrecord` either, spawning throws, and [AndroidVideoCapture] records
 * the session from screenshots instead.
 */
internal class AndroidScreenProducerFactory(
  private val scrcpy: H264Tee.ProducerFactory = AdbScrcpyProducerFactory,
  private val screenrecord: H264Tee.ProducerFactory = AdbScreenrecordProducerFactory,
  private val scrcpyEnabled: () -> Boolean = { scrcpyEnabledByEnv },
  private val screenrecordAvailable: (TrailblazeDeviceId) -> Boolean = { AndroidScreenrecordSupport.isAvailable(it) },
  private val nowMs: () -> Long = System::currentTimeMillis,
) : H264Tee.ProducerFactory {

  private val scrcpyFailedAt = ConcurrentHashMap<String, Long>()

  override fun spawn(
    deviceId: TrailblazeDeviceId,
    videoSize: String,
    bitRate: String,
    unlimited: Boolean,
  ): H264Tee.ProducerHandle = pick(deviceId, videoSize, bitRate, unlimited, retryScrcpy = true)

  /**
   * A respawn mid-recording (screenrecord's 3-minute cap below Android 14) stays on screenrecord once
   * scrcpy has failed, even past the retry window: a server that hangs rather than exits would leave
   * the recording without frames for the whole connect wait.
   */
  override fun respawn(
    deviceId: TrailblazeDeviceId,
    videoSize: String,
    bitRate: String,
    unlimited: Boolean,
  ): H264Tee.ProducerHandle = pick(deviceId, videoSize, bitRate, unlimited, retryScrcpy = false)

  private fun pick(
    deviceId: TrailblazeDeviceId,
    videoSize: String,
    bitRate: String,
    unlimited: Boolean,
    retryScrcpy: Boolean,
  ): H264Tee.ProducerHandle {
    val failedAt = scrcpyFailedAt[deviceId.instanceId]
    val tryScrcpy = failedAt == null || (retryScrcpy && nowMs() - failedAt >= RETRY_AFTER_MS)
    if (scrcpyEnabled() && tryScrcpy) {
      try {
        return scrcpy.spawn(deviceId, videoSize, bitRate, unlimited).also {
          scrcpyFailedAt.remove(deviceId.instanceId)
        }
      } catch (e: Exception) {
        scrcpyFailedAt[deviceId.instanceId] = nowMs()
        Console.log(
          "[AndroidScreenProducerFactory] scrcpy could not stream ${deviceId.instanceId} " +
            "(${e.message}); using screenrecord, whose frames are timed on arrival",
        )
      }
    }
    if (!screenrecordAvailable(deviceId)) {
      throw IllegalStateException("${deviceId.instanceId} has no screenrecord, and scrcpy is not streaming it")
    }
    return screenrecord.spawn(deviceId, videoSize, bitRate, unlimited)
  }

  companion object {
    const val ENV_VAR = "TRAILBLAZE_ANDROID_VIDEO_SOURCE"
    internal const val RETRY_AFTER_MS = 5 * 60_000L

    private val scrcpyEnabledByEnv: Boolean by lazy {
      !System.getenv(ENV_VAR).orEmpty().trim().equals("screenrecord", ignoreCase = true)
    }

    /** The process-wide factory every Android tee uses, so the retry window is shared. */
    val default: AndroidScreenProducerFactory by lazy { AndroidScreenProducerFactory() }
  }
}
