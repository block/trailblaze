package xyz.block.trailblaze.host

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.bundle.WorkspaceClientDtsGenerator
import xyz.block.trailblaze.config.InlineScriptToolConfig

/**
 * Unit tests for [TrailValidationDescriptorSidecar] — the arg-type sidecar that lets the
 * trail-recording validator coerce recorded scalar args to their declared types. Pins the pure
 * merge/schema-parse contract and the on-disk round-trip so the validator gets the same parameter
 * types that back the co-emitted `.d.ts`.
 */
class TrailValidationDescriptorSidecarTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private fun scriptedTool(name: String, schema: JsonObject) =
    InlineScriptToolConfig(script = "$name.ts", name = name, inputSchema = schema)

  private fun objectSchema(vararg props: Pair<String, String>, required: List<String> = emptyList()) =
    buildJsonObject {
      put("type", "object")
      putJsonObject("properties") {
        props.forEach { (name, type) -> putJsonObject(name) { put("type", type) } }
      }
      put("required", buildJsonArray { required.forEach { add(it) } })
    }

  @Test
  fun `builds scripted descriptor from the tool's own inputSchema`() {
    val descriptors = TrailValidationDescriptorSidecar.buildValidationDescriptors(
      kotlinDescriptors = emptyList(),
      scriptedTools = listOf(
        scriptedTool("setFeatureFlag", objectSchema("passcode" to "string", "enabled" to "boolean", required = listOf("passcode"))),
      ),
      typedOverrides = emptyMap(),
    )

    val d = descriptors.single()
    assertEquals("setFeatureFlag", d.name)
    assertEquals(listOf("passcode" to "string"), d.requiredParameters.map { it.name to it.type })
    assertEquals(listOf("enabled" to "boolean"), d.optionalParameters.map { it.name to it.type })
  }

  @Test
  fun `analyzer override inputSchema wins over the tool's own inputSchema`() {
    // The tool's YAML-less config carries an empty schema; the analyzer override carries the real one.
    val descriptors = TrailValidationDescriptorSidecar.buildValidationDescriptors(
      kotlinDescriptors = emptyList(),
      scriptedTools = listOf(scriptedTool("app_inputText", buildJsonObject { put("type", "object") })),
      typedOverrides = mapOf(
        "app_inputText" to WorkspaceClientDtsGenerator.TypedToolOverride(
          description = null,
          inputSchema = objectSchema("text" to "string", required = listOf("text")),
          outputSchema = buildJsonObject { put("type", "object") },
        ),
      ),
    )

    val d = descriptors.single()
    assertEquals(listOf("text" to "string"), d.requiredParameters.map { it.name to it.type })
  }

  @Test
  fun `a Kotlin descriptor shadows a scripted tool of the same name`() {
    val descriptors = TrailValidationDescriptorSidecar.buildValidationDescriptors(
      kotlinDescriptors = listOf(
        ToolDescriptor(
          name = "shared",
          description = "kotlin",
          requiredParameters = listOf(ToolParameterDescriptor("text", "", ToolParameterType.String)),
          optionalParameters = emptyList(),
        ),
      ),
      scriptedTools = listOf(scriptedTool("shared", objectSchema("count" to "integer"))),
      typedOverrides = emptyMap(),
    )

    // Only the Kotlin descriptor survives (first-write-wins by name), matching the .d.ts merge.
    val d = descriptors.single()
    assertEquals(listOf("text"), d.requiredParameters.map { it.name })
    assertTrue(d.optionalParameters.isEmpty(), "scripted 'count' must not leak in: ${d.optionalParameters}")
  }

  @Test
  fun `a property with no inline type defaults to string (shared scripted-tool builder)`() {
    // Delegating to the runtime's LazyYamlScriptedToolRegistration.buildScriptedToolDescriptor means
    // a property with no inline `type` (a `$ref`/`anyOf`) is recorded as `string`, not dropped.
    // Harmless: coercion only ever re-types scalar VALUES, so an object value stays untouched
    // regardless of the declared type here.
    val schema = buildJsonObject {
      put("type", "object")
      putJsonObject("properties") {
        putJsonObject("name") { put("type", "string") }
        putJsonObject("nested") { put("\$ref", "#/definitions/Foo") }
      }
    }
    val d = TrailValidationDescriptorSidecar.buildValidationDescriptors(
      kotlinDescriptors = emptyList(),
      scriptedTools = listOf(scriptedTool("t", schema)),
      typedOverrides = emptyMap(),
    ).single()

    val types = (d.requiredParameters + d.optionalParameters).associate { it.name to it.type }
    assertEquals(mapOf("name" to "string", "nested" to "string"), types)
  }

  @Test
  fun `write then read round-trips the descriptors keyed by name`() {
    val trailmapDir = tmp.newFolder("demoapp").toPath()
    Files.createDirectories(trailmapDir.resolve(WorkspaceClientDtsGenerator.TRAILMAP_TOOLS_SUBDIR))
    val built = TrailValidationDescriptorSidecar.buildValidationDescriptors(
      kotlinDescriptors = emptyList(),
      scriptedTools = listOf(scriptedTool("setFeatureFlag", objectSchema("passcode" to "string", required = listOf("passcode")))),
      typedOverrides = emptyMap(),
    )

    TrailValidationDescriptorSidecar.write(trailmapDir, built)
    val read = TrailValidationDescriptorSidecar.read(trailmapDir)

    assertEquals(setOf("setFeatureFlag"), read.keys)
    assertEquals("string", read.getValue("setFeatureFlag").requiredParameters.single { it.name == "passcode" }.type)
  }

  @Test
  fun `write then read preserves the nested inputSchema the validator coerces against`() {
    // The whole feature hinges on the descriptor's `inputSchema` (a body `var`) surviving the JSON
    // round-trip — that's what lets the validator reach a scalar buried in `overrides[].value`. A
    // future change of the field to `val`/`@Transient` would silently drop it here (in-process
    // replay would still work, masking the regression), so pin it explicitly.
    val nested = buildJsonObject {
      put("type", "object")
      putJsonObject("properties") {
        putJsonObject("overrides") {
          put("type", "array")
          putJsonObject("items") {
            put("type", "object")
            putJsonObject("properties") {
              putJsonObject("value") { put("type", "string") }
            }
          }
        }
      }
    }
    val trailmapDir = tmp.newFolder("nested").toPath()
    Files.createDirectories(trailmapDir.resolve(WorkspaceClientDtsGenerator.TRAILMAP_TOOLS_SUBDIR))
    val built = TrailValidationDescriptorSidecar.buildValidationDescriptors(
      kotlinDescriptors = emptyList(),
      scriptedTools = listOf(scriptedTool("setFeatureFlag", nested)),
      typedOverrides = emptyMap(),
    )
    // Sanity: the built descriptor actually carries the schema before we serialize it.
    assertEquals(nested, built.single().inputSchema)

    TrailValidationDescriptorSidecar.write(trailmapDir, built)
    val read = TrailValidationDescriptorSidecar.read(trailmapDir)

    assertEquals(nested, read.getValue("setFeatureFlag").inputSchema)
  }

  @Test
  fun `an identical re-write is skipped (no mtime churn)`() {
    val trailmapDir = tmp.newFolder("idempotent").toPath()
    Files.createDirectories(trailmapDir.resolve(WorkspaceClientDtsGenerator.TRAILMAP_TOOLS_SUBDIR))
    val built = TrailValidationDescriptorSidecar.buildValidationDescriptors(
      kotlinDescriptors = emptyList(),
      scriptedTools = listOf(scriptedTool("t", objectSchema("a" to "string"))),
      typedOverrides = emptyMap(),
    )
    val path = trailmapDir
      .resolve(WorkspaceClientDtsGenerator.TRAILMAP_TOOLS_SUBDIR)
      .resolve(TrailValidationDescriptorSidecar.FILE_NAME)

    TrailValidationDescriptorSidecar.write(trailmapDir, built)
    // Backdate the mtime; an identical second write must leave it untouched (skip-if-unchanged).
    val backdated = FileTime.fromMillis(Files.getLastModifiedTime(path).toMillis() - 5_000)
    Files.setLastModifiedTime(path, backdated)
    TrailValidationDescriptorSidecar.write(trailmapDir, built)

    assertEquals(backdated, Files.getLastModifiedTime(path), "identical write must be skipped")
  }

  @Test
  fun `writing an empty list overwrites a previously non-empty sidecar`() {
    val trailmapDir = tmp.newFolder("shrunk").toPath()
    Files.createDirectories(trailmapDir.resolve(WorkspaceClientDtsGenerator.TRAILMAP_TOOLS_SUBDIR))
    TrailValidationDescriptorSidecar.write(
      trailmapDir,
      TrailValidationDescriptorSidecar.buildValidationDescriptors(
        kotlinDescriptors = emptyList(),
        scriptedTools = listOf(scriptedTool("t", objectSchema("a" to "string"))),
        typedOverrides = emptyMap(),
      ),
    )
    assertEquals(setOf("t"), TrailValidationDescriptorSidecar.read(trailmapDir).keys)

    // A tool set that shrank to nothing must not leave the stale non-empty sidecar behind.
    TrailValidationDescriptorSidecar.write(trailmapDir, emptyList())
    assertTrue(TrailValidationDescriptorSidecar.read(trailmapDir).isEmpty())
  }

  @Test
  fun `read returns empty when the sidecar is absent`() {
    val trailmapDir = tmp.newFolder("nosidecar").toPath()
    assertTrue(TrailValidationDescriptorSidecar.read(trailmapDir).isEmpty())
  }

  @Test
  fun `read never throws on a corrupt sidecar`() {
    val trailmapDir = tmp.newFolder("corrupt").toPath()
    val toolsDir = trailmapDir.resolve(WorkspaceClientDtsGenerator.TRAILMAP_TOOLS_SUBDIR)
    Files.createDirectories(toolsDir)
    Files.writeString(toolsDir.resolve(TrailValidationDescriptorSidecar.FILE_NAME), "{ not valid json")

    assertTrue(TrailValidationDescriptorSidecar.read(trailmapDir).isEmpty())
  }
}
