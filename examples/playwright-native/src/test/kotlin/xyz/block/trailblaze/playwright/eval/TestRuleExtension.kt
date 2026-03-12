package xyz.block.trailblaze.playwright.eval

import java.lang.reflect.Method
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.InvocationInterceptor
import org.junit.jupiter.api.extension.ReflectiveInvocationContext
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * JUnit 5 extension that wraps a JUnit 4 [TestRule].
 *
 * This allows reusing existing JUnit 4 rules in JUnit 5 tests without maintaining
 * two separate implementations. The extension intercepts test method execution and
 * wraps it in the JUnit 4 rule's [TestRule.apply] lifecycle.
 *
 * Usage:
 * ```
 * @JvmField
 * @RegisterExtension
 * val myExtension = TestRuleExtension(myJunit4Rule)
 * ```
 */
class TestRuleExtension(
  val rule: TestRule,
) : InvocationInterceptor {

  override fun interceptTestMethod(
    invocation: InvocationInterceptor.Invocation<Void>,
    invocationContext: ReflectiveInvocationContext<Method>,
    extensionContext: ExtensionContext,
  ) {
    val description = Description.createTestDescription(
      extensionContext.requiredTestClass,
      extensionContext.requiredTestMethod.name,
    )
    val baseStatement = object : Statement() {
      override fun evaluate() {
        invocation.proceed()
      }
    }
    rule.apply(baseStatement, description).evaluate()
  }
}
