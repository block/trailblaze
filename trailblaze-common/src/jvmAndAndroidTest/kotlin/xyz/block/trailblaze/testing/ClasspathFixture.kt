package xyz.block.trailblaze.testing

import java.io.File
import java.net.URLClassLoader
import kotlin.io.path.createTempDirectory

/**
 * Shared test infrastructure for tests that need to swap [Thread.currentThread]'s
 * context classloader to point at a fake "framework" classpath rooted at a temp
 * directory. Used by:
 *
 *  - `PackSourceTest`
 *  - `TrailblazePackManifestLoaderTest`
 *  - `TrailblazeProjectConfigLoaderTest`
 *
 * Two test classes outside this module (`WaypointDiscoveryTest` in `:trailblaze-host`,
 * `ClasspathResourceDiscoveryRecursiveTest` in `:trailblaze-models`) keep small inline
 * copies of the same pattern — sharing across KMP module boundaries via
 * `java-test-fixtures` is significant Gradle plumbing for ~30 lines, and an in-module
 * dedup already eliminates the bulk of the duplication. If a fourth out-of-module
 * test starts copying this pattern, that's the trigger to invest in test-fixtures
 * infrastructure.
 *
 * ## Usage
 *
 * ```
 * class MyTest {
 *   private val classpath = ClasspathFixture()
 *
 *   @AfterTest fun cleanup() = classpath.cleanup()
 *
 *   @Test fun `something`() {
 *     val root = classpath.newTempDir()
 *     // populate root with fake classpath layout, then:
 *     classpath.withClasspathRoot(root) {
 *       // body runs with Thread.currentThread.contextClassLoader pointed at `root`
 *     }
 *   }
 * }
 * ```
 *
 * The fixture is instance-scoped so each test class manages its own cleanup; this
 * avoids static state that could leak between tests run in the same JVM.
 */
class ClasspathFixture {

  private val tempDirs = mutableListOf<File>()

  /**
   * Creates a temp directory tracked for deletion in [cleanup]. Directories are
   * named after `prefix` so failed-test residue is recognizable.
   */
  fun newTempDir(prefix: String = "classpath-fixture"): File =
    createTempDirectory(prefix).toFile().also { tempDirs += it }

  /**
   * Runs [block] with [Thread.currentThread]'s context classloader swapped for a
   * fresh [URLClassLoader] rooted at [root]. The original classloader is restored
   * (and the [URLClassLoader] closed) on both normal return and exception, so a
   * failing test doesn't leak classloader state into the next one.
   */
  fun <T> withClasspathRoot(root: File, block: () -> T): T {
    val classLoader = URLClassLoader(arrayOf(root.toURI().toURL()), null)
    val originalCcl = Thread.currentThread().contextClassLoader
    Thread.currentThread().contextClassLoader = classLoader
    try {
      return block()
    } finally {
      Thread.currentThread().contextClassLoader = originalCcl
      classLoader.close()
    }
  }

  /** Recursively removes every directory created via [newTempDir]. Idempotent. */
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }
}
