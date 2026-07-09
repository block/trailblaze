package xyz.block.trailblaze.scripting.fetch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.fail

/**
 * Regression guard for the quickjs-kt 1.0.5 native JNI bug (block/trailblaze#194) -- same bug
 * class as `QuickJsToolHostAsyncBindingRegressionTest` in `:trailblaze-quickjs-tools`, applied to
 * this module's own `__trailblazeFetch` binding. See that test's kdoc, and the devlog entry
 * `2026-07-07-quickjs-async-host-binding-fix.md`, for the full rationale: `asyncFunction`'s
 * native invoke path crashes the JVM independent of thread confinement, and the crash needs real
 * device I/O timing plus full-daemon GC pressure to reproduce -- so this is a source-level guard,
 * not a runtime one.
 */
class OkHttpFetchExtensionAsyncBindingRegressionTest {

  @Test
  fun `OkHttpFetchExtension never binds __trailblazeFetch via asyncFunction`() {
    val source = locateSource().readText()
    assertFalse(
      source.contains("asyncFunction("),
      "OkHttpFetchExtension.kt calls asyncFunction(...) again. quickjs-kt 1.0.5's asyncFunction " +
        "invoke path crashes the JVM (block/trailblaze#194) independent of thread confinement " +
        "-- __trailblazeFetch must stay on a synchronous `function` binding + runBlocking. See " +
        "the devlog entry 2026-07-07-quickjs-async-host-binding-fix.md before reverting this.",
    )
  }

  private fun locateSource(): File {
    // Walks up from cwd looking for the repo-relative path, rather than guessing a fixed set of
    // candidate cwds -- same pattern as BundlerYamlSchemaDriftTest.locate() in :trailblaze-common.
    // The path has NO leading directory prefix so the walk-up resolves it no matter how deeply the
    // module is nested under the repo root.
    val repoRelativePath =
      "trailblaze-scripting-fetch/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/scripting/fetch/OkHttpFetchExtension.kt"
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
      val candidate = File(dir, repoRelativePath)
      if (candidate.isFile) return candidate
      dir = dir.parentFile
    }
    fail("Could not locate $repoRelativePath by walking up from ${System.getProperty("user.dir")}.")
  }
}
