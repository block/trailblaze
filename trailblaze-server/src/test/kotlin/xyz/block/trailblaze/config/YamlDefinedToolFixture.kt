package xyz.block.trailblaze.config

import xyz.block.trailblaze.logs.client.TrailblazeSerializationInitializer
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog

/**
 * A YAML-defined (`tools:` mode) tool for tests. No built-in tool uses that mode, so tests of the
 * YAML-defined machinery register this one through the workspace overlay, the same path a
 * trailmap's `*.tool.yaml` takes.
 *
 * It composes two class-backed tools and has one optional integer parameter, [PARAM].
 */
object YamlDefinedToolFixture {

  const val NAME = "fixture_eraseAndHideKeyboard"

  const val PARAM = "characters"

  val toolName = ToolName(NAME)

  val config: ToolYamlConfig = TrailblazeConfigYaml.instance.decodeFromString(
    ToolYamlConfig.serializer(),
    """
    id: $NAME
    description: Erases characters from the focused field, then hides the keyboard.
    parameters:
      - name: $PARAM
        type: integer
        required: false
        description: Number of characters to erase.
    tools:
      - eraseText:
          charactersToErase: "{{params.$PARAM}}"
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
   * Runs [block] with the fixture registered AND delivered by the always-enabled
   * `core_interaction` toolset, the way a built-in YAML-defined tool would be.
   */
  fun <T> inCoreInteraction(block: () -> T): T = registered {
    val coreInteraction = TrailblazeToolSetCatalog.defaultEntries().single { it.id == "core_interaction" }
    TrailblazeToolSetCatalog.registerWorkspaceToolSets(
      listOf(coreInteraction.copy(yamlToolNames = coreInteraction.yamlToolNames + toolName)),
    )
    try {
      block()
    } finally {
      TrailblazeToolSetCatalog.registerWorkspaceToolSets(emptyList())
    }
  }
}
