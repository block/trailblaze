package xyz.block.trailblaze.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Run [block] with `System.err` redirected into an in-memory buffer and return both
 * the block's exit code and the captured stderr text. Restores the prior stream in
 * `finally` so a thrown assertion can't leak the redirect.
 *
 * Lives at the top level because three CLI tests already grew their own copy of
 * this same shape — [ToolCommandTest], [CheckCommandTest], and (in a different
 * variant returning bare `String`, kept separate) `PerTrailmapTsconfigEmitterTest`.
 * Centralizing the `() -> Int` / `Pair<Int, String>` variant here so any future CLI
 * test that needs the actionable-error wording can `captureStderr { … }` without
 * yet another local clone.
 *
 * **Not thread-safe.** `System.setErr` is process-global; running two captures
 * concurrently in the same JVM would interleave streams. The cli test suite runs
 * tests sequentially within a single class which is the only invariant relied
 * upon here.
 */
internal fun captureStderr(block: () -> Int): Pair<Int, String> {
  val originalErr = System.err
  val buffer = ByteArrayOutputStream()
  System.setErr(PrintStream(buffer, true, Charsets.UTF_8))
  try {
    val exit = block()
    return exit to buffer.toString(Charsets.UTF_8)
  } finally {
    System.setErr(originalErr)
  }
}

/**
 * Variant for callers that only care about the captured stderr, not an exit code —
 * unit-testing pure rendering helpers (e.g. `emitNoDevicesEnvelope`) where the
 * `Int`-returning shape would force a synthetic `0` at every call site.
 *
 * Kept as a distinct name (rather than an overload of [captureStderr]) because
 * Kotlin treats `() -> Int` as assignable to `() -> Unit` — overloading the two
 * makes every call site ambiguous at the lambda's `{ … }` boundary. Separate
 * names sidestep the resolution.
 *
 * Same thread-safety caveat as [captureStderr]: `System.setErr` is process-global,
 * so concurrent captures in the same JVM would interleave streams.
 */
internal fun captureStderrText(block: () -> Unit): String {
  val (_, stderr) = captureStderr {
    block()
    0
  }
  return stderr
}
