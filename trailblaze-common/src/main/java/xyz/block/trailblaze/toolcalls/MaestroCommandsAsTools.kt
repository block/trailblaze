package xyz.block.trailblaze.toolcalls

import maestro.orchestra.AddMediaCommand
import maestro.orchestra.AssertCommand
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.BackPressCommand
import maestro.orchestra.ClearKeychainCommand
import maestro.orchestra.ClearStateCommand
import maestro.orchestra.Command
import maestro.orchestra.CopyTextFromCommand
import maestro.orchestra.EraseTextCommand
import maestro.orchestra.EvalScriptCommand
import maestro.orchestra.HideKeyboardCommand
import maestro.orchestra.InputRandomCommand
import maestro.orchestra.InputTextCommand
import maestro.orchestra.KillAppCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.OpenLinkCommand
import maestro.orchestra.PasteTextCommand
import maestro.orchestra.PressKeyCommand
import maestro.orchestra.RunScriptCommand
import maestro.orchestra.ScrollCommand
import maestro.orchestra.ScrollUntilVisibleCommand
import maestro.orchestra.SetAirplaneModeCommand
import maestro.orchestra.SetLocationCommand
import maestro.orchestra.StopAppCommand
import maestro.orchestra.SwipeCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.TapOnPointV2Command
import maestro.orchestra.ToggleAirplaneModeCommand
import maestro.orchestra.TravelCommand
import maestro.orchestra.WaitForAnimationToEndCommand
import kotlin.reflect.KClass

/**
 * Manual calls we register that are not related to Maestro
 */
object MaestroCommandsAsTools {

  private fun maestroDocLink(commandClass: KClass<out Command>): String? {
    val pageName = when (commandClass) {
      AssertCommand::class -> "assertvisible"
      BackPressCommand::class -> "back"
      ClearStateCommand::class -> "clearstate"
      CopyTextFromCommand::class -> "copytextFrom"
      EraseTextCommand::class -> "erasetext"
      HideKeyboardCommand::class -> "hidekeyboard"
      InputTextCommand::class -> "inputtext"
      KillAppCommand::class -> "killapp"
      LaunchAppCommand::class -> "launchapp"
      OpenLinkCommand::class -> "openlink"
      PasteTextCommand::class -> "pastetext"
      ToggleAirplaneModeCommand::class -> "toggleairplanemode"
      ScrollCommand::class -> "scroll"
      SetLocationCommand::class -> "setlocation"
      StopAppCommand::class -> "stopapp"
      TapOnElementCommand::class -> "tapon"
      WaitForAnimationToEndCommand::class -> "waitforanimationtoend"
      else -> {
        error("Unregistered Maestro Command $commandClass")
      }
    }
    return "https://raw.githubusercontent.com/mobile-dev-inc/maestro-docs/refs/heads/main/api-reference/commands/$pageName.md"
  }

  val CURRENTLY_NOT_SUPPORTED = listOf<KClass<out Command>>(
    // Unhandled val mediaPaths: List<String>,
    AddMediaCommand::class,
    // Complex type "Condition" which has dependency on JS Engine.  Use AssertCommand for now.
    AssertConditionCommand::class,
    // We should only register this on iOS
    ClearKeychainCommand::class,
    // No JS Engine Support
    EvalScriptCommand::class,
    // Complex Type "InputRandomType"
    InputRandomCommand::class,
    // Complex type "KeyCode"
    PressKeyCommand::class,
    // No support for JS Engine
    RunScriptCommand::class,
    // Complex Type "ScrollDirection"
    ScrollUntilVisibleCommand::class,
    // Unhandled "AirplaneValue"
    SetAirplaneModeCommand::class,
    // Unhandled "SwipeDirection" and "Point"
    SwipeCommand::class,
    // We want to discourage tapping on x,y coordinates
    TapOnPointV2Command::class,
    // Fake Location Change
    TravelCommand::class,

    // Not Using Yet (Should we even?)
    ToggleAirplaneModeCommand::class,
    SetLocationCommand::class,
    ClearStateCommand::class,
    LaunchAppCommand::class,
    OpenLinkCommand::class,
    CopyTextFromCommand::class,
    AssertCommand::class,
    PasteTextCommand::class,
    HideKeyboardCommand::class,
  )
}
