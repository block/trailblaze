package xyz.block.trailblaze.yaml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract tests for [TrailArgTokens] — the `{{args.x}}` token grammar validator shared by
 * `trailblaze check` and the runtime. Pins two rules that are permanent by design:
 *  - tokens are dotted paths only, never expressions (tokens are LLM-writable);
 *  - only the `args.` namespace is validated here — bare `{{x}}` / `{{memory.x}}` are untouched.
 */
class TrailArgTokensTest {

  @Test
  fun `scans only args tokens, leaving memory and bare tokens alone`() {
    val refs = TrailArgTokens.scanArgsTokens("{{args.recipient}} then {{memory.email}} then {{bare}}")
    assertEquals(listOf("args.recipient"), refs.map { it.body })
  }

  @Test
  fun `scans both delimiter styles`() {
    val refs = TrailArgTokens.scanArgsTokens("\${args.a} and {{args.b}}")
    assertEquals(listOf("args.a", "args.b"), refs.map { it.body })
  }

  @Test
  fun `top-level name strips the prefix and the dotted path tail`() {
    assertEquals("reply_to", TrailArgTokens.topLevelArgName("args.reply_to.email"))
    assertEquals("recipient", TrailArgTokens.topLevelArgName("args.recipient"))
  }

  @Test
  fun `dotted paths are valid, expressions are not`() {
    assertTrue(TrailArgTokens.isValidDottedPath("args.recipient"))
    assertTrue(TrailArgTokens.isValidDottedPath("args.reply_to.email"))
    assertTrue(!TrailArgTokens.isValidDottedPath("args.count + 1"))
    assertTrue(!TrailArgTokens.isValidDottedPath("args.x | upper"))
    assertTrue(!TrailArgTokens.isValidDottedPath("args.items[0]"))
  }

  @Test
  fun `an expression-bearing token is a malformed error`() {
    val errors = TrailArgTokens.validate("send {{args.count + 1}} times", declaredArgNames = setOf("count"))
    assertEquals(1, errors.size)
    assertTrue(errors.single().contains("dotted paths only"), errors.single())
  }

  @Test
  fun `an undeclared arg reference is an error naming the arg`() {
    val errors = TrailArgTokens.validate("to {{args.recipient}}", declaredArgNames = setOf("subject"))
    assertEquals(1, errors.size)
    assertTrue(errors.single().contains("recipient"), errors.single())
    assertTrue(errors.single().contains("Undeclared", ignoreCase = true), errors.single())
  }

  @Test
  fun `a declared reference validates clean`() {
    val errors = TrailArgTokens.validate("to {{args.recipient}}", declaredArgNames = setOf("recipient"))
    assertTrue(errors.isEmpty(), errors.toString())
  }

  @Test
  fun `a dotted sub-path validates against its top-level declared name`() {
    val errors = TrailArgTokens.validate(
      "to {{args.reply_to.email}}",
      declaredArgNames = setOf("reply_to"),
    )
    assertTrue(errors.isEmpty(), errors.toString())
  }

  @Test
  fun `a declared-but-unused arg is not an error`() {
    // Declaring more than a trail references is a harmless superset (shared arg sets across a
    // family of trails), so validation only flags references, never unused declarations.
    val errors = TrailArgTokens.validate("no tokens here", declaredArgNames = setOf("recipient"))
    assertTrue(errors.isEmpty(), errors.toString())
  }

  @Test
  fun `memory tokens are never validated by the args grammar`() {
    val errors = TrailArgTokens.validate("{{memory.whatever}} {{bare}}", declaredArgNames = emptySet())
    assertTrue(errors.isEmpty(), errors.toString())
  }
}
