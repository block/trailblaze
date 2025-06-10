package xyz.block.trailblaze.util

import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.test.assertTrue

class TemplatingUtilTest {

  @Test
  fun replaceVariables() {
    val template = """Something ${'$'}var1 and ${'$'}{var2}""".trimIndent()
    println(template)
    TemplatingUtil.renderTemplate(
      template = template,
      values = mapOf(
        "var1" to "value1",
        "var2" to "value2",
      ),
    ).also {
      println(it)
    }
  }

  @Test
  fun missingRequiredValues() {
    val template = """Something ${'$'}var1 and ${'$'}{var2}""".trimIndent()
    println(template)
    val exception = assertThrows(IllegalStateException::class.java) {
      TemplatingUtil.renderTemplate(
        template = template,
        values = mapOf(),
      )
    }
    assertTrue(exception.message!!.trim().endsWith("Missing required template variables: var1, var2"))
  }
}
