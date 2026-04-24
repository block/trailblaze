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
 * After the YAML-defined tool migration, `core_interaction.yaml` lists `eraseText` alongside
 * class-backed tools like `hideKeyboard`. Authors should not need to know which backing a
 * tool uses; the resolver + resolved toolset must handle both uniformly.
 */
class YamlToolSetResolutionTest {

  private val resolver = ToolNameResolver.fromBuiltInAndCustomTools()

  @Test
  fun `resolver reports eraseText as known via the YAML backing`() {
    assertTrue(resolver.isKnown("eraseText"), "eraseText should be known (YAML-defined)")
    assertNull(resolver.resolveOrNull("eraseText"), "eraseText has no KClass — resolveOrNull returns null")
    assertEquals(ToolName("eraseText"), resolver.resolveYamlNameOrNull("eraseText"))
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
      names = listOf("hideKeyboard", "eraseText", "definitelyNotARealTool"),
    )
    assertEquals(1, partitioned.classBacked.size, "hideKeyboard is class-backed")
    assertEquals(setOf(ToolName("eraseText")), partitioned.yamlDefinedNames)
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
  fun `core_interaction toolset resolves eraseText through the YAML backing`() {
    val yaml = """
      id: test_core
      description: Test toolset
      drivers:
        - android-ondevice-instrumentation
      tools:
        - hideKeyboard
        - eraseText
    """.trimIndent()

    val toolSet = ToolSetYamlLoader.loadFromYaml(yaml, resolver)
    val classNames = toolSet.resolvedToolClasses.map {
      it.simpleName?.removeSuffix("TrailblazeTool") ?: it.toString()
    }.toSet()
    assertTrue("HideKeyboard" in classNames, "hideKeyboard should appear as class-backed")
    assertEquals(setOf(ToolName("eraseText")), toolSet.resolvedYamlToolNames)
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
        - eraseText
    """.trimIndent()

    val entry = ToolSetYamlLoader.loadFromYaml(yaml, resolver).toCatalogEntry()
    assertEquals(setOf(ToolName("eraseText")), entry.yamlToolNames)
    assertTrue(entry.toolNames.containsAll(listOf("hideKeyboard", "eraseText")))
  }

  @Test
  fun `YamlBackedHostAppTarget exposes eraseText via the new yaml tool names accessor`() {
    val coreToolSet = ToolSetYamlLoader.loadFromYaml(
      yamlString = """
        id: test_core
        description: Test
        drivers:
          - android-ondevice-instrumentation
        tools:
          - hideKeyboard
          - eraseText
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
      setOf(ToolName("eraseText")),
      target.getCustomYamlToolNamesForDriver(
        TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      ),
    )
    val iosYaml = target.getCustomYamlToolNamesForDriver(TrailblazeDriverType.IOS_HOST)
    assertFalse(ToolName("eraseText") in iosYaml, "iOS platform isn't wired here, so nothing is exposed")
  }

  @Suppress("NOTHING_TO_INLINE")
  private inline fun assertNotNull(value: Any?) {
    kotlin.test.assertNotNull(value)
  }
}
