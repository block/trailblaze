package xyz.block.trailblaze.capture.video

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import xyz.block.trailblaze.util.Console

/**
 * Drains an [H264Tee.Consumer] and emits decoded JPEG frames to a caller-supplied sink.
 *
 * Pipes the raw H.264 NAL stream into a long-running `ffmpeg ... -c:v mjpeg -f image2pipe -`
 * sidecar process; reads JPEG frames out of the sidecar's stdout by splitting on the JPEG
 * Start-of-Image (`0xFFD8`) / End-of-Image (`0xFFD9`) markers. Each completed frame is
 * SHA-256-hashed and compared against the last emitted hash. Identical frames are suppressed
 * between periodic heartbeats, so a still screen produces at most one frame per second.
 * Heartbeats fire only while the decoder keeps producing frames — a damage-driven encoder
 * (the emulator's `screenrecord`) emits nothing at all for a static screen, so subscribers
 * that need to distinguish "static screen" from "dead pipeline" use [onFeedAlive].
 *
 * **Why JPEG?** WebP would require either a per-frame ffmpeg subprocess (high latency) or a
 * native encoder dependency. JPEG is browser-native, ffmpeg's mjpeg encoder is the fastest
 * thing in the box, and frame size at q=5 is small enough that the wire cost is dominated by
 * base64 overhead either way. Set-of-mark / stored-waypoint paths remain on their own formats
 * (PNG/WebP); only the *streaming* wire is JPEG.
 *
 * **No audio.** The mjpeg encoder is invoked with `-an` for safety.
 *
 * **Sidecar death.** The ffmpeg process can die without any fault in this JVM — CI agents under
 * memory pressure kill it outright (observed in the wild: full startup stderr, then silence, no
 * exit message). Death is detected promptly as EOF on its stdout (or a failed stdin write) and
 * healed in place: a replacement consumer is attached to the tee *before* the dead one detaches
 * (so a shared screenrecord producer never tears down), the tee seeds it with the cached keyframe
 * so the fresh decoder has SPS/PPS and an IDR to start from, and a new sidecar plus pump threads
 * take over. Respawns are bounded: [MAX_CONSECUTIVE_SILENT_DEATHS] consecutive deaths without a
 * single decoded frame give up and detach entirely, so the liveness pings stop and subscribers'
 * stall watchdogs fire rather than being masked by a permanently dead decoder.
 *
 * Lifecycle: [start] spins up the sidecar process and the drain thread. [stop] closes the
 * tee consumer, terminates the sidecar, and joins the drain thread. Safe to call [stop]
 * more than once.
 */
class LiveFrameConsumer(
  private val tee: H264Tee,
  /**
   * Callback invoked for changed frames and periodic still-screen heartbeats. Runs on the
   * current pipeline's frame-pump thread, but delivery is not strictly serial across a sidecar
   * respawn: when a failed stdin write triggers the swap, the dead sidecar's stdout pump can
   * still be draining its last buffered frames while the replacement's pump starts emitting,
   * so implementations must tolerate a brief two-thread overlap. `isContentChange` is false
   * for a heartbeat re-emit of an unchanged frame — exposed so subscribers that care about
   * the distinction (e.g. stream-quiet detection) don't have to re-hash every frame this
   * consumer already hashed.
   */
  private val onFrame: (jpeg: ByteArray, isContentChange: Boolean) -> Unit,
  /**
   * Optional out-of-band liveness signal, invoked (throttled to ~2 Hz) from the tee drain
   * loop while the capture pipeline stays attached — including when no bytes flow at all.
   * Damage-driven encoders (the emulator's `screenrecord`) emit nothing for a static screen,
   * so heartbeat re-emits via [onFrame] only prove liveness while frames keep decoding; this
   * callback proves it when they don't. Runs on the tee drain thread. Note it attests to the
   * tee attachment being drained, not to the ffmpeg sidecar's health — a dead sidecar is
   * detected via EOF on its stdout and healed by a bounded respawn (see the class kdoc); pings
   * keep flowing across the swap, and stop for good only when the respawn budget is exhausted.
   *
   * Wire this ONLY for damage-driven feeds, where upstream silence is the healthy static-screen
   * state. On a continuously-encoding feed (e.g. iOS baguette, ~60 fps even for a static
   * screen) frames themselves prove liveness, and an idle-loop ping would instead mask a
   * wedged upstream producer — the drain loop can't tell those states apart; only the caller
   * knows the feed's cadence.
   */
  private val onFeedAlive: (() -> Unit)? = null,
  /** Ring-buffer capacity for this consumer. Default sized for live-viewer drop tolerance. */
  private val ringBufferBytes: Int = DEFAULT_RING_BUFFER_BYTES,
  /** Test seam: ffmpeg binary path. */
  private val ffmpegBinary: String = "ffmpeg",
  /** Mjpeg quality scale (1–31, lower = better). Default 5 matches "high quality". */
  private val jpegQ: Int = 5,
) {

  /** One ffmpeg sidecar plus the tee attachment and pump threads bound to it. Replaced wholesale on respawn. */
  private class Pipeline(
    val consumer: H264Tee.Consumer,
    val process: Process,
  ) {
    /** True once this sidecar has produced at least one complete JPEG — resets the respawn budget. */
    val producedFrame = AtomicBoolean(false)

    /** Ensures a sidecar death is handled exactly once even when both pumps observe it. */
    val deathHandled = AtomicBoolean(false)

    var teeToFfmpegThread: Thread? = null
    var ffmpegToFramesThread: Thread? = null
  }

  private val lifecycleLock = Any()

  /** Guarded by [lifecycleLock]. */
  private var pipeline: Pipeline? = null

  /** Guarded by [lifecycleLock]. */
  private var consecutiveSilentDeaths = 0

  private val stopped = AtomicBoolean(false)

  fun start() {
    val firstConsumer = tee.attach(ringBufferBytes)
    val started = try {
      Pipeline(firstConsumer, spawnFfmpeg())
    } catch (e: Exception) {
      // An attached tee owns the screenrecord producer. If the decoder cannot start, tear
      // the attachment down immediately instead of leaving screenrecord running until the
      // WebSocket eventually closes.
      stopped.set(true)
      firstConsumer.detach()
      throw e
    }
    synchronized(lifecycleLock) { pipeline = started }
    // Pumps start only after the pipeline is published as current. An ffmpeg that dies the
    // instant it spawns is observed by the pumps as EOF; if that could happen before
    // publication, onSidecarExit would discard the death as stale (pipeline !== dead) after
    // already consuming deathHandled, leaving the consumer wedged on a dead decoder forever.
    startPumps(started)
  }

  fun stop() {
    if (!stopped.compareAndSet(false, true)) return
    val current = synchronized(lifecycleLock) { pipeline.also { pipeline = null } }
    current?.consumer?.detach()
    val proc = current?.process
    if (proc != null) {
      runCatching { proc.destroy() }
      if (!proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
        runCatching { proc.destroyForcibly() }
      }
    }
    current?.teeToFfmpegThread?.join(2_000)
    current?.ffmpegToFramesThread?.join(2_000)
  }

  /** Test seam: the current ffmpeg sidecar, so tests can kill it and observe the respawn contract. */
  internal fun sidecarProcessForTest(): Process? = synchronized(lifecycleLock) { pipeline?.process }

  /**
   * Starts the two pump threads for [p]. Callers must have already published [p] as the current
   * [pipeline]; either pump can observe a sidecar death within microseconds of starting, and
   * [onSidecarExit] treats a death on a non-current pipeline as stale.
   */
  private fun startPumps(p: Pipeline) {
    p.teeToFfmpegThread = Thread({ pumpTeeIntoFfmpeg(p) }, "live-frame-tee-to-ffmpeg").apply {
      isDaemon = true
      start()
    }
    p.ffmpegToFramesThread = Thread({ pumpFfmpegIntoFrames(p) }, "live-frame-ffmpeg-to-frames").apply {
      isDaemon = true
      start()
    }
  }

  /**
   * Handles an unexpected sidecar exit (EOF on its stdout / failed stdin write while not
   * [stopped]): respawn a fresh pipeline, or give up and detach once the budget of consecutive
   * frameless deaths is spent. Runs on the dead pipeline's pump threads; [Pipeline.deathHandled]
   * dedupes the two observers.
   */
  private fun onSidecarExit(dead: Pipeline) {
    if (!dead.deathHandled.compareAndSet(false, true)) return
    // Normally the process is already gone (that's why we're here); destroy covers the rare
    // "closed its pipes but lingers" shape so exitValue below can't throw.
    runCatching { dead.process.destroyForcibly() }
    val exitCode = if (dead.process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) dead.process.exitValue() else null
    // Rate-cap respawns before taking the lock. The frameless-death budget can't bound a
    // sidecar that decodes a frame before each death (every such death resets it), so without
    // a pause an externally-killed-but-productive decoder would respawn in a tight loop. Same
    // flat pause as H264Tee's producer respawn. Unlike H264Tee we continue on interrupt rather
    // than return: deathHandled is already consumed, so bailing here would leave the death
    // unhandled forever.
    try {
      Thread.sleep(SIDECAR_RESPAWN_PAUSE_MS)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
    synchronized(lifecycleLock) {
      if (stopped.get() || pipeline !== dead) return
      consecutiveSilentDeaths = if (dead.producedFrame.get()) 0 else consecutiveSilentDeaths + 1
      if (consecutiveSilentDeaths >= MAX_CONSECUTIVE_SILENT_DEATHS) {
        Console.log(
          "[LiveFrameConsumer] ffmpeg died (exit=$exitCode) $consecutiveSilentDeaths times in a row " +
            "without decoding a frame; giving up and detaching from the tee",
        )
        pipeline = null
        dead.consumer.detach()
        return
      }
      Console.log("[LiveFrameConsumer] ffmpeg exited unexpectedly (exit=$exitCode); respawning the decoder sidecar")
      // Attach the replacement BEFORE detaching the dead pipeline's consumer so the tee's
      // ref-count never touches zero (which would tear down a shared screenrecord producer).
      // The mid-stream attach is seeded with the tee's cached keyframe, so the fresh decoder
      // has SPS/PPS and an IDR to start from without waiting for the encoder's next keyframe.
      val newConsumer = tee.attach(ringBufferBytes)
      val next = try {
        Pipeline(newConsumer, spawnFfmpeg())
      } catch (e: Exception) {
        Console.log("[LiveFrameConsumer] decoder respawn failed: ${e.message}; detaching from the tee")
        newConsumer.detach()
        pipeline = null
        dead.consumer.detach()
        return
      }
      pipeline = next
      // Safe to start pumps while holding the lock: a pump observing an instant death blocks
      // on this lock in onSidecarExit and proceeds only after [pipeline] = next is visible.
      startPumps(next)
      dead.consumer.detach()
    }
  }

  private fun spawnFfmpeg(): Process {
    val pb = ProcessBuilder(
      ffmpegBinary,
      // Decode the H.264 elementary stream from stdin. Do not pass `-fflags nobuffer` here:
      // despite its name, that input flag drops the packets ffmpeg needs to probe a live raw
      // H.264 pipe, so no decoded frame is emitted until EOF. The screenrecord stream already
      // arrives without a container-level buffer; low_delay is sufficient on this path.
      "-flags", "+low_delay",
      // Raw H.264 has no container metadata to probe. The defaults may wait for megabytes of
      // input before starting the decoder; SPS/PPS at the head of screenrecord's Annex-B stream
      // is enough to identify it immediately.
      "-probesize", "32",
      "-analyzeduration", "0",
      // Tell ffmpeg the input is roughly 25 fps. This assigns each decoded frame a constant-rate
      // PTS, which the image2pipe muxer needs to emit frames at all (without it, timestamps are
      // unspecified → "Output file is empty, nothing was encoded"). Do NOT also pass
      // `-use_wallclock_as_timestamps 1`: screenrecord delivers a frame's bytes in a tight burst,
      // so timestamping by arrival wallclock collapses a burst into near-identical PTS and the
      // muxer withholds those frames until the pipe closes — a live viewer then sees nothing until
      // the stream ends. The CFR hint alone keeps frames flowing while the pipe stays open.
      "-framerate", "25",
      "-f", "h264",
      "-i", "pipe:0",
      // Encode every decoded frame as JPEG and write the concatenated JPEGs to stdout.
      "-an",
      // screenrecord emits limited-range (`tv`) YUV, but ffmpeg 8's mjpeg encoder rejects
      // non-full-range YUV with `ff_frame_thread_encoder_init failed` unless we either
      // lower compliance or explicitly convert to full-range JPEG-friendly YUV. The
      // format filter is the cleaner fix and what the JPEG spec actually expects.
      "-vf", "format=yuvj420p",
      "-c:v", "mjpeg",
      "-q:v", jpegQ.toString(),
      "-f", "image2pipe",
      "-",
    )
    pb.redirectErrorStream(false)
    val proc = pb.start()
    // Drain stderr in a daemon thread so a wedged stderr pipe can't stall the subprocess.
    // Logs each line so a misconfigured ffmpeg (missing codec, format-detection failure,
    // bad input) surfaces in the daemon log instead of silently breaking the live stream.
    // Prior version discarded stderr entirely, which made debugging "ffmpeg produces zero
    // frames" essentially impossible — frames just never arrived and the cause was opaque.
    Thread(
      {
        try {
          proc.errorStream.bufferedReader().use { reader ->
            reader.lineSequence().forEach { line ->
              Console.log("[LiveFrameConsumer/ffmpeg] $line")
            }
          }
        } catch (_: Exception) { /* expected on close */ }
      },
      "live-frame-ffmpeg-stderr",
    ).apply { isDaemon = true; start() }
    Console.log("[LiveFrameConsumer] spawned ffmpeg pid=${proc.pid()} for live JPEG decode")
    return proc
  }

  private fun pumpTeeIntoFfmpeg(p: Pipeline) {
    val cons = p.consumer
    val sink: OutputStream = p.process.outputStream
    val buf = ByteArray(64 * 1024)
    val alivePingGate = AlivePingGate(FEED_ALIVE_PING_INTERVAL_MS)
    // One-shot: the liveness signal was once silently dead for a whole release; a single log
    // line makes "pings are flowing" verifiable from the daemon log.
    var firstPingLogged = false
    try {
      while (!stopped.get()) {
        val n = cons.read(buf)
        when {
          n > 0 -> {
            try {
              sink.write(buf, 0, n)
              sink.flush()
            } catch (e: IOException) {
              // ffmpeg died or stop() closed the pipe. The stdout pump usually sees the death
              // first (EOF fires the instant the process dies); this covers a race where the
              // write fails before that EOF is observed.
              if (!stopped.get()) onSidecarExit(p)
              return
            }
          }
          n == H264Tee.READ_RESULT_RESTART -> {
            // A new screenrecord subprocess is feeding the tee. The mjpeg decoder can keep
            // chewing — the new stream begins with its own SPS/PPS, which ffmpeg parses
            // inline. We don't need to restart ffmpeg.
          }
          n == H264Tee.READ_RESULT_DETACHED -> return
          n == 0 -> Thread.sleep(IDLE_SLEEP_MS)
        }
        // Reaching here means the attachment is alive and being drained (a detached tee or a
        // dead ffmpeg pipe returned above) — including the n == 0 idle case, which is exactly
        // the state a static screen leaves us in.
        if (onFeedAlive != null && alivePingGate.tryPing(System.nanoTime() / NANOS_PER_MILLISECOND)) {
          if (!firstPingLogged) {
            firstPingLogged = true
            Console.log("[LiveFrameConsumer] feed-alive pings active (every ${FEED_ALIVE_PING_INTERVAL_MS}ms)")
          }
          runCatching { onFeedAlive?.invoke() }
        }
      }
    } finally {
      runCatching { sink.flush() }
      runCatching { sink.close() }
    }
  }

  private fun pumpFfmpegIntoFrames(p: Pipeline) {
    val input: InputStream = p.process.inputStream
    val splitter = JpegFrameSplitter()
    val readBuf = ByteArray(64 * 1024)
    val emissionGate = FrameEmissionGate(MAX_IDENTICAL_FRAME_SILENCE_MS)
    try {
      while (!stopped.get()) {
        val n = try {
          input.read(readBuf)
        } catch (_: IOException) {
          -1
        }
        if (n <= 0) break
        splitter.feed(readBuf, 0, n) { jpeg ->
          p.producedFrame.set(true)
          val hash = sha256(jpeg)
          val emission = emissionGate.admit(hash, System.nanoTime() / NANOS_PER_MILLISECOND)
          if (emission != null) {
            try {
              onFrame(jpeg, emission == FrameEmissionGate.Emission.CONTENT_CHANGE)
            } catch (e: Exception) {
              Console.log("[LiveFrameConsumer] onFrame callback threw: ${e.message}")
            }
          }
        }
      }
    } catch (e: Exception) {
      Console.log("[LiveFrameConsumer] frame-pump exited: ${e.message}")
    }
    // EOF on the sidecar's stdout while this consumer is live means the sidecar died (observed
    // in the wild: CI agents under memory pressure kill ffmpeg outright, with no exit message).
    // All remaining buffered frames were drained above, so recovery loses nothing.
    if (!stopped.get()) onSidecarExit(p)
  }

  /**
   * Reassembles whole JPEG frames from arbitrarily chunked byte input. JPEG files begin with
   * the SOI marker `FF D8` and end with the EOI marker `FF D9`. ffmpeg's `image2pipe` muxer
   * emits exactly one of these per decoded frame back-to-back, so a streaming splitter just
   * needs to track "are we currently inside a frame" and emit the buffer when EOI arrives.
   *
   * Inlined here (rather than reusing a third-party MJPEG parser) because the logic is small,
   * non-blocking, and easy to audit. Test seam: see [feedForTest].
   */
  internal class JpegFrameSplitter {
    private val current = ByteArrayOutputStream()
    private var inFrame = false
    // Tracks the most recent byte seen, used to detect a marker that straddles a feed boundary
    // (the FF in one feed, the D8/D9 in the next).
    private var prevByte: Int = -1

    fun feed(src: ByteArray, off: Int, len: Int, emit: (ByteArray) -> Unit) {
      var i = off
      val end = off + len
      while (i < end) {
        val b = src[i].toInt() and 0xff
        if (prevByte == 0xff && b == 0xd8) {
          // SOI: start a fresh frame. Drop any stray bytes accumulated before the marker.
          current.reset()
          current.write(0xff)
          current.write(0xd8)
          inFrame = true
          prevByte = -1
          i++
          continue
        }
        if (inFrame) {
          if (prevByte == 0xff && b == 0xd9) {
            // EOI: finish the frame.
            current.write(0xd9)
            val bytes = current.toByteArray()
            current.reset()
            inFrame = false
            prevByte = -1
            emit(bytes)
            i++
            continue
          }
          current.write(b)
        }
        prevByte = b
        i++
      }
    }

    /** Test entry point: feeds bytes and returns the list of completed frames. */
    internal fun feedForTest(src: ByteArray): List<ByteArray> {
      val out = mutableListOf<ByteArray>()
      feed(src, 0, src.size) { out.add(it) }
      return out
    }
  }

  /**
   * Content-dedup policy for the live wire. Changed frames pass immediately; an unchanged
   * screen passes only after [maxSilenceMillis] so the browser's stall watchdog keeps seeing
   * proof of life without paying full frame rate for a static display.
   */
  internal class FrameEmissionGate(private val maxSilenceMillis: Long) {
    enum class Emission { CONTENT_CHANGE, HEARTBEAT }

    private var lastSentHash: ByteArray? = null
    private var lastSentAtMillis: Long = Long.MIN_VALUE

    /** Returns how this frame should be emitted, or null to suppress it. */
    fun admit(hash: ByteArray, nowMillis: Long): Emission? {
      val changed = !hash.contentEquals(lastSentHash)
      val heartbeatDue =
        lastSentAtMillis == Long.MIN_VALUE || nowMillis - lastSentAtMillis >= maxSilenceMillis
      if (!changed && !heartbeatDue) return null
      lastSentHash = hash.copyOf()
      lastSentAtMillis = nowMillis
      return if (changed) Emission.CONTENT_CHANGE else Emission.HEARTBEAT
    }
  }

  /**
   * Throttle for the drain loop's [onFeedAlive] pings. The "never pinged" state is a separate
   * flag, NOT an in-domain sentinel value: the clock is `System.nanoTime()`-derived, whose
   * origin (and sign) is arbitrary, so any sentinel arithmetic on it risks overflow — a
   * `now - Long.MIN_VALUE` compare silently disabled the ping entirely on positive-origin
   * clocks.
   */
  internal class AlivePingGate(private val intervalMillis: Long) {
    private var hasPinged = false
    private var lastPingAtMillis = 0L

    /** True when a ping is due (always on the first call); records the ping time when it is. */
    fun tryPing(nowMillis: Long): Boolean {
      if (hasPinged && nowMillis - lastPingAtMillis < intervalMillis) {
        return false
      }
      hasPinged = true
      lastPingAtMillis = nowMillis
      return true
    }
  }

  companion object {
    private const val IDLE_SLEEP_MS: Long = 2L
    private const val MAX_IDENTICAL_FRAME_SILENCE_MS: Long = 1_000L

    /**
     * How many consecutive sidecar deaths without a single decoded frame are tolerated: reaching
     * this count gives up, so the deaths before it are respawned (an ffmpeg that can't survive
     * startup — bad binary, unresolvable input — would otherwise crash-loop). A sidecar that
     * decoded at least one frame resets the budget, so a long-lived stream survives any number
     * of isolated external kills.
     */
    internal const val MAX_CONSECUTIVE_SILENT_DEATHS: Int = 3

    /**
     * Flat pause before handling a sidecar death, rate-capping respawn storms. Matches
     * [H264Tee]'s producer-respawn delay; no escalating backoff here because
     * [MAX_CONSECUTIVE_SILENT_DEATHS] already bounds the only unbounded-loop shape (a decoder
     * that never produces a frame).
     */
    internal const val SIDECAR_RESPAWN_PAUSE_MS: Long = 250L
    /**
     * Subscribers treat prolonged silence (no frames AND no pings) as a dead pipeline — their
     * stall thresholds are calibrated to a sub-second idle ping cadence. Keep this interval
     * well below any such threshold (seconds) or quiet-but-healthy feeds read as stalled.
     */
    private const val FEED_ALIVE_PING_INTERVAL_MS: Long = 500L
    private const val NANOS_PER_MILLISECOND: Long = 1_000_000L

    /**
     * 20 MB ring at 4 Mbps screenrecord ≈ ~40 s of slack. Generous because if the live
     * consumer falls behind, ffmpeg's mjpeg decoder simply outputs older frames — the
     * resulting JPEG stream is still self-consistent. Dropping bytes corrupts decoding, so
     * we'd rather have headroom than chase the absolute minimum.
     */
    private const val DEFAULT_RING_BUFFER_BYTES: Int = 20 * 1024 * 1024

    private fun sha256(bytes: ByteArray): ByteArray =
      MessageDigest.getInstance("SHA-256").digest(bytes)
  }
}
