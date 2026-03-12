package xyz.block.trailblaze.api

import kotlinx.serialization.Serializable

@Serializable
enum class AgentActionType(val displayLabel: String) {
  AIRPLANE_MODE("Airplane"),
  ENTER_TEXT("Type"),
  LAUNCH_APP("Launch"),
  STOP_APP("Stop"),
  SWIPE("Swipe"),
  TAP_POINT("Tap"),
  LONG_PRESS_POINT("Long press"),
  GRANT_PERMISSIONS("Permissions"),
  CLEAR_APP_STATE("Clear state"),
  KILL_APP("Kill"),
  BACK_PRESS("Back"),
  ADD_MEDIA("Media"),
  ASSERT_CONDITION("Assert"),
  WEB_ACTION("Web"),
  PRESS_HOME("Home"),
  HIDE_KEYBOARD("Keyboard"),
  ERASE_TEXT("Erase"),
  SCROLL("Scroll"),
  WAIT_FOR_SETTLE("Settle"),
}

interface HasClickCoordinates {
  val x: Int
  val y: Int
}

/**
 * Right now used just for logging
 */
@Serializable
sealed interface AgentDriverAction {

  val type: AgentActionType

  @Serializable
  data class AddMedia(val mediaFiles: List<String>) : AgentDriverAction {
    override val type = AgentActionType.ADD_MEDIA
  }

  @Serializable
  data class AssertCondition(
    val conditionDescription: String,
    override val x: Int,
    override val y: Int,
    val isVisible: Boolean, // true = assertVisible, false = assertNotVisible
    val textToDisplay: String? = null, // For notVisible assertions, what text we confirmed is NOT there
    val succeeded: Boolean = true, // true if the assertion succeeded
  ) : AgentDriverAction,
    HasClickCoordinates {
    override val type = AgentActionType.ASSERT_CONDITION
  }

  @Serializable
  data class ClearAppState(val appId: String) : AgentDriverAction {
    override val type = AgentActionType.CLEAR_APP_STATE
  }

  @Serializable
  data class AirplaneMode(val enable: Boolean) : AgentDriverAction {
    override val type = AgentActionType.AIRPLANE_MODE
  }

  @Serializable
  data object BackPress : AgentDriverAction {
    override val type = AgentActionType.BACK_PRESS
  }

  @Serializable
  data class StopApp(val appId: String) : AgentDriverAction {
    override val type = AgentActionType.STOP_APP
  }

  @Serializable
  data class KillApp(val appId: String) : AgentDriverAction {
    override val type = AgentActionType.KILL_APP
  }

  @Serializable
  data class GrantPermissions(val appId: String, val permissions: Map<String, String>) :
    AgentDriverAction {
    override val type = AgentActionType.GRANT_PERMISSIONS
  }

  @Serializable
  data class LaunchApp(val appId: String) : AgentDriverAction {
    override val type = AgentActionType.LAUNCH_APP
  }

  @Serializable
  data class TapPoint(override val x: Int, override val y: Int) :
    AgentDriverAction,
    HasClickCoordinates {
    override val type = AgentActionType.TAP_POINT
  }

  @Serializable
  data class LongPressPoint(override val x: Int, override val y: Int) :
    AgentDriverAction,
    HasClickCoordinates {
    override val type = AgentActionType.LONG_PRESS_POINT
  }

  @Serializable
  data class Swipe(
    val direction: String,
    val durationMs: Long,
    val startX: Int? = null,
    val startY: Int? = null,
    val endX: Int? = null,
    val endY: Int? = null,
  ) : AgentDriverAction {
    override val type = AgentActionType.SWIPE
  }

  @Serializable
  data class EnterText(val text: String) : AgentDriverAction {
    override val type = AgentActionType.ENTER_TEXT
  }

  @Serializable
  data class OtherAction(override val type: AgentActionType) : AgentDriverAction

  @Serializable
  data object PressHome : AgentDriverAction {
    override val type = AgentActionType.PRESS_HOME
  }

  @Serializable
  data object HideKeyboard : AgentDriverAction {
    override val type = AgentActionType.HIDE_KEYBOARD
  }

  @Serializable
  data class EraseText(val characters: Int) : AgentDriverAction {
    override val type = AgentActionType.ERASE_TEXT
  }

  @Serializable
  data class Scroll(val forward: Boolean) : AgentDriverAction {
    override val type = AgentActionType.SCROLL
  }

  @Serializable
  data class WaitForSettle(val timeoutMs: Long) : AgentDriverAction {
    override val type = AgentActionType.WAIT_FOR_SETTLE
  }
}
