package xyz.block.trailblaze.capture.video

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * Tests for [H264Tee]. We don't need real H.264 bytes — the tee multiplexes opaque byte
 * streams. Tests use canned [ByteArrayInputStream]s or [PipedOutputStream] for controllable
 * timing.
 */
class H264TeeTest {

  private val deviceId = TrailblazeDeviceId("emulator-test", TrailblazeDevicePlatform.ANDROID)

  @BeforeTest
  fun setUp() {
    H264Tee.resetRegistryForTests()
  }

  @AfterTest
  fun tearDown() {
    H264Tee.resetRegistryForTests()
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Ring buffer
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `ring buffer drops oldest input chunk on overflow keeping trailing bytes`() {
    val rb = H264Tee.RingBuffer(8)
    val dropped = rb.writeOrDrop(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), 0, 10)
    assertEquals(2, dropped, "two leading bytes should be dropped to fit 10 into capacity 8")
    val out = ByteArray(8)
    val n = rb.read(out)
    assertEquals(8, n)
    assertEquals(listOf<Byte>(3, 4, 5, 6, 7, 8, 9, 10), out.toList())
  }

  @Test
  fun `ring buffer round-trips wraparound writes correctly`() {
    val rb = H264Tee.RingBuffer(8)
    rb.writeOrDrop(byteArrayOf(1, 2, 3, 4, 5), 0, 5)
    val tmp = ByteArray(3)
    rb.read(tmp) // now readIdx=3, writeIdx=5, size=2
    rb.writeOrDrop(byteArrayOf(6, 7, 8, 9, 10, 11), 0, 6) // wraps
    val out = ByteArray(8)
    val n = rb.read(out)
    assertEquals(8, n)
    assertEquals(listOf<Byte>(4, 5, 6, 7, 8, 9, 10, 11), out.toList())
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Multi-consumer dispatch — fast and slow consumers
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `two consumers see same bytes when both drain fast enough`() {
    // Use a piped stream rather than a canned ByteArrayInputStream so the test thread
    // controls *when* bytes become available: not until both consumers are attached. A
    // ByteArrayInputStream EOFs in microseconds and the tee's reader thread could race
    // through fanOut() before the second consumer was registered in the map (the first-
    // attach race is fixed at the production-code level; this second-attach race is
    // inherent because the tee documents consumers added mid-iteration as potentially
    // missing chunks — production screenrecord emits at ~4 Mbps and the gap between two
    // attach() calls is comfortably under one chunk, so this never bites real callers).
    val totalBytes = 1024
    val payload = ByteArray(totalBytes) { (it and 0xff).toByte() }
    val pipeOut = PipedOutputStream()
    val pipeIn = PipedInputStream(pipeOut, totalBytes * 2)
    val tee = H264Tee(
      deviceId = deviceId,
      videoSize = "720x1280",
      bitRate = "4000000",
      producerFactory = singleShotProducer(pipeIn),
      sdkLevelProvider = { H264Tee.ANDROID_R_SDK }, // unlimited, no restart
    )

    val consumerA = tee.attach(ringBufferBytes = 64 * 1024)
    val consumerB = tee.attach(ringBufferBytes = 64 * 1024)
    // Both consumers attached — now feed the payload through the producer pipe.
    pipeOut.write(payload)
    pipeOut.close()

    val gotA = drainConsumer(consumerA, totalBytes)
    val gotB = drainConsumer(consumerB, totalBytes)

    consumerA.detach()
    consumerB.detach()

    assertEquals(totalBytes, gotA.size)
    assertEquals(totalBytes, gotB.size)
    // Bytes should match the payload prefix; consumers attached before any read so they get
    // the full stream.
    assertTrue(gotA.contentEquals(payload), "consumer A should receive identical bytes")
    assertTrue(gotB.contentEquals(payload), "consumer B should receive identical bytes")
  }

  @Test
  fun `slow consumer drops bytes while fast consumer keeps draining`() {
    // Use a piped stream so we can pace bytes from the test thread — otherwise a single
    // ByteArrayInputStream gets read in a tight loop before any consumer can drain, which
    // would make the *fast* consumer also drop. This mirrors the production case where
    // screenrecord emits bytes at ~4 Mbps, far slower than the reader can drain.
    val totalBytes = 256 * 1024 // 256 KB total
    val payload = ByteArray(totalBytes) { (it and 0xff).toByte() }
    val pipeOut = PipedOutputStream()
    val pipeIn = PipedInputStream(pipeOut, 4 * 1024)

    val tee = H264Tee(
      deviceId = deviceId,
      videoSize = "720x1280",
      bitRate = "4000000",
      producerFactory = singleShotProducer(pipeIn),
      sdkLevelProvider = { H264Tee.ANDROID_R_SDK },
    )

    val fastConsumer = tee.attach(ringBufferBytes = 512 * 1024) // plenty of room
    val slowConsumer = tee.attach(ringBufferBytes = 2 * 1024) // tiny — will drop

    val fastCollected = java.io.ByteArrayOutputStream()
    val slowCollected = java.io.ByteArrayOutputStream()

    val drainerFast = Thread {
      val buf = ByteArray(4096)
      val deadline = System.currentTimeMillis() + 10_000
      while (System.currentTimeMillis() < deadline && fastCollected.size() < totalBytes) {
        val n = fastConsumer.read(buf)
        if (n > 0) fastCollected.write(buf, 0, n) else Thread.sleep(1)
      }
    }.apply { isDaemon = true; start() }

    // Slow consumer drains only a tiny amount before each producer write.
    val drainerSlow = Thread {
      val buf = ByteArray(64)
      val deadline = System.currentTimeMillis() + 10_000
      while (System.currentTimeMillis() < deadline && slowCollected.size() < totalBytes) {
        val n = slowConsumer.read(buf)
        if (n > 0) slowCollected.write(buf, 0, n)
        Thread.sleep(5)
      }
    }.apply { isDaemon = true; start() }

    // Producer: feed bytes in chunks with a small delay so the fast consumer can drain while
    // the slow consumer falls behind and drops bytes.
    val chunkSize = 4096
    var pos = 0
    while (pos < totalBytes) {
      val end = minOf(pos + chunkSize, totalBytes)
      pipeOut.write(payload, pos, end - pos)
      pipeOut.flush()
      pos = end
      Thread.sleep(2)
    }
    pipeOut.close()

    drainerFast.join(5_000)
    drainerSlow.join(2_000)

    fastConsumer.detach()
    slowConsumer.detach()

    assertEquals(totalBytes, fastCollected.size(), "fast consumer must receive all bytes")
    assertTrue(
      slowCollected.size() < totalBytes,
      "slow consumer should NOT receive all bytes (drained ${slowCollected.size()} / $totalBytes)",
    )
    assertTrue(
      slowConsumer.droppedBytes > 0,
      "slow consumer should report droppedBytes>0 (actual ${slowConsumer.droppedBytes})",
    )
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Restart signal — when one screenrecord subprocess exits before shutdown
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `restart signal fires when producer exits on a pre-Android-11 device`() {
    // Two subprocesses worth of bytes, signaled by the producer factory returning a fresh
    // ByteArrayInputStream on each call.
    val first = byteArrayOf(1, 2, 3, 4)
    val second = byteArrayOf(5, 6, 7, 8)
    val callCount = AtomicInteger(0)
    val factory = H264Tee.ProducerFactory { _, _, _, unlimited ->
      assertEquals(false, unlimited, "pre-API-30 should not request unlimited time-limit")
      val n = callCount.getAndIncrement()
      val bytes = when (n) {
        0 -> first
        1 -> second
        else -> throw IllegalStateException("only two producers expected, asked for $n")
      }
      object : H264Tee.ProducerHandle {
        override val input: InputStream = ByteArrayInputStream(bytes)
        override fun close() {}
      }
    }
    val tee = H264Tee(
      deviceId = deviceId,
      videoSize = "720x1280",
      bitRate = "4000000",
      producerFactory = factory,
      sdkLevelProvider = { 28 }, // < 30, forces restart-on-exit chain
    )
    val consumer = tee.attach(ringBufferBytes = 64 * 1024)

    val firstChunk = readBytesUntilRestart(consumer, timeoutMs = 2_000L)
    // The next read after a restart returns bytes from the new subprocess.
    val secondChunk = readBytesUntilEmpty(consumer, expected = second.size, timeoutMs = 2_000L)

    consumer.detach()

    assertTrue(firstChunk.contentEquals(first), "first chunk should match the first producer's bytes")
    assertTrue(secondChunk.contentEquals(second), "second chunk should match the second producer's bytes")
    assertTrue(callCount.get() >= 2, "producer factory should be invoked at least twice (got ${callCount.get()})")
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Lifecycle — ref-counted start/stop
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `producer is spawned on first attach and stopped on last detach`() {
    val spawns = AtomicInteger(0)
    val closes = AtomicInteger(0)
    // Hold the pipe open so the producer doesn't exit on its own — we want closure to be the
    // result of the consumer detach path, not EOF.
    val pipeOut = PipedOutputStream()
    val pipeIn = PipedInputStream(pipeOut, 16)
    val handleRef = AtomicReference<H264Tee.ProducerHandle?>()
    val factory = H264Tee.ProducerFactory { _, _, _, _ ->
      spawns.incrementAndGet()
      val handle = object : H264Tee.ProducerHandle {
        override val input: InputStream = pipeIn
        override fun close() {
          closes.incrementAndGet()
          runCatching { pipeOut.close() }
        }
      }
      handleRef.set(handle)
      handle
    }
    val tee = H264Tee(
      deviceId = deviceId,
      videoSize = "720x1280",
      bitRate = "4000000",
      producerFactory = factory,
      sdkLevelProvider = { H264Tee.ANDROID_R_SDK }, // unlimited so no auto-restart
    )

    assertEquals(0, spawns.get())
    val a = tee.attach(64 * 1024)
    assertEquals(1, spawns.get(), "first attach should spawn the producer")

    val b = tee.attach(64 * 1024)
    assertEquals(1, spawns.get(), "second attach should NOT spawn another producer")

    a.detach()
    assertEquals(0, closes.get(), "consumer A detach with consumer B still attached should NOT close")

    b.detach()
    // close() runs as part of last detach
    // give the reader thread a moment to acknowledge close
    Thread.sleep(100)
    assertEquals(1, closes.get(), "last detach should close the producer exactly once")

    // Re-attach should respawn the producer.
    val c = tee.attach(64 * 1024)
    assertEquals(2, spawns.get(), "re-attach after detach-to-zero should respawn the producer")
    c.detach()
  }

  @Test
  fun `producer spawn failure leaves tee clean for retry`() {
    // Pins the recovery path called out in `H264Tee.attach` (the catch block on
    // `startProducer()`). A prior version of `attach` incremented refCount and inserted the
    // consumer into the map BEFORE spawning; a spawn failure then left the tee with
    // refCount > 0 and an orphaned consumer entry, so the next attach skipped the first-
    // attach branch (refCount != 0) and never spawned a new producer — the tee was wedged
    // until the JVM restarted. Codex + Copilot both flagged this on PR #3021 and the fix
    // moved the spawn inside the lock with a try/catch that reverts state. This test
    // guards the recovery contract: a thrown spawn must not register the consumer, must
    // not raise refCount, and the very next attach must succeed by hitting the first-
    // attach branch again.
    val spawnAttempts = AtomicInteger(0)
    val factory = H264Tee.ProducerFactory { _, _, _, _ ->
      val n = spawnAttempts.incrementAndGet()
      if (n == 1) {
        // First attach: throw to simulate adb/screenrecord startup failure (device offline,
        // permission denied, etc.).
        throw java.io.IOException("simulated screenrecord startup failure")
      }
      // Second attach: succeed with a benign empty stream. The reader will EOF immediately
      // but the tee should accept the attach and register the consumer normally.
      object : H264Tee.ProducerHandle {
        override val input: InputStream = ByteArrayInputStream(ByteArray(0))
        override fun close() {}
      }
    }
    val tee = H264Tee(
      deviceId = deviceId,
      videoSize = "720x1280",
      bitRate = "4000000",
      producerFactory = factory,
      sdkLevelProvider = { H264Tee.ANDROID_R_SDK },
    )

    // First attach throws — recovery happens silently in the tee, but `attach` rethrows so
    // the caller knows the spawn didn't take.
    val first = runCatching { tee.attach(64 * 1024) }
    assertTrue(first.isFailure, "first attach should rethrow when producer factory throws")
    assertEquals(1, spawnAttempts.get(), "factory should have been called exactly once on the failed attach")

    // Second attach should hit the first-attach branch AGAIN (refCount must have rolled
    // back to 0). If `attach` had left the tee in a wedged state, the factory would NOT
    // be invoked here and the new consumer would never receive bytes.
    val second = tee.attach(64 * 1024)
    assertEquals(2, spawnAttempts.get(), "second attach must invoke the factory — proving refCount rolled back to 0")
    second.detach()
    Thread.sleep(50) // let the reader thread settle so tearDown's registry reset is clean
  }

  // ──────────────────────────────────────────────────────────────────────────
  // forDevice registry — different params on the same device log and reuse
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `forDevice returns same instance for same device id`() {
    val a = H264Tee.forDevice(deviceId, "720x1280", "4000000")
    val b = H264Tee.forDevice(deviceId, "720x1280", "4000000")
    assertTrue(a === b, "same (device, size, bitrate) tuple should yield same instance")
  }

  @Test
  fun `forDevice ignores second request with different params and returns existing`() {
    val a = H264Tee.forDevice(deviceId, "720x1280", "4000000")
    val b = H264Tee.forDevice(deviceId, "1080x1920", "8000000")
    assertTrue(a === b, "second request with different params should NOT spawn a new tee")
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Helpers
  // ──────────────────────────────────────────────────────────────────────────

  /** Producer factory that returns the same single InputStream once and then EOFs. */
  private fun singleShotProducer(stream: InputStream): H264Tee.ProducerFactory =
    H264Tee.ProducerFactory { _, _, _, _ ->
      object : H264Tee.ProducerHandle {
        override val input: InputStream = stream
        override fun close() = runCatching { stream.close() }.let { Unit }
      }
    }

  /** Drains [consumer] until [expected] bytes have arrived or timeout. */
  private fun drainConsumer(consumer: H264Tee.Consumer, expected: Int, timeoutMs: Long = 5_000): ByteArray {
    val collected = java.io.ByteArrayOutputStream()
    val buf = ByteArray(4096)
    val deadline = System.currentTimeMillis() + timeoutMs
    while (collected.size() < expected && System.currentTimeMillis() < deadline) {
      val n = consumer.read(buf)
      when {
        n > 0 -> collected.write(buf, 0, n)
        n == 0 -> Thread.sleep(5)
        else -> break
      }
    }
    return collected.toByteArray()
  }

  /** Reads bytes from [consumer] until a [H264Tee.READ_RESULT_RESTART] arrives. */
  private fun readBytesUntilRestart(consumer: H264Tee.Consumer, timeoutMs: Long): ByteArray {
    val collected = java.io.ByteArrayOutputStream()
    val buf = ByteArray(4096)
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val n = consumer.read(buf)
      when {
        n > 0 -> collected.write(buf, 0, n)
        n == H264Tee.READ_RESULT_RESTART -> return collected.toByteArray()
        n == 0 -> Thread.sleep(5)
        else -> return collected.toByteArray()
      }
    }
    return collected.toByteArray()
  }

  private fun readBytesUntilEmpty(consumer: H264Tee.Consumer, expected: Int, timeoutMs: Long): ByteArray {
    val collected = java.io.ByteArrayOutputStream()
    val buf = ByteArray(4096)
    val deadline = System.currentTimeMillis() + timeoutMs
    while (collected.size() < expected && System.currentTimeMillis() < deadline) {
      val n = consumer.read(buf)
      when {
        n > 0 -> collected.write(buf, 0, n)
        n == 0 -> Thread.sleep(5)
        n == H264Tee.READ_RESULT_RESTART -> {
          // unexpected — keep going; continue reading
        }
        else -> break
      }
    }
    return collected.toByteArray()
  }
}
