package xyz.block.trailblaze.api

import kotlinx.serialization.Serializable

enum class AgentActionType {
  ENTER_TEXT,
  LAUNCH_APP,
  STOP_APP,
  SWIPE,
  TAP_POINT,
  LONG_PRESS_POINT,
  GRANT_PERMISSIONS,
  CLEAR_APP_STATE,
  KILL_APP,
  BACK_PRESS,
  ADD_MEDIA,
}

interface HasClickCoordinates {
  val x: Int
  val y: Int
}

/**
 * Right now used just for logging
 */
@Serializable
sealed interface MaestroDriverActionType {

  val type: AgentActionType

  @Serializable
  data class AddMedia(val mediaFiles: List<String>) : MaestroDriverActionType {
    override val type = AgentActionType.ADD_MEDIA
  }

  @Serializable
  data class ClearAppState(val appId: String) : MaestroDriverActionType {
    override val type = AgentActionType.CLEAR_APP_STATE
  }

  @Serializable
  data object BackPress : MaestroDriverActionType {
    override val type = AgentActionType.BACK_PRESS
  }

  @Serializable
  data class StopApp(val appId: String) : MaestroDriverActionType {
    override val type = AgentActionType.STOP_APP
  }

  @Serializable
  data class KillApp(val appId: String) : MaestroDriverActionType {
    override val type = AgentActionType.KILL_APP
  }

  @Serializable
  data class GrantPermissions(val appId: String, val permissions: Map<String, String>) : MaestroDriverActionType {
    override val type = AgentActionType.GRANT_PERMISSIONS
  }

  @Serializable
  data class LaunchApp(val appId: String) : MaestroDriverActionType {
    override val type = AgentActionType.LAUNCH_APP
  }

  @Serializable
  data class TapPoint(override val x: Int, override val y: Int) :
    MaestroDriverActionType,
    HasClickCoordinates {
    override val type = AgentActionType.TAP_POINT
  }

  @Serializable
  data class LongPressPoint(override val x: Int, override val y: Int) :
    MaestroDriverActionType,
    HasClickCoordinates {
    override val type = AgentActionType.LONG_PRESS_POINT
  }

  @Serializable
  data class Swipe(val direction: String, val durationMs: Long) : MaestroDriverActionType {
    override val type = AgentActionType.SWIPE
  }

  @Serializable
  data class EnterText(val text: String) : MaestroDriverActionType {
    override val type = AgentActionType.ENTER_TEXT
  }

  @Serializable
  data class OtherAction(override val type: AgentActionType) : MaestroDriverActionType
}
