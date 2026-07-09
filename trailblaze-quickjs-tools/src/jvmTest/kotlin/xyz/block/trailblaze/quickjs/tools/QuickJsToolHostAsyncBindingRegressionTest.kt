package xyz.block.trailblaze.quickjs.tools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.fail

/**
 * Regression guard for the quickjs-kt 1.0.5 native JNI bug (block/trailblaze#194): its
 * `asyncFunction` invoke path has a reference-lifecycle bug that crashes the JVM independent of
 * thread confinement -- see the `__trailblazeCall` install site in [QuickJsToolHost] for the full
 * rationale, and the devlog entry `2026-07-07-quickjs-async-host-binding-fix.md` for how this
 * repo ended up shipping the crash for three days despite already having backported a fix for
 * #194 (the wrong one -- a same-day upstream fix supersession we didn't know to go back for).
 *
 * The crash itself needs real device I/O timing and full-daemon GC pressure to reproduce -- a
 * pure-JVM harness didn't trip it even before the fix, per that devlog. So a unit test can't
 * exercise the native bug directly. The next-best guard is a source-level check that nobody
 * reintroduces `asyncFunction` for this binding during a future refactor (e.g. "clean this up to
 * use the idiomatic async API").
 */
class QuickJsToolHostAsyncBindingRegressionTest {

  @Test
  fun `QuickJsToolHost never binds __trailblazeCall via asyncFunction`() {
    val source = locateSource().readText()
    assertFalse(
      source.contains("asyncFunction("),
      "QuickJsToolHost.kt calls asyncFunction(...) again. quickjs-kt 1.0.5's asyncFunction " +
        "invoke path crashes the JVM (block/trailblaze#194) independent of thread confinement " +
        "-- __trailblazeCall must stay on a synchronous `function` binding + runBlocking. See " +
        "the devlog entry 2026-07-07-quickjs-async-host-binding-fix.md before reverting this.",
    )
  }

  private fun locateSource(): File {
    // Walks up from cwd looking for the repo-relative path, rather than guessing a fixed set of
    // candidate cwds -- same pattern as BundlerYamlSchemaDriftTest.locate() in :trailblaze-common.
    // Repo-relative path with NO leading directory prefix, so the walk-up resolves it no matter how
    // deeply the module is nested under the repo root -- same pattern as
    // BundlerYamlSchemaDriftTest.locate() in :trailblaze-common.
    val repoRelativePath =
      "trailblaze-quickjs-tools/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/quickjs/tools/QuickJsToolHost.kt"
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
      val candidate = File(dir, repoRelativePath)
      if (candidate.isFile) return candidate
      dir = dir.parentFile
    }
    fail("Could not locate $repoRelativePath by walking up from ${System.getProperty("user.dir")}.")
  }
}
