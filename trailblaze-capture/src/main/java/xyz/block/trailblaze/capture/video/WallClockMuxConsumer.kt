package xyz.block.trailblaze.capture.video

import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import xyz.block.trailblaze.util.Console

/** Result of a completed mux — the top-level seam type [WallClockVideoMux.stop] returns. */
data class MuxResult(
  val file: File,
  /** Host epoch (ms) of the first frame written — the file's offset-0 wall-clock anchor. */
  val firstFrameEpochMs: Long,
  /** Host epoch (ms) of the last frame written. */
  val lastFrameEpochMs: Long,
  /**
   * Whether the feed was still streaming when the recording stopped. False means the file ends
   * where the producer died (screenrecord or adb gone), not where the screen went still, so its
   * last frame must not be held to the stop.
   */
  val feedAlive: Boolean = true,
)

/**
 * The mux surface the video captures drive. Extracted so a recorder's start / stop /
 * mid-session-feed-death routing can be unit-tested with a fake mux (no ffmpeg, no real H.264 feed);
 * [WallClockMuxConsumer] is the production implementation.
 */
interface WallClockVideoMux {
  fun start()

  /**
   * True once the mux has taken a first frame, i.e. the recording has real content. A recorder
   * polls this after [start] to learn when the feed it attached to is actually producing, rather
   * than assuming a started mux is a recording one.
   */
  fun hasContent(): Boolean

  fun stop(): MuxResult?
}

/**
 * Drains an [H264Tee.Consumer] and writes it to a video file whose per-frame PTS are **host
 * wall-clock**, by piping the raw Annex-B stream through one long-running
 * `ffmpeg -use_wallclock_as_timestamps 1` for the whole session.
 *
 * ### Why wall-clock stamping
 * The raw elementary stream carries no timing. Both feeds hand every access unit to the host
 * **live**, so rather than guess a uniform rate after the fact we stamp each frame at the instant it
 * arrives: `-use_wallclock_as_timestamps 1` reads the pipe promptly and assigns each input packet a
 * PTS from `av_gettime()` — the same clock as `System.currentTimeMillis()`. The muxer normalizes
 * the first timestamp to zero, so playback offset `t` corresponds to wall-clock
 * `firstFrameEpoch + t`, frame-accurate. That is exactly the timeline the report overlays events
 * on: set the artifact's `startTimestampMs` to [MuxResult.firstFrameEpochMs] and a session-log
 * event's epoch lands on its visually-correct frame with no report-side change.
 *
 * ### Two outputs
 * - [Output.Mp4Copy] — `-c copy` into an mp4. No re-encode, no decode; the device's H.264 is
 *   carried as-is. The fallback on a host whose ffmpeg has no VP9 encoder ([RecordingFormat.MP4]).
 * - [Output.WebmVp9] — a live VP9 encode into a WebM, the container browsers play inline. The
 *   HTML report embeds this file and plays it as the session's timeline, so it is written with the
 *   report's needs in mind: every captured frame kept (`-fps_mode passthrough`), the capture's own
 *   resolution, and — because it is written **during** the session — a file that is valid on disk
 *   at every moment. The encoder's lookahead is disabled (`-lag-in-frames 0`): with the default it
 *   holds dozens of frames in memory and writes nothing for many seconds, so a crash would lose the
 *   whole recording. Frames are flushed to disk in one-second clusters instead, and on a clean stop
 *   ffmpeg writes the duration and seek index so the finished file is an ordinary seekable WebM.
 *   Measured on a 61 s Android session the encode runs at well over real time, so the file is
 *   complete the moment the session stops and nothing is left to do afterwards.
 *
 * ### Continuous-encode assumption
 * Both `screenrecord` and baguette (VideoToolbox low-latency) encode with no B-frames, so per-packet
 * DTS are monotonic — the invariant `-c copy` to mp4 requires, and the decode order the VP9 encoder
 * assumes. The pipe read latency (our write → ffmpeg's read) is the only skew between a frame's
 * true arrival and its stamped PTS, well under a single frame.
 *
 * Not registry-shared: one consumer per recording. The tee it drains may be shared with other
 * consumers (the live-viewer / screenshot path) — that's the tee's job, not this class's.
 */
class WallClockMuxConsumer(
  private val outputFile: File,
  private val tee: H264Tee,
  private val output: Output = Output.Mp4Copy,
  private val ringBufferBytes: Int = DEFAULT_RING_BUFFER_BYTES,
  /**
   * How far the device's portrait framebuffer has to be turned to play the right way up. Fixed for
   * the life of this mux: neither an encode filter nor a container tag can change partway through a
   * file, so a device that rotates mid-session is handled by starting a new mux, not by changing
   * this one.
   */
  private val rotation: IosScreenRotation = IosScreenRotation.NONE,
  /** Test seam: swap the ffmpeg binary path. */
  private val ffmpegBinary: String = "ffmpeg",
) : WallClockVideoMux {

  /** How the wall-clock-stamped stream is written. See the class doc for the trade-off. */
  sealed class Output {
    /** Container the file should be named for. */
    abstract val fileExtension: String

    internal abstract fun ffmpegArgs(): List<String>

    /**
     * Args placed **before** `-i` that express [rotation] without re-encoding, for a container that
     * can carry it out of band. Empty when this container can't.
     */
    internal open fun rotationInputArgs(rotation: IosScreenRotation): List<String> = emptyList()

    /** Args placed **after** `-i` that bake [rotation] into the pixels. Empty when unnecessary. */
    internal open fun rotationOutputArgs(rotation: IosScreenRotation): List<String> = emptyList()

    /** Remux the device's H.264 as-is into a fragmented mp4. */
    data object Mp4Copy : Output() {
      override val fileExtension = "mp4"

      override fun ffmpegArgs(): List<String> = listOf(
        // No re-encode: carry the wall-clock PTS straight into the container.
        "-c", "copy",
        // Preserve the variable, wall-clock-spaced PTS as-is — do not resample to a constant rate.
        "-fps_mode", "passthrough",
        // Fragmented + faststart. NOTE: a fragment closes only at a keyframe, and a feed that emits
        // one keyframe per session (Android `screenrecord`) therefore holds the entire recording in
        // ffmpeg's memory until stop — this mode is NOT crash-safe on such a feed. The WebM mode is.
        "-movflags", "+faststart+frag_keyframe",
      )

      /**
       * mp4 carries rotation in the stream's display matrix, so a landscape recording costs nothing
       * here — the `-c copy` above survives. `-display_rotation` is an *input* option in ffmpeg 9:
       * it sets how the incoming stream is to be interpreted, and with a stream copy that angle is
       * written straight through to the output's matrix. This is the same representation
       * `simctl io recordVideo` produces for a landscape session.
       */
      override fun rotationInputArgs(rotation: IosScreenRotation): List<String> =
        if (rotation == IosScreenRotation.NONE) emptyList()
        else listOf("-display_rotation:v:0", rotation.displayRotationDegrees.toString())
    }

    /**
     * Encode to VP9 in a WebM, live, flushing to disk every second. [crf] is libvpx's
     * constant-quality level (lower is better; 32 is visually transparent for UI recordings and
     * roughly half the size of the mp4 it comes from). The codec settings are [vp9CodecArgs]; this
     * output adds the live-file concerns on top.
     */
    data class WebmVp9(val crf: Int = DEFAULT_CRF) : Output() {
      override val fileExtension = "webm"

      override fun ffmpegArgs(): List<String> = vp9CodecArgs(crf) + listOf(
        // Timestamp the encoded frames in MILLISECONDS. Without this ffmpeg derives the encoder's
        // time base from the stream's *declared* frame rate, and a screen feed's declared rate has
        // nothing to do with when its frames actually arrived: a raw H.264 pipe declares nothing, so
        // ffmpeg assumes 25fps and snaps every wall-clock stamp to a 40ms grid. Measured on a real
        // Android session that collapsed 353 frames onto 148 timestamps — two or three frames share
        // a stamp, and a player shows only one of each group, so the recording stutters at 25fps
        // while paying for 60. The same feed with a millisecond time base: 356 stamps, 356 frames.
        // 1/1000 is also exactly the WebM container's own resolution, so nothing is rounded twice.
        //
        // Live-only, and deliberately so. This fixes a feed that declares NO timing; an encode that
        // runs after the session reads a real container whose time base is already finer than a
        // millisecond, and forcing 1:1000 there would round timestamps that did not need it.
        "-enc_time_base", "1:1000",
        // Encode as frames arrive. The default lookahead holds ~25 frames — on a damage-driven feed
        // that is many seconds of session — and nothing reaches the file until it drains, which on a
        // crash is never. With zero lag each frame is written as soon as it is encoded. Live-only:
        // it buys crash safety on a file being written, which an encode that runs to completion
        // after the session has no use for.
        "-lag-in-frames", "0",
        // Keep the capture's own frame timing. `screenrecord` is damage-driven — a burst of frames
        // while the screen changes, nothing while it is still — and ffmpeg's default output mode
        // resamples that to a constant rate, silently DROPPING frames (measured at 76%, 48% and 14%
        // of frames across three real sessions). Those are the frames a viewer scrubs to.
        "-fps_mode", "passthrough",
        // Close and write a cluster at least every second, and push it through to the file rather
        // than leaving it in ffmpeg's I/O buffer. This is what makes the on-disk file playable
        // mid-session: a daemon crash loses at most the second in progress.
        "-cluster_time_limit", "1000",
        "-flush_packets", "1",
      )

      /**
       * WebM has no display matrix, so a landscape recording has to be turned in the pixels. This
       * encode is already running, so the transpose is close to free — unlike [Mp4Copy], where it
       * would mean giving up the stream copy entirely.
       */
      override fun rotationOutputArgs(rotation: IosScreenRotation): List<String> =
        rotation.transposeFilter?.let { listOf("-vf", it) }.orEmpty()
    }

    companion object {
      const val DEFAULT_CRF: Int = 32

      /**
       * The VP9 encoder configuration every WebM recording uses, live or not — one measured setting,
       * so a stitched or transcoded recording looks like a live one. [crf] is libvpx's
       * constant-quality level (lower is better; 32 is visually transparent for UI recordings).
       */
      fun vp9CodecArgs(crf: Int = DEFAULT_CRF): List<String> = listOf(
        "-c:v", "libvpx-vp9",
        "-crf", crf.toString(),
        "-b:v", "0",
        // Realtime deadline: over 100x real time on a laptop, and at equal SSIM it came out at HALF
        // the size of the `good` deadline once lookahead is off. Keeping up is what matters for a
        // live encode — falling behind fills the tee's ring buffer and then drops bytes. The
        // after-the-fact encodes keep it too, on purpose: they run inside session teardown, where
        // a slower deadline buys compression at the cost of stalling the step. It is also why they
        // do not need the live path's `-lag-in-frames 0` — the realtime deadline uses no lookahead
        // to disable.
        "-deadline", "realtime",
        "-cpu-used", "6",
        "-row-mt", "1",
        "-pix_fmt", "yuv420p",
      )

      /**
       * Whether [ffmpegBinary] can encode VP9. `-encoders` lists what the binary was built with;
       * a homebrew or distro ffmpeg built without libvpx has no `libvpx-vp9` line. Callers use this
       * to fall back to [Mp4Copy] rather than spawn an encode that fails on its first frame.
       *
       * Bounded by [timeoutSeconds] end to end, via [runSubprocessWithTimeout]: this runs on
       * capture start, and an ffmpeg that wedges (or never closes stdout) must cost the session a
       * few seconds and the mp4 fallback, not hang it. Reading stdout to EOF before waiting would
       * make the timeout unreachable — the drain has to happen on a side thread.
       */
      fun vp9EncoderAvailable(ffmpegBinary: String = "ffmpeg", timeoutSeconds: Long = 20): Boolean =
        probeLiveMuxSupport(ffmpegBinary, timeoutSeconds).vp9Encoder

      /**
       * One `ffmpeg -encoders` run, read for both things a live recording depends on: the VP9
       * encoder, and the version banner (printed because `-hide_banner` is left off) that says
       * whether this ffmpeg keeps wall-clock timestamps through a re-encode. Same bound as
       * [vp9EncoderAvailable]; an ffmpeg that cannot be run reports neither.
       */
      fun probeLiveMuxSupport(ffmpegBinary: String = "ffmpeg", timeoutSeconds: Long = 20): LiveMuxSupport {
        val result = runSubprocessWithTimeout(
          listOf(ffmpegBinary, "-encoders"),
          timeoutSeconds = timeoutSeconds,
        ) ?: return LiveMuxSupport(vp9Encoder = false, ffmpegVersion = null)
        return LiveMuxSupport(vp9EncoderListed(result.output), ffmpegVersionIn(result.output))
      }

      /** The version token of ffmpeg's banner line (`ffmpeg version 6.1.1-3ubuntu5 Copyright…`). */
      internal fun ffmpegVersionIn(output: String): String? =
        Regex("""^ffmpeg version (\S+)""", RegexOption.MULTILINE).find(output)?.groupValues?.get(1)

      /**
       * Whether this ffmpeg version throws away `-use_wallclock_as_timestamps` stamps when it
       * DECODES a raw H.264 pipe (FFmpeg ticket #11268). It flags the raw demuxer's timestamps as
       * unreliable even though the wall clock generated them, and the re-encode then spaces every
       * frame evenly at the stream's declared rate — so a [WebmVp9] recording stops following the
       * session. A stream copy never decodes, so [Mp4Copy] keeps the arrival times. The bug came
       * in with 6.1 and was fixed in 6.1.3, 7.0.3 and 7.1.2; 8.0 and later never had it. Ubuntu
       * 24.04 ships 6.1.1.
       *
       * Read from the version string alone. A version that doesn't parse (a git build like
       * `N-117000-g…`) counts as unaffected; a distro build that backported the fix is still
       * reported affected, which only costs it the mp4 fallback.
       */
      internal fun dropsWallClockOnDecode(ffmpegVersion: String): Boolean {
        val match = Regex("""^n?(\d+)\.(\d+)(?:\.(\d+))?""").find(ffmpegVersion) ?: return false
        val major = match.groupValues[1].toInt()
        val minor = match.groupValues[2].toInt()
        val patch = match.groupValues[3].toIntOrNull() ?: 0
        return when (major to minor) {
          6 to 1, 7 to 0 -> patch < 3
          7 to 1 -> patch < 2
          else -> false
        }
      }

      /** Pure half of [vp9EncoderAvailable]: does an `ffmpeg -encoders` listing include libvpx-vp9. */
      internal fun vp9EncoderListed(encodersListing: String): Boolean =
        encodersListing.lineSequence().any { line ->
          line.trim().split(Regex("\\s+")).getOrNull(1) == "libvpx-vp9"
        }
    }

    /** What [probeLiveMuxSupport] found out about this host's ffmpeg. */
    data class LiveMuxSupport(val vp9Encoder: Boolean, val ffmpegVersion: String?) {
      /** False when a [WebmVp9] re-encode would lose the recording's timing; see [dropsWallClockOnDecode]. */
      val reencodeKeepsWallClock: Boolean
        get() = ffmpegVersion == null || !dropsWallClockOnDecode(ffmpegVersion)
    }
  }

  private var consumer: H264Tee.Consumer? = null
  private var process: Process? = null
  private var drainThread: Thread? = null
  private val stopped = AtomicBoolean(false)

  /** Host epoch (ms) when the first byte was handed to ffmpeg; -1 until then. */
  private val firstFrameEpochMs = AtomicLong(-1L)

  /** Host epoch (ms) when the most recent byte was handed to ffmpeg; -1 until any byte. */
  private val lastFrameEpochMs = AtomicLong(-1L)

  /**
   * Total bytes handed to ffmpeg. Read at stop as the progress signal that tells a BACKLOG apart
   * from a wedge: a drain that is still moving bytes is doing its job, however long it takes.
   */
  private val drainedBytes = AtomicLong(0L)

  /** True once at least one byte has flowed into ffmpeg (i.e. the recording has real content). */
  override fun hasContent(): Boolean = firstFrameEpochMs.get() >= 0L

  /** Starts the ffmpeg mux and the tee-drain thread. Must be called once. */
  override fun start() {
    consumer = tee.attach(ringBufferBytes)
    try {
      process = spawnFfmpeg()
    } catch (e: Exception) {
      // ffmpeg couldn't start — release the tee attachment so we don't hold the feed open.
      consumer?.detach()
      consumer = null
      throw e
    }
    drainThread = Thread(::runDrainLoop, "wallclock-mux").apply {
      isDaemon = true
      start()
    }
  }

  /**
   * Stops the mux: detaches the tee (which drains any buffered bytes into ffmpeg), closes ffmpeg's
   * stdin so it finalizes the file, and waits for it to exit. Returns the [MuxResult], or null when
   * no bytes were ever captured or the output is empty. Idempotent-ish: a second call returns null.
   */
  override fun stop(): MuxResult? {
    if (!stopped.compareAndSet(false, true)) return null
    // Read before detaching: detaching the last consumer stops the producer, which reads as dead.
    val feedAlive = tee.isFeeding
    // Detach first so the drain loop flushes remaining buffered bytes, then exits on DETACHED and
    // closes ffmpeg's stdin — mirrors MuxToMp4Consumer's drain-to-completion contract.
    consumer?.detach()
    val thread = drainThread
    val drained = thread == null || awaitDrain(
      isAlive = { thread.isAlive },
      drainedBytes = { drainedBytes.get() },
      joinSlice = { thread.join(it) },
    )

    val proc = process
    process = null
    if (proc != null) {
      if (!drained) {
        // The drain thread stopped moving bytes — it's wedged in sink.write against a stuck ffmpeg,
        // so its `finally` never closed ffmpeg's stdin and ffmpeg will never see EOF.
        // Force-destroy now (which closes the pipe and unblocks the drain thread) rather than block
        // the caller for the full finalize timeout on a pipe that can't drain. The file may be
        // truncated, but that's the wedged-case outcome either way. Console.error, not log: this is a
        // real fault the loud [baguette-video] alarm points at, and log is suppressed in quiet mode.
        Console.error(
          "[WallClockMuxConsumer] drain thread moved no bytes for ${DRAIN_STALL_TIMEOUT_MS}ms for " +
            "${outputFile.name} — destroying ffmpeg (video may be truncated)",
        )
        proc.destroyForcibly()
      } else if (!proc.waitFor(FFMPEG_FINALIZE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        // stdin was closed by the drain loop's finally; ffmpeg should have written the trailer.
        Console.error("[WallClockMuxConsumer] ffmpeg did not finalize within ${FFMPEG_FINALIZE_TIMEOUT_SECONDS}s for ${outputFile.name} — destroying")
        proc.destroyForcibly()
      } else if (proc.exitValue() != 0) {
        Console.error("[WallClockMuxConsumer] ffmpeg exited ${proc.exitValue()} for ${outputFile.name}")
      }
    }

    val first = firstFrameEpochMs.get()
    if (first < 0L || !outputFile.exists() || outputFile.length() == 0L) {
      Console.log(
        "[WallClockMuxConsumer] no video captured for ${outputFile.name} " +
          "(firstFrameEpoch=$first exists=${outputFile.exists()} len=${if (outputFile.exists()) outputFile.length() else -1})",
      )
      return null
    }
    return MuxResult(
      file = outputFile,
      firstFrameEpochMs = first,
      lastFrameEpochMs = lastFrameEpochMs.get(),
      feedAlive = feedAlive,
    )
  }

  private fun spawnFfmpeg(): Process {
    outputFile.parentFile?.mkdirs()
    val pb = ProcessBuilder(buildFfmpegCommand())
    pb.redirectErrorStream(false)
    val proc = pb.start()
    // Drain stderr so a misconfigured ffmpeg surfaces in the log rather than silently stalling,
    // and so a full stderr pipe can't wedge the subprocess.
    Thread(
      {
        try {
          proc.errorStream.bufferedReader().use { reader ->
            reader.lineSequence().forEach { line -> Console.log("[WallClockMuxConsumer/ffmpeg] $line") }
          }
        } catch (_: Exception) { /* expected on close */ }
      },
      "wallclock-mux-stderr",
    ).apply { isDaemon = true; start() }
    Console.log(
      "[WallClockMuxConsumer] spawned ffmpeg pid=${proc.pid()} → ${outputFile.name} " +
        "(${output::class.simpleName}, rotation=$rotation)",
    )
    return proc
  }

  /**
   * The ffmpeg invocation this mux runs. Rotation args are placed by side: the mp4 display matrix
   * has to precede `-i` to apply to the input stream, the WebM transpose has to follow it to apply
   * to the encode. Getting either on the wrong side of `-i` is accepted silently by ffmpeg and
   * produces an unrotated recording, so the order is asserted rather than assumed.
   */
  internal fun buildFfmpegCommand(): List<String> = listOf(
    ffmpegBinary,
    "-y",
    // Stamp every input packet with the host wall clock at read time. This is the whole point:
    // it turns the timing-less raw H.264 pipe into a wall-clock-PTS stream.
    "-use_wallclock_as_timestamps", "1",
    // Start immediately — raw H.264 has no container to probe, and the SPS/PPS at the head of
    // the stream is enough to identify it. Mirrors LiveFrameConsumer.
    "-flags", "+low_delay",
    "-probesize", "32",
    "-analyzeduration", "0",
    "-f", "h264",
  ) + output.rotationInputArgs(rotation) + listOf(
    "-i", "pipe:0",
    "-an",
  ) + output.rotationOutputArgs(rotation) + output.ffmpegArgs() + outputFile.absolutePath

  private fun runDrainLoop() {
    val cons = consumer ?: return
    val proc = process ?: return
    val sink: OutputStream = proc.outputStream
    val buf = ByteArray(DRAIN_CHUNK_BYTES)
    try {
      while (true) {
        when (val n = cons.read(buf)) {
          H264Tee.READ_RESULT_DETACHED -> return
          H264Tee.READ_RESULT_RESTART -> {
            // A new producer generation began (screenrecord's 3-minute cap on Android < 11). ffmpeg
            // parses the new SPS/PPS inline on both paths, so nothing to do here.
          }
          0 -> Thread.sleep(IDLE_SLEEP_MS)
          else -> {
            try {
              sink.write(buf, 0, n)
              sink.flush()
              drainedBytes.addAndGet(n.toLong())
              val now = System.currentTimeMillis()
              firstFrameEpochMs.compareAndSet(-1L, now)
              lastFrameEpochMs.set(now)
            } catch (_: Exception) {
              // ffmpeg died or stop() closed the pipe — exit cleanly.
              return
            }
          }
        }
      }
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
    } finally {
      // Detach regardless of how the loop exits (DETACHED, or sink.write threw on a mid-session
      // ffmpeg death). Without this, an early exit leaves the consumer subscribed to the tee —
      // still buffering/dropping bytes — until stop() runs. detach() is idempotent, so the normal
      // stop()-driven detach that ends the loop double-detaching here is harmless.
      runCatching { cons.detach() }
      runCatching { sink.flush() }
      runCatching { sink.close() }
    }
  }

  companion object {
    /** 50 MB ≈ ~100 s of 4 Mbps H.264 slack. Dropped bytes here corrupt the output, so size generously. */
    private const val DEFAULT_RING_BUFFER_BYTES: Int = 50 * 1024 * 1024
    private const val DRAIN_CHUNK_BYTES: Int = 64 * 1024
    private const val IDLE_SLEEP_MS: Long = 5L

    /**
     * How long the drain may move NO bytes at all before stop gives up on it. This is the wedge
     * test, and it is deliberately not a bound on how long draining may take: the ring buffer
     * holds ~100 s of feed, and flushing a full one on a loaded box is slow work, not a fault.
     * Killing a drain that is still writing truncates a recording that was about to be complete.
     */
    private const val DRAIN_STALL_TIMEOUT_MS: Long = 2_000L

    /**
     * Absolute bound on the wait, so a drain that keeps making token progress against a nearly
     * stuck ffmpeg cannot hold a session's teardown open forever.
     */
    private const val DRAIN_TOTAL_TIMEOUT_MS: Long = 60_000L

    /** How often progress is re-read while waiting. */
    private const val DRAIN_PROGRESS_SLICE_MS: Long = 100L

    private const val FFMPEG_FINALIZE_TIMEOUT_SECONDS: Long = 30L

    /**
     * Waits for the drain to finish, tolerating a backlog but not a wedge. True when it drained;
     * false when it stopped moving bytes for [DRAIN_STALL_TIMEOUT_MS] or ran past
     * [DRAIN_TOTAL_TIMEOUT_MS], which is the caller's signal to destroy ffmpeg.
     *
     * Split out and parameterized so the distinction it exists to make — backlogged versus wedged
     * — is testable without a real thread or a real ffmpeg.
     */
    internal fun awaitDrain(
      isAlive: () -> Boolean,
      drainedBytes: () -> Long,
      joinSlice: (Long) -> Unit,
      nowMs: () -> Long = System::currentTimeMillis,
    ): Boolean {
      val startedAt = nowMs()
      var lastBytes = drainedBytes()
      var lastProgressAt = startedAt
      while (isAlive()) {
        joinSlice(DRAIN_PROGRESS_SLICE_MS)
        if (!isAlive()) break
        val now = nowMs()
        val bytes = drainedBytes()
        if (bytes != lastBytes) {
          lastBytes = bytes
          lastProgressAt = now
        } else if (now - lastProgressAt >= DRAIN_STALL_TIMEOUT_MS) {
          return false
        }
        if (now - startedAt >= DRAIN_TOTAL_TIMEOUT_MS) return false
      }
      return true
    }
  }
}
