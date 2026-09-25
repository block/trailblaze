package xyz.block.trailblaze.yaml.unified

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailMetadataValue.ListValue
import xyz.block.trailblaze.yaml.TrailMetadataValue.MapValue
import xyz.block.trailblaze.yaml.TrailMetadataValue.StringValue
import xyz.block.trailblaze.yaml.TrailSourceType
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.displayText
import xyz.block.trailblaze.yaml.leaves
import xyz.block.trailblaze.yaml.list
import xyz.block.trailblaze.yaml.map
import xyz.block.trailblaze.yaml.string

/**
 * `config.metadata:` values may be a string, a list, or a map, nested to any depth, and every shape
 * survives every path a config travels: YAML emit/parse, lowering to [TrailConfig], the JSON session log, and merging.
 */
class TrailMetadataTest {

  private val yaml = TrailblazeYaml.Default

  private fun trailWithMetadata(metadataBlock: String) = """
    |config:
    |  id: x
    |  metadata:
    |${metadataBlock.trimIndent().prependIndent("    ")}
    |trail:
    |  - step: hi
  """.trimMargin()

  @Test
  fun `metadata accepts string and list values side by side, in authored order`() {
    val metadata = yaml.decodeUnifiedTrail(
      trailWithMetadata(
        """
        jira: PROJ-123
        owners: [payments, checkout]
        count: 5
        ids:
          - 7
          - C123
        none: []
        """,
      ),
    ).config.metadata!!

    assertEquals(
      linkedMapOf(
        "jira" to StringValue("PROJ-123"),
        "owners" to ListValue.of("payments", "checkout"),
        "count" to StringValue("5"),
        "ids" to ListValue.of("7", "C123"),
        "none" to ListValue(emptyList()),
      ).toList(),
      metadata.toList(),
    )
    assertEquals("PROJ-123", metadata.string("jira"))
    assertEquals(listOf("payments", "checkout"), metadata.list("owners"))
    assertNull(metadata.string("owners"), "a list is not a string")
    assertNull(metadata.list("jira"), "a string is not a list")
  }

  @Test
  fun `emit then parse round-trips list metadata as a YAML list`() {
    val original = UnifiedTrail(
      config = UnifiedTrailConfig(
        id = "x",
        metadata = mapOf("jira" to StringValue("PROJ-123"), "owners" to ListValue.of("payments", "checkout")),
      ),
      trail = listOf(UnifiedTrailStep(step = "hi", recordings = emptyMap())),
    )

    val emitted = yaml.encodeUnifiedTrailToString(original)
    assertTrue("- payments" in emitted && "- checkout" in emitted, "expected a YAML list, got:\n$emitted")
    assertTrue("jira: PROJ-123" in emitted, "expected a plain scalar, got:\n$emitted")
    assertEquals(original.config, yaml.decodeUnifiedTrail(emitted).config)
  }

  @Test
  fun `metadata accepts maps and lists nested to any depth, and round-trips them`() {
    val metadata = yaml.decodeUnifiedTrail(
      trailWithMetadata(
        """
        tracker:
          ticket: 1017
          suites: [smoke, nightly]
        matrix:
          - [a, b]
          - os: android
        """,
      ),
    ).config.metadata!!

    val tracker = MapValue(mapOf("ticket" to StringValue("1017"), "suites" to ListValue.of("smoke", "nightly")))
    val matrix = ListValue(listOf(ListValue.of("a", "b"), MapValue(mapOf("os" to StringValue("android")))))
    assertEquals(mapOf("tracker" to tracker, "matrix" to matrix), metadata)
    assertEquals(listOf("smoke", "nightly"), metadata.map("tracker")?.list("suites"))
    assertNull(metadata.list("matrix"), "a list holding non-strings is not a list of strings")
    assertEquals("a, b, android", matrix.leaves.joinToString(", "))
    assertEquals("ticket: 1017, suites: [smoke, nightly]", tracker.displayText)

    val trail = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", metadata = metadata),
      trail = listOf(UnifiedTrailStep(step = "hi", recordings = emptyMap())),
    )
    assertEquals(trail.config, yaml.decodeUnifiedTrail(yaml.encodeUnifiedTrailToString(trail)).config)

    val json = TrailblazeJson.defaultWithoutToolsInstance
    val config = TrailConfig(id = "x", metadata = metadata)
    assertEquals(config, json.decodeFromString(TrailConfig.serializer(), json.encodeToString(TrailConfig.serializer(), config)))
  }

  @Test
  fun `a null value is rejected naming its path`() {
    val topLevel = assertFailsWith<Exception> { yaml.decodeUnifiedTrail(trailWithMetadata("owner:")) }
    assertTrue("owner" in topLevel.messageChain() && "not null" in topLevel.messageChain(), topLevel.messageChain())

    val nested = assertFailsWith<Exception> { yaml.decodeUnifiedTrail(trailWithMetadata("owner:\n  team: ~")) }
    assertTrue("team" in nested.messageChain() && "not null" in nested.messageChain(), nested.messageChain())

    val json = assertFailsWith<Exception> {
      TrailblazeJson.defaultWithoutToolsInstance.decodeFromString(
        TrailConfig.serializer(),
        """{"id":"x","metadata":{"owner":{"team":null}}}""",
      )
    }
    assertTrue("`team`" in json.messageChain() && "not null" in json.messageChain(), json.messageChain())
  }

  @Test
  fun `lowered config carries list metadata and survives the JSON session log`() {
    val lowered = yaml.extractTrailConfig(
      trailWithMetadata(
        """
        jira: PROJ-123
        owners: [payments, checkout]
        """,
      ),
    ) ?: error("expected a config")
    assertEquals(listOf("payments", "checkout"), lowered.metadata?.list("owners"))

    val json = TrailblazeJson.defaultWithoutToolsInstance
    val encoded = json.encodeToString(TrailConfig.serializer(), lowered)
    assertEquals(
      JsonArray(listOf(JsonPrimitive("payments"), JsonPrimitive("checkout"))),
      json.parseToJsonElement(encoded).jsonObject["metadata"]?.jsonObject?.get("owners"),
      "a list value is a JSON array on the wire",
    )
    assertEquals(lowered, json.decodeFromString(TrailConfig.serializer(), encoded))
  }

  @Test
  fun `a JSON log written before list metadata still decodes`() {
    val decoded = TrailblazeJson.defaultWithoutToolsInstance.decodeFromString(
      TrailConfig.serializer(),
      """{"id":"x","metadata":{"jira":"PROJ-123"}}""",
    )
    assertEquals(mapOf("jira" to StringValue("PROJ-123")), decoded.metadata)
  }

  @Test
  fun `a list under the reserved source key is plain metadata, not a source bridge`() {
    val lowered = yaml.extractTrailConfig(trailWithMetadata("source: [${TrailSourceType.HANDWRITTEN.name}]"))
      ?: error("expected a config")
    assertNull(lowered.source)
    assertEquals(listOf(TrailSourceType.HANDWRITTEN.name), lowered.metadata?.list("source"))
  }

  @Test
  fun `a non-string source reason stays in metadata instead of being dropped`() {
    val lowered = yaml.extractTrailConfig(
      trailWithMetadata(
        """
        source: ${TrailSourceType.HANDWRITTEN.name}
        sourceReason: [flaky, rewritten]
        """,
      ),
    ) ?: error("expected a config")
    assertEquals(TrailSourceType.HANDWRITTEN, lowered.source?.type)
    assertNull(lowered.source?.reason)
    assertEquals(mapOf("sourceReason" to ListValue.of("flaky", "rewritten")), lowered.metadata)
  }

  @Test
  fun `merging keeps list metadata per key`() {
    val base = UnifiedTrailConfig(metadata = mapOf("owners" to ListValue.of("payments")))
    val fallback = UnifiedTrailConfig(
      metadata = mapOf("owners" to ListValue.of("checkout"), "areas" to ListValue.of("tipping")),
    )
    assertEquals(
      mapOf("owners" to ListValue.of("payments"), "areas" to ListValue.of("tipping")),
      UnifiedTrailAdapter.fillMissingConfigScalars(base, fallback).metadata,
      "base wins a shared key; the fallback fills new keys",
    )
  }

  @Test
  fun `merging combines nested maps per key, while lists and strings stay whole`() {
    val base = UnifiedTrailConfig(
      metadata = mapOf(
        "tracker" to MapValue(mapOf("suites" to ListValue.of("smoke"), "board" to MapValue(mapOf("lane" to StringValue("a"))))),
        "owners" to ListValue.of("payments"),
      ),
    )
    val fallback = UnifiedTrailConfig(
      metadata = mapOf(
        "tracker" to MapValue(
          mapOf(
            "ticket" to StringValue("PROJ-1"),
            "suites" to ListValue.of("nightly"),
            "board" to MapValue(mapOf("column" to StringValue("done"))),
          ),
        ),
        "owners" to ListValue.of("checkout"),
      ),
    )
    assertEquals(
      mapOf(
        "tracker" to MapValue(
          mapOf(
            "ticket" to StringValue("PROJ-1"),
            "suites" to ListValue.of("smoke"),
            "board" to MapValue(mapOf("column" to StringValue("done"), "lane" to StringValue("a"))),
          ),
        ),
        "owners" to ListValue.of("payments"),
      ),
      UnifiedTrailAdapter.fillMissingConfigScalars(base, fallback).metadata,
    )
  }

  private fun Throwable.messageChain(): String =
    generateSequence(this) { it.cause }.mapNotNull { it.message }.joinToString(" <- ")
}
