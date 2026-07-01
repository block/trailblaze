package xyz.block.trailblaze.host

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Unit tests for [TrailTscValidator]'s pure codegen + diagnostic-remap logic — the two halves that
 * carry the load-bearing contract (one tool-call per line; tsc diagnostics map back to the right
 * trail + step). The IO orchestration (`validate`) is exercised end-to-end by `trailblaze check`
 * with the env var set; these tests pin the parts that must be correct without a device or a
 * compiler, following the repo's "extract the pure logic and test it directly" guidance.
 */
class TrailTscValidatorTest {

  @Test
  fun `generateGenFile emits one tool-call statement per line and maps each line back to its call`() {
    val calls = listOf(
      TrailTscValidator.RecordedCall("web_navigate", """{"url":"https://example.com"}""", 1, "Open site"),
      TrailTscValidator.RecordedCall("web_verifyTextVisible", """{"text":"Welcome"}""", 2, "Verify banner"),
    )
    val gen = TrailTscValidator.generateGenFile("trails/demo.trail.yaml", calls)
    val lines = gen.source.lines()

    // Every table entry points at a real generated line that contains exactly that tool call —
    // this is the invariant the diagnostic remap depends on.
    assertEquals(2, gen.table.size)
    gen.table.forEach { (lineNo, call) ->
      val line = lines[lineNo - 1] // table keys are 1-based
      assertTrue(line.contains("client.tools.${call.toolName}("), "line $lineNo should call ${call.toolName}: $line")
      assertTrue(line.contains(call.argsJson), "line $lineNo should carry the args literal: $line")
    }
    // The two calls land on distinct lines (one statement per line).
    assertEquals(2, gen.table.keys.distinct().size)
    // Source is a valid-shaped TS module: typed client declaration, no execution.
    assertTrue(gen.source.contains("declare const client: TrailblazeClient;"))
  }

  @Test
  fun `generateGenFile uses bracket access with an escaped key for a non-identifier tool name`() {
    val calls = listOf(TrailTscValidator.RecordedCall("weird-tool.name", "{}", 1, "Edge"))
    val gen = TrailTscValidator.generateGenFile("trails/x.trail.yaml", calls)
    val (lineNo, _) = gen.table.entries.single()
    val line = gen.source.lines()[lineNo - 1]
    // A `-`/`.` name can't be a dot-access identifier; bracket access with a quoted key keeps the
    // call aligned with the typed surface (and can't break out of the generated TS).
    assertTrue(line.contains("""client.tools["weird-tool.name"]({})"""), "bracket access: $line")
    assertTrue(!line.contains("client.tools.weird-tool"))
  }

  @Test
  fun `generateGenFile escapes a bracket-access key containing a quote`() {
    // A malformed/hand-edited tool name with a `"` must be escaped inside the bracket-access string
    // key so it stays a single valid string literal and can't break out of the generated TS.
    val calls = listOf(TrailTscValidator.RecordedCall("ev\"il", "{}", 1, "Edge"))
    val gen = TrailTscValidator.generateGenFile("trails/x.trail.yaml", calls)
    val (lineNo, _) = gen.table.entries.single()
    val line = gen.source.lines()[lineNo - 1]
    assertTrue(line.contains("""client.tools["ev\"il"]({})"""), "quote escaped in bracket key: $line")
  }

  @Test
  fun `generateGenFile handles an empty call list as a valid empty module`() {
    val gen = TrailTscValidator.generateGenFile("trails/empty.trail.yaml", emptyList())
    assertTrue(gen.table.isEmpty())
    // Still a well-formed TS module the compiler accepts (no dangling statements).
    assertTrue(gen.source.contains("async function __trail__"))
    assertTrue(gen.source.contains("void __trail__;"))
  }

  @Test
  fun `generateGenFile keeps each statement on a single line even when a step label has newlines`() {
    val calls = listOf(
      TrailTscValidator.RecordedCall("tap", "{}", 1, "line one\nline two\nline three"),
    )
    val gen = TrailTscValidator.generateGenFile("trails/x.trail.yaml", calls)
    val (lineNo, _) = gen.table.entries.single()
    val line = gen.source.lines()[lineNo - 1]
    // The whole call (and its comment) stays on one physical line — the remap's line-table
    // invariant depends on it, so an embedded newline in the label must not split it.
    assertTrue(line.contains("client.tools.tap({})"), "statement intact: $line")
    assertTrue(!line.contains("\n"))
  }

  @Test
  fun `deviceClassifiersFromStem splits a per-device filename into lowercase classifiers`() {
    assertEquals(
      listOf("ios", "iphone"),
      TrailTscValidator.deviceClassifiersFromStem("iOS-iPhone").map { it.classifier },
    )
    assertEquals(
      listOf("android", "phone"),
      TrailTscValidator.deviceClassifiersFromStem("android-phone").map { it.classifier },
    )
  }

  @Test
  fun `remap keys a diagnostic back to the trail and step via the line table`() {
    val table = mapOf(
      5 to TrailTscValidator.RecordedCall("web_verifyTextVisible", """{"txt":"x"}""", 3, "Verify banner"),
    )
    val metas = mapOf("login.trail.gen.ts" to TrailTscValidator.GenFileMeta("trails/login.trail.yaml", table))
    val tsc =
      "/abs/path/login.trail.gen.ts(5,40): error TS2561: Object literal may only specify known " +
        "properties, but 'txt' does not exist in type '{ text: string; }'."

    val findings = TrailTscValidator.remap(tsc, metas)

    assertEquals(1, findings.size)
    val f = findings.single()
    assertEquals("trails/login.trail.yaml", f.trailRelPath)
    assertEquals(3, f.stepIndex)
    assertEquals("web_verifyTextVisible", f.toolName)
    assertEquals("TS2561", f.tsCode)
    assertTrue(f.message.contains("'txt' does not exist"), "message preserved: ${f.message}")
  }

  @Test
  fun `remap folds indented continuation lines into the preceding finding`() {
    val table = mapOf(7 to TrailTscValidator.RecordedCall("inputText", "{}", 4, "Type passcode"))
    val metas = mapOf("x.trail.gen.ts" to TrailTscValidator.GenFileMeta("trails/x.trail.yaml", table))
    val tsc = buildString {
      appendLine("x.trail.gen.ts(7,16): error TS2345: Argument of type '{}' is not assignable.")
      appendLine("  Property 'text' is missing in type '{}' but required in type '{ text: string; }'.")
    }

    val findings = TrailTscValidator.remap(tsc, metas)

    assertEquals(1, findings.size)
    assertTrue(findings.single().message.contains("Property 'text' is missing"), "continuation folded in")
  }

  @Test
  fun `remap drops diagnostics on lines with no table entry (e g header lines)`() {
    val table = mapOf(5 to TrailTscValidator.RecordedCall("tap", "{}", 1, "Tap"))
    val metas = mapOf("y.trail.gen.ts" to TrailTscValidator.GenFileMeta("trails/y.trail.yaml", table))
    // Diagnostic on line 2 (a header line) — not in the table.
    val tsc = "y.trail.gen.ts(2,1): error TS2307: Cannot find module '@trailblaze/scripting'."

    assertTrue(TrailTscValidator.remap(tsc, metas).isEmpty())
  }

  @Test
  fun `remap ignores diagnostics for unknown gen files`() {
    val metas = mapOf("known.trail.gen.ts" to TrailTscValidator.GenFileMeta("trails/known.trail.yaml", mapOf(5 to TrailTscValidator.RecordedCall("tap", "{}", 1, "Tap"))))
    val tsc = "other.trail.gen.ts(5,1): error TS2339: Property 'x' does not exist."
    assertTrue(TrailTscValidator.remap(tsc, metas).isEmpty())
    assertNull(metas["other.trail.gen.ts"])
  }
}
