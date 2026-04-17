package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.Command
import maestro.orchestra.LaunchAppCommand
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.isSuccess

@Serializable
@TrailblazeToolClass("launchApp")
@LLMDescription(
  "Use this to open an app on the device as if a user tapped on the app icon in the launcher.",
)
data class LaunchAppTrailblazeTool(
  @LLMDescription("The package name of the app to launch. Example: 'com.example.app'")
  val appId: String,
  @LLMDescription(
    """
Available App Launch Modes:
- "REINSTALL" (Default if unspecified) will launch the app as if it was just installed and never run on the device before.
- "RESUME" will launch the app like you would from the apps launcher.  If the app was in memory, it'll pick up where it left off.
- "FORCE_RESTART" will force stop the application and then launch the app like you would from the app launcher.
    """,
  )
  val launchMode: LaunchMode = LaunchMode.REINSTALL,
  override val reasoning: String? = null,
) : MapsToMaestroCommands(), ReasoningTrailblazeTool {

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    // iOS system apps (Calendar, Contacts, etc.) cannot have their state cleared — the OS
    // prohibits uninstalling them. Skip clearState upfront rather than catching the error.
    // All Apple system apps use the com.apple.* prefix, which third-party apps cannot use.
    val isIosSystemApp = toolExecutionContext.trailblazeDeviceInfo.platform == TrailblazeDevicePlatform.IOS &&
      appId.startsWith("com.apple.")
    val effectiveLaunchMode = if (launchMode == LaunchMode.REINSTALL && isIosSystemApp) {
      LaunchMode.FORCE_RESTART
    } else {
      launchMode
    }
    val result = if (effectiveLaunchMode != launchMode) {
      copy(launchMode = effectiveLaunchMode).execute(toolExecutionContext)
    } else {
      super.execute(toolExecutionContext)
    }

    // Wait for the app to reach the foreground before returning, so the next view hierarchy
    // snapshot is stable. This replaces a blind 2s delay — polling returns as soon as the app
    // is ready (typically <1s) while still handling slow CI cold starts (up to 30s).
    if (toolExecutionContext.trailblazeDeviceInfo.platform == TrailblazeDevicePlatform.ANDROID) {
      toolExecutionContext.androidDeviceCommandExecutor
        ?.waitUntilAppInForeground(appId)
    }
    if (result.isSuccess()) {
      return TrailblazeToolResult.Success(message = "Launched $appId ($effectiveLaunchMode)")
    }
    return result
  }

  override fun toMaestroCommands(memory: AgentMemory): List<Command> = listOf(
    LaunchAppCommand(
      appId = appId,
      clearState = when (launchMode) {
        LaunchMode.REINSTALL -> true
        LaunchMode.RESUME,
        LaunchMode.FORCE_RESTART,
        -> false
      },
      stopApp = when (launchMode) {
        LaunchMode.RESUME -> false
        LaunchMode.FORCE_RESTART,
        LaunchMode.REINSTALL,
        -> true
      },
    ),
  )

  enum class LaunchMode {
    /**
     * Launch the app in a clean state, like when the app is initially installed.
     */
    REINSTALL,

    /**
     * Resume the app without clearing its state.
     */
    RESUME,

    /**
     * Stop the application and then restart it without clearing its state.
     */
    FORCE_RESTART,

    ;

    companion object {
      fun fromString(value: String?): LaunchMode = LaunchMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: REINSTALL
    }
  }
}
