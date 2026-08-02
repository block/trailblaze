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
  /** Host epoch (ms) of the first frame written — the MP4's offset-0 wall-clock anchor. */
  val firstFrameEpochMs: Long,
  /** Host epoch (ms) of the last frame written. */
  val lastFrameEpochMs: Long,
)

/**
 * The mux surface `BaguetteIosVideoCapture` drives. Extracted so the host recorder's start / stop /
 * mid-session-feed-death routing can be unit-tested with a fake mux (no ffmpeg, no real H.264 feed);
 * [WallClockMp4MuxConsumer] is the production implementation.
 */
interface WallClockVideoMux {
  fun start()

  fun stop(): MuxResult?
}

/**
 * Drains an [H264Tee.Consumer] and muxes it into an MP4 whose per-frame PTS are **host wall-clock**,
 * by piping the raw Annex-B stream through a long-running `ffmpeg -use_wallclock_as_timestamps 1
 * -c copy` for the whole session.
 *
 * ### Why this exists (vs [MuxToMp4Consumer])
 * [MuxToMp4Consumer] writes the tee to `.h264` segment files and concats them at stop with `-c
 * copy`; the raw elementary stream carries no timing, so the resulting MP4 gets synthetic CFR PTS
 * that [VideoSpriteExtractor.maybeRestamp] later has to *guess back* into wall-clock (uniformly).
 * That's fine for Android's `screenrecord`, but the iOS baguette feed hands every access unit to
 * the host **live**, so we can do better than a uniform guess: stamp each frame at the instant it
 * arrives. `ffmpeg -use_wallclock_as_timestamps 1` reads the pipe promptly and assigns each input
 * packet a PTS from `av_gettime()` — the same wall clock as `System.currentTimeMillis()` — and
 * `-c copy` carries those timestamps straight into the MP4 (no re-encode, no decode). The mp4
 * muxer normalizes the first DTS to zero, so playback offset `t` corresponds to wall-clock
 * `firstFrameEpoch + t`, frame-accurate.
 *
 * That is exactly the timeline the report overlays events on: `videoPositionMs = eventEpochMs -
 * startTimestampMs`. Set the artifact's `startTimestampMs` to [MuxResult.firstFrameEpochMs] and a
 * session-log event's epoch lands on its visually-correct frame with no report-side change.
 *
 * ### Continuous-encode assumption
 * baguette (VideoToolbox low-latency) encodes ~60 fps continuously with no B-frames, so per-packet
 * DTS are monotonic — the invariant `-c copy` to MP4 requires. The pipe read latency (our write →
 * ffmpeg's read) is the only skew between a frame's true arrival and its stamped PTS, well under a
 * single 60 fps frame.
 *
 * Not registry-shared: one consumer per recording. The tee it drains may be shared with other
 * consumers (the live-viewer / screenshot path) — that's the tee's job, not this class's.
 */
class WallClockMp4MuxConsumer(
  private val outputFile: File,
  private val tee: H264Tee,
  private val ringBufferBytes: Int = DEFAULT_RING_BUFFER_BYTES,
  /** Test seam: swap the ffmpeg binary path. */
  private val ffmpegBinary: String = "ffmpeg",
) : WallClockVideoMux {

  private var consumer: H264Tee.Consumer? = null
  private var process: Process? = null
  private var drainThread: Thread? = null
  private val stopped = AtomicBoolean(false)

  /** Host epoch (ms) when the first byte was handed to ffmpeg; -1 until then. */
  private val firstFrameEpochMs = AtomicLong(-1L)

  /** Host epoch (ms) when the most recent byte was handed to ffmpeg; -1 until any byte. */
  private val lastFrameEpochMs = AtomicLong(-1L)

  /** True once at least one byte has flowed into ffmpeg (i.e. the recording has real content). */
  fun hasContent(): Boolean = firstFrameEpochMs.get() >= 0L

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
    drainThread = Thread(::runDrainLoop, "wallclock-mp4-mux").apply {
      isDaemon = true
      start()
    }
  }

  /**
   * Stops the mux: detaches the tee (which drains any buffered bytes into ffmpeg), closes ffmpeg's
   * stdin so it finalizes the MP4, and waits for it to exit. Returns the [MuxResult], or null when
   * no bytes were ever captured or the output is empty. Idempotent-ish: a second call returns null.
   */
  override fun stop(): MuxResult? {
    if (!stopped.compareAndSet(false, true)) return null
    // Detach first so the drain loop flushes remaining buffered bytes, then exits on DETACHED and
    // closes ffmpeg's stdin — mirrors MuxToMp4Consumer's drain-to-completion contract.
    consumer?.detach()
    drainThread?.join(STOP_JOIN_TIMEOUT_MS)

    val proc = process
    process = null
    if (proc != null) {
      if (drainThread?.isAlive == true) {
        // The drain thread is still running past the join window — it's wedged in sink.write against
        // a stuck ffmpeg, so its `finally` never closed ffmpeg's stdin and ffmpeg will never see EOF.
        // Force-destroy now (which closes the pipe and unblocks the drain thread) rather than block
        // the caller for the full finalize timeout on a pipe that can't drain. The mp4 may be
        // truncated, but that's the wedged-case outcome either way. Console.error, not log: this is a
        // real fault the loud [baguette-video] alarm points at, and log is suppressed in quiet mode.
        Console.error(
          "[WallClockMp4MuxConsumer] drain thread did not exit within ${STOP_JOIN_TIMEOUT_MS}ms for " +
            "${outputFile.name} — destroying ffmpeg (video may be truncated)",
        )
        proc.destroyForcibly()
      } else if (!proc.waitFor(FFMPEG_FINALIZE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        // stdin was closed by the drain loop's finally; ffmpeg should have written the moov atom.
        Console.error("[WallClockMp4MuxConsumer] ffmpeg did not finalize within ${FFMPEG_FINALIZE_TIMEOUT_SECONDS}s for ${outputFile.name} — destroying")
        proc.destroyForcibly()
      } else if (proc.exitValue() != 0) {
        Console.error("[WallClockMp4MuxConsumer] ffmpeg exited ${proc.exitValue()} for ${outputFile.name}")
      }
    }

    val first = firstFrameEpochMs.get()
    if (first < 0L || !outputFile.exists() || outputFile.length() == 0L) {
      Console.log(
        "[WallClockMp4MuxConsumer] no video captured for ${outputFile.name} " +
          "(firstFrameEpoch=$first exists=${outputFile.exists()} len=${if (outputFile.exists()) outputFile.length() else -1})",
      )
      return null
    }
    return MuxResult(
      file = outputFile,
      firstFrameEpochMs = first,
      lastFrameEpochMs = lastFrameEpochMs.get(),
    )
  }

  private fun spawnFfmpeg(): Process {
    outputFile.parentFile?.mkdirs()
    val pb = ProcessBuilder(
      ffmpegBinary,
      "-y",
      // Stamp every input packet with the host wall clock at read time. This is the whole point:
      // it turns the timing-less raw H.264 pipe into a wall-clock-PTS stream.
      "-use_wallclock_as_timestamps", "1",
      // Start decoding/copying immediately — raw H.264 has no container to probe, and the SPS/PPS
      // at the head of baguette's stream is enough to identify it. Mirrors LiveFrameConsumer.
      "-flags", "+low_delay",
      "-probesize", "32",
      "-analyzeduration", "0",
      "-f", "h264",
      "-i", "pipe:0",
      "-an",
      // No re-encode: carry the wall-clock PTS straight into the container.
      "-c", "copy",
      // Preserve the variable, wall-clock-spaced PTS as-is — do not resample to a constant rate.
      "-fps_mode", "passthrough",
      // Fragmented + faststart so a daemon crash mid-session still leaves a playable file.
      "-movflags", "+faststart+frag_keyframe",
      outputFile.absolutePath,
    )
    pb.redirectErrorStream(false)
    val proc = pb.start()
    // Drain stderr so a misconfigured ffmpeg surfaces in the log rather than silently stalling,
    // and so a full stderr pipe can't wedge the subprocess.
    Thread(
      {
        try {
          proc.errorStream.bufferedReader().use { reader ->
            reader.lineSequence().forEach { line -> Console.log("[WallClockMp4MuxConsumer/ffmpeg] $line") }
          }
        } catch (_: Exception) { /* expected on close */ }
      },
      "wallclock-mp4-mux-stderr",
    ).apply { isDaemon = true; start() }
    Console.log("[WallClockMp4MuxConsumer] spawned ffmpeg pid=${proc.pid()} → ${outputFile.name}")
    return proc
  }

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
            // A new producer generation began (not expected on the single-shot baguette feed).
            // ffmpeg parses the new SPS/PPS inline on the copy path, so nothing to do here.
          }
          0 -> Thread.sleep(IDLE_SLEEP_MS)
          else -> {
            try {
              sink.write(buf, 0, n)
              sink.flush()
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
    /** 50 MB ≈ ~100 s of 4 Mbps H.264 slack. Dropped bytes here corrupt the MP4, so size generously. */
    private const val DEFAULT_RING_BUFFER_BYTES: Int = 50 * 1024 * 1024
    private const val DRAIN_CHUNK_BYTES: Int = 64 * 1024
    private const val IDLE_SLEEP_MS: Long = 5L
    private const val STOP_JOIN_TIMEOUT_MS: Long = 2_000L
    private const val FFMPEG_FINALIZE_TIMEOUT_SECONDS: Long = 30L
  }
}
