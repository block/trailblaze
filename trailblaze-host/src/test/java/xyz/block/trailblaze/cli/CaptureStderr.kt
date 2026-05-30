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
