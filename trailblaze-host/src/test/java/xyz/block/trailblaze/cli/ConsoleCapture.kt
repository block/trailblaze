package xyz.block.trailblaze.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import xyz.block.trailblaze.util.Console

/** What [Console] printed during a [captureConsole] block, split by stream. */
internal class CapturedConsole<T>(val result: T, val out: String, val err: String)

/**
 * Runs [block] and returns everything [Console] printed, separated into the visible stream and
 * stderr.
 *
 * [Console] snapshots `System.out` into private fields when its class initializes, so whether
 * `System.setOut` (or [CliOutCapture]) reaches [Console.log] and [Console.info] depends on which
 * test class happened to load [Console] first in the shared test JVM — green alone, empty in a
 * full run. Assigning those fields is the only capture that does not depend on test order.
 * [Console.error] resolves `System.err` on each call, so that half is an ordinary `System.setErr`.
 *
 * Quiet mode is lifted for the duration and restored afterwards: it silences [Console.log], and a
 * command in another test can leave it on.
 */
internal fun <T> captureConsole(block: () -> T): CapturedConsole<T> {
  val outBuffer = ByteArrayOutputStream()
  val errBuffer = ByteArrayOutputStream()
  val consoleFields = listOf("out", "userOut").map {
    Console::class.java.getDeclaredField(it).apply { isAccessible = true }
  }
  val originalConsoleStreams = consoleFields.map { it.get(Console) }
  val originalErr = System.err
  val wasQuiet = Console.isQuietMode()

  consoleFields.forEach { it.set(Console, PrintStream(outBuffer, true, Charsets.UTF_8)) }
  System.setErr(PrintStream(errBuffer, true, Charsets.UTF_8))
  if (wasQuiet) Console.disableQuietMode()
  try {
    val result = block()
    return CapturedConsole(result, outBuffer.toString(Charsets.UTF_8), errBuffer.toString(Charsets.UTF_8))
  } finally {
    System.setErr(originalErr)
    consoleFields.zip(originalConsoleStreams).forEach { (field, original) -> field.set(Console, original) }
    if (wasQuiet) Console.enableQuietMode()
  }
}
