package xyz.block.trailblaze.capture.video

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import xyz.block.trailblaze.util.Console

/**
 * Drains an [H264Tee.Consumer] and writes the raw H.264 NAL stream to a sequence of
 * `video.NNN.h264` files in the session directory, one file per producer generation.
 *
 * On [stop], the segments are stitched into a single `video.mp4` via `ffmpeg -f concat -c copy`
 * — a no-reencode mux, so the operation is fast and lossless. The resulting `.mp4` is written
 * with `+faststart+frag_keyframe` so a daemon crash mid-session still leaves a playable file.
 *
 * Compared to the previous on-device chain, this approach:
 *  - Wastes no device storage. Bytes flow straight from the device's wire protocol into a
 *    host-side file.
 *  - Eliminates the brittle "kick over to a new file 10s before the 3-min cap" timer. The tee
 *    surfaces the boundary deterministically via [H264Tee.READ_RESULT_RESTART].
 *  - Cooperates with the live viewer's consumer on the same tee, sharing the single
 *    `screenrecord` invocation rather than fighting for the hardware encoder.
 *
 * **Drop budget.** Bytes dropped here mean the recording is corrupt — unlike the live viewer,
 * an MP4 with missing NAL units can't gracefully recover. The default ring buffer (50 MB,
 * ~100 s of 4 Mbps H.264) is sized to never overflow under normal conditions. If [droppedBytes]
 * goes non-zero, the consumer logs loudly with the session directory so operators can identify
 * which recording is suspect.
 */
internal class MuxToMp4Consumer(
  private val sessionDir: File,
  private val tee: H264Tee,
  private val ringBufferBytes: Int = DEFAULT_RING_BUFFER_BYTES,
  /** Test seam: swap out the ffmpeg binary path. */
  private val ffmpegBinary: String = "ffmpeg",
) {

  private var consumer: H264Tee.Consumer? = null
  private var drainThread: Thread? = null

  // Synchronized access for segment list — the drain thread appends, stop() snapshots.
  private val segmentsLock = Any()
  private val segments = mutableListOf<File>()

  // Current segment file output, owned by the drain thread.
  private var currentSegment: File? = null
  private var currentSink: OutputStream? = null
  private var nextSegmentIndex = 0

  /** Starts the drain thread. Must be called once. */
  fun start() {
    consumer = tee.attach(ringBufferBytes)
    rollToNextSegment()
    drainThread = Thread(::runDrainLoop, "h264-tee-mp4-consumer").apply {
      isDaemon = true
      start()
    }
  }

  /**
   * Stops the drain, closes any open segment, concats segments into `video.mp4`, and returns
   * the merged file (or the single segment promoted to `video.mp4` if there was only one).
   * Returns `null` if no bytes were captured.
   *
   * **Drain-to-completion semantics.** We detach the consumer first, then join the drain
   * thread. The drain loop is structured to keep reading until it sees
   * [H264Tee.READ_RESULT_DETACHED] — which only fires once the ring buffer is fully drained,
   * not when [Consumer.detach] is first called. That means any bytes still buffered at stop
   * time get written to the current segment file before the loop exits, so the final segment
   * isn't truncated. Review feedback on PR #3021 caught a previous version that exited the
   * drain loop on a separate `stopRequested` flag *before* the buffer was drained, silently
   * losing the last frame or two and corrupting the MP4 tail.
   */
  fun stop(): File? {
    val drainer = drainThread
    val cons = consumer
    cons?.detach()
    drainer?.join(STOP_JOIN_TIMEOUT_MS)
    // Best-effort close of any segment still open if the drain thread didn't get there.
    closeCurrentSegment()

    val snapshot = synchronized(segmentsLock) { segments.toList() }
    if (snapshot.isEmpty() || snapshot.all { it.length() == 0L }) {
      Console.log("[MuxToMp4Consumer] no segments captured in ${sessionDir.absolutePath}")
      return null
    }

    val droppedBytes = cons?.droppedBytes ?: 0L
    if (droppedBytes > 0) {
      Console.log(
        "[MuxToMp4Consumer] WARNING session=${sessionDir.name} dropped $droppedBytes bytes — " +
          "video.mp4 may be corrupt; increase ring buffer if this recurs",
      )
    }

    val nonEmpty = snapshot.filter { it.length() > 0 }
    val output = File(sessionDir, "video.mp4")
    return concatSegments(nonEmpty, output)
  }

  private fun runDrainLoop() {
    val buf = ByteArray(DRAIN_CHUNK_BYTES)
    val cons = consumer ?: return
    // Drain until the consumer reports DETACHED. detach() flips the flag immediately, but
    // [H264Tee.Consumer.read] only returns DETACHED *after* the ring buffer is empty — so
    // this loop naturally flushes any in-flight bytes before exiting on stop(). No separate
    // stop flag is needed; detach is the only exit signal we honor.
    while (true) {
      val n = cons.read(buf)
      when {
        n > 0 -> {
          val sink = currentSink
          if (sink != null) {
            try {
              sink.write(buf, 0, n)
            } catch (e: Exception) {
              Console.log("[MuxToMp4Consumer] write to ${currentSegment?.name} failed: ${e.message}")
            }
          }
        }
        n == H264Tee.READ_RESULT_RESTART -> {
          Console.log("[MuxToMp4Consumer] producer restart — rolling to new segment")
          rollToNextSegment()
        }
        n == H264Tee.READ_RESULT_DETACHED -> return
        n == 0 -> {
          // No bytes yet; sleep briefly so we don't busy-spin the CPU.
          Thread.sleep(IDLE_SLEEP_MS)
        }
      }
    }
  }

  private fun rollToNextSegment() {
    closeCurrentSegment()
    val idx = nextSegmentIndex++
    val file = File(sessionDir, "video.${"%03d".format(idx)}.h264")
    sessionDir.mkdirs()
    val sink = BufferedOutputStream(FileOutputStream(file))
    currentSegment = file
    currentSink = sink
    synchronized(segmentsLock) { segments.add(file) }
  }

  private fun closeCurrentSegment() {
    val sink = currentSink
    if (sink != null) {
      runCatching { sink.flush() }
      runCatching { sink.close() }
    }
    currentSink = null
    currentSegment = null
  }

  /**
   * Run `ffmpeg -f concat -safe 0 -i list.txt -c copy -movflags +faststart+frag_keyframe`
   * to produce a playable MP4 with no re-encode. If ffmpeg is missing or the concat fails,
   * fall back to renaming the first segment so the caller still gets a file (the existing
   * sprite-extractor path can handle a raw H.264 file via ImageIO, though seeking won't work).
   */
  private fun concatSegments(segments: List<File>, output: File): File? {
    if (segments.size == 1) {
      // Single segment is the fast path — wrap it in an MP4 container so callers (sprite
      // extractor, UI <video>) see a uniform input. Still -c copy, so no re-encode.
      return wrapSingleSegment(segments[0], output) ?: segments[0]
    }
    val listFile = File(sessionDir, "video.segments.txt")
    listFile.writeText(segments.joinToString("\n") { "file '${it.absolutePath}'" })
    val result =
      runSubprocessWithTimeout(
        command =
          listOf(
            ffmpegBinary,
            "-y",
            "-f", "concat",
            "-safe", "0",
            "-i", listFile.absolutePath,
            "-c", "copy",
            "-movflags", "+faststart+frag_keyframe",
            output.absolutePath,
          ),
        timeoutSeconds = FFMPEG_TIMEOUT_SECONDS,
      )
    if (result == null) {
      Console.log(
        "[MuxToMp4Consumer] ffmpeg concat did not start or timed out after ${FFMPEG_TIMEOUT_SECONDS}s"
      )
      return segments.firstOrNull()
    }
    if (result.exitCode != 0 || output.length() == 0L) {
      Console.log(
        "[MuxToMp4Consumer] ffmpeg concat failed: exit=${result.exitCode}\n" +
          sanitizeSubprocessOutputForLog(result.output)
      )
      return segments.firstOrNull()
    }
    // Concat succeeded — clean up the per-segment files and the list.
    segments.forEach { it.delete() }
    listFile.delete()
    return output
  }

  /**
   * Wrap a single raw H.264 segment in an MP4 container via `-c copy`. Same fast-start flags
   * as the multi-segment path so the resulting file has identical properties regardless of
   * how many segments produced it.
   */
  private fun wrapSingleSegment(segment: File, output: File): File? {
    val result =
      runSubprocessWithTimeout(
        command =
          listOf(
            ffmpegBinary,
            "-y",
            "-i", segment.absolutePath,
            "-c", "copy",
            "-movflags", "+faststart+frag_keyframe",
            output.absolutePath,
          ),
        timeoutSeconds = FFMPEG_TIMEOUT_SECONDS,
      )
    if (result == null) {
      Console.log("[MuxToMp4Consumer] ffmpeg wrap did not start or timed out")
      return null
    }
    if (result.exitCode != 0 || output.length() == 0L) {
      Console.log(
        "[MuxToMp4Consumer] ffmpeg wrap failed: exit=${result.exitCode}\n" +
          sanitizeSubprocessOutputForLog(result.output)
      )
      return null
    }
    segment.delete()
    return output
  }

  companion object {
    /**
     * 50 MB of ring buffer at 4 Mbps screenrecord ≈ 100 s of slack. Sized for "consumer never
     * drops under normal conditions" — anything dropped here means MP4 corruption.
     */
    private const val DEFAULT_RING_BUFFER_BYTES: Int = 50 * 1024 * 1024

    private const val DRAIN_CHUNK_BYTES: Int = 64 * 1024
    private const val IDLE_SLEEP_MS: Long = 5L
    private const val STOP_JOIN_TIMEOUT_MS: Long = 2_000L
    private const val FFMPEG_TIMEOUT_SECONDS: Long = 120L
  }
}
