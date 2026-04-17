package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.KeyCode
import maestro.orchestra.Command
import maestro.orchestra.PressKeyCommand
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.isSuccess
import xyz.block.trailblaze.yaml.serializers.CaseInsensitiveEnumSerializer

@Serializable
@TrailblazeToolClass("pressKey")
@LLMDescription(
  """
Use this tool to press special keys that are not used for regular text input.
Examples of when to use this tool:
- Press back to navigate to the previous page or state (Android only).
- Press enter to submit the current form or text input.
- Press home to go to the device's home screen.
- Press home to send the current app to the background.
""",
)
data class PressKeyTrailblazeTool(
  val keyCode: PressKeyCode,
) : MapsToMaestroCommands() {

  @Serializable(with = PressKeyCode.Serializer::class)
  enum class PressKeyCode {
    BACK,
    ENTER,
    HOME;

    object Serializer : CaseInsensitiveEnumSerializer<PressKeyCode>(PressKeyCode::class)
  }

  override fun toMaestroCommands(memory: AgentMemory): List<Command> = listOf(
    PressKeyCommand(
      code = keyCode.let { code ->
        when (code) {
          PressKeyCode.BACK -> KeyCode.BACK
          PressKeyCode.ENTER -> KeyCode.ENTER
          PressKeyCode.HOME -> KeyCode.HOME
        }
      },
    ),
  )

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val result = super.execute(toolExecutionContext)
    if (result.isSuccess()) return TrailblazeToolResult.Success(message = "Pressed ${keyCode.name}")
    return result
  }
}
