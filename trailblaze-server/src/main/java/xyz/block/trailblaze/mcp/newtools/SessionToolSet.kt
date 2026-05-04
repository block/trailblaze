package xyz.block.trailblaze.mcp.newtools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.capture.logcat.LogcatParser
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.getSessionStartedInfo
import xyz.block.trailblaze.mcp.McpToolProfile
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.report.utils.TrailblazeYamlSessionRecording.generateRecordedYaml
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.TrailConfig
import java.io.File

/**
 * Session lifecycle management tool.
 *
 * Handles:
 * - START: Create a session with automatic video + device log capture
 * - STOP: Stop capture, optionally save as trail, end session
 * - SAVE: Save current session as a reusable trail YAML
 * - INFO: Get info about the current or a specific session
 * - LIST: List recent sessions
 * - ARTIFACTS: List artifacts in a session
 */
@Suppress("unused")
class SessionToolSet(
  private val sessionContext: TrailblazeMcpSessionContext?,
  private val mcpBridge: TrailblazeMcpBridge,
  private val logsRepo: LogsRepo? = null,
  private val sessionIdProvider: (() -> SessionId?)? = null,
  private val trailsDirectory: String = "./trails",
  /**
   * Starts capture (video + device logs). Returns a description of what's being captured.
   * Parameters: sessionId, noVideo, noLogs.
   * The implementation should store a stop callback on [TrailblazeMcpSessionContext.stopCaptureCallback].
   */
  private val startCaptureProvider: ((SessionId, Boolean, Boolean) -> String)? = null,
) : ToolSet {

  enum class SessionAction {
    /** Start a new session with automatic capture */
    START,
    /** Stop the current session and finalize captures */
    STOP,
    /** Save the current session as a reusable trail */
    SAVE,
    /** Get info about the current or a specific session */
    INFO,
    /** List recent sessions */
    LIST,
    /** List artifacts in a session */
    ARTIFACTS,
    /** Delete a session's logs and artifacts */
    DELETE,
    /** Generate and return the recording YAML for a session */
    RECORDING,
    /** Read device logs (logcat / iOS log stream) from a session */
    DEVICE_LOGS,
  }

  @LLMDescription(
    """
    Manage sessions — start, stop, save, and inspect.

    session(action=START) → start session with video + log capture
    session(action=START, title="Login flow") → named session
    session(action=STOP) → stop capture, print artifacts
    session(action=STOP, save=true) → stop + save as trail
    session(action=SAVE, title="Login flow") → save session as trail YAML
    session(action=INFO) → current session info
    session(action=INFO, id="abc123") → specific session info
    session(action=LIST) → recent sessions
    session(action=ARTIFACTS, id="abc123") → list artifacts
    session(action=DELETE, id="abc123") → delete a session
    session(action=RECORDING) → return recording YAML for current session
    session(action=RECORDING, id="abc123") → return recording YAML for a specific session
    session(action=SAVE, id="abc123", title="Login") → save a specific session as trail
    session(action=DEVICE_LOGS) → read device logs from current session
    session(action=DEVICE_LOGS, id="abc123", limit=200) → last 200 lines
    session(action=DEVICE_LOGS, id="abc123", filter="Exception") → search device logs
    session(action=DEVICE_LOGS, startMs=1772846521000, endMs=1772846525000) → logs in time window

    Video is captured by default when a session starts; device log capture (Android
    logcat and iOS Simulator system logs) is off by default and is toggled per-platform
    in the desktop app's settings.
    """
  )
  @Tool(McpToolProfile.TOOL_SESSION)
  suspend fun session(
    @LLMDescription("Action: START, STOP, SAVE, INFO, LIST, ARTIFACTS, RECORDING, DELETE, or DEVICE_LOGS")
    action: SessionAction,
    @LLMDescription("Session title (for START or SAVE)")
    title: String? = null,
    @LLMDescription("Session ID for INFO, ARTIFACTS, SAVE, RECORDING, DEVICE_LOGS (defaults to current session)")
    id: String? = null,
    @LLMDescription("Disable video capture (default: false)")
    noVideo: Boolean = false,
    @LLMDescription("Disable device log capture (default: false)")
    noLogs: Boolean = false,
    @LLMDescription("Save as trail when stopping (default: false)")
    save: Boolean = false,
    @LLMDescription("Max results for LIST, or max lines for DEVICE_LOGS (default: 20 for LIST, 100 for DEVICE_LOGS)")
    limit: Int? = null,
    @LLMDescription("Filter pattern for DEVICE_LOGS — only return lines containing this string (case-insensitive)")
    filter: String? = null,
    @LLMDescription("For DEVICE_LOGS: start of time window (epoch millis). Use with endMs to get logs for a specific event.")
    startMs: Long? = null,
    @LLMDescription("For DEVICE_LOGS: end of time window (epoch millis). Use with startMs to get logs for a specific event.")
    endMs: Long? = null,
  ): String {
    return when (action) {
      SessionAction.START -> handleStart(title, noVideo, noLogs)
      SessionAction.STOP -> handleStop(save, title)
      SessionAction.SAVE -> handleSave(title, id)
      SessionAction.INFO -> handleInfo(id)
      SessionAction.LIST -> handleList(limit ?: 20)
      SessionAction.ARTIFACTS -> handleArtifacts(id)
      SessionAction.DELETE -> handleDelete(id)
      SessionAction.RECORDING -> handleRecording(id)
      SessionAction.DEVICE_LOGS -> handleLogcat(id, limit ?: 100, filter, startMs, endMs)
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // START / STOP
  // ─────────────────────────────────────────────────────────────────────────────

  private suspend fun handleStart(
    title: String?,
    noVideo: Boolean,
    noLogs: Boolean,
  ): String {
    // Create the Trailblaze session
    val sessionId = try {
      mcpBridge.ensureSessionAndGetId(title)
    } catch (e: Exception) {
      return SessionResult(error = "Failed to create session: ${e.message}").toJson()
    }

    if (sessionId == null) {
      return SessionResult(
        error =
          "No active device. Use device(action=ANDROID), device(action=IOS), or device(action=WEB) first.",
      )
        .toJson()
    }

    sessionContext?.sessionTitle = title

    // Start recording
    if (title != null) {
      sessionContext?.startTrailRecording(title)
    } else {
      sessionContext?.startImplicitRecording()
    }

    // Start capture (video + device logs by default)
    val captureStatus = startCapture(sessionId, noVideo, noLogs)

    Console.log("")
    Console.log("┌──────────────────────────────────────────────────────────────────────────────")
    Console.log("│ [session] Started: ${title ?: "(unnamed)"}")
    Console.log("│ Session ID: ${sessionId.value}")
    Console.log("│ Capture: $captureStatus")
    Console.log("└──────────────────────────────────────────────────────────────────────────────")

    return SessionResult(
      sessionId = sessionId.value,
      title = title,
      status = "started",
      message = buildString {
        append("Session started")
        if (title != null) append(": $title")
        append(". $captureStatus")
      },
    ).toJson()
  }

  private fun startCapture(
    sessionId: SessionId,
    noVideo: Boolean,
    noLogs: Boolean,
  ): String {
    val provider = startCaptureProvider
      ?: return "Capture not available (no capture provider configured)"
    return try {
      provider(sessionId, noVideo, noLogs)
    } catch (e: Exception) {
      "Capture failed to start: ${e.message}"
    }
  }

  private suspend fun handleStop(save: Boolean, title: String?): String {
    // Capture sessionId before clearing state — endSession/clear will make it unavailable
    val sessionId = sessionIdProvider?.invoke()

    // Save first if requested
    val saveResult = if (save) {
      handleSave(title)
    } else null

    // Check if save actually succeeded by parsing the JSON result
    val saveSucceeded = if (saveResult != null) {
      try {
        val json = TrailblazeJsonInstance.parseToJsonElement(saveResult).jsonObject
        json["error"] == null || json["error"]?.jsonPrimitive?.content.isNullOrBlank()
      } catch (_: Exception) {
        false
      }
    } else false

    // Stop capture
    val artifacts = stopCapture()

    // End the session
    try {
      mcpBridge.endSession()
    } catch (e: Exception) {
      Console.error("[session] Failed to end session: ${e.message}")
    }
    sessionContext?.clearRecording()
    sessionContext?.clearAssociatedDevice()
    sessionContext?.sessionTitle = null
    sessionContext?.stopCaptureCallback = null

    Console.log("")
    Console.log("┌──────────────────────────────────────────────────────────────────────────────")
    Console.log("│ [session] Stopped")
    Console.log("│ Artifacts: ${artifacts.size}")
    Console.log("└──────────────────────────────────────────────────────────────────────────────")

    return SessionResult(
      sessionId = sessionId?.value,
      status = "stopped",
      artifacts = artifacts.map { ArtifactEntry(it.name, it.type, it.sizeBytes) },
      message = buildString {
        append("Session stopped.")
        if (artifacts.isNotEmpty()) {
          append(
            " Artifacts: ${artifacts.joinToString { "${it.name} (${it.sizeBytes / 1024}KB)" }}"
          )
        }
        if (save && saveSucceeded) {
          append(" Trail saved.")
        } else if (save && !saveSucceeded) {
          append(" Trail save failed.")
        }
      },
      saveResult = saveResult,
    ).toJson()
  }

  private fun stopCapture(): List<TrailblazeMcpSessionContext.CaptureArtifactInfo> {
    val callback = sessionContext?.stopCaptureCallback ?: return emptyList()
    return try {
      callback()
    } catch (e: Exception) {
      Console.error("[session] Failed to stop capture: ${e.message}")
      emptyList()
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // SAVE
  // ─────────────────────────────────────────────────────────────────────────────

  private suspend fun handleSave(title: String?, id: String? = null): String {
    // Resolve target session — explicit id, or current session
    val targetSessionId = if (id != null) {
      resolveSessionId(id)
        ?: return SessionResult(error = "Session not found: $id").toJson()
    } else {
      sessionIdProvider?.invoke()
    }

    val trailName = title
      ?: sessionContext?.sessionTitle
      ?: sessionContext?.getCurrentTrailName()
    if (trailName == null) {
      return SessionResult(
        error = "No title for trail. Use session(action=SAVE, title='your title')",
      ).toJson()
    }

    if (title != null) {
      sessionContext?.setTrailName(title)
    }

    val platform = sessionContext?.associatedDeviceId?.let { deviceId ->
      mcpBridge.getAvailableDevices().find { it.trailblazeDeviceId == deviceId }?.platform
    }

    // Try log-based trail generation first (preferred)
    if (logsRepo != null && targetSessionId != null) {
      return saveFromLogs(trailName, targetSessionId, platform)
    }

    // Fallback: in-memory RecordedStep path (only for current session)
    return saveFromRecordedSteps(trailName, platform)
  }

  private fun saveFromLogs(
    trailName: String,
    sessionId: SessionId,
    platform: TrailblazeDevicePlatform?,
  ): String {
    val logs = logsRepo!!.getLogsForSession(sessionId)
    if (logs.isEmpty()) {
      return SessionResult(
        error = "No logs found for session. Use blaze() or ask() first.",
      ).toJson()
    }

    val startedStatus = logs.getSessionStartedInfo()
    val sessionTrailConfig = startedStatus?.let { started ->
      val originalConfig = started.trailConfig
      TrailConfig(
        id = originalConfig?.id,
        title = trailName,
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
    } ?: TrailConfig(title = trailName, platform = platform?.name?.lowercase())

    val yamlContent = try {
      logs.generateRecordedYaml(sessionTrailConfig = sessionTrailConfig)
    } catch (e: Exception) {
      return SessionResult(error = "Failed to generate trail: ${e.message}").toJson()
    }

    if (yamlContent.isBlank() || !yamlContent.contains("- prompts:")) {
      return SessionResult(
        error = "No recordable steps found. Use blaze() or ask() first.",
      ).toJson()
    }

    return writeTrailFile(trailName, yamlContent, platform)
  }

  private fun saveFromRecordedSteps(
    trailName: String,
    platform: TrailblazeDevicePlatform?,
  ): String {
    val steps = sessionContext?.getRecordedSteps() ?: emptyList()
    if (steps.isEmpty()) {
      return SessionResult(
        error = "No steps recorded yet. Use blaze() or ask() first.",
      ).toJson()
    }

    val trailFileManager = TrailFileManager(trailsDirectory)
    val saveResult = trailFileManager.saveTrail(
      name = trailName,
      steps = steps,
      platform = platform,
    )

    return if (saveResult.success) {
      SessionResult(
        status = "saved",
        file = saveResult.filePath,
        message = "Trail saved with ${steps.size} steps: ${saveResult.filePath}",
      ).toJson()
    } else {
      SessionResult(error = saveResult.error ?: "Unknown error saving trail").toJson()
    }
  }

  private fun handleRecording(id: String?): String {
    val repo = logsRepo
      ?: return SessionResult(error = "Recording not available (no logs configured)").toJson()

    val sessionId = if (id != null) {
      resolveSessionId(id)
        ?: return SessionResult(error = "Session not found: $id").toJson()
    } else {
      sessionIdProvider?.invoke()
        ?: return SessionResult(error = "No active session. Specify id parameter.").toJson()
    }

    val logs = repo.getLogsForSession(sessionId)
    if (logs.isEmpty()) {
      return SessionResult(error = "No logs found for session ${sessionId.value}").toJson()
    }

    val startedStatus = logs.getSessionStartedInfo()
    val sessionTrailConfig = startedStatus?.let { started ->
      val originalConfig = started.trailConfig
      TrailConfig(
        id = originalConfig?.id,
        title = originalConfig?.title ?: sessionContext?.sessionTitle,
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
    }

    val yamlContent = try {
      logs.generateRecordedYaml(sessionTrailConfig = sessionTrailConfig)
    } catch (e: Exception) {
      return SessionResult(error = "Failed to generate recording: ${e.message}").toJson()
    }

    if (yamlContent.isBlank()) {
      return SessionResult(
        error = "No recordable steps found in session ${sessionId.value}",
      ).toJson()
    }

    return SessionResult(
      sessionId = sessionId.value,
      status = "recording_generated",
      yaml = yamlContent,
      message = "Recording generated for session ${sessionId.value}",
    ).toJson()
  }

  private fun writeTrailFile(
    trailName: String,
    yamlContent: String,
    platform: TrailblazeDevicePlatform?,
  ): String {
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

      Console.log("[session] Trail saved to: ${filePath.absolutePath}")
      SessionResult(
        status = "saved",
        file = filePath.absolutePath,
        message = "Trail saved: ${filePath.absolutePath}",
      ).toJson()
    } catch (e: Exception) {
      SessionResult(error = "Failed to write trail file: ${e.message}").toJson()
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // INFO / LIST / ARTIFACTS
  // ─────────────────────────────────────────────────────────────────────────────

  private fun handleInfo(id: String?): String {
    if (id != null && logsRepo == null) {
      return SessionResult(error = "Session browsing not available (no logs configured)").toJson()
    }
    val sessionId = if (id != null) {
      resolveSessionId(id)
        ?: return SessionResult(error = "Session not found: $id").toJson()
    } else {
      sessionIdProvider?.invoke()
        ?: return SessionResult(error = "No active session").toJson()
    }

    val info = logsRepo?.getSessionInfoDirect(sessionId)
    val sessionDir = logsRepo?.getSessionDir(sessionId)
    val statusStr = info?.latestStatus?.let { formatStatus(it) } ?: "unknown"
    val displayTitle = info?.displayName ?: sessionContext?.sessionTitle

    // Include recorded steps for the current session
    val currentSessionId = sessionIdProvider?.invoke()
    val stepEntries = if (sessionId == currentSessionId) {
      sessionContext?.getRecordedSteps()?.mapIndexed { idx, step ->
        SessionStepEntry(index = idx + 1, type = step.type.name, input = step.input)
      }
    } else null

    return SessionResult(
      sessionId = sessionId.value,
      title = displayTitle,
      status = statusStr,
      device = info?.trailblazeDeviceId?.instanceId
        ?: sessionContext?.associatedDeviceId?.instanceId,
      platform = info?.trailblazeDeviceId?.trailblazeDevicePlatform?.name
        ?: sessionContext?.associatedDeviceId?.trailblazeDevicePlatform?.name,
      path = sessionDir?.absolutePath,
      steps = stepEntries,
      message = buildString {
        append("Session: ${sessionId.value}")
        if (displayTitle != null) append(" ($displayTitle)")
        append(" — $statusStr")
      },
    ).toJson()
  }

  private fun handleList(limit: Int): String {
    val repo = logsRepo
      ?: return SessionResult(error = "Session listing not available").toJson()

    val sessions = repo.sessionInfoFlow.value
    if (sessions.isEmpty()) {
      return SessionResult(message = "No sessions found.").toJson()
    }

    val entries = sessions
      .sortedByDescending { it.timestamp }
      .take(limit)
      .map { info ->
        SessionListEntry(
          id = info.sessionId.value,
          title = info.displayName,
          status = formatStatus(info.latestStatus),
          startedAt = info.timestamp.toString(),
          durationMs = info.durationMs,
          device = info.trailblazeDeviceId?.instanceId,
          platform = info.trailblazeDeviceId?.trailblazeDevicePlatform?.name,
        )
      }

    return SessionListResult(
      sessions = entries,
      total = entries.size,
    ).toJson()
  }

  private fun handleArtifacts(id: String?): String {
    if (logsRepo == null) {
      return SessionResult(error = "Session browsing not available (no logs configured)").toJson()
    }
    val sessionId = if (id != null) {
      resolveSessionId(id)
        ?: return SessionResult(error = "Session not found: $id").toJson()
    } else {
      sessionIdProvider?.invoke()
        ?: return SessionResult(error = "No active session").toJson()
    }

    val sessionDir = logsRepo?.getSessionDir(sessionId)
    if (sessionDir == null || !sessionDir.exists()) {
      return SessionResult(error = "Session directory not found").toJson()
    }

    val artifactFiles = sessionDir.listFiles()?.filter { it.isFile }?.map { file ->
      ArtifactEntry(
        name = file.name,
        type = categorizeArtifact(file.name),
        sizeBytes = file.length(),
      )
    } ?: emptyList()

    return SessionResult(
      sessionId = sessionId.value,
      path = sessionDir.absolutePath,
      artifacts = artifactFiles,
      message = "${artifactFiles.size} artifacts in ${sessionDir.absolutePath}",
    ).toJson()
  }

  private fun handleDelete(id: String?): String {
    val repo = logsRepo
      ?: return SessionResult(error = "Session deletion not available (no logs configured)").toJson()

    if (id == null) {
      return SessionResult(error = "Session ID required. Use session(action=DELETE, id=\"...\")").toJson()
    }

    val sessionId = resolveSessionId(id)
      ?: return SessionResult(error = "Session not found: $id").toJson()

    repo.deleteLogsForSession(sessionId)
    return SessionResult(
      sessionId = sessionId.value,
      status = "deleted",
      message = "Session deleted: ${sessionId.value}",
    ).toJson()
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // LOGCAT
  // ─────────────────────────────────────────────────────────────────────────────

  private fun handleLogcat(
    id: String?,
    maxLines: Int,
    filter: String?,
    startMs: Long? = null,
    endMs: Long? = null,
  ): String {
    if (logsRepo == null) {
      return SessionResult(error = "Session browsing not available (no logs configured)").toJson()
    }
    val sessionId = if (id != null) {
      resolveSessionId(id)
        ?: return SessionResult(error = "Session not found: $id").toJson()
    } else {
      sessionIdProvider?.invoke()
        ?: return SessionResult(error = "No active session").toJson()
    }

    val sessionDir = logsRepo?.getSessionDir(sessionId)
    if (sessionDir == null || !sessionDir.exists()) {
      return SessionResult(error = "Session directory not found").toJson()
    }

    val logcatFile = LogcatParser.findDeviceLogFile(sessionDir)

    if (logcatFile == null || !logcatFile.exists()) {
      return SessionResult(
        sessionId = sessionId.value,
        error = "No device logs found. Logcat capture may not have been enabled for this session.",
      ).toJson()
    }

    // Validate time-range parameters: both must be provided together
    if ((startMs != null) != (endMs != null)) {
      return SessionResult(
        sessionId = sessionId.value,
        error = "Both startMs and endMs must be provided together for time-range filtering.",
      ).toJson()
    }
    if (startMs != null && endMs != null && startMs > endMs) {
      return SessionResult(
        sessionId = sessionId.value,
        error = "startMs ($startMs) must be <= endMs ($endMs).",
      ).toJson()
    }

    // If time range is specified, use LogcatParser for timestamp-based slicing
    val baseLines: List<String> = if (startMs != null && endMs != null) {
      LogcatParser.sliceByTimeRange(logcatFile, startMs, endMs)
        .map { it.text }
    } else {
      logcatFile.readLines()
    }

    val filteredLines = if (filter != null) {
      baseLines.filter { it.contains(filter, ignoreCase = true) }
    } else {
      baseLines
    }

    val totalLines = baseLines.size
    val matchedLines = filteredLines.size
    val safeMaxLines = maxLines.coerceAtLeast(0)
    val outputLines = filteredLines.takeLast(safeMaxLines)
    val truncated = matchedLines > safeMaxLines

    val content = outputLines.joinToString("\n")

    return DeviceLogsResult(
      sessionId = sessionId.value,
      file = logcatFile.name,
      totalLines = totalLines,
      matchedLines = matchedLines,
      returnedLines = outputLines.size,
      truncated = truncated,
      filter = filter,
      timeRange = if (startMs != null && endMs != null) "${startMs}-${endMs}" else null,
      content = content,
    ).toJson()
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Helpers
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * Resolves a session ID, supporting prefix matching.
   */
  private fun resolveSessionId(idOrPrefix: String): SessionId? {
    val repo = logsRepo ?: return SessionId(idOrPrefix)
    val allIds = repo.getSessionIds()
    // Exact match first
    allIds.find { it.value == idOrPrefix }?.let { return it }
    // Prefix match
    val matches = allIds.filter { it.value.startsWith(idOrPrefix) }
    return when (matches.size) {
      1 -> matches.first()
      0 -> null
      else -> matches.first() // Most recent (already sorted)
    }
  }

  private fun formatStatus(status: SessionStatus): String {
    val durationSuffix = if (status is SessionStatus.Ended) {
      val seconds = status.durationMs / 1000.0
      if (seconds < 60) " (%.1fs)".format(seconds)
      else " (%dm %ds)".format((seconds / 60).toLong(), (seconds % 60).toLong())
    } else ""
    return when (status) {
      is SessionStatus.Unknown -> "Unknown"
      is SessionStatus.Started -> "In Progress"
      is SessionStatus.Ended.Succeeded -> "Succeeded$durationSuffix"
      is SessionStatus.Ended.Failed -> "Failed$durationSuffix"
      is SessionStatus.Ended.Cancelled -> "Cancelled$durationSuffix"
      is SessionStatus.Ended.TimeoutReached -> "Timeout$durationSuffix"
      is SessionStatus.Ended.SucceededWithSelfHeal -> "Succeeded (with self-heal)$durationSuffix"
      is SessionStatus.Ended.FailedWithSelfHeal -> "Failed (with self-heal)$durationSuffix"
      is SessionStatus.Ended.MaxCallsLimitReached -> "Max calls reached$durationSuffix"
    }
  }

  private fun categorizeArtifact(fileName: String): String {
    return when {
      fileName.endsWith(".mp4") || fileName.endsWith(".webm") -> "video"
      LogcatParser.isDeviceLogFile(fileName) -> "device_log"
      fileName.endsWith(".trail.yaml") -> "trail"
      fileName.endsWith(".json") -> "metadata"
      fileName.endsWith(".png") || fileName.endsWith(".jpg") || fileName.endsWith(".webp") -> "screenshot"
      else -> "other"
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Result types
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class SessionResult(
  val sessionId: String? = null,
  val title: String? = null,
  val status: String? = null,
  val device: String? = null,
  val platform: String? = null,
  val path: String? = null,
  val file: String? = null,
  val message: String? = null,
  val error: String? = null,
  val yaml: String? = null,
  val artifacts: List<ArtifactEntry>? = null,
  val saveResult: String? = null,
  val steps: List<SessionStepEntry>? = null,
) {
  fun toJson(): String = TrailblazeJsonInstance.encodeToString(serializer(), this)
}

@Serializable
data class SessionStepEntry(
  val index: Int,
  val type: String,
  val input: String,
)

@Serializable
data class ArtifactEntry(
  val name: String,
  val type: String,
  val sizeBytes: Long,
)

@Serializable
data class SessionListEntry(
  val id: String,
  val title: String? = null,
  val status: String,
  val startedAt: String? = null,
  val durationMs: Long? = null,
  val device: String? = null,
  val platform: String? = null,
)

@Serializable
data class SessionListResult(
  val sessions: List<SessionListEntry>,
  val total: Int,
) {
  fun toJson(): String = TrailblazeJsonInstance.encodeToString(serializer(), this)
}

@Serializable
data class DeviceLogsResult(
  val sessionId: String,
  val file: String,
  val totalLines: Int,
  val matchedLines: Int,
  val returnedLines: Int,
  val truncated: Boolean,
  val filter: String? = null,
  val timeRange: String? = null,
  val content: String,
) {
  fun toJson(): String = TrailblazeJsonInstance.encodeToString(serializer(), this)
}
