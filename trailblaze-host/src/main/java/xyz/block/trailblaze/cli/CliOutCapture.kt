package xyz.block.trailblaze.cli

import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thread-local stdout/stderr capture, used by the daemon-side `/cli/exec` handler
 * to run a picocli command in-process while isolating its output from other
 * concurrent daemon work.
 *
 * [install] must be called once at main() before any code references `System.out`
 * (including [xyz.block.trailblaze.util.Console], whose JVM implementation caches
 * `System.out` into a field at class-init time). After install, writes go:
 *
 *   PrintStream(DispatchingOutputStream)
 *     ├── if a thread-local override is active for the calling thread → write there
 *     └── else → write to the original System.out/err (normal daemon logging)
 *
 * Later wrappers like `DesktopLogFileWriter.install` tee on top; their Tee stream
 * writes into our DispatchingOutputStream, so the thread-local routing still
 * applies underneath.
 *
 * **Fallback semantics.** Threads *without* a thread-local override set fall
 * through to the original System.out/err, intentionally bypassing per-request
 * capture. This is how MCP and background daemon threads keep logging normally
 * while a CLI exec is in flight on another thread — capture is an opt-in scope
 * (`withCapture`), not a global redirection. Future maintainers: do not route
 * the fallback path through the latest capture; that would corrupt captured
 * output and leak unrelated daemon logs into the `/cli/exec` response.
 */
object CliOutCapture {

  private val threadOut = ThreadLocal<OutputStream?>()
  private val threadErr = ThreadLocal<OutputStream?>()

  private val installed = AtomicBoolean(false)

  /**
   * Wrap System.out/err so writes route through a thread-local override when one
   * is set. Idempotent — safe to call multiple times, only the first installs.
   * Uses [AtomicBoolean.compareAndSet] so concurrent early calls can't
   * double-wrap the streams.
   *
   * The wrapping [PrintStream] is pinned to UTF-8 so that captured bytes match
   * the `Charsets.UTF_8` decode the daemon does when serializing stdout/stderr
   * for the `/cli/exec` response. Relying on the platform default would corrupt
   * non-ASCII output on JVMs whose `file.encoding` isn't UTF-8.
   */
  fun install() {
    if (!installed.compareAndSet(false, true)) return
    val origOut = System.out
    val origErr = System.err
    System.setOut(
      PrintStream(DispatchingOutputStream(origOut, threadOut), /* autoFlush = */ true, StandardCharsets.UTF_8),
    )
    System.setErr(
      PrintStream(DispatchingOutputStream(origErr, threadErr), /* autoFlush = */ true, StandardCharsets.UTF_8),
    )
  }

  /**
   * Run [block] with the current thread's stdout/stderr redirected to [out]/[err].
   * Any call that lands on another thread (coroutine hop to a different dispatcher,
   * OkHttp callback thread, etc.) will miss the override and fall through to the
   * daemon's normal stdout/stderr — acceptable for the in-process CLI shortcut
   * because `cliWithDevice` uses `runBlocking` which stays on the current thread.
   */
  fun <T> withCapture(out: OutputStream, err: OutputStream, block: () -> T): T {
    val prevOut = threadOut.get()
    val prevErr = threadErr.get()
    threadOut.set(out)
    threadErr.set(err)
    try {
      return block()
    } finally {
      threadOut.set(prevOut)
      threadErr.set(prevErr)
    }
  }

  private class DispatchingOutputStream(
    private val fallback: OutputStream,
    private val override: ThreadLocal<OutputStream?>,
  ) : OutputStream() {

    override fun write(b: Int) {
      (override.get() ?: fallback).write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
      (override.get() ?: fallback).write(b, off, len)
    }

    override fun flush() {
      (override.get() ?: fallback).flush()
    }
  }
}
