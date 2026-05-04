package xyz.block.trailblaze.logs.client.temp

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.toolcalls.OTHER_TRAILBLAZE_TOOL_NAME_FIELD
import xyz.block.trailblaze.toolcalls.OTHER_TRAILBLAZE_TOOL_RAW_FIELD
import kotlin.test.assertFailsWith

/**
 * Regression coverage for [OtherTrailblazeToolFlatSerializer]'s JSON path. Pairs with
 * [xyz.block.trailblaze.yaml.TrailblazeToolJsonSerializerTest] — the contextual
 * `TrailblazeToolJsonSerializer` and this flat serializer both emit / accept the
 * `{toolName, raw}` shape, and they MUST stay in sync (strictness, field names, field
 * types). Drift between them silently loses data on round-trip.
 */
class OtherTrailblazeToolFlatSerializerTest {

  @Test
  fun `JSON wire format uses the shared OTHER_TRAILBLAZE_TOOL_*_FIELD constants`() {
    // Pin that the on-disk field names match the constants exposed for cross-serializer
    // coordination. A future renamer would have to change this test alongside the
    // constants — making field-name drift impossible to land silently.
    val tool = OtherTrailblazeTool(
      toolName = "tap",
      raw = buildJsonObject { put("ref", "z639") },
    )
    val json = TrailblazeJsonInstance.encodeToString(OtherTrailblazeTool.serializer(), tool)

    val parsed = TrailblazeJsonInstance.decodeFromString<JsonObject>(json)
    assertThat(parsed.keys).contains(OTHER_TRAILBLAZE_TOOL_NAME_FIELD)
    assertThat(parsed.keys).contains(OTHER_TRAILBLAZE_TOOL_RAW_FIELD)
  }

  @Test
  fun `JSON round-trip preserves toolName and raw verbatim`() {
    val tool = OtherTrailblazeTool(
      toolName = "ios_contacts_create_contact",
      raw = buildJsonObject {
        put("firstName", "Ada")
        put("lastName", "Lovelace")
      },
    )
    val json = TrailblazeJsonInstance.encodeToString(OtherTrailblazeTool.serializer(), tool)
    val decoded = TrailblazeJsonInstance.decodeFromString(OtherTrailblazeTool.serializer(), json)

    assertThat(decoded.toolName).isEqualTo("ios_contacts_create_contact")
    assertThat(decoded.raw).isEqualTo(tool.raw)
  }

  @Test
  fun `decode rejects missing toolName field`() {
    // Strictness mirrors `TrailblazeToolJsonSerializer.deserialize`: missing toolName cannot
    // be routed downstream; fail at the decode boundary instead of silently producing an
    // empty-name tool that breaks dispatch later.
    val json = """{"raw":{"a":"b"}}"""
    val ex = assertFailsWith<SerializationException> {
      TrailblazeJsonInstance.decodeFromString(OtherTrailblazeTool.serializer(), json)
    }
    assertThat(ex.message ?: "").contains(OTHER_TRAILBLAZE_TOOL_NAME_FIELD)
  }

  @Test
  fun `decode rejects blank toolName`() {
    // Aligns flat-serializer strictness with the contextual TrailblazeToolJsonSerializer.
    // Pre-fix the flat serializer accepted blank → built `OtherTrailblazeTool("", raw)`,
    // creating a payload that decoded successfully but couldn't be dispatched.
    val json = """{"toolName":"","raw":{}}"""
    val ex = assertFailsWith<SerializationException> {
      TrailblazeJsonInstance.decodeFromString(OtherTrailblazeTool.serializer(), json)
    }
    assertThat(ex.message ?: "").contains("blank")
  }

  @Test
  fun `decode tolerates omitted raw field`() {
    val json = """{"toolName":"tap"}"""
    val decoded = TrailblazeJsonInstance.decodeFromString(OtherTrailblazeTool.serializer(), json)
    assertThat(decoded.toolName).isEqualTo("tap")
    assertThat(decoded.raw).isEqualTo(JsonObject(emptyMap()))
  }

  @Test
  fun `decode legacy class with no dots uses class as-is`() {
    // `substringAfterLast('.')` returns the input unchanged when there's no dot — a class
    // name without a package is still a usable tool identifier.
    val json = """{"class":"SimpleClassName","field":"value"}"""
    val decoded = TrailblazeJsonInstance.decodeFromString(OtherTrailblazeTool.serializer(), json)
    assertThat(decoded.toolName).isEqualTo("SimpleClassName")
  }

  @Test
  fun `decode legacy class as blank string is rejected`() {
    // Mirrors the strictness of the new `{toolName, raw}` shape: a blank legacy class
    // would yield a blank derived toolName that can't be routed downstream.
    val json = """{"class":"","field":"value"}"""
    val ex = assertFailsWith<SerializationException> {
      TrailblazeJsonInstance.decodeFromString(OtherTrailblazeTool.serializer(), json)
    }
    assertThat(ex.message ?: "").contains("blank")
  }

  @Test
  fun `decode prefers toolName over legacy class when both are present`() {
    // Hybrid shape (transitional logs / hand-crafted payloads): the new field wins so the
    // strict path takes precedence over the lenient legacy path.
    val json = """{
      "toolName":"newName",
      "class":"xyz.block.trailblaze.toolcalls.commands.LegacyName",
      "raw":{"k":"v"}
    }""".trimIndent()
    val decoded = TrailblazeJsonInstance.decodeFromString(OtherTrailblazeTool.serializer(), json)
    assertThat(decoded.toolName).isEqualTo("newName")
    assertThat(decoded.raw["k"]?.toString() ?: "").contains("v")
  }

  @Test
  fun `decode accepts legacy class-discriminator shape from old log files`() {
    // Pre-#2634 log files persisted tools polymorphically as
    // `{"class": "fqcn.SomeTool", ...flatToolFields}` rather than the current `{toolName,
    // raw}` shape. Production logs in this format exist on disk; the framework must keep
    // reading them. Tool name is best-effort derived from `class.simpleName`; remaining
    // fields become `raw`.
    val legacyJson =
      """{
        "class": "xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool",
        "text": "jane@example.com"
      }""".trimIndent()
    val decoded = TrailblazeJsonInstance.decodeFromString(
      OtherTrailblazeTool.serializer(),
      legacyJson,
    )
    assertThat(decoded.toolName).isEqualTo("InputTextTrailblazeTool")
    assertThat(decoded.raw["text"]?.toString() ?: "").contains("jane@example.com")
  }
}
