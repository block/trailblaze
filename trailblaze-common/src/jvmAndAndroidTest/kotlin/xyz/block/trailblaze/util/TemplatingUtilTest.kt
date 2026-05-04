package xyz.block.trailblaze.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.test.assertTrue

class TemplatingUtilTest {

  @Test
  fun replaceVariables() {
    val template = """Something {{var1}} and {{var2}}""".trimIndent()
    Console.log(template)
    TemplatingUtil.renderTemplate(
      template = template,
      values = mapOf(
        "var1" to "value1",
        "var2" to "value2",
      ),
    ).also {
      Console.log(it)
    }
  }

  @Test
  fun missingRequiredValues() {
    val template = """Something {{var1}} and {{var2}}""".trimIndent()
    Console.log(template)
    val exception = assertThrows(IllegalStateException::class.java) {
      TemplatingUtil.renderTemplate(
        template = template,
        values = mapOf(),
      )
    }
    assertTrue(exception.message!!.trim().endsWith("Missing required template variables: var1, var2"))
  }

  @Test
  fun deferredVariablesPassThroughUntouched() {
    val template = "Hello {{deferred_var}} and {{var1}}"
    val result = TemplatingUtil.replaceVariables(
      template = template,
      values = mapOf("var1" to "value1"),
      deferred = setOf("deferred_var"),
    )
    assertEquals("Hello {{deferred_var}} and value1", result)
  }

  @Test
  fun deferredMissingVariableDoesNotThrow() {
    // deferred_var isn't in `values` but is deferred — the runtime will substitute it later.
    val template = "Hello {{deferred_var}}"
    val result = TemplatingUtil.replaceVariables(
      template = template,
      values = emptyMap(),
      deferred = setOf("deferred_var"),
    )
    assertEquals("Hello {{deferred_var}}", result)
  }

  @Test
  fun nonDeferredMissingVariableStillThrows() {
    val template = "Hello {{deferred_var}} and {{typo_var}}"
    val exception = assertThrows(IllegalStateException::class.java) {
      TemplatingUtil.replaceVariables(
        template = template,
        values = emptyMap(),
        deferred = setOf("deferred_var"),
      )
    }
    // The error message includes the full template above the "Missing required …" line,
    // so we can't just substring-match the whole message. Inspect only the listing line.
    val missingLine = exception.message!!
      .lines()
      .single { it.startsWith("Missing required template variables:") }
    assertTrue(
      missingLine.contains("typo_var"),
      "Real missing var should be flagged. Got: $missingLine",
    )
    assertTrue(
      !missingLine.contains("deferred_var"),
      "Deferred var should NOT be flagged as missing. Got: $missingLine",
    )
  }

  @Test
  fun deferredKeyInValuesIsStillNotSubstituted() {
    // Defensive: even if a caller puts a deferred key in `values`, the {{key}} stays a literal.
    val template = "Email {{deferred_var}}"
    val result = TemplatingUtil.replaceVariables(
      template = template,
      values = mapOf("deferred_var" to "should-not-appear@example.com"),
      deferred = setOf("deferred_var"),
    )
    assertEquals("Email {{deferred_var}}", result)
  }
}
