package xyz.block.trailblaze.cli

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the contract of [TrailCommand.Companion.parseProvidedArgs] — the helper that merges
 * `--args-file` (applied first) and repeatable `--arg KEY=VAL` (overrides per key) into the
 * provided-args map that is later declaration-bound at dispatch. Observable contract:
 *
 *  1. A `--arg` value is ALWAYS a JSON string ([JsonPrimitive] of the raw text). No CLI-side
 *     type guessing — `retries=3` stays the string "3"; declaration-driven coercion at bind
 *     time turns it into a number for an `integer` arg. `--arg x=null` is the 4-char string,
 *     never JSON null.
 *  2. `--arg` splits on the FIRST `=`, later-wins on repeat, allows an empty value, and rejects
 *     a malformed entry (no `=` / empty key) loudly (→ CLI MISUSE before any device/LLM work).
 *  3. `--args-file` keeps each value's parsed YAML/JSON shape (numbers native, leading-zero
 *     strings preserved, YAML-null → JSON null so bind can reject it loudly).
 *  4. `--arg` overrides an `--args-file` entry with the same key.
 */
class TrailCommandParseProvidedArgsTest {

  private fun tempArgsFile(content: String): File =
    File.createTempFile("args-", ".yaml").apply {
      deleteOnExit()
      writeText(content)
    }

  @Test
  fun `single --arg is a JSON string primitive`() {
    val args = TrailCommand.parseProvidedArgs(listOf("recipient=sam@example.com"), null)
    assertEquals(mapOf("recipient" to JsonPrimitive("sam@example.com")), args)
    assertTrue(args.getValue("recipient").let { it as JsonPrimitive }.isString)
  }

  @Test
  fun `numeric-looking --arg value stays a string (no CLI-side coercion)`() {
    val args = TrailCommand.parseProvidedArgs(listOf("retries=3"), null)
    val value = args.getValue("retries") as JsonPrimitive
    assertTrue(value.isString, "CLI must not guess types; coercion is declaration-driven at bind")
    assertEquals("3", value.content)
  }

  @Test
  fun `--arg x=null is the 4-char string, never JSON null`() {
    val args = TrailCommand.parseProvidedArgs(listOf("x=null"), null)
    val value = args.getValue("x") as JsonPrimitive
    assertTrue(value.isString)
    assertEquals("null", value.content)
  }

  @Test
  fun `--arg splits on the first equals so the value may contain more equals`() {
    val args = TrailCommand.parseProvidedArgs(listOf("token=a=b=c"), null)
    assertEquals(JsonPrimitive("a=b=c"), args.getValue("token"))
  }

  @Test
  fun `repeated --arg key is later-wins`() {
    val args = TrailCommand.parseProvidedArgs(listOf("user=first", "user=third"), null)
    assertEquals(JsonPrimitive("third"), args.getValue("user"))
  }

  @Test
  fun `empty --arg value is preserved`() {
    val args = TrailCommand.parseProvidedArgs(listOf("note="), null)
    assertEquals(JsonPrimitive(""), args.getValue("note"))
  }

  @Test
  fun `empty inputs yield empty map`() {
    assertEquals(emptyMap(), TrailCommand.parseProvidedArgs(emptyList(), null))
  }

  @Test
  fun `malformed --arg without equals throws`() {
    val e = assertFailsWith<IllegalArgumentException> {
      TrailCommand.parseProvidedArgs(listOf("noequals"), null)
    }
    // Load-bearing fragments only: names the offending entry and the expected shape.
    assertTrue(e.message!!.contains("noequals"), e.message)
    assertTrue(e.message!!.contains("KEY=VAL"), e.message)
  }

  @Test
  fun `malformed --arg with empty key throws`() {
    assertFailsWith<IllegalArgumentException> {
      TrailCommand.parseProvidedArgs(listOf("=value"), null)
    }
  }

  @Test
  fun `--args-file keeps native types and preserves leading-zero strings`() {
    val file = tempArgsFile(
      """
      recipient: sam@example.com
      retries: 3
      zip: "007"
      """.trimIndent(),
    )
    val args = TrailCommand.parseProvidedArgs(emptyList(), file)

    assertEquals(JsonPrimitive("sam@example.com"), args.getValue("recipient"))

    val retries = args.getValue("retries") as JsonPrimitive
    assertFalse(retries.isString, "A genuine YAML integer stays a JSON number")
    assertEquals("3", retries.content)

    val zip = args.getValue("zip") as JsonPrimitive
    assertTrue(zip.isString, "A leading-zero value must survive as a string")
    assertEquals("007", zip.content)
  }

  @Test
  fun `--args-file YAML null surfaces as JSON null (rejected later at bind)`() {
    val file = tempArgsFile("recipient: null")
    val args = TrailCommand.parseProvidedArgs(emptyList(), file)
    assertEquals(JsonNull, args.getValue("recipient"))
  }

  @Test
  fun `--arg overrides an --args-file entry with the same key`() {
    val file = tempArgsFile(
      """
      recipient: from-file@example.com
      retries: 1
      """.trimIndent(),
    )
    val args = TrailCommand.parseProvidedArgs(listOf("recipient=from-cli@example.com"), file)
    assertEquals(JsonPrimitive("from-cli@example.com"), args.getValue("recipient"))
    // File-only key is untouched by the override.
    assertEquals("1", (args.getValue("retries") as JsonPrimitive).content)
  }

  @Test
  fun `--args-file that is not a map throws`() {
    val file = tempArgsFile("- just\n- a\n- list")
    val e = assertFailsWith<IllegalArgumentException> {
      TrailCommand.parseProvidedArgs(emptyList(), file)
    }
    assertTrue(e.message!!.contains("must be a map of arg-name to value"))
  }

  @Test
  fun `--args-file that does not exist throws`() {
    val missing = File("/nonexistent/does-not-exist.yaml")
    val e = assertFailsWith<IllegalArgumentException> {
      TrailCommand.parseProvidedArgs(emptyList(), missing)
    }
    assertTrue(e.message!!.contains("--args-file not found"))
  }

  @Test
  fun `--args-file that is not parseable YAML or JSON throws a syntax error, not an IO error`() {
    val file = tempArgsFile("recipient: [unclosed")
    val e = assertFailsWith<IllegalArgumentException> {
      TrailCommand.parseProvidedArgs(emptyList(), file)
    }
    assertTrue(e.message!!.contains("not valid YAML/JSON"), e.message)
    assertTrue(e.message!!.contains(file.path), e.message)
  }
}
