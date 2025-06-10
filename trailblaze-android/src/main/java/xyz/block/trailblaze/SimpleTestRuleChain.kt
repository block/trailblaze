package xyz.block.trailblaze

import org.junit.rules.TestRule
import org.junit.runner.Description

/**
 * This implementation of [TestRule] attempts to make execution order more clear and
 * provides logging statements regarding the order of execution for easier debugging.
 *
 * Order: ruleCreation() "apply()" -> beforeTestExecution -> base.evaluate() -> afterTestExecution
 */
abstract class SimpleTestRuleChain(private vararg val testRule: SimpleTestRule) : SimpleTestRule() {

  override fun ruleCreation(description: Description) = testRule.forEach { it.ruleCreation(description) }

  override fun beforeTestExecution(description: Description) = testRule.forEach { it.beforeTestExecution(description) }

  override fun afterTestExecution(description: Description, result: Result<Nothing?>) {
    testRule.reversed().forEach { it.afterTestExecution(description, result) }
  }
}
