package xyz.block.trailblaze

import android.util.Log
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.MultipleFailureException
import org.junit.runners.model.Statement

/**
 * This implementation of [TestRule] attempts to make execution order more clear and
 * provides logging statements regarding the order of execution for easier debugging.
 *
 * Order: ruleCreation() "apply()" -> beforeTestExecution -> base.evaluate() -> afterTestExecution
 */
abstract class SimpleTestRule(private val enableLogging: Boolean = false) : TestRule {

  /**
   * Occurs when the chain of statements is being constructed in the apply() method.
   *
   * This apply() phase is responsible for continuing the chain of [Statement]s.
   *
   * Order: outerRule -> innerRule.
   */
  open fun ruleCreation(description: Description) {
  }

  /**
   * Before test statement is executed.
   *
   * Order: innerRule -> outerRule
   */
  open fun beforeTestExecution(description: Description) {
  }

  /**
   * After test statement is executed.
   *
   * [result] is [Result.success] if the test passed, [Result.failure] if it failed, with the
   * exception populated.
   *
   * Implementations may throw from here to make a successful test fail.
   *
   *
   * Order: outerRule -> innerRule
   */
  open fun afterTestExecution(description: Description, result: Result<Nothing?>) {
  }

  private val testRuleClassName = this::class.simpleName

  private fun log(
    description: Description,
    message: String,
  ) {
    if (enableLogging) {
      Log.d(
        SimpleTestRule::class.java.simpleName,
        "$testRuleClassName - $message - ${description.testClass}#${description.methodName}",
      )
    }
  }

  final override fun apply(
    base: Statement,
    description: Description,
  ): Statement {
    log(description, "ruleCreation()")
    ruleCreation(description)
    return object : Statement() {
      @Suppress("TooGenericExceptionCaught")
      override fun evaluate() {
        log(description, "beforeTestExecution()")
        beforeTestExecution(description)
        val errors = mutableListOf<Throwable>()
        try {
          log(description, "base.evaluate()")
          base.evaluate()
        } catch (t: Throwable) {
          errors.add(t)
        } finally {
          val result = if (errors.isEmpty()) {
            Result.success(null)
          } else {
            Result.failure(errors.first())
          }
          try {
            log(description, "afterTestExecution()")
            afterTestExecution(description, result)
          } catch (t: Throwable) {
            errors.add(t)
          }
        }
        if (errors.isNotEmpty()) {
          throw MultipleFailureException(errors)
        }
      }
    }
  }
}
