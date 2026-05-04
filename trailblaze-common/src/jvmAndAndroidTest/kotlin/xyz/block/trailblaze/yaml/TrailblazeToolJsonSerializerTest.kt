package xyz.block.trailblaze.yaml

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.RawArgumentTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import kotlin.test.assertFailsWith

/**
 * Regression coverage for the contextual
 * [xyz.block.trailblaze.toolcalls.TrailblazeToolJsonSerializer] registered on the JSON
 * module — exercised here via [TrailblazeToolYamlWrapper] (which carries an abstract
 * `@Contextual TrailblazeTool` field), so the same tests cover both the contextual
 * `{toolName, raw}` shape and the wrapper round-trip semantics that recording / replay /
 * diagnostics depend on.
 */
class TrailblazeToolJsonSerializerTest {

  // ── Encode: each tool kind hits its dedicated serializer branch ─────────────────────────

  @Test
  fun `class-backed tool round-trips through JSON with args preserved in raw`() {
    val original = TrailblazeToolYamlWrapper(
      name = "inputText",
      trailblazeTool = InputTextTrailblazeTool(text = "Jane Doe"),
    )

    val json = TrailblazeJsonInstance.encodeToString(
      TrailblazeToolYamlWrapper.serializer(),
      original,
    )

    val parsed = TrailblazeJsonInstance.decodeFromString<JsonObject>(json)
    assertThat(parsed["name"]?.jsonPrimitive?.content).isEqualTo("inputText")
    val toolJson = parsed["trailblazeTool"]?.jsonObject!!
    assertThat(toolJson["toolName"]?.jsonPrimitive?.content).isEqualTo("inputText")
    val raw = toolJson["raw"]?.jsonObject!!
    assertThat(raw["text"]?.jsonPrimitive?.content).isEqualTo("Jane Doe")

    val decoded = TrailblazeJsonInstance.decodeFromString(
      TrailblazeToolYamlWrapper.serializer(),
      json,
    )
    val decodedTool = decoded.trailblazeTool as OtherTrailblazeTool
    assertThat(decoded.name).isEqualTo("inputText")
    assertThat(decodedTool.toolName).isEqualTo("inputText")
    assertThat(decodedTool.raw["text"]?.jsonPrimitive?.content).isEqualTo("Jane Doe")
  }

  @Test
  fun `OtherTrailblazeTool round-trips with toolName and raw preserved`() {
    val original = TrailblazeToolYamlWrapper(
      name = "tap",
      trailblazeTool = OtherTrailblazeTool(
        toolName = "tap",
        raw = buildJsonObject { put("ref", "z639") },
      ),
    )

    val json = TrailblazeJsonInstance.encodeToString(
      TrailblazeToolYamlWrapper.serializer(),
      original,
    )
    val decoded = TrailblazeJsonInstance.decodeFromString(
      TrailblazeToolYamlWrapper.serializer(),
      json,
    )

    val decodedTool = decoded.trailblazeTool as OtherTrailblazeTool
    assertThat(decodedTool.toolName).isEqualTo("tap")
    assertThat(decodedTool.raw["ref"]?.jsonPrimitive?.content).isEqualTo("z639")
  }

  @Test
  fun `RawArgumentTrailblazeTool encodes its instance toolName and raw args`() {
    val original = TrailblazeToolYamlWrapper(
      name = "ios_contacts_create_contact",
      trailblazeTool = TestRawTool(
        instanceToolName = "ios_contacts_create_contact",
        rawToolArguments = buildJsonObject {
          put("firstName", "Ada")
          put("lastName", "Lovelace")
        },
      ),
    )

    val json = TrailblazeJsonInstance.encodeToString(
      TrailblazeToolYamlWrapper.serializer(),
      original,
    )

    val parsed = TrailblazeJsonInstance.decodeFromString<JsonObject>(json)
    val toolJson = parsed["trailblazeTool"]?.jsonObject!!
    assertThat(toolJson["toolName"]?.jsonPrimitive?.content).isEqualTo("ios_contacts_create_contact")
    val raw = toolJson["raw"]?.jsonObject!!
    assertThat(raw["firstName"]?.jsonPrimitive?.content).isEqualTo("Ada")
    assertThat(raw["lastName"]?.jsonPrimitive?.content).isEqualTo("Lovelace")
  }

  // ── Encode: fallback paths produce structurally valid output (with diagnostic logs) ────

  @Test
  fun `non-Serializable class-backed tool falls back to empty raw with annotation name`() {
    // Sanity: the tool has @TrailblazeToolClass but is NOT @Serializable. The serializer
    // should still produce a structurally valid {toolName, raw:{}} payload instead of
    // throwing — `class.serializer()` reflection fails and the exception is swallowed
    // to keep persistence working (with a Console.error so the failure is observable).
    val original = TrailblazeToolYamlWrapper(
      name = "test_unserializable",
      trailblazeTool = TestUnserializableAnnotatedTool(reason = "ignored"),
    )

    val json = TrailblazeJsonInstance.encodeToString(
      TrailblazeToolYamlWrapper.serializer(),
      original,
    )

    val toolJson = TrailblazeJsonInstance
      .decodeFromString<JsonObject>(json)["trailblazeTool"]?.jsonObject!!
    assertThat(toolJson["toolName"]?.jsonPrimitive?.content).isEqualTo("test_unserializable")
    assertThat(toolJson["raw"]?.jsonObject!!.entries).isEmpty()
  }

  @Test
  fun `tool without TrailblazeToolClass annotation falls back to simpleName`() {
    // No @TrailblazeToolClass annotation, no @Serializable — both fallbacks fire: name
    // resolves to class.simpleName, raw resolves to empty object. Wire shape stays valid.
    val original = TrailblazeToolYamlWrapper(
      name = "simpleNameFallback",
      trailblazeTool = TestUnannotatedTool,
    )

    val json = TrailblazeJsonInstance.encodeToString(
      TrailblazeToolYamlWrapper.serializer(),
      original,
    )

    val toolJson = TrailblazeJsonInstance
      .decodeFromString<JsonObject>(json)["trailblazeTool"]?.jsonObject!!
    // The fallback uses `class.simpleName`; the test class is `TestUnannotatedTool`.
    assertThat(toolJson["toolName"]?.jsonPrimitive?.content).isEqualTo("TestUnannotatedTool")
  }

  // ── Decode: malformed payloads fail loud at the serializer boundary ─────────────────────

  @Test
  fun `decode rejects payload missing toolName`() {
    val json = """{"name":"tap","trailblazeTool":{"raw":{}}}"""
    val ex = assertFailsWith<SerializationException> {
      TrailblazeJsonInstance.decodeFromString(TrailblazeToolYamlWrapper.serializer(), json)
    }
    assertThat(ex.message ?: "").contains("toolName")
  }

  @Test
  fun `decode rejects payload with blank toolName`() {
    // A blank toolName cannot be routed by `TrailblazeToolRepo.toolCallToTrailblazeTool` —
    // fail at the decode boundary so the malformed payload surfaces immediately rather than
    // silently breaking dispatch downstream.
    val json = """{"name":"tap","trailblazeTool":{"toolName":"","raw":{}}}"""
    val ex = assertFailsWith<SerializationException> {
      TrailblazeJsonInstance.decodeFromString(TrailblazeToolYamlWrapper.serializer(), json)
    }
    assertThat(ex.message ?: "").contains("blank")
  }

  @Test
  fun `decode rejects payload with non-object raw field`() {
    // Upstream serialization drift could produce `raw: [1,2,3]` or `raw: "stringified"`.
    // Silently coercing to empty would make data loss invisible — fail loud instead.
    val json = """{"name":"tap","trailblazeTool":{"toolName":"tap","raw":[1,2,3]}}"""
    val ex = assertFailsWith<SerializationException> {
      TrailblazeJsonInstance.decodeFromString(TrailblazeToolYamlWrapper.serializer(), json)
    }
    assertThat(ex.message ?: "").contains("must be a JSON object")
  }

  @Test
  fun `decode tolerates payload with omitted raw field`() {
    // Unlike `raw: <wrong type>`, an entirely missing `raw` field is benign — a tool with
    // no parameters legitimately has nothing to write. Default to an empty raw object.
    val json = """{"name":"tap","trailblazeTool":{"toolName":"tap"}}"""
    val decoded = TrailblazeJsonInstance.decodeFromString(
      TrailblazeToolYamlWrapper.serializer(),
      json,
    )
    val tool = decoded.trailblazeTool as OtherTrailblazeTool
    assertThat(tool.toolName).isEqualTo("tap")
    assertThat(tool.raw.entries).isEmpty()
  }

  // ── Backwards compat: old log shapes with extra `class` discriminator still decode ─────

  @Test
  fun `decode tolerates legacy class discriminator alongside toolName-raw`() {
    // Old log files (pre-#2634) had the polymorphic dispatcher emit a `class` field as the
    // discriminator. The new decoder reads only `toolName` + `raw` — extra fields are
    // ignored — so historical logs continue to decode without a migration step.
    val json = """{
      "name":"tap",
      "trailblazeTool":{
        "class":"OtherTrailblazeTool",
        "toolName":"tap",
        "raw":{"ref":"legacy"}
      }
    }""".trimIndent()
    val decoded = TrailblazeJsonInstance.decodeFromString(
      TrailblazeToolYamlWrapper.serializer(),
      json,
    )
    val tool = decoded.trailblazeTool as OtherTrailblazeTool
    assertThat(tool.toolName).isEqualTo("tap")
    assertThat(tool.raw["ref"]?.jsonPrimitive?.content).isEqualTo("legacy")
  }

  // ── Round-trip idempotency: encode → decode → encode produces same JSON ────────────────

  // ── HostLocalExecutableTrailblazeTool: dynamic name flows through the contextual path ──

  @Test
  fun `HostLocalExecutableTrailblazeTool encodes via advertisedToolName not class simpleName`() {
    // Dual-path consistency regression: previously the JSON contextual path read class
    // metadata (annotation or `class.simpleName`), but the log-emit path read
    // `advertisedToolName` via `getToolNameFromAnnotation`. Same tool, two names. The fix
    // makes HostLocal implement InstanceNamedTrailblazeTool, so the shared encoder picks
    // up the dynamic name uniformly.
    val original = TrailblazeToolYamlWrapper(
      name = "subprocess_foo",
      trailblazeTool = StubHostLocalTool(advertisedToolName = "subprocess_foo"),
    )
    val json = TrailblazeJsonInstance.encodeToString(
      TrailblazeToolYamlWrapper.serializer(),
      original,
    )
    val toolJson = TrailblazeJsonInstance
      .decodeFromString<JsonObject>(json)["trailblazeTool"]?.jsonObject!!
    assertThat(toolJson["toolName"]?.jsonPrimitive?.content).isEqualTo("subprocess_foo")
  }

  @Test
  fun `class-backed tool encode-decode-encode is idempotent`() {
    // A class-backed tool encodes to {toolName, raw}, decodes as OtherTrailblazeTool, and
    // re-encoding the OtherTrailblazeTool MUST produce identical JSON. The OtherTrailblazeTool
    // pass-through branch in toOtherTrailblazeToolPayload is the contract that makes this
    // hold; a future change to that branch could silently break it.
    val original = TrailblazeToolYamlWrapper(
      name = "inputText",
      trailblazeTool = InputTextTrailblazeTool(text = "Jane Doe"),
    )
    val firstJson = TrailblazeJsonInstance.encodeToString(
      TrailblazeToolYamlWrapper.serializer(),
      original,
    )
    val decoded = TrailblazeJsonInstance.decodeFromString(
      TrailblazeToolYamlWrapper.serializer(),
      firstJson,
    )
    val secondJson = TrailblazeJsonInstance.encodeToString(
      TrailblazeToolYamlWrapper.serializer(),
      decoded,
    )
    assertThat(secondJson).isEqualTo(firstJson)
  }

  // ── Tools and stubs ─────────────────────────────────────────────────────────────────────

  private data class TestRawTool(
    override val instanceToolName: String,
    override val rawToolArguments: JsonObject,
  ) : RawArgumentTrailblazeTool

  /**
   * Annotated but NOT `@Serializable`. Forces `class.serializer()` reflection to fail so the
   * fallback to `{toolName: <annotation>, raw: {}}` is exercised.
   */
  @TrailblazeToolClass(name = "test_unserializable")
  private data class TestUnserializableAnnotatedTool(val reason: String) : TrailblazeTool

  /** Not `@Serializable` and no `@TrailblazeToolClass` — both fallbacks fire. */
  private object TestUnannotatedTool : TrailblazeTool

  /**
   * Stand-in for a dynamically-constructed host-local tool (e.g. subprocess MCP). Surfaces
   * its dynamic name through `advertisedToolName` and — by virtue of HostLocal extending
   * InstanceNamedTrailblazeTool — through `instanceToolName`, so the shared encoder reads
   * the correct identifier instead of `class.simpleName` ("StubHostLocalTool").
   */
  private class StubHostLocalTool(
    override val advertisedToolName: String,
  ) : HostLocalExecutableTrailblazeTool {
    override suspend fun execute(
      toolExecutionContext: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult = error("not invoked in serializer tests")
  }
}
