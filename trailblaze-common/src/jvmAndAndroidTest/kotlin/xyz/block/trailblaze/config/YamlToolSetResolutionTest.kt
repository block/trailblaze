package xyz.block.trailblaze.config

import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.toolcalls.ToolName
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the "toolsets list tools by bare name, regardless of backing" invariant.
 *
 * A toolset can list a YAML-defined tool ([YamlDefinedToolFixture]) alongside class-backed tools
 * like `hideKeyboard`. Authors should not need to know which backing a tool uses; the resolver +
 * resolved toolset must handle both uniformly.
 */
class YamlToolSetResolutionTest {

  private val resolver = YamlDefinedToolFixture.registered { ToolNameResolver.fromBuiltInAndCustomTools() }

  private val yamlTool = YamlDefinedToolFixture.NAME

  @Test
  fun `resolver reports a YAML-defined tool as known via the YAML backing`() {
    assertTrue(resolver.isKnown(yamlTool), "$yamlTool should be known (YAML-defined)")
    assertNull(resolver.resolveOrNull(yamlTool), "$yamlTool has no KClass — resolveOrNull returns null")
    assertEquals(ToolName(yamlTool), resolver.resolveYamlNameOrNull(yamlTool))
  }

  @Test
  fun `assertWaypoint is registered class-backed so recorded trails resolve it`() {
    // Regression guard: a class-backed tool is only resolvable if it has a `*.tool.yaml`
    // descriptor (the registry is built from those, not a classpath annotation scan). Without
    // `assertWaypoint.tool.yaml`, the `verification` toolset would silently skip the name and a
    // recorded `- assertWaypoint:` step would deserialize to an unknown tool and fail at run time.
    assertTrue(resolver.isKnown("assertWaypoint"), "assertWaypoint must be registered via its .tool.yaml")
    assertEquals(
      xyz.block.trailblaze.toolcalls.commands.AssertWaypointTrailblazeTool::class,
      resolver.resolveOrNull("assertWaypoint"),
      "assertWaypoint must resolve to AssertWaypointTrailblazeTool",
    )
  }

  @Test
  fun `resolver reports hideKeyboard as known via the class-backed backing`() {
    assertTrue(resolver.isKnown("hideKeyboard"))
    assertNotNull(resolver.resolveOrNull("hideKeyboard"))
    assertNull(resolver.resolveYamlNameOrNull("hideKeyboard"))
  }

  @Test
  fun `partitionLenient splits a mixed name list by backing`() {
    val partitioned = resolver.partitionLenient(
      names = listOf("hideKeyboard", yamlTool, "definitelyNotARealTool"),
    )
    assertEquals(1, partitioned.classBacked.size, "hideKeyboard is class-backed")
    assertEquals(setOf(ToolName(yamlTool)), partitioned.yamlDefinedNames)
  }

  @Test
  fun `collision between class-backed and YAML-defined names fails fast`() {
    val existingClass = resolver.resolveOrNull("hideKeyboard")
      ?: error("hideKeyboard should be class-backed for this test to make sense")
    val exception = assertFailsWith<IllegalArgumentException> {
      ToolNameResolver(
        knownTools = mapOf(xyz.block.trailblaze.toolcalls.ToolName("hideKeyboard") to existingClass),
        knownYamlToolNames = setOf(xyz.block.trailblaze.toolcalls.ToolName("hideKeyboard")),
      )
    }
    assertTrue(
      exception.message?.contains("collision") == true,
      "Expected collision error, got: ${exception.message}",
    )
  }

  @Test
  fun `collision between class-backed and scripted names fails fast`() {
    val existingClass = resolver.resolveOrNull("hideKeyboard")
      ?: error("hideKeyboard should be class-backed for this test to make sense")
    val exception = assertFailsWith<IllegalArgumentException> {
      ToolNameResolver(
        knownTools = mapOf(ToolName("hideKeyboard") to existingClass),
        knownScriptedToolNames = setOf(ToolName("hideKeyboard")),
      )
    }
    assertTrue(
      exception.message?.contains("class-backed vs scripted") == true,
      "Expected a class-backed-vs-scripted collision error, got: ${exception.message}",
    )
  }

  @Test
  fun `a toolset resolves a YAML-defined tool through the YAML backing`() {
    val yaml = """
      id: test_core
      description: Test toolset
      drivers:
        - android-ondevice-instrumentation
      tools:
        - hideKeyboard
        - $yamlTool
    """.trimIndent()

    val toolSet = ToolSetYamlLoader.loadFromYaml(yaml, resolver)
    val classNames = toolSet.resolvedToolClasses.map {
      it.simpleName?.removeSuffix("TrailblazeTool") ?: it.toString()
    }.toSet()
    assertTrue("HideKeyboard" in classNames, "hideKeyboard should appear as class-backed")
    assertEquals(setOf(ToolName(yamlTool)), toolSet.resolvedYamlToolNames)
  }

  @Test
  fun `toCatalogEntry forwards both class-backed and YAML tool names`() {
    val yaml = """
      id: test_cat
      description: Catalog entry test
      drivers:
        - android-ondevice-instrumentation
      tools:
        - hideKeyboard
        - $yamlTool
    """.trimIndent()

    val entry = ToolSetYamlLoader.loadFromYaml(yaml, resolver).toCatalogEntry()
    assertEquals(setOf(ToolName(yamlTool)), entry.yamlToolNames)
    assertTrue(entry.toolNames.containsAll(listOf("hideKeyboard", yamlTool)))
  }

  @Test
  fun `YamlBackedHostAppTarget exposes a YAML-defined tool via the yaml tool names accessor`() {
    val coreToolSet = ToolSetYamlLoader.loadFromYaml(
      yamlString = """
        id: test_core
        description: Test
        drivers:
          - android-ondevice-instrumentation
        tools:
          - hideKeyboard
          - $yamlTool
      """.trimIndent(),
      toolNameResolver = resolver,
    )

    val target = AppTargetYamlLoader.loadFromYaml(
      yamlString = """
        id: test
        display_name: Test
        platforms:
          android:
            tool_sets: [test_core]
      """.trimIndent(),
      toolNameResolver = resolver,
      availableToolSets = mapOf("test_core" to coreToolSet),
    )

    assertEquals(
      setOf(ToolName(yamlTool)),
      target.getCustomYamlToolNamesForDriver(
        TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      ),
    )
    val iosYaml = target.getCustomYamlToolNamesForDriver(TrailblazeDriverType.IOS_HOST)
    assertFalse(ToolName(yamlTool) in iosYaml, "iOS platform isn't wired here, so nothing is exposed")
  }

  @Suppress("NOTHING_TO_INLINE")
  private inline fun assertNotNull(value: Any?) {
    kotlin.test.assertNotNull(value)
  }
}
