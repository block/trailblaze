package xyz.block.trailblaze.cli

import org.junit.rules.ExternalResource
import xyz.block.trailblaze.util.Console

/**
 * Fails a test that leaves [Console] quiet mode switched on, and puts the flag back either way.
 *
 * Quiet mode is process-global and the module's tests share a JVM, so a test that drives a CLI
 * command and leaves the flag set silences every test scheduled after it — and the failure lands
 * on whichever class ran next. This names the class that actually leaked, in its own failure, and
 * stops the cascade.
 *
 * ```kotlin
 * @Rule @JvmField val quietMode = QuietModeRule()
 * ```
 */
class QuietModeRule : ExternalResource() {

  override fun before() {
    // Start loud regardless of what ran before, so a class behind a leaker still tests itself.
    Console.disableQuietMode()
  }

  override fun after() {
    val leaked = Console.isQuietMode()
    Console.disableQuietMode()
    check(!leaked) {
      "This test left Console quiet mode switched on. Quiet mode is process-global, so every " +
        "test scheduled after this one in the shared JVM would have run with Console.log " +
        "silenced. Wrap the scope in Console.runQuiet or quietUnlessVerbose rather than calling " +
        "Console.enableQuietMode() bare."
    }
  }
}
