package xyz.block.trailblaze.config

import java.io.File
import xyz.block.trailblaze.llm.config.WorkspaceConfigDirHolder
import xyz.block.trailblaze.logs.client.TrailblazeSerializationInitializer
import xyz.block.trailblaze.toolcalls.ToolName

/**
 * A YAML-defined (`tools:` mode) tool for tests. No built-in tool uses that mode, so tests of the
 * YAML-defined machinery install this one the way a workspace trailmap's `*.tool.yaml` arrives.
 *
 * It composes two class-backed tools. `clearFirst` declares no default, so omitting it drops the
 * key and `inputText` keeps its own non-null default.
 */
object YamlDefinedToolFixture {

  const val NAME = "fixture_replaceFieldText"

  val toolName = ToolName(NAME)

  private val yaml =
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
    """.trimIndent()

  val config: ToolYamlConfig =
    TrailblazeConfigYaml.instance.decodeFromString(ToolYamlConfig.serializer(), yaml).also { it.validate() }

  /** Runs [block] with the fixture registered in the YAML-defined tool overlay, then removes it. */
  fun <T> registered(block: () -> T): T {
    TrailblazeSerializationInitializer.registerWorkspaceYamlTools(mapOf(toolName to config))
    try {
      return block()
    } finally {
      TrailblazeSerializationInitializer.registerWorkspaceYamlTools(emptyMap())
    }
  }

  /**
   * Runs [block] with the fixture written into a workspace trailmap under [configDir] and that
   * workspace selected, so config discovery finds it on disk, and registered in the overlay.
   */
  fun <T> inWorkspace(configDir: File, block: () -> T): T = registered {
    File(configDir, "trailmaps/fixtures/tools/$NAME.tool.yaml").apply {
      parentFile.mkdirs()
      writeText(yaml)
    }
    val previousResolver = WorkspaceConfigDirHolder.resolver
    WorkspaceConfigDirHolder.resolver = { configDir }
    try {
      block()
    } finally {
      WorkspaceConfigDirHolder.resolver = previousResolver
    }
  }
}
