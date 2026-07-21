package xyz.block.trailblaze.capture.video

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.util.Console

/**
 * Per-device fan-out for a single `adb exec-out screenrecord --output-format=h264` stream.
 *
 * **Why one tee per device?** Most Android devices serialize the hardware H.264 encoder — a
 * second `screenrecord` invocation on the same device blocks or fails. Both the live `/devices`
 * viewer and the trail-run MP4 capture want screen video, so they share a single producer here
 * and consume independently. If callers ask for different encoder parameters (size/bitrate), the
 * tee logs a warning and uses whichever attached first — last-writer-wins would silently swap
 * the live viewer's resolution mid-stream.
 *
 * **Mid-stream joins.** The tee caches the most recent keyframe (SPS+PPS+IDR) and seeds it into
 * every consumer that attaches while a producer is already running. screenrecord emits an IDR
 * essentially only at stream start, so a late joiner would otherwise receive only P-slices that
 * reference parameter sets it never saw — undecodable. This is what lets an MP4 recording that
 * starts while the live viewer already holds the tee produce a valid file, and lets a browser
 * WebCodecs decoder configure without waiting for a fresh IDR that may never come on a static
 * screen. The first consumer is not seeded: it receives the live stream head, which already
 * begins with SPS/PPS/IDR.
 *
 * **Consumers and back-pressure.** Each consumer ([Consumer]) gets a non-blocking ring buffer
 * sized at construction time. A slow consumer drops *bytes* (not whole frames — the H.264 NAL
 * stream is self-synchronizing on the next IDR / start code, so a downstream decoder can
 * resync) and the rest of the system keeps moving. The reader thread never blocks on a slow
 * consumer.
 *
 * **Restarts.** screenrecord caps invocations at 3 minutes on Android < 11 (API < 30); on
 * Android 11+, `--time-limit 0` lets one invocation run indefinitely. A capped or unexpectedly
 * exited subprocess is restarted. All consumers receive [RestartSignal] *before* the next
 * subprocess's SPS/PPS arrives. The MP4 consumer rolls to a new segment file on that signal; live
 * consumers reset their parsers and continue with the new SPS/PPS.
 *
 * **Lifecycle.** Ref-counted via [attach] / [Consumer.detach]. The first attach spawns the
 * subprocess and reader thread; the last detach reaps both. The instance is reusable across
 * its lifetime — a fresh attach after a detach-to-zero starts a new subprocess.
 *
 * **ADB env vars.** `ANDROID_ADB_SERVER_PORT` and `ADB_SERVER_SOCKET` are honored to match the
 * rest of the daemon. They're read once on first use (matching `AndroidHostAdbUtils`'s
 * one-shot env semantics — see CLAUDE.md). Restart the daemon to pick up changes.
 *
 * Not thread-safe across attach / detach for the same consumer object; safe across consumers.
 */
class H264Tee internal constructor(
  internal val deviceId: TrailblazeDeviceId,
  private val videoSize: String,
  private val bitRate: String,
  /**
   * Test seam: spawns the producer subprocess. Default uses `adb exec-out screenrecord`. Tests
   * can pass a fixed [InputStream] of canned bytes plus a no-op closer.
   */
  private val producerFactory: ProducerFactory = AdbScreenrecordProducerFactory,
  /**
   * Test seam: lookup of Android SDK level. Default queries the device; tests inject 30+ to
   * exercise the unlimited-time-limit path and < 30 to exercise the restart-on-exit chain.
   */
  private val sdkLevelProvider: () -> Int = { sdkLevelFromDevice(deviceId) },
  /** Test seam for canned finite streams. Production always recovers an unexpected EOF. */
  private val restartOnUnexpectedExit: Boolean = true,
) {

  // Synchronized on this Object for refCount/state transitions only — never held across a
  // subprocess wait or a network read. Consumers are tracked separately in a concurrent map so
  // the producer thread can iterate them without holding our lock.
  private val refCountLock = Any()
  private var refCount = 0
  private var producerHandle: ProducerHandle? = null
  private var readerThread: Thread? = null

  private val consumers = ConcurrentHashMap<Long, Consumer>()
  private val nextConsumerId = AtomicLong(0)

  // Set true when the last consumer detaches; the reader thread checks this to know that an
  // exit is intentional rather than a screenrecord 3-min cap that needs a restart.
  private val shuttingDown = AtomicBoolean(false)

  // Latest decodable keyframe (SPS+PPS+IDR in Annex-B) seen on the current producer session,
  // used to seed a consumer that attaches mid-stream. screenrecord emits an IDR essentially only
  // at stream start, so without this a late joiner — e.g. the MP4 recording that begins while the
  // live viewer already holds the tee open — would receive only P-slices referencing parameter
  // sets it never saw, i.e. an undecodable stream. Written by the reader thread and by
  // startProducer (which runs before the reader thread starts), read under refCountLock in attach;
  // volatile guarantees the late joiner sees a complete array reference. The splitter is touched
  // only by the reader thread (and by startProducer before that thread exists). Ordering
  // invariant: the reader updates this cache BEFORE fanning a chunk out, so once any consumer
  // has observed a keyframe, every later attach is seeded with it (or a fresher one).
  @Volatile private var cachedKeyframe: ByteArray? = null
  private val gopSplitter = AnnexBAccessUnitSplitter()

  /**
   * Attach a new consumer with the given ring-buffer capacity. If this is the first consumer,
   * the producer subprocess is spawned. Returns a [Consumer] handle the caller drains via
   * [Consumer.read] and releases via [Consumer.detach].
   *
   * **Failure semantics.** Producer spawn (the underlying `adb exec-out screenrecord`
   * invocation) can throw — adb missing, device offline, encoder unavailable. In that case
   * we leave the tee in its no-consumers state and rethrow, so a future attach is treated
   * as the first attach again and can retry the spawn. The previous implementation
   * incremented [refCount] and inserted the consumer *before* spawning, leaving the tee
   * permanently wedged on spawn failure — review feedback on PR #3021 caught it.
   */
  fun attach(ringBufferBytes: Int): Consumer {
    val consumer = Consumer(
      id = nextConsumerId.getAndIncrement(),
      ringBufferCapacity = ringBufferBytes,
      tee = this,
    )
    synchronized(refCountLock) {
      // Seed a mid-stream joiner with the last keyframe so its decoder has parameter sets and an
      // IDR to start from. Only when a producer is already running (refCount > 0) — the first
      // consumer receives the live stream head, which begins with SPS/PPS/IDR, and startProducer
      // clears any stale cache. The seed is written before the consumer enters the map so it
      // precedes any live fanOut bytes for this consumer. Trade-off: the seeded keyframe predates
      // the following live P-slices, so the very first GOP can show a brief artifact until the
      // next IDR — decodable-with-glitch beats the undecodable stream a late joiner got before.
      if (refCount > 0) cachedKeyframe?.let { consumer.seed(it) }
      // Register the consumer in the map BEFORE starting the producer/reader thread. The
      // reader thread doesn't take refCountLock when iterating consumers in fanOut(); if we
      // started it first, it could read the very first chunk and fan it out to an empty map
      // before this thread finished inserting the consumer. Inserting first guarantees the
      // first attach never misses the start of the stream. (Production adb subprocesses take
      // milliseconds to emit their first byte and the race rarely won there; canned-byte
      // unit tests EOF in microseconds and lost the race deterministically.)
      consumers[consumer.id] = consumer
      refCount++
      if (refCount == 1) {
        shuttingDown.set(false)
        try {
          startProducer()
        } catch (e: Exception) {
          // Producer didn't start — revert flags and rethrow without registering the
          // consumer or incrementing the ref-count. The tee returns to a clean "no
          // consumers, no producer" state so the next attach retries the spawn.
          consumers.remove(consumer.id)
          refCount--
          shuttingDown.set(true)
          throw e
        }
      }
    }
    return consumer
  }

  internal fun detach(consumer: Consumer) {
    consumers.remove(consumer.id)
    consumer.markDetached()
    synchronized(refCountLock) {
      refCount--
      if (refCount == 0) {
        shuttingDown.set(true)
        stopProducer()
      }
    }
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Producer lifecycle (called under refCountLock)
  // ────────────────────────────────────────────────────────────────────────────

  private fun startProducer() {
    // Fresh producer session — discard any keyframe cached from a previous session so the first
    // consumer isn't seeded with a stale one. Safe to touch the splitter here: startProducer runs
    // under refCountLock on the first attach, before the reader thread that owns it exists.
    cachedKeyframe = null
    gopSplitter.reset()
    val sdk = runCatching { sdkLevelProvider() }.getOrDefault(0)
    val unlimited = sdk >= ANDROID_R_SDK
    Console.log(
      "[H264Tee] starting screenrecord for ${deviceId.instanceId} " +
        "(size=$videoSize bitRate=$bitRate sdk=$sdk unlimited=$unlimited)",
    )
    val handle = producerFactory.spawn(deviceId, videoSize, bitRate, unlimited)
    producerHandle = handle
    readerThread = Thread(
      {
        runReaderLoop(handle, unlimited = unlimited)
      },
      "h264-tee-reader-${deviceId.instanceId}",
    ).apply {
      isDaemon = true
      start()
    }
  }

  private fun stopProducer() {
    val handle = producerHandle
    producerHandle = null
    runCatching { handle?.close() }
    // The reader thread will see EOF on the input stream, observe shuttingDown=true, and exit
    // without restarting. We don't join here because stopProducer is called under refCountLock
    // and we don't want to hold a lock waiting on I/O. The thread is a daemon so it can't
    // block JVM exit.
    readerThread = null
  }

  private fun runReaderLoop(initialHandle: ProducerHandle, unlimited: Boolean) {
    var handle: ProducerHandle = initialHandle
    val buf = ByteArray(READ_CHUNK_BYTES)
    // Consecutive generations that spawned but delivered zero bytes. A device whose screenrecord
    // spawns then instantly EOFs (encoder unavailable, transport reset on every attempt) would
    // otherwise respawn at a fixed 250ms — ~4 adb invocations/sec indefinitely. Escalate the delay
    // on repeated empty exits; a generation that actually delivers bytes (a healthy stream, or a
    // normal 3-min-cap restart) resets it, so normal restarts keep the fast 250ms turnaround.
    var consecutiveEmptyExits = 0
    while (true) {
      var bytesThisGeneration = 0L
      try {
        while (true) {
          val n = try {
            handle.input.read(buf)
          } catch (_: IOException) {
            -1
          }
          if (n <= 0) break
          bytesThisGeneration += n
          // Cache before fan-out: once any consumer has observed a byte, a consumer attaching
          // afterwards is guaranteed a seed at least as fresh as that byte. The reverse order
          // left a window where a joiner missed both the fanned-out keyframe and the seed —
          // an undecodable stream. (The joiner can now be seeded with a keyframe from a chunk
          // it also receives live — a duplicated keyframe is decodable, a missing one is not.)
          updateKeyframeCache(buf, n)
          fanOut(buf, n)
        }
      } catch (e: Exception) {
        Console.log("[H264Tee] reader thread for ${deviceId.instanceId}: ${e.message}")
      }
      if (shuttingDown.get() || (unlimited && !restartOnUnexpectedExit)) {
        // Don't close the handle here — stopProducer() (called from the detach path) handles
        // it, and on EOF a second close would be redundant. If we exited because the
        // subprocess died on its own with no restart policy, leaving close to the next
        // stopProducer (or never) is fine; the OS reaped the process when read() saw EOF.
        Console.log("[H264Tee] producer exited (shuttingDown=${shuttingDown.get()}); reader stopping")
        return
      }
      // Pre-Android-11 screenrecord normally reaches its 3-minute cap. Newer screenrecord is
      // unlimited, so any EOF there is unexpected (adb transport reset, encoder process death,
      // device sleep, etc.) and must recover too; otherwise subscribers receive heartbeats but
      // never another frame until every viewer disconnects and reconnects.
      runCatching { handle.close() }
      // Flush the trailing NAL of the ending generation into the cache, then reset the splitter so
      // the next generation parses from its own SPS/PPS. cachedKeyframe is intentionally kept — a
      // consumer attaching during the restart gap still gets the last good keyframe until the new
      // generation delivers a fresh one.
      finishKeyframeGeneration()
      Console.log(
        "[H264Tee] screenrecord exited " +
          (if (unlimited) "unexpectedly" else "at its time limit") +
          "; signaling restart and respawning",
      )
      broadcastRestart()
      // Exponential backoff on repeated empty exits, capped; a productive generation resets it.
      consecutiveEmptyExits = if (bytesThisGeneration > 0) 0 else consecutiveEmptyExits + 1
      val backoffMillis = minOf(
        RESPAWN_DELAY_MILLIS shl minOf(consecutiveEmptyExits, RESPAWN_MAX_BACKOFF_SHIFT),
        RESPAWN_MAX_DELAY_MILLIS,
      )
      if (consecutiveEmptyExits > 0) {
        Console.log(
          "[H264Tee] ${deviceId.instanceId} produced no bytes " +
            "($consecutiveEmptyExits in a row); backing off ${backoffMillis}ms before respawn",
        )
      }
      try {
        Thread.sleep(backoffMillis)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return
      }
      if (shuttingDown.get()) return
      handle = try {
        producerFactory.spawn(deviceId, videoSize, bitRate, unlimited = unlimited)
      } catch (e: Exception) {
        Console.log("[H264Tee] respawn failed: ${e.message}; reader stopping")
        return
      }
      synchronized(refCountLock) {
        // If the last consumer detached while we were respawning, throw the new handle away.
        if (shuttingDown.get()) {
          runCatching { handle.close() }
          return
        }
        producerHandle = handle
      }
    }
  }

  private fun fanOut(buf: ByteArray, len: Int) {
    // ConcurrentHashMap.values() is weakly consistent — fine: a consumer added mid-iteration
    // missing the very next chunk just means its first bytes are the chunk after attach, not
    // this one. A consumer removed mid-iteration is already detached and discards writes.
    for (consumer in consumers.values) {
      consumer.writeBytes(buf, 0, len)
    }
  }

  private fun broadcastRestart() {
    for (consumer in consumers.values) {
      consumer.signalRestart()
    }
  }

  /**
   * Feeds the just-read chunk into the keyframe splitter and records the newest IDR access unit.
   * The splitter prepends the retained SPS/PPS to each IDR picture, so [cachedKeyframe] is a
   * self-contained, decodable start point. Runs only on the reader thread.
   */
  private fun updateKeyframeCache(buf: ByteArray, len: Int) {
    gopSplitter.feed(buf, 0, len) { accessUnit ->
      if (accessUnit.isKeyFrame) cachedKeyframe = accessUnit.bytes
    }
  }

  /** Flushes the ending generation's pending picture into the cache and resets the splitter. */
  private fun finishKeyframeGeneration() {
    gopSplitter.finish { accessUnit ->
      if (accessUnit.isKeyFrame) cachedKeyframe = accessUnit.bytes
    }
    gopSplitter.reset()
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Consumer-facing API
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * A single subscriber to the tee. Bytes are written into one of a small queue of per-generation
   * ring buffers by the producer thread; the consumer drains via [read]. Each producer-subprocess
   * lifetime is one *generation*. When the producer restarts (e.g. screenrecord hits the 3-min
   * cap on Android < 11), the tee opens a new generation and the consumer observes the boundary
   * via a single [READ_RESULT_RESTART] return from [read] — bytes never bleed across the boundary.
   *
   * Slow consumers drop bytes silently from the producer's perspective; the consumer side
   * observes a non-zero [droppedBytes] counter and a rate-limited log.
   */
  class Consumer internal constructor(
    internal val id: Long,
    private val ringBufferCapacity: Int,
    private val tee: H264Tee,
  ) {
    // One ring buffer per generation. Producer writes to the back; consumer reads from the
    // front. When the front is exhausted AND there's a later generation behind it, the consumer
    // surfaces READ_RESULT_RESTART and rolls forward. A consumer that hasn't drained the front
    // by the time the next generation starts will see the rest of the old generation's bytes
    // first, then the boundary signal, then the new generation's bytes — in order.
    private val generations = ArrayDeque<RingBuffer>().apply { addLast(RingBuffer(ringBufferCapacity)) }
    private val generationsLock = Any()
    private val detached = AtomicBoolean(false)
    private val droppedBytesTotal = AtomicLong(0)
    private val lastLogMillis = AtomicLong(0)

    /** Total bytes silently dropped because the back-generation ring buffer was full. */
    val droppedBytes: Long get() = droppedBytesTotal.get()

    /**
     * Reads up to `dest.size` bytes from the consumer's current generation. Returns the number
     * of bytes read; 0 means "no bytes available right now, try again". Returns
     * [READ_RESULT_RESTART] (== -2) when the current generation is exhausted AND a new
     * generation has begun — the caller should close its current downstream sink (e.g. roll to
     * a new MP4 segment), then call [read] again to receive bytes from the new subprocess.
     * Returns [READ_RESULT_DETACHED] (== -1) after [detach] has been called and every
     * generation is drained.
     */
    fun read(dest: ByteArray): Int {
      synchronized(generationsLock) {
        while (generations.isNotEmpty()) {
          val front = generations.first()
          val n = front.read(dest)
          if (n > 0) return n
          // Front is empty. If there's a generation behind it, signal restart and roll forward;
          // exactly one READ_RESULT_RESTART is emitted per producer transition.
          if (generations.size > 1) {
            generations.removeFirst()
            return READ_RESULT_RESTART
          }
          // Only one generation, and it's empty.
          if (detached.get()) return READ_RESULT_DETACHED
          return 0
        }
        return if (detached.get()) READ_RESULT_DETACHED else 0
      }
    }

    /** Detach this consumer from the tee, releasing its slot. Idempotent. */
    fun detach() {
      if (detached.compareAndSet(false, true)) {
        tee.detach(this)
      }
    }

    internal fun markDetached() {
      detached.set(true)
    }

    /**
     * Pre-loads bytes into this consumer's current generation before it starts receiving live
     * fanOut. Used by [attach] to seed a mid-stream joiner with the cached keyframe so its
     * downstream decoder has parameter sets and an IDR to start from.
     */
    internal fun seed(bytes: ByteArray) {
      synchronized(generationsLock) {
        generations.last().writeOrDrop(bytes, 0, bytes.size)
      }
    }

    internal fun writeBytes(src: ByteArray, off: Int, len: Int) {
      if (detached.get()) return
      val dropped = synchronized(generationsLock) {
        generations.last().writeOrDrop(src, off, len)
      }
      if (dropped > 0) {
        val now = droppedBytesTotal.addAndGet(dropped.toLong())
        val last = lastLogMillis.get()
        val nowMs = System.currentTimeMillis()
        if (nowMs - last >= DROPPED_LOG_INTERVAL_MS &&
          lastLogMillis.compareAndSet(last, nowMs)
        ) {
          Console.log(
            "[H264Tee] consumer ${id} on ${tee.deviceId.instanceId} dropped $dropped bytes " +
              "(total=$now); ring buffer too small for this consumer's drain rate",
          )
        }
      }
    }

    /**
     * Open a new generation for this consumer. Subsequent producer writes go to the new
     * generation's ring buffer. The previous generation's buffer stays until the consumer
     * drains it, at which point [read] returns [READ_RESULT_RESTART] and rolls forward.
     */
    internal fun signalRestart() {
      synchronized(generationsLock) {
        // Cap pending generations at MAX_GENERATIONS — a runaway producer cycling restarts
        // without the consumer draining would otherwise grow the queue unbounded. When at the
        // cap, drop the front (oldest, never-drained) generation and log; the consumer skips
        // straight to the newest. This is a degenerate case (consumer wedged), and we'd rather
        // surface fresh frames than backed-up history.
        if (generations.size >= MAX_GENERATIONS) {
          generations.removeFirst()
          Console.log(
            "[H264Tee] consumer ${id} on ${tee.deviceId.instanceId} fell behind " +
              "$MAX_GENERATIONS generations; dropping oldest generation",
          )
        }
        generations.addLast(RingBuffer(ringBufferCapacity))
      }
    }
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Ring buffer — minimal, single-producer / single-consumer per Consumer.
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * Single-producer / single-consumer byte ring buffer with drop-on-overflow semantics on the
   * write side. Capacity is fixed at construction. Sized in bytes — H.264 NAL units vary
   * wildly, so a frame-based bound would be hard to tune.
   *
   * Not lock-free: a single `synchronized` block guards the read+write indices and the array.
   * We considered a `MutableRingBuffer` / `Atomic*Array` lock-free scheme but the producer
   * write rate (~4 Mbps ÷ packet size ≈ a few hundred writes/sec) is nowhere near the rate at
   * which lock contention starts mattering, and a synchronized block is auditable.
   */
  internal class RingBuffer(capacity: Int) {
    private val buf = ByteArray(capacity)
    private val cap = capacity
    private var writeIdx = 0
    private var readIdx = 0
    private var size = 0
    private val lock = Any()

    /**
     * Writes [len] bytes from [src] starting at [off]. Returns the number of bytes *lost* under
     * back-pressure (>0 means overflow). Eviction policy: under sustained back-pressure, the
     * oldest buffered bytes are dropped to make room for the freshest input. For an H.264
     * stream this is the right trade — the decoder downstream resyncs on the next start
     * code/IDR after a discontinuity, and getting *to* that next start code requires the
     * consumer to read freshly-arrived bytes, not stale ones that have been waiting in the
     * buffer since the producer wrote them. Review feedback on PR #3021 caught that the
     * previous "drop newest" implementation left slow consumers stuck on stale data.
     *
     * Three regimes:
     *  - Room for the whole chunk → write all of it, drop nothing.
     *  - Chunk fits if we evict some old → advance [readIdx] past `len - free` bytes, then
     *    write the entire chunk. Returned drop count is the evicted old bytes.
     *  - Chunk bigger than the whole buffer → reset and keep the trailing [cap] bytes of the
     *    input. Returned drop count is `len - cap`.
     */
    fun writeOrDrop(src: ByteArray, off: Int, len: Int): Int {
      synchronized(lock) {
        val freeBytes = cap - size
        val dropped: Int
        val toWrite: Int
        val srcStart: Int
        when {
          len <= freeBytes -> {
            dropped = 0
            toWrite = len
            srcStart = off
          }
          len <= cap -> {
            // Evict just enough old bytes to make the new chunk fit.
            val mustEvict = len - freeBytes
            readIdx = (readIdx + mustEvict) % cap
            size -= mustEvict
            dropped = mustEvict
            toWrite = len
            srcStart = off
          }
          else -> {
            // Input chunk is bigger than the entire buffer. Drop everything buffered and
            // keep just the trailing [cap] bytes of the input — they're the freshest.
            readIdx = 0
            writeIdx = 0
            size = 0
            dropped = len - cap
            toWrite = cap
            srcStart = off + dropped
          }
        }
        // Copy as up to two contiguous spans across the wraparound.
        var remaining = toWrite
        var s = srcStart
        while (remaining > 0) {
          val span = minOf(remaining, cap - writeIdx)
          System.arraycopy(src, s, buf, writeIdx, span)
          writeIdx = (writeIdx + span) % cap
          s += span
          remaining -= span
        }
        size += toWrite
        return dropped
      }
    }

    /**
     * Drains up to `dest.size` bytes into [dest]. Returns the count drained, possibly 0 when
     * the buffer is empty. Never blocks — the caller spins or sleeps on its own cadence; the
     * H264Tee consumer side is wired into downstream pipes that have their own back-pressure.
     */
    fun read(dest: ByteArray): Int {
      synchronized(lock) {
        if (size == 0) return 0
        val toRead = minOf(dest.size, size)
        var remaining = toRead
        var d = 0
        while (remaining > 0) {
          val span = minOf(remaining, cap - readIdx)
          System.arraycopy(buf, readIdx, dest, d, span)
          readIdx = (readIdx + span) % cap
          d += span
          remaining -= span
        }
        size -= toRead
        return toRead
      }
    }

    /** Currently buffered bytes. Test seam. */
    internal fun currentSize(): Int = synchronized(lock) { size }
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Producer abstraction — real impl shells out to adb; tests inject canned bytes.
  // ────────────────────────────────────────────────────────────────────────────

  /** Handle to a running screenrecord producer. */
  interface ProducerHandle : AutoCloseable {
    val input: InputStream
  }

  /** Strategy: spawn a new screenrecord-equivalent producer. */
  fun interface ProducerFactory {
    fun spawn(
      deviceId: TrailblazeDeviceId,
      videoSize: String,
      bitRate: String,
      /** If true, request `--time-limit 0` (Android 11+); otherwise default 3-min cap. */
      unlimited: Boolean,
    ): ProducerHandle
  }

  companion object {
    /** Returned by [Consumer.read] when a producer restart is pending. */
    const val READ_RESULT_RESTART: Int = -2

    /** Returned by [Consumer.read] after [Consumer.detach] and the ring buffer is empty. */
    const val READ_RESULT_DETACHED: Int = -1

    /** Android 11. `screenrecord --time-limit 0` works on this and later. */
    internal const val ANDROID_R_SDK: Int = 30

    private const val RESPAWN_DELAY_MILLIS: Long = 250L

    /** Upper bound for the respawn backoff (a wedged device tops out at one respawn / 5s). */
    private const val RESPAWN_MAX_DELAY_MILLIS: Long = 5_000L

    /** Cap the left-shift so `RESPAWN_DELAY_MILLIS shl n` can't overflow before the delay cap. */
    private const val RESPAWN_MAX_BACKOFF_SHIFT: Int = 5

    /** Reader thread chunk size. 256 KB ≈ ~250 ms of 8 Mbps H.264, well below ring sizes. */
    internal const val READ_CHUNK_BYTES: Int = 256 * 1024

    /** Throttle "dropped bytes" logs to roughly one per second per consumer. */
    private const val DROPPED_LOG_INTERVAL_MS: Long = 1_000L

    /**
     * Maximum number of pending generations a single consumer can hold before the oldest one
     * is dropped. With screenrecord chained every ~170s on Android < 11, a consumer would have
     * to be wedged for several minutes before reaching even 2; 4 is a safety margin that lets
     * a transient stall recover without unbounded memory growth.
     */
    private const val MAX_GENERATIONS: Int = 4

    /**
     * Registry of one tee per device. Different consumers on the same device share the same
     * underlying screenrecord stream. Different sizes/bitrates: first one wins, second logs.
     */
    private val instances = ConcurrentHashMap<String, H264Tee>()
    private val instanceLock = Any()

    /**
     * Obtain (or lazily create) the tee for [deviceId]. If an instance already exists with
     * different [videoSize] / [bitRate], a warning is logged and the existing instance is
     * returned (it would be incorrect to silently swap encoder params on an active stream).
     */
    fun forDevice(
      deviceId: TrailblazeDeviceId,
      videoSize: String,
      bitRate: String,
    ): H264Tee {
      val key = deviceId.instanceId
      synchronized(instanceLock) {
        val existing = instances[key]
        if (existing != null) {
          if (existing.videoSize != videoSize || existing.bitRate != bitRate) {
            Console.log(
              "[H264Tee] forDevice($key): existing tee is " +
                "${existing.videoSize}/${existing.bitRate}; ignoring request for $videoSize/$bitRate",
            )
          }
          return existing
        }
        val created = H264Tee(deviceId, videoSize, bitRate)
        instances[key] = created
        return created
      }
    }

    /** Test seam: clear the registry so each test starts clean. */
    internal fun resetRegistryForTests() {
      synchronized(instanceLock) { instances.clear() }
    }

    /**
     * Query the device's SDK level via a one-shot `adb shell getprop ro.build.version.sdk`.
     * Returns 0 on failure so the caller falls back to the chained-segment path (safe
     * default — works on every API level).
     */
    internal fun sdkLevelFromDevice(deviceId: TrailblazeDeviceId): Int {
      return try {
        val sdkText = xyz.block.trailblaze.util.AndroidHostAdbUtils
          .execAdbShellCommandWithTimeout(
            deviceId = deviceId,
            args = listOf("getprop", "ro.build.version.sdk"),
            timeoutMs = 5_000L,
          )
          ?.trim()
          .orEmpty()
        sdkText.toIntOrNull() ?: 0
      } catch (_: Exception) {
        0
      }
    }
  }
}

/**
 * Default producer factory: invokes `adb exec-out -s <serial> screenrecord ...` via
 * [ProcessBuilder]. We deliberately do NOT route through
 * [xyz.block.trailblaze.util.AndroidHostAdbUtils.streamingShell] — that path is for
 * short-lived shell commands with line-buffered stdout. screenrecord emits a continuous
 * binary stream and we want the raw stdout pipe straight through to a JVM reader.
 *
 * Honors `ANDROID_ADB_SERVER_PORT` / `ADB_SERVER_SOCKET` env vars by passing the corresponding
 * `adb -L`/`-P` flags before the subcommand. Read once on construction.
 */
internal object AdbScreenrecordProducerFactory : H264Tee.ProducerFactory {
  override fun spawn(
    deviceId: TrailblazeDeviceId,
    videoSize: String,
    bitRate: String,
    unlimited: Boolean,
  ): H264Tee.ProducerHandle {
    val args = buildList {
      add(xyz.block.trailblaze.util.AdbPathResolver.ADB_COMMAND)
      addAll(resolveServerFlags())
      add("-s")
      add(deviceId.instanceId)
      add("exec-out")
      add("screenrecord")
      add("--output-format=h264")
      add("--size")
      add(videoSize)
      add("--bit-rate")
      add(bitRate)
      if (unlimited) {
        add("--time-limit")
        add("0")
      }
      add("-")
    }
    val pb = ProcessBuilder(args)
      // Don't redirect stderr to stdout — stderr carries human-readable diagnostics from
      // screenrecord that would otherwise corrupt the raw H.264 byte stream we're piping
      // back. We drop stderr on the floor; if needed for debugging, wire up a separate
      // drain thread.
      .redirectErrorStream(false)
    val process = pb.start()
    // Drain stderr in a daemon thread so a wedged stderr pipe can't block the subprocess.
    Thread(
      {
        try {
          process.errorStream.use { errStream ->
            val buf = ByteArray(4096)
            while (errStream.read(buf) > 0) {
              // Discard — we don't surface screenrecord stderr by default.
            }
          }
        } catch (_: Exception) { /* expected on close */ }
      },
      "h264-tee-stderr-${deviceId.instanceId}",
    ).apply { isDaemon = true; start() }
    return object : H264Tee.ProducerHandle {
      override val input: InputStream = process.inputStream
      override fun close() {
        runCatching { process.destroy() }
        if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
          process.destroyForcibly()
        }
      }
    }
  }

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

  private fun resolveServerFlags(): List<String> = serverFlags
}
