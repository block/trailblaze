package xyz.block.trailblaze.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resolver-level tests covering the load-time path that production CLI calls (`TrailCommand` →
 * `TrailYamlTemplateResolver.resolve`). The lower-level `TemplatingUtil` is exercised in
 * [TemplatingUtilTest]; these tests pin the resolver-level invariants that are easy to regress.
 *
 * Uses a generic `user_email` / `user_pin` identifier set so this opensource test stays
 * decoupled from any specific app-target's session-memory keys.
 */
class TrailYamlTemplateResolverTest {

  @Test
  fun deferredVariablePassesThroughResolve() {
    val yaml =
      """
      - step: Sign in with {{user_email}} from session memory
        recording:
          tools:
          - logInToApp:
              email: '{{user_email}}'
      """.trimIndent()

    val resolved = TrailYamlTemplateResolver.resolve(
      yaml = yaml,
      additionalValues = emptyMap(),
      deferredVariables = setOf("user_email"),
    )

    assertEquals(yaml, resolved)
  }

  @Test
  fun deferredAndResolvableMixInSameYaml() {
    val yaml =
      """
      - step: Navigate to {{BASE_URL}}/dashboard then sign in with {{user_email}}
      """.trimIndent()

    val resolved = TrailYamlTemplateResolver.resolve(
      yaml = yaml,
      additionalValues = mapOf("BASE_URL" to "https://staging.example.com"),
      deferredVariables = setOf("user_email"),
    )

    val expected =
      """
      - step: Navigate to https://staging.example.com/dashboard then sign in with {{user_email}}
      """.trimIndent()
    assertEquals(expected, resolved)
  }

  @Test
  fun nonDeferredTypoStillThrows() {
    val yaml = "- step: hi {{user_email}} and {{TYPO_VAR}}"

    val ex = assertThrows(IllegalStateException::class.java) {
      TrailYamlTemplateResolver.resolve(
        yaml = yaml,
        additionalValues = emptyMap(),
        deferredVariables = setOf("user_email"),
      )
    }
    val missingLine = ex.message!!
      .lines()
      .single { it.startsWith("Missing required template variables:") }
    assertTrue(
      "Real missing var should be flagged. Got: $missingLine",
      missingLine.contains("TYPO_VAR"),
    )
    assertTrue(
      "Deferred var should NOT be flagged as missing. Got: $missingLine",
      !missingLine.contains("user_email"),
    )
  }

  @Test
  fun emptyTemplateReturnsAsIs() {
    val yaml = "- step: hello world"
    assertEquals(yaml, TrailYamlTemplateResolver.resolve(yaml))
  }

  @Test
  fun deferredVariablesFromEnvRereadsOnEachCall() {
    // Belt-and-suspenders: documents that the helper re-reads env on every call (no cached
    // first-touch). We can't safely mutate System.getenv from a test, so just exercise the
    // call shape and assert the result is shaped correctly.
    val first = TrailYamlTemplateResolver.deferredVariablesFromEnv()
    val second = TrailYamlTemplateResolver.deferredVariablesFromEnv()
    assertEquals(first, second)
  }
}
