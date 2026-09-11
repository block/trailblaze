package xyz.block.trailblaze.inprocessidle

import org.junit.Test
import xyz.block.trailblaze.device.AndroidPackageDump
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The turbo AOT-compile decision, shared by the host installer and the on-device attacher.
 *
 * Worth pinning off-device because the two things that went wrong here were an argument (`-f`) and
 * a missing guard — neither of which any device test would have caught, since a wrong answer costs
 * only time and the run still passes.
 */
class AotCompileDecisionTest {

  @Test
  fun `the compile is never forced, because an attach is not once per run`() {
    // THE regression. `-f` recompiles a package already at `speed`, and anything that drops the
    // detector re-attaches: on an Android tablet, two app-data clears per trail each
    // paid a full forced recompile, ~150s of dead wall clock, turning turbo into a net loss there.
    assertEquals(listOf("-m", "speed"), AotCompileDecision.COMPILER_FILTER_ARGS)
    assertTrue("-f" !in AotCompileDecision.COMPILER_FILTER_ARGS)
  }

  @Test
  fun `a release target is compiled`() {
    assertEquals(
      AotCompileDecision.Compile(AotCompileDecision.COMPILER_FILTER_ARGS),
      decisionFor(requested = true, debuggable = AndroidPackageDump.Debuggable.NO),
    )
  }

  @Test
  fun `a debuggable target is not compiled at all`() {
    // ART compiles a debuggable package at `verify` whatever is asked, so the compile does the full
    // work and changes nothing. Every dev / eng build and every debug-variant CI run is here
    // — including the runs that measure turbo, which is why this was pure cost on every device.
    val decision = decisionFor(requested = true, debuggable = AndroidPackageDump.Debuggable.YES)
    val skip = decision as? AotCompileDecision.Skip
      ?: error("expected a skip, got $decision")
    // The reason is the log line an operator reads, and a skip is otherwise indistinguishable from
    // a step that never ran, so it has to name both the package and why.
    assertTrue("com.example.app" in skip.reason, skip.reason)
    assertTrue("debuggable" in skip.reason, skip.reason)
  }

  @Test
  fun `a target we could not read is compiled anyway`() {
    // Deliberately not a skip. Losing the compile is what actually breaks an attach — it is what
    // keeps the instrumented cold start inside the platform's proc-start ANR window — whereas an
    // unnecessary compile only costs time, and unforced it costs almost none on a repeat.
    assertEquals(
      AotCompileDecision.Compile(AotCompileDecision.COMPILER_FILTER_ARGS),
      decisionFor(requested = true, debuggable = AndroidPackageDump.Debuggable.UNKNOWN),
    )
  }

  @Test
  fun `a run that did not ask for the compile never probes the package`() {
    // Asserted with an exploding probe rather than a plain null check, because no answer could
    // change the verdict and this is the common case: every lane with the knob unset would
    // otherwise pay a device round trip to be told something already known.
    assertEquals(
      AotCompileDecision.NotRequested,
      AotCompileDecision.forTarget(
        requested = false,
        appId = "com.example.app",
        debuggable = { error("probed the package for a run that never asked for a compile") },
      ),
    )
  }

  @Test
  fun `the package is probed at most once`() {
    // The probe is a device round trip on the attach path, which runs again after every
    // `clearAppData` and re-launch. A second call per decision would double that cost silently.
    var probes = 0
    AotCompileDecision.forTarget(
      requested = true,
      appId = "com.example.app",
      debuggable = {
        probes++
        AndroidPackageDump.Debuggable.NO
      },
    )
    assertEquals(1, probes)
  }

  private fun decisionFor(
    requested: Boolean,
    debuggable: AndroidPackageDump.Debuggable,
  ): AotCompileDecision = AotCompileDecision.forTarget(
    requested = requested,
    appId = "com.example.app",
    debuggable = { debuggable },
  )
}
