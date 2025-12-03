package xyz.block.trailblaze.rules

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * A JUnit rule that retries a test if it fails.
 *
 * ## How it works
 * When a test fails, this rule catches the exception and re-executes the test method up to [maxRetries] times.
 * If any attempt succeeds, the test passes. If all attempts fail, the last exception is thrown.
 *
 * ## Test Lifecycle During Retries
 * **IMPORTANT**: When a retry occurs, the test class instance is NOT re-instantiated.
 * The same test instance is reused, and only the test method is called again.
 *
 * **What happens on each retry:**
 * - ✅ Test method code runs again from the beginning
 * - ✅ Other rules in the chain re-execute (if this is the outermost rule)
 * - ✅ Any cleanup/setup done within the test method runs again
 * - ❌ Test class instance is NOT re-created
 * - ❌ Instance variables are NOT reset (they retain values from previous attempt)
 * - ❌ @Before/@BeforeEach methods do NOT run again
 * - ❌ @After/@AfterEach methods do NOT run between retries (only after final attempt)
 *
 * ## Best Practices
 * 1. **Use for external flakiness only**: This rule is designed for tests that fail due to external
 *    factors (network, timing, device state), not for masking bugs in test logic.
 *
 * 2. **Keep tests stateless**: Avoid relying on instance variables that accumulate state across retries.
 *    If you must track state across retries, use a companion object (static/class-level state).
 *
 * 3. **Clean up in the test method**: Any cleanup should happen within the test method itself,
 *    not in @Before/@After methods, since those won't run between retries.
 *
 * 4. **Use with RuleChain**: When combining with other rules, make RetryRule the outermost rule
 *    so it wraps all other setup/teardown logic:
 *    ```
 *    @get:Rule
 *    val ruleChain = RuleChain
 *      .outerRule(RetryRule(maxRetries = 1))
 *      .around(otherRule)
 *    ```
 *
 * ## Example
 * ```kotlin
 * class MyTest {
 *   @get:Rule
 *   val retryRule = RetryRule(maxRetries = 1)
 *
 *   @Test
 *   fun testWithExternalDependency() {
 *     // Clean up state at the start of each attempt
 *     cleanupExternalState()
 *
 *     // Test logic that might fail due to external flakiness
 *     val result = callFlakyExternalService()
 *     assertEquals(expected, result)
 *   }
 * }
 * ```
 *
 * @param maxRetries The maximum number of times to retry the test after the initial attempt.
 *                   Total executions = 1 initial attempt + maxRetries.
 *                   Example: maxRetries=1 means the test runs at most 2 times total.
 */
class RetryRule(private val maxRetries: Int) : TestRule {

  companion object {
    private val retriedTests = mutableListOf<RetryInfo>()
    private var shutdownHookInstalled = false

    /**
     * Information about a test that was retried
     */
    data class RetryInfo(
      val testName: String,
      val className: String,
      val attemptCount: Int,
      val succeeded: Boolean,
    )

    init {
      // Install a shutdown hook to print the summary when the JVM exits
      installShutdownHook()
    }

    private fun installShutdownHook() {
      if (!shutdownHookInstalled) {
        Runtime.getRuntime().addShutdownHook(
          Thread {
            printRetrySummary()
          },
        )
        shutdownHookInstalled = true
      }
    }

    private fun printRetrySummary() {
      if (retriedTests.isEmpty()) {
        return
      }

      println("\n" + "=".repeat(70))
      println("📊 RETRY SUMMARY - ${retriedTests.size} test(s) required retries")
      println("=".repeat(70))

      val succeeded = retriedTests.filter { it.succeeded }
      val failed = retriedTests.filter { !it.succeeded }

      if (succeeded.isNotEmpty()) {
        println("\n✅ Tests that PASSED after retry (${succeeded.size}):")
        succeeded.forEach { info ->
          println("   • ${info.className}.${info.testName}")
          println("     └─ Passed on attempt ${info.attemptCount}")
        }
      }

      if (failed.isNotEmpty()) {
        println("\n❌ Tests that FAILED even after retry (${failed.size}):")
        failed.forEach { info ->
          println("   • ${info.className}.${info.testName}")
          println("     └─ Failed after ${info.attemptCount} attempts")
        }
      }

      println("\n⚠️  WARNING: Retried tests indicate flakiness and should be investigated!")
      println("=".repeat(70) + "\n")
    }

    /**
     * Returns a list of all tests that needed retries during this test run.
     * Useful for identifying flaky tests in CI.
     */
    fun getRetriedTests(): List<RetryInfo> = retriedTests.toList()

    /**
     * Clears the retry statistics. Useful for testing.
     */
    fun clearRetryStats() {
      retriedTests.clear()
    }
  }

  override fun apply(base: Statement, description: Description): Statement {
    return object : Statement() {
      override fun evaluate() {
        var lastException: Throwable? = null
        var hadRetry = false

        // Initial attempt + retries
        for (attempt in 0..maxRetries) {
          try {
            if (attempt > 0) {
              hadRetry = true
              println("\n⚠️  ============================================================")
              println("⚠️  RETRY: ${description.displayName}")
              println("⚠️  Attempt ${attempt + 1} of ${maxRetries + 1}")
              println("⚠️  ============================================================\n")
            }
            base.evaluate()

            // Test passed
            if (hadRetry) {
              // Test passed after retry - this is important to know!
              println("\n✅ ============================================================")
              println("✅ RETRY SUCCEEDED: ${description.displayName}")
              println("✅ Test passed on attempt ${attempt + 1} after $attempt failure(s)")
              println("✅ ============================================================\n")

              // Track this retry
              retriedTests.add(
                RetryInfo(
                  testName = description.methodName,
                  className = description.className,
                  attemptCount = attempt + 1,
                  succeeded = true,
                ),
              )
            }
            return
          } catch (t: Throwable) {
            lastException = t
            if (attempt < maxRetries) {
              println("\n⚠️  Test ${description.displayName} failed on attempt ${attempt + 1}, will retry...")
            }
          }
        }

        // All attempts exhausted, throw the last exception
        println("\n❌ ============================================================")
        println("❌ RETRY EXHAUSTED: ${description.displayName}")
        println("❌ Test failed after ${maxRetries + 1} attempt(s)")
        println("❌ ============================================================\n")

        // Track this failed retry
        retriedTests.add(
          RetryInfo(
            testName = description.methodName,
            className = description.className,
            attemptCount = maxRetries + 1,
            succeeded = false,
          ),
        )

        throw lastException!!
      }
    }
  }
}
