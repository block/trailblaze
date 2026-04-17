package xyz.block.trailblaze.mcp.newtools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.client.LogEmitter
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.getSessionStartedInfo
import xyz.block.trailblaze.mcp.McpToolProfile
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.report.utils.TrailblazeYamlSessionRecording.generateRecordedYaml
import java.io.File
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.TrailConfig

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
class TrailMcpTool(
  private val sessionContext: TrailblazeMcpSessionContext?,
  private val mcpBridge: TrailblazeMcpBridge,
  private val trailsDirectory: String = "./trails",
  private val logEmitter: LogEmitter? = null,
  /** LogsRepo for log-based trail generation. When available, trails are computed from on-disk logs. */
  private val logsRepo: LogsRepo? = null,
  /** Provides the active Trailblaze session ID for reading logs. */
  private val sessionIdProvider: (() -> SessionId?)? = null,
) : ToolSet {

  /** Lazy-initialized file manager for trail operations */
  private val trailFileManager: TrailFileManager by lazy {
    TrailFileManager(trailsDirectory)
  }

  /** Lazy-initialized executor for running trails deterministically */
  private val trailExecutor: TrailExecutor by lazy {
    TrailExecutorImpl(mcpBridge, sessionContext, trailsDirectory, logEmitter)
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
    - trail(action=LIST) → shows first 20 available trails (with titles)
    - trail(action=LIST, filter="login") → shows trails matching "login" (searches path and title)
    - trail(action=LIST, page=2) → shows the next page of results
    
    Tip: Sessions are always recorded. Save anytime to create a reusable test!
    """
  )
  @Tool(McpToolProfile.TOOL_TRAIL)
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
    @LLMDescription("Filter string for LIST trails (searches path and title, case-insensitive)")
    filter: String? = null,
    @LLMDescription("Page number for LIST results (1-based, 20 results per page)")
    page: Int? = null,
  ): String {
    return when (action) {
      TrailAction.START -> handleStart(name, platform, device)
      TrailAction.SAVE -> {
        Console.log("[trail] Deprecation: trail(action=SAVE) is deprecated. Use session(action=SAVE, title='...') instead.")
        handleSave(name)
      }
      TrailAction.RUN -> handleRun(name, file, platform, device)
      TrailAction.LIST -> handleList(filter, page ?: 1)
      TrailAction.END -> {
        Console.log("[trail] Deprecation: trail(action=END) is deprecated. Use session(action=STOP) instead.")
        handleEnd()
      }
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

    // Get platform from connected device
    val platform = sessionContext?.associatedDeviceId?.let { deviceId ->
      mcpBridge.getAvailableDevices().find { it.trailblazeDeviceId == deviceId }?.platform
    }

    // Try log-based trail generation first (reads from LogsRepo on disk)
    val sessionId = sessionIdProvider?.invoke()
    if (logsRepo != null && sessionId != null) {
      return handleSaveFromLogs(trailName, sessionId, platform)
    }

    // Fallback: use in-memory RecordedStep path
    return handleSaveFromRecordedSteps(trailName, platform)
  }

  /**
   * Generates trail YAML from on-disk logs via [TrailblazeYamlSessionRecording].
   *
   * This is the preferred path because it uses actual TrailblazeTool instances from the logs
   * (with proper serializers), rather than wrapping everything as OtherTrailblazeTool.
   */
  private fun handleSaveFromLogs(
    trailName: String,
    sessionId: SessionId,
    platform: TrailblazeDevicePlatform?,
  ): String {
    val logs = logsRepo!!.getLogsForSession(sessionId)
    if (logs.isEmpty()) {
      return TrailSaveResult(
        saved = false,
        error = "No logs found for session. Use blaze(), verify(), or ask() first.",
      ).toJson()
    }

    Console.log("")
    Console.log("┌──────────────────────────────────────────────────────────────────────────────")
    Console.log("│ [trail] Saving from logs: $trailName")
    Console.log("│ Session: ${sessionId.value}")
    Console.log("│ Logs: ${logs.size}")
    Console.log("│ Platform: ${platform?.displayName ?: "unknown"}")
    Console.log("└──────────────────────────────────────────────────────────────────────────────")

    // Extract session info from Started log to include platform/driver/app in the recording
    val startedStatus = logs.getSessionStartedInfo()
    val sessionTrailConfig = startedStatus?.let { started ->
      val originalConfig = started.trailConfig
      TrailConfig(
        id = originalConfig?.id,
        title = originalConfig?.title,
        description = originalConfig?.description,
        priority = originalConfig?.priority,
        context = originalConfig?.context,
        source = originalConfig?.source,
        metadata = originalConfig?.metadata,
        target = originalConfig?.target,
        electron = originalConfig?.electron,
        driver = started.trailblazeDeviceInfo.trailblazeDriverType.name,
        platform = started.trailblazeDeviceInfo.platform.name.lowercase(),
      )
    } ?: platform?.let { p -> TrailConfig(platform = p.name.lowercase()) }

    val yamlContent = try {
      logs.generateRecordedYaml(sessionTrailConfig = sessionTrailConfig)
    } catch (e: Exception) {
      Console.log("[TrailMcpTool] Log-based generation failed: ${e.message}")
      return TrailSaveResult(
        saved = false,
        error = "Failed to generate trail from logs: ${e.message}",
      ).toJson()
    }

    if (yamlContent.isBlank() || !yamlContent.contains("- prompts:")) {
      return TrailSaveResult(
        saved = false,
        error = "No recordable steps found in session logs. Use blaze(), verify(), or ask() first.",
      ).toJson()
    }

    // Write the YAML to disk
    return try {
      val sanitizedName = trailName.replace(" ", "-").lowercase()
      val dir = File(trailsDirectory)
      if (!dir.exists()) dir.mkdirs()

      val trailDir = File(dir, sanitizedName)
      if (!trailDir.exists()) trailDir.mkdirs()

      val fileName = if (platform != null) {
        "${platform.name.lowercase()}.trail.yaml"
      } else {
        "trail.yaml"
      }
      val filePath = File(trailDir, fileName)
      filePath.writeText(yamlContent)

      Console.log("[TrailMcpTool] Saved trail from logs to: ${filePath.absolutePath}")
      TrailSaveResult(
        saved = true,
        name = trailName,
        file = filePath.absolutePath,
        message = "Trail saved from session logs! Run anytime with trail(action=RUN, name='$trailName')",
      ).toJson()
    } catch (e: Exception) {
      TrailSaveResult(
        saved = false,
        error = "Failed to save trail file: ${e.message}",
      ).toJson()
    }
  }

  /**
   * Fallback: generates trail from in-memory [RecordedStep] objects via [TrailFileManager].
   * Used when LogsRepo is not available.
   */
  private fun handleSaveFromRecordedSteps(
    trailName: String,
    platform: TrailblazeDevicePlatform?,
  ): String {
    val steps = sessionContext?.getRecordedSteps() ?: emptyList()
    if (steps.isEmpty()) {
      return TrailSaveResult(
        saved = false,
        error = "No steps recorded yet. Use blaze(), verify(), or ask() first.",
      ).toJson()
    }

    Console.log("")
    Console.log("┌──────────────────────────────────────────────────────────────────────────────")
    Console.log("│ [trail] Saving from recorded steps: $trailName")
    Console.log("│ Steps: ${steps.size}")
    Console.log("│ Platform: ${platform?.displayName ?: "unknown"}")
    Console.log("└──────────────────────────────────────────────────────────────────────────────")

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

  private suspend fun handleList(filter: String?, page: Int): String {
    val result = trailFileManager.listTrails(filter, page)
    return TrailListResult(
      trails = result.trails.map { TrailListEntry(path = it.path, title = it.title) },
      count = result.trails.size,
      totalCount = result.totalCount,
      page = result.page,
      hasMore = result.hasMore,
      message = if (result.trails.isEmpty()) {
        if (filter != null) "No trails found matching '$filter'" else "No trails found"
      } else {
        buildString {
          append("Showing ${result.trails.size} of ${result.totalCount} trail(s)")
          if (result.hasMore) {
            append(". Use page=${result.page + 1} to see more")
          }
        }
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
  // Trail editing
  // ─────────────────────────────────────────────────────────────────────────────

  enum class TrailEditOperation {
    /** Retrieve trail contents with indexed steps */
    GET,
    /** Insert new steps at a position */
    INSERT,
    /** Replace a step at a given index */
    REPLACE,
    /** Delete steps by index */
    DELETE,
    /** Reorder a step */
    MOVE,
    /** Strip recordings from steps so they run with AI next time */
    CLEAR_RECORDING,
  }

  @LLMDescription(
    """
    Edit a saved trail's steps.

    The natural language prompt is the source of truth. Recordings are an optimization
    that can be regenerated by running the trail and re-saving.

    Operations:
    - GET: Inspect trail with indexed steps and recording status
    - INSERT: Add new steps (prompt-only for AI execution, or with recording)
    - REPLACE: Change a step's prompt (clears recording) or fix a recording
    - DELETE: Remove steps
    - MOVE: Reorder a step
    - CLEAR_RECORDING: Strip recordings so steps run with AI on next execution

    Workflow: Edit prompts → Run trail (AI handles unrecorded steps) → Save to capture recordings

    Examples:
    - trailEdit(operation=GET, name="login_flow")
    - trailEdit(operation=INSERT, name="login_flow", position=0, prompt="Clear app data and launch fresh")
    - trailEdit(operation=REPLACE, name="login_flow", index=3, prompt="Select first contact in list")
    - trailEdit(operation=DELETE, name="login_flow", index=2, count=2)
    - trailEdit(operation=MOVE, name="login_flow", index=5, position=2)
    - trailEdit(operation=CLEAR_RECORDING, name="login_flow", index=3)
    - trailEdit(operation=CLEAR_RECORDING, name="login_flow") → clears ALL recordings
    """
  )
  @Tool(McpToolProfile.TOOL_TRAIL_EDIT)
  suspend fun trailEdit(
    @LLMDescription("Edit operation: GET, INSERT, REPLACE, DELETE, MOVE, or CLEAR_RECORDING")
    operation: TrailEditOperation,
    @LLMDescription("Trail name (required)")
    name: String,
    @LLMDescription("Step index (for REPLACE, DELETE, MOVE, CLEAR_RECORDING)")
    index: Int? = null,
    @LLMDescription("Insert position or move target (0=prepend, -1=append)")
    position: Int? = null,
    @LLMDescription("Number of steps to delete/clear (default 1)")
    count: Int? = null,
    @LLMDescription("Step prompt text (for INSERT or REPLACE)")
    prompt: String? = null,
    @LLMDescription("Step type: 'step' (default) or 'verify'")
    stepType: String? = null,
  ): String {
    // Resolve trail file
    val trailFile = trailFileManager.findTrailByName(name)
      ?: return TrailEditResult(
        success = false,
        error = "Trail '$name' not found. Use trail(action=LIST) to see available trails.",
      ).toJson()

    return when (operation) {
      TrailEditOperation.GET -> handleEditGet(trailFile)
      TrailEditOperation.INSERT -> handleEditInsert(trailFile, position, prompt, stepType)
      TrailEditOperation.REPLACE -> handleEditReplace(trailFile, index, prompt, stepType)
      TrailEditOperation.DELETE -> handleEditDelete(trailFile, index, count)
      TrailEditOperation.MOVE -> handleEditMove(trailFile, index, position)
      TrailEditOperation.CLEAR_RECORDING -> handleEditClearRecording(trailFile, index, count)
    }
  }

  private fun handleEditGet(trailFile: String): String {
    val (config, steps) = trailFileManager.getEditableSteps(trailFile)
      ?: return TrailEditResult(
        success = false,
        error = "Failed to load trail for editing",
      ).toJson()

    val recorded = steps.count { it.recording != null }
    val stepInfos = steps.mapIndexed { idx, step ->
      TrailEditStepInfo(
        index = idx,
        prompt = step.prompt,
        type = step.type,
        hasRecording = step.recording != null,
        toolNames = step.recording?.tools?.map { it.name } ?: emptyList(),
      )
    }

    return TrailEditGetResult(
      success = true,
      file = trailFile,
      title = config?.title,
      steps = stepInfos,
      totalSteps = steps.size,
      recordedSteps = recorded,
      unrecordedSteps = steps.size - recorded,
    ).toJson()
  }

  private fun handleEditInsert(
    trailFile: String,
    position: Int?,
    prompt: String?,
    stepType: String?,
  ): String {
    if (prompt == null) {
      return TrailEditResult(
        success = false,
        error = "Missing prompt. Example: trailEdit(operation=INSERT, name='...', prompt='Tap the login button')",
      ).toJson()
    }

    val (config, steps) = trailFileManager.getEditableSteps(trailFile)
      ?: return TrailEditResult(success = false, error = "Failed to load trail").toJson()

    val mutableSteps = steps.toMutableList()
    val newStep = TrailFileManager.EditableStep(
      prompt = prompt,
      type = stepType ?: "step",
      recording = null, // Prompt-only: AI will handle on next run
    )

    val insertAt = when {
      position == null || position == -1 -> mutableSteps.size
      position < 0 -> return TrailEditResult(
        success = false,
        error = "Invalid position: $position. Use 0 to prepend, -1 to append.",
      ).toJson()
      position > mutableSteps.size -> return TrailEditResult(
        success = false,
        error = "Position $position out of range (trail has ${mutableSteps.size} steps)",
      ).toJson()
      else -> position
    }

    mutableSteps.add(insertAt, newStep)

    val result = trailFileManager.saveEditedSteps(trailFile, config, mutableSteps)
    return if (result.success) {
      TrailEditResult(
        success = true,
        file = trailFile,
        totalSteps = result.totalSteps,
        recordedSteps = result.recordedSteps,
        unrecordedSteps = result.unrecordedSteps,
        changes = listOf("Inserted '${prompt.take(60)}' at position $insertAt (AI-driven, no recording)"),
        message = "Step inserted. Run the trail and re-save to capture a recording for this step.",
      ).toJson()
    } else {
      TrailEditResult(success = false, error = result.error).toJson()
    }
  }

  private fun handleEditReplace(
    trailFile: String,
    index: Int?,
    prompt: String?,
    stepType: String?,
  ): String {
    if (index == null) {
      return TrailEditResult(
        success = false,
        error = "Missing index. Example: trailEdit(operation=REPLACE, name='...', index=3, prompt='New step text')",
      ).toJson()
    }
    if (prompt == null) {
      return TrailEditResult(
        success = false,
        error = "Missing prompt. Provide the new step text.",
      ).toJson()
    }

    val (config, steps) = trailFileManager.getEditableSteps(trailFile)
      ?: return TrailEditResult(success = false, error = "Failed to load trail").toJson()

    if (index < 0 || index >= steps.size) {
      return TrailEditResult(
        success = false,
        error = "Index $index out of range (trail has ${steps.size} steps, indices 0-${steps.size - 1})",
      ).toJson()
    }

    val mutableSteps = steps.toMutableList()
    val oldStep = mutableSteps[index]
    val promptChanged = oldStep.prompt != prompt

    // If the prompt changed, clear the recording (it no longer matches the intent)
    val newStep = TrailFileManager.EditableStep(
      prompt = prompt,
      type = stepType ?: oldStep.type,
      recording = if (promptChanged) null else oldStep.recording,
    )
    mutableSteps[index] = newStep

    val result = trailFileManager.saveEditedSteps(trailFile, config, mutableSteps)
    return if (result.success) {
      val changeDesc = if (promptChanged) {
        "Replaced step $index: '${oldStep.prompt.take(40)}' → '${prompt.take(40)}' (recording cleared)"
      } else {
        "Updated step $index type to ${newStep.type}"
      }
      TrailEditResult(
        success = true,
        file = trailFile,
        totalSteps = result.totalSteps,
        recordedSteps = result.recordedSteps,
        unrecordedSteps = result.unrecordedSteps,
        changes = listOf(changeDesc),
        message = if (promptChanged) {
          "Step replaced. Recording cleared — run the trail and re-save to capture a new recording."
        } else {
          "Step updated."
        },
      ).toJson()
    } else {
      TrailEditResult(success = false, error = result.error).toJson()
    }
  }

  private fun handleEditDelete(
    trailFile: String,
    index: Int?,
    count: Int?,
  ): String {
    if (index == null) {
      return TrailEditResult(
        success = false,
        error = "Missing index. Example: trailEdit(operation=DELETE, name='...', index=2)",
      ).toJson()
    }

    val (config, steps) = trailFileManager.getEditableSteps(trailFile)
      ?: return TrailEditResult(success = false, error = "Failed to load trail").toJson()

    val deleteCount = count ?: 1
    if (index < 0 || index >= steps.size) {
      return TrailEditResult(
        success = false,
        error = "Index $index out of range (trail has ${steps.size} steps, indices 0-${steps.size - 1})",
      ).toJson()
    }
    val endIndex = (index + deleteCount).coerceAtMost(steps.size)

    val mutableSteps = steps.toMutableList()
    val removed = mutableSteps.subList(index, endIndex).map { it.prompt.take(50) }
    mutableSteps.subList(index, endIndex).clear()

    val result = trailFileManager.saveEditedSteps(trailFile, config, mutableSteps)
    return if (result.success) {
      TrailEditResult(
        success = true,
        file = trailFile,
        totalSteps = result.totalSteps,
        recordedSteps = result.recordedSteps,
        unrecordedSteps = result.unrecordedSteps,
        changes = removed.mapIndexed { i, prompt ->
          "Deleted step ${index + i}: '$prompt'"
        },
        message = "Deleted ${removed.size} step(s).",
      ).toJson()
    } else {
      TrailEditResult(success = false, error = result.error).toJson()
    }
  }

  private fun handleEditMove(
    trailFile: String,
    index: Int?,
    position: Int?,
  ): String {
    if (index == null || position == null) {
      return TrailEditResult(
        success = false,
        error = "Missing index or position. Example: trailEdit(operation=MOVE, name='...', index=5, position=2)",
      ).toJson()
    }

    val (config, steps) = trailFileManager.getEditableSteps(trailFile)
      ?: return TrailEditResult(success = false, error = "Failed to load trail").toJson()

    if (index < 0 || index >= steps.size) {
      return TrailEditResult(
        success = false,
        error = "Index $index out of range (trail has ${steps.size} steps)",
      ).toJson()
    }
    if (position < 0 || position >= steps.size) {
      return TrailEditResult(
        success = false,
        error = "Position $position out of range (trail has ${steps.size} steps)",
      ).toJson()
    }
    if (index == position) {
      return TrailEditResult(
        success = true,
        file = trailFile,
        totalSteps = steps.size,
        changes = emptyList(),
        message = "Step is already at position $position.",
      ).toJson()
    }

    val mutableSteps = steps.toMutableList()
    val step = mutableSteps.removeAt(index)
    mutableSteps.add(position, step)

    val result = trailFileManager.saveEditedSteps(trailFile, config, mutableSteps)
    return if (result.success) {
      TrailEditResult(
        success = true,
        file = trailFile,
        totalSteps = result.totalSteps,
        recordedSteps = result.recordedSteps,
        unrecordedSteps = result.unrecordedSteps,
        changes = listOf("Moved step '${ step.prompt.take(50) }' from index $index to $position"),
        message = "Step moved.",
      ).toJson()
    } else {
      TrailEditResult(success = false, error = result.error).toJson()
    }
  }

  private fun handleEditClearRecording(
    trailFile: String,
    index: Int?,
    count: Int?,
  ): String {
    val (config, steps) = trailFileManager.getEditableSteps(trailFile)
      ?: return TrailEditResult(success = false, error = "Failed to load trail").toJson()

    val mutableSteps = steps.toMutableList()
    val changes = mutableListOf<String>()

    if (index == null) {
      // Clear ALL recordings
      val clearedCount = mutableSteps.count { it.recording != null }
      for (i in mutableSteps.indices) {
        if (mutableSteps[i].recording != null) {
          mutableSteps[i] = mutableSteps[i].copy(recording = null)
        }
      }
      changes.add("Cleared recordings from all $clearedCount steps")
    } else {
      if (index < 0 || index >= steps.size) {
        return TrailEditResult(
          success = false,
          error = "Index $index out of range (trail has ${steps.size} steps)",
        ).toJson()
      }
      val clearCount = (count ?: 1).coerceAtMost(steps.size - index)
      for (i in index until index + clearCount) {
        if (mutableSteps[i].recording != null) {
          mutableSteps[i] = mutableSteps[i].copy(recording = null)
          changes.add("Cleared recording from step $i: '${mutableSteps[i].prompt.take(50)}'")
        }
      }
      if (changes.isEmpty()) {
        changes.add("No recordings to clear in the specified range")
      }
    }

    val result = trailFileManager.saveEditedSteps(trailFile, config, mutableSteps)
    return if (result.success) {
      TrailEditResult(
        success = true,
        file = trailFile,
        totalSteps = result.totalSteps,
        recordedSteps = result.recordedSteps,
        unrecordedSteps = result.unrecordedSteps,
        changes = changes,
        message = "Recordings cleared. Run the trail and re-save to capture fresh recordings.",
      ).toJson()
    } else {
      TrailEditResult(success = false, error = result.error).toJson()
    }
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
data class TrailListEntry(
  val path: String,
  val title: String? = null,
)

@Serializable
data class TrailListResult(
  val trails: List<TrailListEntry>? = null,
  val count: Int? = null,
  val totalCount: Int? = null,
  val page: Int? = null,
  val hasMore: Boolean? = null,
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

// ─────────────────────────────────────────────────────────────────────────────
// Trail edit result types
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class TrailEditResult(
  val success: Boolean,
  val file: String? = null,
  val totalSteps: Int? = null,
  val recordedSteps: Int? = null,
  val unrecordedSteps: Int? = null,
  val changes: List<String>? = null,
  val message: String? = null,
  val error: String? = null,
) {
  fun toJson(): String = TrailblazeJsonInstance.encodeToString(serializer(), this)
}

@Serializable
data class TrailEditStepInfo(
  val index: Int,
  val prompt: String,
  val type: String,
  val hasRecording: Boolean,
  val toolNames: List<String> = emptyList(),
)

@Serializable
data class TrailEditGetResult(
  val success: Boolean,
  val file: String? = null,
  val title: String? = null,
  val steps: List<TrailEditStepInfo>? = null,
  val totalSteps: Int? = null,
  val recordedSteps: Int? = null,
  val unrecordedSteps: Int? = null,
  val error: String? = null,
) {
  fun toJson(): String = TrailblazeJsonInstance.encodeToString(serializer(), this)
}
