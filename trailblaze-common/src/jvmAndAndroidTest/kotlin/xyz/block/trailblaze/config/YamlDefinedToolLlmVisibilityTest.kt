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
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog

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
