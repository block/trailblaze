package xyz.block.trailblaze.android.test

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.Description
import xyz.block.trailblaze.tracing.TraceLevel
import xyz.block.trailblaze.tracing.TrailblazeTracer

/**
 * On-device contract for how [AndroidTestLoggingRule] hands the trace level to a run.
 *
 * The level is process-global and one instrumentation runs many test classes, so every claim here
 * is really the same claim: this rule must leave the level exactly as it found it, on every path
 * out. Three ways out:
 * 1. **The test ran.** Applied for the test, restored afterwards.
 * 2. **Setup failed.** [xyz.block.trailblaze.rules.SimpleTestRule] calls `beforeTestExecution`
 *    *outside* the `try/finally` that runs `afterTestExecution`, so a rule that applies the level
 *    and then throws never gets its teardown — and leaks the level into every later class in the
 *    run.
 * 3. **Nothing was asked for.** No requested level must mean "leave it alone", not "pin it to
 *    normal". An app harness that configured the tracer itself keeps its setting.
 *
 * Drives the rule's lifecycle methods directly rather than through a JUnit runner: the failure in
 * claim 2 is a *setup* failure, and a rule that fails its own setup cannot also be the rule under
 * test in the same run.
 */
class AndroidTestLoggingRuleTraceLevelOnDeviceTest {

  private lateinit var incomingLevel: TraceLevel

  @Before
  fun captureIncomingLevel() {
    incomingLevel = TrailblazeTracer.level
  }

  @After
  fun restoreIncomingLevel() {
    TrailblazeTracer.level = incomingLevel
    TrailblazeTracer.clear()
  }

  @Test
  fun appliesTheRequestedLevelForTheTestAndRestoresItAfterwards() {
    TrailblazeTracer.level = TraceLevel.NORMAL
    val rule = AndroidTestLoggingRule(requestedTraceLevel = TraceLevel.VERBOSE)
    val description = Description.createTestDescription(javaClass, "appliesTheRequestedLevel")

    rule.beforeTestExecution(description)
    assertEquals(
      TraceLevel.VERBOSE,
      TrailblazeTracer.level,
      "The requested level was not applied, so the driver's traceDetail phases would record " +
        "nothing for a run that explicitly asked for them.",
    )

    rule.afterTestExecution(description, Result.success(null))
    assertEquals(
      TraceLevel.NORMAL,
      TrailblazeTracer.level,
      "The level was not handed back after the test. It is process-global, so every later test " +
        "class in this instrumentation would inherit a level it never asked for.",
    )
  }

  @Test
  fun restoresTheLevelWhenSessionStartFails() {
    TrailblazeTracer.level = TraceLevel.NORMAL
    val rule = AndroidTestLoggingRule(requestedTraceLevel = TraceLevel.VERBOSE)

    // A Description with no test class: the base rule builds the session name from
    // `description.testClass.canonicalName`, so starting the session throws.
    val setupOutcome = runCatching {
      rule.beforeTestExecution(Description.createSuiteDescription("no-test-class"))
    }

    assertTrue(
      setupOutcome.isFailure,
      "Session start did not fail, so this test proved nothing. It exists to cover the case where " +
        "setup throws; if the base rule stopped throwing here, find another way to make it fail " +
        "rather than deleting the assertion below.",
    )
    assertEquals(
      TraceLevel.NORMAL,
      TrailblazeTracer.level,
      "The level stayed applied after setup failed. SimpleTestRule calls beforeTestExecution " +
        "outside the try/finally that runs afterTestExecution, so nothing else will put it back — " +
        "the whole rest of the instrumentation runs at a level it never asked for.",
    )
  }

  @Test
  fun leavesTheProcessLevelAloneWhenNothingWasRequested() {
    TrailblazeTracer.level = TraceLevel.VERBOSE
    val rule = AndroidTestLoggingRule(requestedTraceLevel = null)
    val description = Description.createTestDescription(javaClass, "leavesTheProcessLevelAlone")

    rule.beforeTestExecution(description)
    assertEquals(
      TraceLevel.VERBOSE,
      TrailblazeTracer.level,
      "No level was requested, but the rule changed one anyway — so a harness that configured the " +
        "tracer itself silently loses its setting just by adding this rule.",
    )

    rule.afterTestExecution(description, Result.success(null))
    assertEquals(
      TraceLevel.VERBOSE,
      TrailblazeTracer.level,
      "Teardown changed a level the rule never applied.",
    )
  }
}
