package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.KeyCode
import maestro.orchestra.Command
import maestro.orchestra.PressKeyCommand
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
Press a special key: BACK (previous screen, Android only), ENTER (submit), HOME (home screen,
backgrounding the app), BACKSPACE (delete before the caret), TAB (next field), ESCAPE (dismiss
keyboard or modal).
""",
)
data class PressKeyTrailblazeTool(
  val keyCode: PressKeyCode,
) : MapsToMaestroCommands() {

  @Serializable(with = PressKeyCode.Serializer::class)
  enum class PressKeyCode {
    BACK,
    ENTER,
    HOME,
    // Editing / navigation keys forwarded from the live device viewer's keyboard handler.
    // Adding these makes Backspace/Delete actually delete in form fields, Tab navigate
    // between fields, and Escape dismiss keyboards/modals. Maestro's KeyCode already
    // supports them — we were just not exposing them through this wrapper, so they fell
    // through `OnDeviceRpcDeviceScreenStream.pressKey`'s when-branch as silent no-ops.
    BACKSPACE,
    TAB,
    ESCAPE,
    ;

    object Serializer : CaseInsensitiveEnumSerializer<PressKeyCode>(PressKeyCode::class, PressKeyCode.entries)
  }

  override fun toMaestroCommands(): List<Command> = listOf(
    PressKeyCommand(
      code = keyCode.let { code ->
        when (code) {
          PressKeyCode.BACK -> KeyCode.BACK
          PressKeyCode.ENTER -> KeyCode.ENTER
          PressKeyCode.HOME -> KeyCode.HOME
          PressKeyCode.BACKSPACE -> KeyCode.BACKSPACE
          PressKeyCode.TAB -> KeyCode.TAB
          PressKeyCode.ESCAPE -> KeyCode.ESCAPE
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
