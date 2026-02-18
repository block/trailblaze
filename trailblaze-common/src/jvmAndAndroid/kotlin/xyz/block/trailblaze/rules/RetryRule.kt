package xyz.block.trailblaze.rules

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import xyz.block.trailblaze.util.Console

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

      Console.log("\n" + "=".repeat(70))
      Console.log("📊 RETRY SUMMARY - ${retriedTests.size} test(s) required retries")
      Console.log("=".repeat(70))

      val succeeded = retriedTests.filter { it.succeeded }
      val failed = retriedTests.filter { !it.succeeded }

      if (succeeded.isNotEmpty()) {
        Console.log("\n✅ Tests that PASSED after retry (${succeeded.size}):")
        succeeded.forEach { info ->
          Console.log("   • ${info.className}.${info.testName}")
          Console.log("     └─ Passed on attempt ${info.attemptCount}")
        }
      }

      if (failed.isNotEmpty()) {
        Console.log("\n❌ Tests that FAILED even after retry (${failed.size}):")
        failed.forEach { info ->
          Console.log("   • ${info.className}.${info.testName}")
          Console.log("     └─ Failed after ${info.attemptCount} attempts")
        }
      }

      Console.log("\n⚠️  WARNING: Retried tests indicate flakiness and should be investigated!")
      Console.log("=".repeat(70) + "\n")
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
              Console.log("\n⚠️  ============================================================")
              Console.log("⚠️  RETRY: ${description.displayName}")
              Console.log("⚠️  Attempt ${attempt + 1} of ${maxRetries + 1}")
              Console.log("⚠️  ============================================================\n")
            }
            base.evaluate()

            // Test passed
            if (hadRetry) {
              // Test passed after retry - this is important to know!
              Console.log("\n✅ ============================================================")
              Console.log("✅ RETRY SUCCEEDED: ${description.displayName}")
              Console.log("✅ Test passed on attempt ${attempt + 1} after $attempt failure(s)")
              Console.log("✅ ============================================================\n")

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
              Console.log("\n⚠️  Test ${description.displayName} failed on attempt ${attempt + 1}, will retry...")
            }
          }
        }

        // All attempts exhausted, throw the last exception
        Console.log("\n❌ ============================================================")
        Console.log("❌ RETRY EXHAUSTED: ${description.displayName}")
        Console.log("❌ Test failed after ${maxRetries + 1} attempt(s)")
        Console.log("❌ ============================================================\n")

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
