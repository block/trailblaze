package xyz.block.trailblaze.mcp.newtools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import java.io.File
import xyz.block.trailblaze.util.Console

/**
 * Trail management tool for test authoring and playback.
 *
 * Handles:
 * - START: Begin a named trail (with optional device connection)
 * - SAVE: Save current session as a trail
 * - RUN: Execute an existing trail
 * - LIST: List available devices or trails
 * - END: End session (discard if not saved)
 */
@Suppress("unused")
class TrailTool(
  private val sessionContext: TrailblazeMcpSessionContext?,
  private val mcpBridge: TrailblazeMcpBridge,
  private val trailsDirectory: String = "./trails",
) : ToolSet {

  /** Lazy-initialized file manager for trail operations */
  private val trailFileManager: TrailFileManager by lazy {
    TrailFileManager(trailsDirectory)
  }

  /** Lazy-initialized executor for running trails deterministically */
  private val trailExecutor: TrailExecutor by lazy {
    TrailExecutorImpl(mcpBridge, sessionContext, trailsDirectory)
  }

  enum class TrailAction {
    /** Start a new named trail (optionally connect to device) */
    START,
    /** Save current session as a trail file */
    SAVE,
    /** Run an existing trail file */
    RUN,
    /** List available devices or trails */
    LIST,
    /** End session without saving */
    END,
  }

  @LLMDescription(
    """
    Manage trails (reusable test recordings).
    
    Actions:
    - START: Begin a named trail (connects to device if platform specified)
    - SAVE: Save your session as a reusable trail (works anytime!)
    - RUN: Execute a saved trail without AI
    - LIST: Show available devices or trails
    - END: End session without saving
    
    Examples:
    - trail(action=START, name="login_flow", platform=ANDROID)
    - trail(action=SAVE, name="my_test") → saves session so far
    - trail(action=RUN, name="login_flow") → runs without AI
    - trail(action=LIST) → shows devices
    - trail(action=LIST, filter="login") → shows trails matching "login"
    
    Tip: Sessions are always recorded. Save anytime to create a reusable test!
    """
  )
  @Tool
  suspend fun trail(
    @LLMDescription("Action: START, SAVE, RUN, LIST, or END")
    action: TrailAction,
    @LLMDescription("Trail name (for START, SAVE, or RUN by name)")
    name: String? = null,
    @LLMDescription("Platform to connect: ANDROID or IOS (for START or RUN)")
    platform: TrailblazeDevicePlatform? = null,
    @LLMDescription("Specific device ID (optional)")
    device: String? = null,
    @LLMDescription("Trail file path (for RUN)")
    file: String? = null,
    @LLMDescription("Filter string (for LIST trails)")
    filter: String? = null,
  ): String {
    return when (action) {
      TrailAction.START -> handleStart(name, platform, device)
      TrailAction.SAVE -> handleSave(name)
      TrailAction.RUN -> handleRun(name, file, platform, device)
      TrailAction.LIST -> handleList(filter)
      TrailAction.END -> handleEnd()
    }
  }

  private suspend fun handleStart(
    name: String?,
    platform: TrailblazeDevicePlatform?,
    device: String?,
  ): String {
    // Connect to device if platform or device specified
    if (platform != null || device != null) {
      val connectResult = connectToDevice(platform, device)
      if (connectResult.startsWith("Error")) {
        return connectResult
      }
    }

    // Start recording with name
    val trailName = name ?: "unnamed_${System.currentTimeMillis()}"
    sessionContext?.startTrailRecording(trailName)

    val deviceInfo = mcpBridge.getAvailableDevices()
      .find { sessionContext?.associatedDeviceId == it.trailblazeDeviceId }
      ?.instanceId ?: "no device"
    
    Console.log("")
    Console.log("┌──────────────────────────────────────────────────────────────────────────────")
    Console.log("│ [trail] Started: $trailName")
    Console.log("│ Device: $deviceInfo")
    Console.log("└──────────────────────────────────────────────────────────────────────────────")

    return TrailStartResult(
      started = true,
      name = trailName,
      device = deviceInfo,
      message = if (name != null) {
        "Trail '$trailName' started. Use blaze(), verify(), ask() to build your test."
      } else {
        "Recording started. Save anytime with trail(action=SAVE, name='your_name')"
      },
    ).toJson()
  }

  private suspend fun handleSave(name: String?): String {
    // Get trail name from param or existing session
    val trailName = name ?: sessionContext?.getCurrentTrailName()
    if (trailName == null) {
      return TrailSaveResult(
        saved = false,
        error = "No trail name. Use trail(action=SAVE, name='your_trail_name')",
      ).toJson()
    }

    // Set the name if it was provided (for implicit recordings)
    if (name != null) {
      sessionContext?.setTrailName(name)
    }

    // Get recorded steps
    val steps = sessionContext?.getRecordedSteps() ?: emptyList()
    if (steps.isEmpty()) {
      return TrailSaveResult(
        saved = false,
        error = "No steps recorded yet. Use blaze(), verify(), or ask() first.",
      ).toJson()
    }

    // Get platform from connected device
    val platform = sessionContext?.associatedDeviceId?.let { deviceId ->
      mcpBridge.getAvailableDevices().find { it.trailblazeDeviceId == deviceId }?.platform
    }

    Console.log("")
    Console.log("┌──────────────────────────────────────────────────────────────────────────────")
    Console.log("│ [trail] Saving: $trailName")
    Console.log("│ Steps: ${steps.size}")
    Console.log("│ Platform: ${platform?.displayName ?: "unknown"}")
    Console.log("└──────────────────────────────────────────────────────────────────────────────")

    // Save using TrailFileManager
    val saveResult = trailFileManager.saveTrail(
      name = trailName,
      steps = steps,
      platform = platform,
    )

    return if (saveResult.success) {
      TrailSaveResult(
        saved = true,
        name = trailName,
        file = saveResult.filePath,
        steps = steps.size,
        message = "Trail saved with ${steps.size} steps! Run anytime with trail(action=RUN, name='$trailName')",
      ).toJson()
    } else {
      TrailSaveResult(
        saved = false,
        error = saveResult.error ?: "Unknown error saving trail",
      ).toJson()
    }
  }

  private suspend fun handleRun(
    name: String?,
    file: String?,
    platform: TrailblazeDevicePlatform?,
    device: String?,
  ): String {
    // Resolve trail file
    val trailFile = when {
      file != null -> file
      name != null -> trailFileManager.findTrailByName(name)
      else -> return TrailRunResult(
        success = false,
        error = "Specify name or file. Example: trail(action=RUN, name='login_test')",
      ).toJson()
    }

    if (trailFile == null) {
      return TrailRunResult(
        success = false,
        error = "Trail '$name' not found. Use trail(action=LIST) to see available trails.",
      ).toJson()
    }

    // Load the trail to validate it exists and get step count
    val loadResult = trailFileManager.loadTrail(trailFile)
    if (!loadResult.success) {
      return TrailRunResult(
        success = false,
        error = loadResult.error ?: "Failed to load trail",
      ).toJson()
    }

    val stepCount = loadResult.promptSteps?.size ?: 0

    // Connect to device if needed
    if (platform != null || device != null) {
      val connectResult = connectToDevice(platform, device)
      if (connectResult.startsWith("Error")) {
        return TrailRunResult(success = false, error = connectResult).toJson()
      }
    } else if (sessionContext?.associatedDeviceId == null) {
      // Try to auto-connect
      val devices = mcpBridge.getAvailableDevices()
      if (devices.isEmpty()) {
        return TrailRunResult(
          success = false,
          error = "No device connected. Specify platform=ANDROID or platform=IOS",
        ).toJson()
      }
      val connectResult = connectToDevice(devices.first().platform, null)
      if (connectResult.startsWith("Error")) {
        return TrailRunResult(success = false, error = connectResult).toJson()
      }
    }

    Console.log("")
    Console.log("┌──────────────────────────────────────────────────────────────────────────────")
    Console.log("│ [trail] Running: $trailFile")
    Console.log("│ Title: ${loadResult.config?.title ?: "untitled"}")
    Console.log("│ Steps: $stepCount")

    // Execute the trail deterministically using TrailExecutor
    val trailItems = loadResult.trailItems
    if (trailItems == null) {
      return TrailRunResult(
        success = false,
        error = "Trail file loaded but contained no executable items",
      ).toJson()
    }
    val executionResult = trailExecutor.execute(
      trailItems = trailItems,
      trailName = loadResult.config?.title ?: File(trailFile).nameWithoutExtension,
    ) { progress ->
      Console.log("│ $progress")
      sessionContext?.sendIndeterminateProgressMessage(progress)
    }

    Console.log("│ Result: ${if (executionResult.passed) "PASSED" else "FAILED"}")
    Console.log("│ Steps executed: ${executionResult.stepsExecuted}, Duration: ${executionResult.durationMs}ms")
    if (!executionResult.passed) {
      Console.log("│ Failed at step ${executionResult.failedAtStep}: ${executionResult.failureReason}")
    }
    Console.log("└──────────────────────────────────────────────────────────────────────────────")

    return TrailRunResult(
      success = executionResult.passed,
      file = trailFile,
      steps = executionResult.stepsExecuted,
      duration = executionResult.durationMs,
      failedAt = executionResult.failedAtStep,
      failureReason = executionResult.failureReason,
      message = if (executionResult.passed) {
        "Trail completed successfully: ${executionResult.stepsExecuted} steps in ${executionResult.durationMs}ms"
      } else {
        "Trail failed at step ${executionResult.failedAtStep}: ${executionResult.failureReason}"
      },
    ).toJson()
  }

  private suspend fun handleList(filter: String?): String {
    // If no filter, list devices
    if (filter == null) {
      val devices = mcpBridge.getAvailableDevices()
      if (devices.isEmpty()) {
        return TrailListResult(
          devices = emptyList(),
          message = "No devices available. Connect an Android device or start an iOS simulator.",
        ).toJson()
      }
      return TrailListResult(
        devices = devices.map { "${it.instanceId} (${it.platform.displayName})" },
        message = "Use device(action=ANDROID) or trail(action=START, platform=ANDROID) to connect.",
      ).toJson()
    }

    // List trails matching filter using TrailFileManager
    val trails = trailFileManager.listTrails(filter)
    return TrailListResult(
      trails = trails,
      count = trails.size,
      message = if (trails.isEmpty()) {
        "No trails found matching '$filter'"
      } else {
        "Found ${trails.size} trail(s). Run with trail(action=RUN, name='...')"
      },
    ).toJson()
  }

  private suspend fun handleEnd(): String {
    val wasEnded = mcpBridge.endSession()
    sessionContext?.clearRecording()
    sessionContext?.clearAssociatedDevice()

    return TrailEndResult(
      ended = wasEnded,
      message = if (wasEnded) {
        "Session ended. Recording discarded (use SAVE to keep trails)."
      } else {
        "No active session."
      },
    ).toJson()
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Helper methods
  // ─────────────────────────────────────────────────────────────────────────────

  private suspend fun connectToDevice(
    platform: TrailblazeDevicePlatform?,
    deviceId: String?,
  ): String {
    val devices = mcpBridge.getAvailableDevices()

    val targetDevice = when {
      deviceId != null -> devices.find { it.instanceId == deviceId }
        ?: return "Error: Device '$deviceId' not found"
      platform != null -> devices.find { it.platform == platform }
        ?: return "Error: No ${platform.displayName} device available"
      else -> devices.firstOrNull()
        ?: return "Error: No devices available"
    }

    mcpBridge.selectDevice(targetDevice.trailblazeDeviceId)
    sessionContext?.setAssociatedDevice(targetDevice.trailblazeDeviceId)
    return "Connected to ${targetDevice.instanceId}"
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Result types
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class TrailStartResult(
  val started: Boolean,
  val name: String? = null,
  val device: String? = null,
  val message: String? = null,
  val error: String? = null,
) {
  fun toJson(): String = TrailblazeJsonInstance.encodeToString(serializer(), this)
}

@Serializable
data class TrailSaveResult(
  val saved: Boolean,
  val name: String? = null,
  val file: String? = null,
  val steps: Int? = null,
  val message: String? = null,
  val error: String? = null,
) {
  fun toJson(): String = TrailblazeJsonInstance.encodeToString(serializer(), this)
}

@Serializable
data class TrailRunResult(
  val success: Boolean,
  val file: String? = null,
  val steps: Int? = null,
  val duration: Long? = null,
  val message: String? = null,
  val failedAt: Int? = null,
  val failureReason: String? = null,
  val error: String? = null,
) {
  fun toJson(): String = TrailblazeJsonInstance.encodeToString(serializer(), this)
}

@Serializable
data class TrailListResult(
  val devices: List<String>? = null,
  val trails: List<String>? = null,
  val count: Int? = null,
  val message: String? = null,
) {
  fun toJson(): String = TrailblazeJsonInstance.encodeToString(serializer(), this)
}

@Serializable
data class TrailEndResult(
  val ended: Boolean,
  val message: String? = null,
) {
  fun toJson(): String = TrailblazeJsonInstance.encodeToString(serializer(), this)
}
