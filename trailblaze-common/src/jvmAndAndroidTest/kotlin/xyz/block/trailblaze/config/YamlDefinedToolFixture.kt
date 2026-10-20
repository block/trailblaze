package xyz.block.trailblaze.config

import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeSerializationInitializer
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog

/**
 * A YAML-defined (`tools:` mode) tool for tests. No built-in tool uses that mode, so tests of the
 * YAML-defined machinery register this one through the workspace overlay, the same path a
 * trailmap's `*.tool.yaml` takes.
 *
 * It composes two class-backed tools. `clearFirst` declares no default, so omitting it drops the
 * key and `inputText` keeps its own non-null default.
 */
object YamlDefinedToolFixture {

  const val NAME = "fixture_replaceFieldText"

  const val TOOLSET_ID = "fixture_yaml_toolset"

  val toolName = ToolName(NAME)

  val config: ToolYamlConfig = TrailblazeConfigYaml.instance.decodeFromString(
    ToolYamlConfig.serializer(),
    """
    id: $NAME
    description: Types text into the focused field, optionally emptying it first, then hides the keyboard.
    parameters:
      - name: text
        type: string
        required: true
        description: Text to type.
      - name: clearFirst
        type: boolean
        required: false
        description: Empty the field before typing.
    tools:
      - inputText:
          text: "{{params.text}}"
          clearFirst: "{{params.clearFirst}}"
          hideKeyboardAfter: false
      - hideKeyboard: {}
    """.trimIndent(),
  ).also { it.validate() }

  /** Runs [block] with the fixture registered as a YAML-defined tool, then removes it. */
  fun <T> registered(block: () -> T): T {
    TrailblazeSerializationInitializer.registerWorkspaceYamlTools(mapOf(toolName to config))
    try {
      return block()
    } finally {
      TrailblazeSerializationInitializer.registerWorkspaceYamlTools(emptyMap())
    }
  }

  /**
   * Runs [block] with the fixture registered AND delivered by a toolset compatible with
   * [compatibleDriverTypes], so catalog-driven defaults pick it up.
   */
  fun <T> inToolSet(compatibleDriverTypes: Set<TrailblazeDriverType>, block: () -> T): T = registered {
    TrailblazeToolSetCatalog.registerWorkspaceToolSets(
      listOf(
        ToolSetCatalogEntry(
          id = TOOLSET_ID,
          description = "Delivers the YAML-defined fixture tool.",
          toolClasses = emptySet(),
          yamlToolNames = setOf(toolName),
          compatibleDriverTypes = compatibleDriverTypes,
        ),
      ),
    )
    try {
      block()
    } finally {
      TrailblazeToolSetCatalog.registerWorkspaceToolSets(emptyList())
    }
  }
}
