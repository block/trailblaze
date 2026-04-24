package xyz.block.trailblaze.config.project

import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.PlatformConfig
import xyz.block.trailblaze.config.TrailblazeConfigYaml
import xyz.block.trailblaze.config.ToolSetYamlConfig
import xyz.block.trailblaze.llm.config.BuiltInProviderConfig
import xyz.block.trailblaze.llm.config.LlmModelConfigEntry

/**
 * Tests for [TrailblazeProjectConfigLoader]. The loader is not yet wired into runtime code
 * paths, so behaviour is exercised here directly.
 */
class TrailblazeProjectConfigLoaderTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `load returns null when no trailblaze_yaml exists`() {
    val missing = File(tempFolder.root, "trailblaze.yaml")
    assertNull(TrailblazeProjectConfigLoader.load(missing))
  }

  @Test
  fun `empty file decodes to an empty config`() {
    val file = tempFolder.writeConfig("")
    val loaded = TrailblazeProjectConfigLoader.load(file)
    assertNotNull(loaded)
    val raw = loaded.raw
    assertNull(raw.defaults)
    assertTrue(raw.targets.isEmpty())
    assertTrue(raw.toolsets.isEmpty())
    assertTrue(raw.tools.isEmpty())
    assertTrue(raw.providers.isEmpty())
    assertNull(raw.llm)
  }

  @Test
  fun `decodes defaults and llm sections`() {
    val file = tempFolder.writeConfig(
      """
      defaults:
        target: sampleapp
        llm: openai/gpt-4.1
      llm:
        providers:
          openai:
            models:
              - id: gpt-4.1
        defaults:
          model: openai/gpt-4.1
      """.trimIndent(),
    )
    val resolved = TrailblazeProjectConfigLoader.loadResolved(file)
    assertNotNull(resolved)
    assertEquals("sampleapp", resolved.defaults?.target)
    assertEquals("openai/gpt-4.1", resolved.defaults?.llm)
    val llm = assertNotNull(resolved.llm)
    assertEquals("openai/gpt-4.1", llm.defaults.model)
    assertEquals(1, llm.providers.size)
  }

  @Test
  fun `inline target entry decodes into AppTargetYamlConfig`() {
    val file = tempFolder.writeConfig(
      """
      targets:
        - id: sampleapp
          display_name: Trailblaze Sample App
          platforms:
            android:
              app_ids:
                - xyz.block.trailblaze.examples.sampleapp
      """.trimIndent(),
    )
    val resolved = TrailblazeProjectConfigLoader.loadResolved(file)!!
    val inline = assertIs<TargetEntry.Inline>(resolved.targets.single())
    assertEquals("sampleapp", inline.config.id)
    assertEquals("Trailblaze Sample App", inline.config.displayName)
    assertEquals(
      listOf("xyz.block.trailblaze.examples.sampleapp"),
      inline.config.platforms?.get("android")?.appIds,
    )
  }

  @Test
  fun `ref target entry resolves relative to trailblaze_yaml dir`() {
    val targetsDir = File(tempFolder.root, "targets").apply { mkdirs() }
    File(targetsDir, "my-app.yaml").writeText(
      """
      id: my-app
      display_name: My App
      platforms:
        android:
          app_ids:
            - com.example.my-app
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - ref: targets/my-app.yaml
      """.trimIndent(),
    )
    val resolved = TrailblazeProjectConfigLoader.loadResolved(file)!!
    val inline = assertIs<TargetEntry.Inline>(resolved.targets.single())
    assertEquals("my-app", inline.config.id)
    assertEquals("My App", inline.config.displayName)
  }

  @Test
  fun `leading slash treats ref as anchor-relative`() {
    val targetsDir = File(tempFolder.root, "targets").apply { mkdirs() }
    File(targetsDir, "anchor-relative.yaml").writeText(
      """
      id: anchored
      display_name: Anchored
      platforms:
        android:
          app_ids:
            - com.example.anchored
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      targets:
        - ref: /targets/anchor-relative.yaml
      """.trimIndent(),
    )
    val resolved = TrailblazeProjectConfigLoader.loadResolved(file)!!
    val inline = assertIs<TargetEntry.Inline>(resolved.targets.single())
    assertEquals("anchored", inline.config.id)
  }

  @Test
  fun `provider ref loads BuiltInProviderConfig`() {
    val providersDir = File(tempFolder.root, "providers").apply { mkdirs() }
    File(providersDir, "custom.yaml").writeText(
      """
      provider_id: custom
      name: Custom Provider
      default_model: x-1
      models:
        - id: x-1
          context_length: 8192
          max_output_tokens: 2048
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      providers:
        - ref: providers/custom.yaml
      """.trimIndent(),
    )
    val resolved = TrailblazeProjectConfigLoader.loadResolved(file)!!
    val inline = assertIs<ProviderEntry.Inline>(resolved.providers.single())
    assertEquals("custom", inline.config.providerId)
    assertEquals("x-1", inline.config.defaultModel)
  }

  @Test
  fun `toolset ref loads ToolSetYamlConfig`() {
    val toolsetsDir = File(tempFolder.root, "toolsets").apply { mkdirs() }
    File(toolsetsDir, "custom.yaml").writeText(
      """
      id: my_custom_toolset
      description: Custom toolset
      tools:
        - some_custom_tool
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      toolsets:
        - ref: toolsets/custom.yaml
      """.trimIndent(),
    )
    val resolved = TrailblazeProjectConfigLoader.loadResolved(file)!!
    val inline = assertIs<ToolsetEntry.Inline>(resolved.toolsets.single())
    assertEquals("my_custom_toolset", inline.config.id)
    assertEquals(listOf("some_custom_tool"), inline.config.tools)
  }

  @Test
  fun `tool ref loads ToolYamlConfig in class mode`() {
    val toolsDir = File(tempFolder.root, "tools").apply { mkdirs() }
    File(toolsDir, "my-tool.yaml").writeText(
      """
      id: my_custom_tool
      class: com.example.tools.MyCustomTool
      """.trimIndent(),
    )
    val file = tempFolder.writeConfig(
      """
      tools:
        - ref: tools/my-tool.yaml
      """.trimIndent(),
    )
    val resolved = TrailblazeProjectConfigLoader.loadResolved(file)!!
    val inline = assertIs<ToolEntry.Inline>(resolved.tools.single())
    assertEquals("my_custom_tool", inline.config.id)
    assertEquals("com.example.tools.MyCustomTool", inline.config.toolClass)
  }

  @Test
  fun `non-scalar ref value is wrapped as TrailblazeProjectConfigException`() {
    // Regression guard: previously this threw raw IllegalStateException from `error(...)`,
    // which escaped the loader's try/catch and produced an inconsistent failure mode.
    val file = tempFolder.writeConfig(
      """
      targets:
        - ref:
            path: nested.yaml
      """.trimIndent(),
    )
    try {
      TrailblazeProjectConfigLoader.load(file)
      fail("Expected TrailblazeProjectConfigException")
    } catch (e: TrailblazeProjectConfigException) {
      assertContains(e.message ?: "", "'ref:' must be a scalar string")
    }
  }

  @Test
  fun `load throws on invalid YAML`() {
    val file = tempFolder.writeConfig("this: is: not: valid")
    try {
      TrailblazeProjectConfigLoader.load(file)
      fail("Expected TrailblazeProjectConfigException")
    } catch (e: TrailblazeProjectConfigException) {
      assertContains(e.message ?: "", "Failed to parse")
    }
  }

  @Test
  fun `missing ref file throws with resolved path`() {
    val file = tempFolder.writeConfig(
      """
      targets:
        - ref: targets/does-not-exist.yaml
      """.trimIndent(),
    )
    val loaded = TrailblazeProjectConfigLoader.load(file)!!
    try {
      TrailblazeProjectConfigLoader.resolveRefs(loaded)
      fail("Expected TrailblazeProjectConfigException")
    } catch (e: TrailblazeProjectConfigException) {
      val msg = e.message ?: ""
      assertContains(msg, "does-not-exist.yaml")
      assertContains(msg, "Referenced target file not found")
    }
  }

  @Test
  fun `ref entry mixing inline fields fails with helpful message`() {
    val file = tempFolder.writeConfig(
      """
      targets:
        - ref: targets/foo.yaml
          id: inline-id
      """.trimIndent(),
    )
    try {
      TrailblazeProjectConfigLoader.load(file)
      fail("Expected TrailblazeProjectConfigException")
    } catch (e: TrailblazeProjectConfigException) {
      assertContains(e.message ?: "", "mixes 'ref:' with inline fields")
    }
  }

  @Test
  fun `encode then decode round-trips inline and ref entries across all sections`() {
    // Guards the four serializers' serialize() methods (the RefOnly encode path and the
    // inline delegation path). Without this, the encode branches are dead code.
    val original = TrailblazeProjectConfig(
      defaults = ProjectDefaults(target = "sampleapp", llm = "openai/gpt-4.1"),
      targets = listOf(
        TargetEntry.Inline(
          AppTargetYamlConfig(
            id = "sampleapp",
            displayName = "Sample App",
            platforms = mapOf("android" to PlatformConfig(appIds = listOf("com.example.sample"))),
          ),
        ),
        TargetEntry.Ref("targets/other.yaml"),
      ),
      toolsets = listOf(
        ToolsetEntry.Inline(ToolSetYamlConfig(id = "t1", tools = listOf("tapOnElement"))),
        ToolsetEntry.Ref("toolsets/shared.yaml"),
      ),
      providers = listOf(
        ProviderEntry.Inline(
          BuiltInProviderConfig(
            providerId = "custom",
            defaultModel = "x-1",
            models = listOf(LlmModelConfigEntry(id = "x-1")),
          ),
        ),
        ProviderEntry.Ref("providers/other.yaml"),
      ),
    )

    val yaml = TrailblazeConfigYaml.instance
    val encoded = yaml.encodeToString(TrailblazeProjectConfig.serializer(), original)
    val decoded = yaml.decodeFromString(TrailblazeProjectConfig.serializer(), encoded)

    assertEquals(original, decoded)
    // Spot-check: ref entries serialize to a single-key `ref:` map, not the inline shape.
    assertContains(encoded, "ref: \"targets/other.yaml\"")
    assertContains(encoded, "ref: \"toolsets/shared.yaml\"")
    assertContains(encoded, "ref: \"providers/other.yaml\"")
  }

  @Test
  fun `blank ref value is rejected with a helpful message`() {
    val file = tempFolder.writeConfig(
      """
      targets:
        - ref: "   "
      """.trimIndent(),
    )
    try {
      TrailblazeProjectConfigLoader.load(file)
      fail("Expected TrailblazeProjectConfigException")
    } catch (e: TrailblazeProjectConfigException) {
      assertContains(e.message ?: "", "'ref:' must not be blank")
    }
  }

  @Test
  fun `ref that resolves to a directory surfaces as read failure`() {
    // A real directory named `targets` exists at the anchor; the ref points at it.
    // The loader should wrap the resulting IOException as TrailblazeProjectConfigException
    // rather than leaking a raw IOException.
    File(tempFolder.root, "targets").apply { mkdirs() }
    val file = tempFolder.writeConfig(
      """
      targets:
        - ref: targets
      """.trimIndent(),
    )
    try {
      TrailblazeProjectConfigLoader.loadResolved(file)
      fail("Expected TrailblazeProjectConfigException")
    } catch (e: TrailblazeProjectConfigException) {
      val msg = e.message ?: ""
      // Either the read fails ("Failed to read") or kaml parses the empty dir-as-text
      // path differently; we just want the wrapping type.
      assertContains(msg, "target ref 'targets'")
    }
  }

  @Test
  fun `field type mismatch surfaces as parse failure`() {
    // `targets:` declared as a scalar instead of a list.
    val file = tempFolder.writeConfig(
      """
      targets: "oops-not-a-list"
      """.trimIndent(),
    )
    try {
      TrailblazeProjectConfigLoader.load(file)
      fail("Expected TrailblazeProjectConfigException")
    } catch (e: TrailblazeProjectConfigException) {
      assertContains(e.message ?: "", "Failed to parse")
    }
  }

  @Test
  fun `schema violation on target missing required id field`() {
    val file = tempFolder.writeConfig(
      """
      targets:
        - display_name: Missing Id
      """.trimIndent(),
    )
    try {
      TrailblazeProjectConfigLoader.load(file)
      fail("Expected TrailblazeProjectConfigException")
    } catch (e: TrailblazeProjectConfigException) {
      assertContains(e.message ?: "", "Failed to parse")
    }
  }

  private fun TemporaryFolder.writeConfig(yaml: String): File {
    val file = File(root, "trailblaze.yaml")
    file.writeText(yaml)
    return file
  }
}
