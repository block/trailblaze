package xyz.block.trailblaze.bundle.yaml

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct unit tests for [YamlEmitter] — the shared YAML emit + plain-scalar resolution
 * helpers used by both `TrailblazeBundledConfigTasks` (the bundled-config plugin's
 * pack→target generator) and `TrailblazeDesktopUtil` (the Goose-config writer).
 *
 * Two prior in-tree implementations of these helpers were tested only indirectly via
 * byte-identical regen (the bundled-config verification task) and runtime Goose
 * interaction (which had no tests at all). This file is the single canonical
 * regression surface — a quoting/scalar-resolution/emit bug here surfaces immediately,
 * and the byte-identical task remains as a higher-level integration check.
 */
class YamlEmitterTest {

  // ---- resolveYamlScalar ----

  @Test
  fun `resolveYamlScalar returns null for empty and explicit null forms`() {
    assertNull(YamlEmitter.resolveYamlScalar(""))
    assertNull(YamlEmitter.resolveYamlScalar("~"))
    assertNull(YamlEmitter.resolveYamlScalar("null"))
    assertNull(YamlEmitter.resolveYamlScalar("Null"))
    assertNull(YamlEmitter.resolveYamlScalar("NULL"))
  }

  @Test
  fun `resolveYamlScalar returns Boolean for canonical YAML 1_2 forms only`() {
    assertEquals(true, YamlEmitter.resolveYamlScalar("true"))
    assertEquals(true, YamlEmitter.resolveYamlScalar("True"))
    assertEquals(true, YamlEmitter.resolveYamlScalar("TRUE"))
    assertEquals(false, YamlEmitter.resolveYamlScalar("false"))
    assertEquals(false, YamlEmitter.resolveYamlScalar("False"))
    assertEquals(false, YamlEmitter.resolveYamlScalar("FALSE"))
    // YAML 1.1 forms intentionally not resolved — they collide with legitimate strings.
    assertEquals("yes", YamlEmitter.resolveYamlScalar("yes"))
    assertEquals("no", YamlEmitter.resolveYamlScalar("no"))
    assertEquals("on", YamlEmitter.resolveYamlScalar("on"))
    assertEquals("off", YamlEmitter.resolveYamlScalar("off"))
  }

  @Test
  fun `resolveYamlScalar returns the original String for ordinary content`() {
    assertEquals("hello", YamlEmitter.resolveYamlScalar("hello"))
    assertEquals("com.example.alpha", YamlEmitter.resolveYamlScalar("com.example.alpha"))
    assertEquals("123", YamlEmitter.resolveYamlScalar("123"))
  }

  // ---- needsYamlQuoting ----

  @Test
  fun `needsYamlQuoting flags reserved words and YAML 1_1 booleans`() {
    listOf(
      "yes", "Yes", "YES", "no", "true", "false", "null", "on", "off", "y", "n", "~",
    ).forEach { assertTrue("expected '$it' to need quoting") { YamlEmitter.needsYamlQuoting(it) } }
  }

  @Test
  fun `needsYamlQuoting flags numeric-looking strings`() {
    assertTrue { YamlEmitter.needsYamlQuoting("123") }
    assertTrue { YamlEmitter.needsYamlQuoting("0.0") }
    assertTrue { YamlEmitter.needsYamlQuoting("-42") }
    assertTrue { YamlEmitter.needsYamlQuoting("1e5") }
  }

  @Test
  fun `needsYamlQuoting flags strings with structural characters`() {
    assertTrue { YamlEmitter.needsYamlQuoting("") }
    assertTrue { YamlEmitter.needsYamlQuoting(" leading-space") }
    assertTrue { YamlEmitter.needsYamlQuoting("trailing-space ") }
    assertTrue { YamlEmitter.needsYamlQuoting("contains: colon") }
    assertTrue { YamlEmitter.needsYamlQuoting("trailing-colon:") }
    assertTrue { YamlEmitter.needsYamlQuoting("[flow]") }
    assertTrue { YamlEmitter.needsYamlQuoting("multi\nline") }
  }

  @Test
  fun `needsYamlQuoting passes ordinary identifiers unquoted`() {
    listOf(
      "hello", "com.example.alpha", "kebab-case", "snake_case", "camelCase",
      "Capitalized", "with space",
    ).forEach { assertFalse("expected '$it' NOT to need quoting") { YamlEmitter.needsYamlQuoting(it) } }
  }

  // ---- formatScalar (the dispatcher) ----

  @Test
  fun `formatScalar emits booleans and numbers without quotes`() {
    assertEquals("true", YamlEmitter.formatScalar(true))
    assertEquals("false", YamlEmitter.formatScalar(false))
    assertEquals("42", YamlEmitter.formatScalar(42))
    assertEquals("3.14", YamlEmitter.formatScalar(3.14))
    assertEquals("null", YamlEmitter.formatScalar(null))
  }

  @Test
  fun `formatScalar quotes only Strings that need quoting`() {
    assertEquals("hello", YamlEmitter.formatScalar("hello"))
    assertEquals("'true'", YamlEmitter.formatScalar("true"))
    assertEquals("'123'", YamlEmitter.formatScalar("123"))
    assertEquals("'yes'", YamlEmitter.formatScalar("yes"))
  }

  @Test
  fun `formatScalar doubles single-quotes inside quoted strings`() {
    // Apostrophes alone don't force quoting under YAML 1.2 plain-scalar rules. Use a
    // string that *does* need quoting (here: contains `: `, a structural separator) so
    // the apostrophe-doubling escape path is exercised.
    assertEquals("'it''s: ok'", YamlEmitter.formatScalar("it's: ok"))
  }

  // ---- renderMap / appendList ----

  @Test
  fun `renderMap emits scalar keys with 2-space indent`() {
    val out = YamlEmitter.renderMap(mapOf("name" to "Alpha", "id" to 1))
    assertEquals("name: Alpha\nid: 1\n", out)
  }

  @Test
  fun `renderMap emits a list of scalars with dashes flush against parent indent`() {
    val out = YamlEmitter.renderMap(mapOf("apps" to listOf("a", "b", "c")))
    assertEquals(
      """
        |apps:
        |  - a
        |  - b
        |  - c
        |
      """.trimMargin(),
      out,
    )
  }

  @Test
  fun `renderMap emits a list of maps with first key on the dash line`() {
    val out = YamlEmitter.renderMap(
      mapOf(
        "items" to listOf(
          linkedMapOf("name" to "alpha", "kind" to "core"),
          linkedMapOf("name" to "beta", "kind" to "verification"),
        ),
      ),
    )
    assertEquals(
      """
        |items:
        |  - name: alpha
        |    kind: core
        |  - name: beta
        |    kind: verification
        |
      """.trimMargin(),
      out,
    )
  }

  @Test
  fun `renderMap handles empty map inside a list as flow-style`() {
    val out = YamlEmitter.renderMap(mapOf("items" to listOf<Map<String, Any?>>(emptyMap())))
    // Flow-style `{}` rather than a dangling dash with nothing after it.
    assertTrue("got: $out") { out.contains("- {}") }
  }

  // ---- 3+ level nesting (L1 regression coverage) ----

  @Test
  fun `renderMap handles list-of-maps containing a list of maps three levels deep`() {
    // A deeply-nested structure that previously had ragged indentation in the
    // standalone TrailblazeDesktopUtil emitter (the "subsequent entries" branch used
    // `indent + "  "` while the first-entry branch used `indent + "    "` for the
    // nested-list case). Verify the consolidated emitter produces consistent depth.
    val tree = mapOf(
      "extensions" to listOf(
        linkedMapOf(
          "name" to "alpha",
          "subentries" to listOf(
            linkedMapOf("k" to "x", "v" to "1"),
            linkedMapOf("k" to "y", "v" to "2"),
          ),
        ),
      ),
    )
    val out = YamlEmitter.renderMap(tree)
    // Round-trip: parse the rendered string back, confirm the structure matches.
    val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))
    val reparsed = YamlEmitter.yamlMapToMutable(yaml.parseToYamlNode(out) as YamlMap)
    @Suppress("UNCHECKED_CAST")
    val outerList = reparsed["extensions"] as List<Map<String, Any?>>
    val firstExt = outerList.single()
    assertEquals("alpha", firstExt["name"])
    @Suppress("UNCHECKED_CAST")
    val subentries = firstExt["subentries"] as List<Map<String, Any?>>
    assertEquals(2, subentries.size)
    assertEquals("x", subentries[0]["k"])
    assertEquals("1", subentries[0]["v"])
    assertEquals("y", subentries[1]["k"])
    assertEquals("2", subentries[1]["v"])
  }

  // ---- end-to-end round-trip ----

  @Test
  fun `emitting then re-parsing a representative tree preserves structure and types`() {
    val tree = linkedMapOf<String, Any?>(
      "id" to "alpha",
      "enabled" to true,
      "version" to 1,
      "platforms" to linkedMapOf(
        "android" to linkedMapOf(
          "app_ids" to listOf("com.example.alpha", "com.example.alpha.beta"),
          "tool_sets" to listOf("core", "verification"),
        ),
      ),
      "tags" to listOf("foo", "bar"),
    )
    val rendered = YamlEmitter.renderMap(tree)
    val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))
    val reparsed = YamlEmitter.yamlMapToMutable(yaml.parseToYamlNode(rendered) as YamlMap)
    assertEquals("alpha", reparsed["id"])
    assertEquals(true, reparsed["enabled"]) // YAML 1.2 boolean preserved through scalar resolution
    assertEquals("1", reparsed["version"]) // integers come back as String — kaml tree API doesn't infer numerics
    @Suppress("UNCHECKED_CAST")
    val android = (reparsed["platforms"] as Map<String, Any?>)["android"] as Map<String, Any?>
    assertEquals(listOf("com.example.alpha", "com.example.alpha.beta"), android["app_ids"])
    assertEquals(listOf("core", "verification"), android["tool_sets"])
  }

  @Test
  fun `boolean values round-trip without losing their type`() {
    val rendered = YamlEmitter.renderMap(mapOf("flag" to true))
    assertEquals("flag: true\n", rendered)
    val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))
    val reparsed = YamlEmitter.yamlMapToMutable(yaml.parseToYamlNode(rendered) as YamlMap)
    assertEquals(true, reparsed["flag"])
  }
}
