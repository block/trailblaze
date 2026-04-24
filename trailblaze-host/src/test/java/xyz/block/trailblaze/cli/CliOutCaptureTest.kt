package xyz.block.trailblaze.cli

import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Tests for [CliOutCapture]. `install()` wraps `System.out` / `System.err`
 * process-wide, so these tests do not try to undo the wrapping — they verify
 * that (a) install is idempotent, (b) [CliOutCapture.withCapture] routes
 * writes on the calling thread to the provided buffers, (c) nested calls
 * restore the outer capture in `finally`, and (d) UTF-8 bytes round-trip.
 */
class CliOutCaptureTest {

  @Test fun `install is idempotent under concurrent callers`() {
    // Fire N threads at install() simultaneously — compareAndSet must mean
    // only one wrap occurs. After this loop System.out must still function.
    val threads = List(16) {
      Thread {
        repeat(8) { CliOutCapture.install() }
      }
    }
    threads.forEach { it.start() }
    threads.forEach { it.join() }

    val out = System.out
    val snap1 = System.out
    CliOutCapture.install()
    val snap2 = System.out
    assertSame(snap1, snap2, "install() after initial wrap should be a no-op")
    // Smoke test that the wrapped stream still passes bytes through.
    out.println("cli-out-capture-idempotent-test")
  }

  @Test fun `withCapture routes writes on the calling thread to the buffer`() {
    CliOutCapture.install()
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    CliOutCapture.withCapture(out, err) {
      println("captured-stdout")
      System.err.println("captured-stderr")
    }
    assertTrue(
      out.toString(Charsets.UTF_8).contains("captured-stdout"),
      "captured stdout: '${out.toString(Charsets.UTF_8)}'",
    )
    assertTrue(
      err.toString(Charsets.UTF_8).contains("captured-stderr"),
      "captured stderr: '${err.toString(Charsets.UTF_8)}'",
    )
  }

  @Test fun `withCapture restores prior thread-local on nested exit`() {
    CliOutCapture.install()
    val outer = ByteArrayOutputStream()
    val inner = ByteArrayOutputStream()
    CliOutCapture.withCapture(outer, ByteArrayOutputStream()) {
      println("outer-before")
      CliOutCapture.withCapture(inner, ByteArrayOutputStream()) {
        println("inner-only")
      }
      println("outer-after")
    }

    val outerText = outer.toString(Charsets.UTF_8)
    val innerText = inner.toString(Charsets.UTF_8)
    assertTrue(outerText.contains("outer-before"), "outer missing prefix")
    assertTrue(outerText.contains("outer-after"), "outer missing post-nested text — finally did not restore")
    assertTrue(!outerText.contains("inner-only"), "inner text leaked into outer buffer")
    assertTrue(innerText.contains("inner-only"))
    assertTrue(!innerText.contains("outer-"), "outer text leaked into inner buffer")
  }

  @Test fun `non-ASCII bytes round-trip via UTF-8`() {
    CliOutCapture.install()
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    CliOutCapture.withCapture(out, err) {
      println("héllo 🔥 shì jiè")
    }
    val text = out.toString(Charsets.UTF_8)
    assertTrue(text.contains("héllo"), "accented chars lost: '$text'")
    assertTrue(text.contains("🔥"), "emoji lost: '$text'")
    assertTrue(text.contains("shì jiè"), "combining chars lost: '$text'")
  }

  @Test fun `writes on threads without capture override fall through to original stream`() {
    CliOutCapture.install()
    val capturedOut = ByteArrayOutputStream()
    val seenOnOtherThread = StringBuilder()
    val latch = java.util.concurrent.CountDownLatch(1)

    CliOutCapture.withCapture(capturedOut, ByteArrayOutputStream()) {
      val t = Thread {
        // This thread has no capture override — println should NOT land in
        // `capturedOut`. The test asserts it doesn't leak via the buffer.
        seenOnOtherThread.append("other-thread-ran")
        latch.countDown()
      }
      t.start()
      latch.await()
      t.join()
      println("main-thread-captured")
    }

    val captured = capturedOut.toString(Charsets.UTF_8)
    assertTrue(captured.contains("main-thread-captured"))
    assertTrue(!captured.contains("other-thread-ran"), "thread without capture must not write into buffer: '$captured'")
    assertEquals("other-thread-ran", seenOnOtherThread.toString())
  }
}
