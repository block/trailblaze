package xyz.block.trailblaze.config

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsNone
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import kotlinx.datetime.Clock
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.KoogToolExt
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.toolcalls.getIsRecordableFromAnnotation
import xyz.block.trailblaze.toolcalls.requiresHostInstance

/**
 * Guards against regressing the YAML-defined tool LLM-visibility wiring. Covers:
 *
 * - `ToolYamlConfig.toTrailblazeToolDescriptor` produces the expected shape.
 * - `TrailblazeToolRepo.asToolRegistry` exposes a YAML-defined tool (`eraseText`) as a Koog
 *   tool so the LLM can select it.
 * - Dynamic-toolset switching (`setActiveToolSets`) picks up `eraseText` via the
 *   `text-editing` catalog entry's `yamlToolNames`.
 */
class YamlDefinedToolLlmVisibilityTest {

  @Test
  fun `toTrailblazeToolDescriptor maps eraseText YAML to the expected shape`() {
    val config = parse(
      """
      id: eraseText
      description: Erases characters from the focused field.
      parameters:
        - name: charactersToErase
          type: integer
          required: false
          default: null
          description: Number of characters to erase.
      tools:
        - maestro:
            commands:
              - eraseText:
                  charactersToErase: "{{params.charactersToErase}}"
      """.trimIndent(),
    )

    val descriptor = config.toTrailblazeToolDescriptor()
    assertThat(descriptor.name).isEqualTo("eraseText")
    assertThat(descriptor.description).isNotNull()
    assertThat(descriptor.description!!).contains("Erases characters")
    assertThat(descriptor.requiredParameters).hasSize(0)
    assertThat(descriptor.optionalParameters).hasSize(1)
    val param = descriptor.optionalParameters.single()
    assertThat(param.name).isEqualTo("charactersToErase")
    assertThat(param.type).isEqualTo("integer")
    assertThat(param.description).isEqualTo("Number of characters to erase.")
  }

  @Test
  fun `asToolRegistry exposes eraseText when yamlToolNames is populated`() {
    val repo = TrailblazeToolRepo(
      trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "set-of-mark",
        toolClasses = TrailblazeToolSet.DefaultLlmTrailblazeTools,
        yamlToolNames = setOf(ToolName("eraseText")),
      ),
    )
    val registry = repo.asToolRegistry(::createContext)
    val toolNames = registry.tools.map { it.descriptor.name }.toSet()
    assertThat(toolNames).contains("eraseText")
  }

  @Test
  fun `tools-mode YAML round-trips is_for_llm is_recordable requires_host`() {
    val config = parse(
      """
      id: customComposite
      description: Composition that opts out of LLM and recording, requires host.
      parameters: []
      is_for_llm: false
      is_recordable: false
      requires_host: true
      tools:
        - takeSnapshot:
            screenName: custom
      """.trimIndent(),
    )
    assertThat(config.isForLlm).isEqualTo(false)
    assertThat(config.isRecordable).isEqualTo(false)
    assertThat(config.requiresHost).isEqualTo(true)

    // The instance-level helpers honor the per-config values.
    val instance = YamlDefinedTrailblazeTool(config = config, params = emptyMap())
    assertThat(instance.getIsRecordableFromAnnotation()).isEqualTo(false)
    assertThat(instance.requiresHostInstance()).isEqualTo(true)
  }

  @Test
  fun `tools-mode defaults all three fields to null which behave like the annotation defaults`() {
    val config = parse(
      """
      id: defaultedComposite
      description: Composition that takes the framework defaults.
      parameters: []
      tools:
        - takeSnapshot:
            screenName: defaulted
      """.trimIndent(),
    )
    assertThat(config.isForLlm).isEqualTo(null)
    assertThat(config.isRecordable).isEqualTo(null)
    assertThat(config.requiresHost).isEqualTo(null)

    // Null collapses to the annotation defaults: recordable=true, requiresHost=false.
    val instance = YamlDefinedTrailblazeTool(config = config, params = emptyMap())
    assertThat(instance.getIsRecordableFromAnnotation()).isEqualTo(true)
    assertThat(instance.requiresHostInstance()).isEqualTo(false)
  }

  @Test
  fun `buildKoogToolsForYamlDefined drops configs with is_for_llm false`() {
    val visible = parse(
      """
      id: visibleTool
      description: Visible to the LLM.
      parameters: []
      tools:
        - takeSnapshot:
            screenName: visible
      """.trimIndent(),
    )
    val hidden = parse(
      """
      id: hiddenTool
      description: Hidden from the LLM via is_for_llm false.
      parameters: []
      is_for_llm: false
      tools:
        - takeSnapshot:
            screenName: hidden
      """.trimIndent(),
    )
    val configsByName = mapOf(
      ToolName(visible.id) to visible,
      ToolName(hidden.id) to hidden,
    )

    // Test seam — bypass the cached classpath scan and pass synthetic configs directly.
    val builtTools = KoogToolExt.buildKoogToolsForYamlDefined(
      yamlToolNames = configsByName.keys,
      configsByName = configsByName,
      trailblazeToolContextProvider = ::createContext,
    )
    val builtNames = builtTools.map { it.descriptor.name }.toSet()
    assertThat(builtNames).contains("visibleTool")
    assertThat(builtNames).containsNone("hiddenTool")

    // Same filter applies on the descriptor-only path used when no execution context is available.
    val builtDescriptors = KoogToolExt.buildDescriptorsForYamlDefined(
      yamlToolNames = configsByName.keys,
      configsByName = configsByName,
    )
    val descriptorNames = builtDescriptors.map { it.name }.toSet()
    assertThat(descriptorNames).contains("visibleTool")
    assertThat(descriptorNames).containsNone("hiddenTool")
  }

  @Test
  fun `class-mode validate rejects is_for_llm is_recordable requires_host is_verification`() {
    listOf(
      "is_for_llm: false",
      "is_recordable: false",
      "requires_host: true",
      "is_verification: true",
    ).forEach { line ->
      val yaml = """
        id: someClassTool
        class: com.example.tools.SomeClassTrailblazeTool
        $line
      """.trimIndent()
      val config = TrailblazeConfigYaml.instance.decodeFromString(ToolYamlConfig.serializer(), yaml)
      try {
        config.validate()
        kotlin.test.fail("Expected class-mode tool with `$line` to fail validation")
      } catch (e: IllegalArgumentException) {
        assertThat(e.message!!).contains("class:")
      }
    }
  }

  @Test
  fun `eraseText and hideKeyboard come through core_interaction (alwaysEnabled)`() {
    val catalog = TrailblazeToolSetCatalog.defaultEntries()
    val coreTools = TrailblazeToolSetCatalog.resolve(emptyList(), catalog)
    val repo = TrailblazeToolRepo(
      trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "core",
        toolClasses = coreTools.toolClasses,
        yamlToolNames = coreTools.yamlToolNames,
      ),
      toolSetCatalog = catalog,
    )

    // core_interaction is alwaysEnabled; eraseText (YAML-defined) and hideKeyboard
    // (class-backed) both sit in that toolset and should be present from the start.
    val tools = repo.asToolRegistry(::createContext).tools.map { it.descriptor.name }.toSet()
    assertThat(tools).contains("eraseText")
    assertThat(tools).contains("hideKeyboard")
  }

  private fun parse(yaml: String): ToolYamlConfig =
    TrailblazeConfigYaml.instance.decodeFromString(ToolYamlConfig.serializer(), yaml)
      .also { it.validate() }

  private fun createContext(): TrailblazeToolExecutionContext = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "test-device",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      ),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      widthPixels = 1080,
      heightPixels = 1920,
    ),
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now())
    },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
  )
}
