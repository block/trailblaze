package xyz.block.trailblaze.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Run [block] with `System.out` redirected into an in-memory buffer and return
 * the captured stdout text. Restores the prior stream in `finally` so a thrown
 * assertion can't leak the redirect.
 *
 * Sibling to [captureStderr] — same lifetime contract, same not-thread-safe
 * caveat. `System.setOut` is process-global; running two captures concurrently
 * in the same JVM would interleave streams. The cli test suite runs tests
 * sequentially within a single class, which is the only invariant relied upon
 * here.
 *
 * **Does NOT redirect [xyz.block.trailblaze.util.Console.log] output.** The
 * JVM impl of `Console` (see `Console.jvm.kt`) caches its `out: PrintStream =
 * System.out` reference at class load — `System.setOut` does not redirect
 * Console's cached reference. Use this helper for code paths that emit via
 * `println` (e.g. [printShellExport]) or anything else that reads `System.out`
 * dynamically. For asserting on `Console.log` output, prefer a source-text
 * static check (the same pattern used in
 * `CliInfrastructureResolverTest.audit-log prefix is shared…`).
 */
internal inline fun captureStdout(block: () -> Unit): String {
  val originalOut = System.out
  val buf = ByteArrayOutputStream()
  System.setOut(PrintStream(buf, true, Charsets.UTF_8))
  try {
    block()
  } finally {
    System.setOut(originalOut)
  }
  return buf.toString(Charsets.UTF_8)
}
