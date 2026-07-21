package xyz.block.trailblaze.ondevice.rpc.proto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import okio.ByteString
import okio.ByteString.Companion.toByteString
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.ScreenshotScalingConfig as ModelScreenshotScalingConfig
import xyz.block.trailblaze.api.TrailblazeImageFormat
import xyz.block.trailblaze.agent.ExecutionState
import xyz.block.trailblaze.agent.ExecutionStatus as ModelExecutionStatus
import xyz.block.trailblaze.agent.TrailblazeProgressEvent as ModelProgressEvent
import xyz.block.trailblaze.devices.TrailblazeDeviceId as ModelDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.ImageTokenFormula
import xyz.block.trailblaze.llm.RunYamlRequest as ModelRunYamlRequest
import xyz.block.trailblaze.llm.RunYamlResponse as ModelRunYamlResponse
import xyz.block.trailblaze.llm.TrailblazeLlmModel as ModelLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider as ModelLlmProvider
import xyz.block.trailblaze.llm.TrailblazeReferrer as ModelReferrer
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateRequest as ModelGetScreenStateRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse as ModelGetScreenStateResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.DrainSessionRequest as ModelDrainSessionRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.DrainSessionResponse as ModelDrainSessionResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetExecutionStatusRequest as ModelGetExecutionStatusRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetExecutionStatusResponse as ModelGetExecutionStatusResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.ListActiveSessionsRequest as ModelListActiveSessionsRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.ListActiveSessionsResponse as ModelListActiveSessionsResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.mcp.android.ondevice.rpc.SubscribeToProgressRequest as ModelSubscribeToProgressRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.SubscribeToProgressResponse as ModelSubscribeToProgressResponse
import xyz.block.trailblaze.model.NodeSelectorMode
import xyz.block.trailblaze.model.TrailblazeConfig as ModelTrailblazeConfig
import xyz.block.trailblaze.api.TrailblazeNode as ModelTrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode as ModelViewHierarchyNode

/** Maps Trailblaze's existing domain models to the Wire-generated transport messages. */
object OnDeviceRpcProtoCodec {

  fun encode(envelope: RpcRequestEnvelope): ByteArray =
    RpcRequestEnvelope.ADAPTER.encode(envelope)

  fun decodeRequest(bytes: ByteArray): RpcRequestEnvelope =
    RpcRequestEnvelope.ADAPTER.decode(bytes)

  fun encode(envelope: RpcResponseEnvelope): ByteArray =
    RpcResponseEnvelope.ADAPTER.encode(envelope)

  fun decodeResponse(bytes: ByteArray): RpcResponseEnvelope =
    RpcResponseEnvelope.ADAPTER.decode(bytes)

  fun encode(envelope: LogUploadEnvelope): ByteArray =
    LogUploadEnvelope.ADAPTER.encode(envelope)

  fun decodeLogUpload(bytes: ByteArray): LogUploadEnvelope =
    LogUploadEnvelope.ADAPTER.decode(bytes)

  fun encode(ack: LogUploadAck): ByteArray = LogUploadAck.ADAPTER.encode(ack)

  fun decodeLogUploadAck(bytes: ByteArray): LogUploadAck = LogUploadAck.ADAPTER.decode(bytes)

  fun ModelGetScreenStateRequest.toProto(): GetScreenStateRequest =
    GetScreenStateRequest(
      include_screenshot = includeScreenshot,
      screenshot_max_dimension_1 = screenshotMaxDimension1,
      screenshot_max_dimension_2 = screenshotMaxDimension2,
      screenshot_image_format = screenshotImageFormat.toProto(),
      screenshot_compression_quality = screenshotCompressionQuality,
      include_annotated_screenshot = includeAnnotatedScreenshot,
      include_all_elements = includeAllElements,
      require_android_accessibility_service = requireAndroidAccessibilityService,
      include_tree = includeTree,
    )

  fun GetScreenStateRequest.toModel(): ModelGetScreenStateRequest =
    ModelGetScreenStateRequest(
      includeScreenshot = include_screenshot,
      screenshotMaxDimension1 = screenshot_max_dimension_1,
      screenshotMaxDimension2 = screenshot_max_dimension_2,
      screenshotImageFormat = screenshot_image_format.toModel(),
      screenshotCompressionQuality = screenshot_compression_quality,
      includeAnnotatedScreenshot = include_annotated_screenshot,
      includeAllElements = include_all_elements,
      requireAndroidAccessibilityService = require_android_accessibility_service,
      includeTree = include_tree,
    )

  fun ModelGetScreenStateResponse.toProto(): GetScreenStateResponse =
    GetScreenStateResponse(
      view_hierarchy = viewHierarchy.toProto(),
      screenshot = screenshotBytes.toByteStringOrEmpty(),
      annotated_screenshot = annotatedScreenshotBytes.toByteStringOrEmpty(),
      device_width = deviceWidth,
      device_height = deviceHeight,
      trailblaze_node_tree = trailblazeNodeTree?.toProto(),
      driver_migration_tree_node = driverMigrationTreeNode?.toProto(),
      page_context_summary = pageContextSummary,
      device_classifiers = deviceClassifiers.orEmpty(),
      captured_at_device_ms = capturedAtDeviceMs,
    )

  fun GetScreenStateResponse.toModel(): ModelGetScreenStateResponse =
    ModelGetScreenStateResponse(
      viewHierarchy = view_hierarchy?.toModel()
        ?: error("Binary screen-state response omitted view_hierarchy"),
      screenshotBase64 = null,
      annotatedScreenshotBase64 = null,
      deviceWidth = device_width,
      deviceHeight = device_height,
      trailblazeNodeTree = trailblaze_node_tree?.toModel(),
      driverMigrationTreeNode = driver_migration_tree_node?.toModel(),
      pageContextSummary = page_context_summary,
      deviceClassifiers = device_classifiers.takeIf { it.isNotEmpty() },
      capturedAtDeviceMs = captured_at_device_ms,
    ).apply {
      screenshotBytes = screenshot.toByteArrayOrNull()
      annotatedScreenshotBytes = annotated_screenshot.toByteArrayOrNull()
    }

  fun ModelRunYamlRequest.toProto(): RunYamlRequest =
    RunYamlRequest(
      test_name = testName,
      yaml = yaml,
      trail_file_path = trailFilePath,
      target_app_name = targetAppName,
      use_recorded_steps = useRecordedSteps,
      device_id = trailblazeDeviceId.toProto(),
      llm_model = trailblazeLlmModel.toProto(),
      config = config.toProto(),
      referrer = referrer.toProto(),
      trace_id = traceId?.traceId,
      driver_type = driverType?.name,
      agent_implementation = agentImplementation.name,
      await_completion = awaitCompletion,
      memory_snapshot = memorySnapshot,
      max_llm_calls = maxLlmCalls,
      initial_memory_seeds = initialMemorySeeds,
      initial_memory_sensitive_seeds = initialMemorySensitiveSeeds,
      sensitive_memory_keys = sensitiveMemoryKeys,
      initial_args = initialArgs,
      args_snapshot = argsSnapshot,
      sensitive_arg_names = sensitiveArgNames,
    )

  fun RunYamlRequest.toModel(): ModelRunYamlRequest =
    ModelRunYamlRequest(
      testName = test_name,
      yaml = yaml,
      trailFilePath = trail_file_path,
      targetAppName = target_app_name,
      useRecordedSteps = use_recorded_steps,
      trailblazeDeviceId = device_id?.toModel()
        ?: error("Binary RunYamlRequest omitted device_id"),
      trailblazeLlmModel = llm_model?.toModel()
        ?: error("Binary RunYamlRequest omitted llm_model"),
      config = config?.toModel() ?: error("Binary RunYamlRequest omitted config"),
      referrer = referrer?.toModel() ?: error("Binary RunYamlRequest omitted referrer"),
      traceId = trace_id?.toTraceId(),
      driverType = driver_type?.let(TrailblazeDriverType::valueOf),
      agentImplementation = AgentImplementation.valueOf(agent_implementation),
      awaitCompletion = await_completion,
      memorySnapshot = memory_snapshot,
      maxLlmCalls = max_llm_calls,
      initialMemorySeeds = initial_memory_seeds,
      initialMemorySensitiveSeeds = initial_memory_sensitive_seeds,
      sensitiveMemoryKeys = sensitive_memory_keys,
      initialArgs = initial_args,
      argsSnapshot = args_snapshot,
      sensitiveArgNames = sensitive_arg_names,
    )

  fun ModelRunYamlResponse.toProto(): RunYamlResponse =
    RunYamlResponse(
      session_id = sessionId.value,
      success = success,
      error_message = errorMessage,
      memory_snapshot = memorySnapshot,
      memory_deletions = memoryDeletions,
      tool_message = toolMessage,
      tool_structured_content_json = toolStructuredContent?.toProtoJson(),
      on_device_tool_log_count = onDeviceToolLogCount,
      non_recoverable_wedge = nonRecoverableWedge,
      sensitive_memory_keys = sensitiveMemoryKeys,
    )

  fun RunYamlResponse.toModel(): ModelRunYamlResponse =
    ModelRunYamlResponse(
      sessionId = SessionId(session_id),
      success = success,
      errorMessage = error_message,
      memorySnapshot = memory_snapshot,
      memoryDeletions = memory_deletions,
      toolMessage = tool_message,
      toolStructuredContent = tool_structured_content_json?.toJsonElement(),
      onDeviceToolLogCount = on_device_tool_log_count,
      nonRecoverableWedge = non_recoverable_wedge,
      sensitiveMemoryKeys = sensitive_memory_keys,
    )

  fun ModelDrainSessionRequest.toProto(): DrainSessionRequest =
    DrainSessionRequest(reason = reason)

  fun DrainSessionRequest.toModel(): ModelDrainSessionRequest =
    ModelDrainSessionRequest(reason = reason)

  fun ModelDrainSessionResponse.toProto(): DrainSessionResponse =
    DrainSessionResponse(ui_automation_cleared = uiAutomationCleared)

  fun DrainSessionResponse.toModel(): ModelDrainSessionResponse =
    ModelDrainSessionResponse(uiAutomationCleared = ui_automation_cleared)

  fun ModelSubscribeToProgressRequest.toProto(): SubscribeToProgressRequest =
    SubscribeToProgressRequest(session_id = sessionId, include_history = includeHistory)

  fun SubscribeToProgressRequest.toModel(): ModelSubscribeToProgressRequest =
    ModelSubscribeToProgressRequest(sessionId = session_id, includeHistory = include_history)

  fun ModelSubscribeToProgressResponse.toProto(): SubscribeToProgressResponse =
    SubscribeToProgressResponse(
      events = events.map { it.toProto() },
      has_more = hasMore,
      last_event_timestamp = lastEventTimestamp,
    )

  fun SubscribeToProgressResponse.toModel(): ModelSubscribeToProgressResponse =
    ModelSubscribeToProgressResponse(
      events = events.map { it.toModel() },
      hasMore = has_more,
      lastEventTimestamp = last_event_timestamp,
    )

  fun ModelGetExecutionStatusRequest.toProto(): GetExecutionStatusRequest =
    GetExecutionStatusRequest(session_id = sessionId)

  fun GetExecutionStatusRequest.toModel(): ModelGetExecutionStatusRequest =
    ModelGetExecutionStatusRequest(sessionId = session_id)

  fun ModelGetExecutionStatusResponse.toProto(): GetExecutionStatusResponse =
    GetExecutionStatusResponse(status = status?.toProto(), found = found)

  fun GetExecutionStatusResponse.toModel(): ModelGetExecutionStatusResponse =
    ModelGetExecutionStatusResponse(status = status?.toModel(), found = found)

  fun ModelListActiveSessionsRequest.toProto(): ListActiveSessionsRequest =
    ListActiveSessionsRequest(include_completed = includeCompleted)

  fun ListActiveSessionsRequest.toModel(): ModelListActiveSessionsRequest =
    ModelListActiveSessionsRequest(includeCompleted = include_completed)

  fun ModelListActiveSessionsResponse.toProto(): ListActiveSessionsResponse =
    ListActiveSessionsResponse(sessions = sessions.map { it.toProto() })

  fun ListActiveSessionsResponse.toModel(): ModelListActiveSessionsResponse =
    ModelListActiveSessionsResponse(sessions = sessions.map { it.toModel() })

  private fun ModelDeviceId.toProto(): DeviceId =
    DeviceId(instance_id = instanceId, platform = trailblazeDevicePlatform.name)

  private fun DeviceId.toModel(): ModelDeviceId =
    ModelDeviceId(
      instanceId = instance_id,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.valueOf(platform),
    )

  private fun ModelLlmModel.toProto(): LlmModel =
    LlmModel(
      provider = trailblazeLlmProvider.toProto(),
      model_id = modelId,
      input_cost_per_one_million_tokens = inputCostPerOneMillionTokens,
      output_cost_per_one_million_tokens = outputCostPerOneMillionTokens,
      cached_input_cost_per_one_million_tokens = cachedInputCostPerOneMillionTokens,
      image_token_formula = imageTokenFormula.name,
      context_length = contextLength,
      max_output_tokens = maxOutputTokens,
      capability_ids = capabilityIds,
      default_temperature = defaultTemperature,
      screenshot_scaling_config = screenshotScalingConfig.toProto(),
    )

  private fun LlmModel.toModel(): ModelLlmModel =
    ModelLlmModel(
      trailblazeLlmProvider = provider?.toModel()
        ?: error("Binary LlmModel omitted provider"),
      modelId = model_id,
      inputCostPerOneMillionTokens = input_cost_per_one_million_tokens,
      outputCostPerOneMillionTokens = output_cost_per_one_million_tokens,
      cachedInputCostPerOneMillionTokens = cached_input_cost_per_one_million_tokens,
      imageTokenFormula = ImageTokenFormula.valueOf(image_token_formula),
      contextLength = context_length,
      maxOutputTokens = max_output_tokens,
      capabilityIds = capability_ids,
      defaultTemperature = default_temperature,
      screenshotScalingConfig = screenshot_scaling_config?.toModel()
        ?: error("Binary LlmModel omitted screenshot_scaling_config"),
    )

  private fun ModelLlmProvider.toProto(): LlmProvider =
    LlmProvider(id = id, display = display, description = description)

  private fun LlmProvider.toModel(): ModelLlmProvider =
    ModelLlmProvider(id = id, display = display, description = description)

  private fun ModelScreenshotScalingConfig.toProto(): ScreenshotScalingConfig =
    ScreenshotScalingConfig(
      max_dimension_1 = maxDimension1,
      max_dimension_2 = maxDimension2,
      image_format = imageFormat.name,
      compression_quality = compressionQuality,
    )

  private fun ScreenshotScalingConfig.toModel(): ModelScreenshotScalingConfig =
    ModelScreenshotScalingConfig(
      maxDimension1 = max_dimension_1,
      maxDimension2 = max_dimension_2,
      imageFormat = TrailblazeImageFormat.valueOf(image_format),
      compressionQuality = compression_quality,
    )

  private fun ModelTrailblazeConfig.toProto(): TrailblazeConfig =
    TrailblazeConfig(
      send_session_start_log = sendSessionStartLog,
      send_session_end_log = sendSessionEndLog,
      override_session_id = overrideSessionId?.value,
      self_heal = selfHeal,
      browser_headless = browserHeadless,
      node_selector_mode = nodeSelectorMode.name,
      prefer_host_agent = preferHostAgent,
      capture_network_traffic = captureNetworkTraffic,
    )

  private fun TrailblazeConfig.toModel(): ModelTrailblazeConfig =
    ModelTrailblazeConfig(
      sendSessionStartLog = send_session_start_log,
      sendSessionEndLog = send_session_end_log,
      overrideSessionId = override_session_id?.let(::SessionId),
      selfHeal = self_heal,
      browserHeadless = browser_headless,
      nodeSelectorMode = NodeSelectorMode.valueOf(node_selector_mode),
      preferHostAgent = prefer_host_agent,
      captureNetworkTraffic = capture_network_traffic,
    )

  private fun ModelReferrer.toProto(): Referrer = Referrer(id = id, display = display)

  private fun Referrer.toModel(): ModelReferrer = ModelReferrer(id = id, display = display)

  private fun ModelExecutionStatus.toProto(): ExecutionStatus =
    ExecutionStatus(
      session_id = sessionId.value,
      device_id = deviceId?.toProto(),
      state = state.name,
      objective = objective,
      agent_implementation = agentImplementation.name,
      progress_percent = progressPercent,
      current_step = currentStep,
      completed_steps = completedSteps,
      total_steps = totalSteps,
      actions_executed = actionsExecuted,
      elapsed_ms = elapsedMs,
      estimated_remaining_ms = estimatedRemainingMs,
      exceptions_handled = exceptionsHandled,
      memory_fact_count = memoryFactCount,
      error_message = errorMessage,
      last_updated = lastUpdated,
    )

  private fun ExecutionStatus.toModel(): ModelExecutionStatus =
    ModelExecutionStatus(
      sessionId = SessionId(session_id),
      deviceId = device_id?.toModel(),
      state = ExecutionState.valueOf(state),
      objective = objective,
      agentImplementation = AgentImplementation.valueOf(agent_implementation),
      progressPercent = progress_percent,
      currentStep = current_step,
      completedSteps = completed_steps,
      totalSteps = total_steps,
      actionsExecuted = actions_executed,
      elapsedMs = elapsed_ms,
      estimatedRemainingMs = estimated_remaining_ms,
      exceptionsHandled = exceptions_handled,
      memoryFactCount = memory_fact_count,
      errorMessage = error_message,
      lastUpdated = last_updated,
    )

  private fun ModelProgressEvent.toProto(): ProgressEvent {
    val eventTimestamp = timestamp
    val eventSessionId = sessionId.value
    val eventDeviceId = deviceId?.toProto()
    return when (this) {
      is ModelProgressEvent.StepStarted -> ProgressEvent(
        timestamp = eventTimestamp,
        session_id = eventSessionId,
        device_id = eventDeviceId,
        step_started = StepStarted(
          step_index = stepIndex,
          step_prompt = stepPrompt,
          estimated_duration_ms = estimatedDurationMs,
          total_steps = totalSteps,
        ),
      )
      is ModelProgressEvent.StepCompleted -> ProgressEvent(
        timestamp = eventTimestamp,
        session_id = eventSessionId,
        device_id = eventDeviceId,
        step_completed = StepCompleted(
          step_index = stepIndex,
          used_recording = usedRecording,
          duration_ms = durationMs,
          success = success,
          error_message = errorMessage,
        ),
      )
      is ModelProgressEvent.SubtaskProgress -> ProgressEvent(
        timestamp = eventTimestamp,
        session_id = eventSessionId,
        device_id = eventDeviceId,
        subtask_progress = SubtaskProgress(
          subtask_index = subtaskIndex,
          subtask_name = subtaskName,
          total_subtasks = totalSubtasks,
          percent_complete = percentComplete,
          actions_in_subtask = actionsInSubtask,
        ),
      )
      is ModelProgressEvent.SubtaskCompleted -> ProgressEvent(
        timestamp = eventTimestamp,
        session_id = eventSessionId,
        device_id = eventDeviceId,
        subtask_completed = SubtaskCompleted(
          subtask_index = subtaskIndex,
          subtask_name = subtaskName,
          duration_ms = durationMs,
          actions_taken = actionsTaken,
        ),
      )
      is ModelProgressEvent.TaskReplanned -> ProgressEvent(
        timestamp = eventTimestamp,
        session_id = eventSessionId,
        device_id = eventDeviceId,
        task_replanned = TaskReplanned(
          original_subtask = originalSubtask,
          block_reason = blockReason,
          new_subtasks_count = newSubtasksCount,
          replan_number = replanNumber,
        ),
      )
      is ModelProgressEvent.ReflectionTriggered -> ProgressEvent(
        timestamp = eventTimestamp,
        session_id = eventSessionId,
        device_id = eventDeviceId,
        reflection_triggered = ReflectionTriggered(
          reason = reason,
          assessment = assessment,
          suggested_action = suggestedAction,
          is_on_track = isOnTrack,
        ),
      )
      is ModelProgressEvent.BacktrackPerformed -> ProgressEvent(
        timestamp = eventTimestamp,
        session_id = eventSessionId,
        device_id = eventDeviceId,
        backtrack_performed = BacktrackPerformed(
          steps_backtracked = stepsBacktracked,
          reason = reason,
        ),
      )
      is ModelProgressEvent.ExceptionHandled -> ProgressEvent(
        timestamp = eventTimestamp,
        session_id = eventSessionId,
        device_id = eventDeviceId,
        exception_handled = ExceptionHandled(
          exception_type = exceptionType,
          recovery_action = recoveryAction,
          success = success,
        ),
      )
      is ModelProgressEvent.FactStored -> ProgressEvent(
        timestamp = eventTimestamp,
        session_id = eventSessionId,
        device_id = eventDeviceId,
        fact_stored = FactStored(key = key, value_preview = valuePreview),
      )
      is ModelProgressEvent.FactRecalled -> ProgressEvent(
        timestamp = eventTimestamp,
        session_id = eventSessionId,
        device_id = eventDeviceId,
        fact_recalled = FactRecalled(key = key, found = found),
      )
      is ModelProgressEvent.ExecutionStarted -> ProgressEvent(
        timestamp = eventTimestamp,
        session_id = eventSessionId,
        device_id = eventDeviceId,
        execution_started = ExecutionStarted(
          objective = objective,
          agent_implementation = agentImplementation.name,
          has_task_plan = hasTaskPlan,
        ),
      )
      is ModelProgressEvent.ExecutionCompleted -> ProgressEvent(
        timestamp = eventTimestamp,
        session_id = eventSessionId,
        device_id = eventDeviceId,
        execution_completed = ExecutionCompleted(
          success = success,
          total_duration_ms = totalDurationMs,
          total_actions = totalActions,
          error_message = errorMessage,
        ),
      )
    }
  }

  private fun ProgressEvent.toModel(): ModelProgressEvent {
    val session = SessionId(session_id)
    val device = device_id?.toModel()
    return when {
      step_started != null -> ModelProgressEvent.StepStarted(
        timestamp,
        session,
        device,
        step_started.step_index,
        step_started.step_prompt,
        step_started.estimated_duration_ms,
        step_started.total_steps,
      )
      step_completed != null -> ModelProgressEvent.StepCompleted(
        timestamp,
        session,
        device,
        step_completed.step_index,
        step_completed.used_recording,
        step_completed.duration_ms,
        step_completed.success,
        step_completed.error_message,
      )
      subtask_progress != null -> ModelProgressEvent.SubtaskProgress(
        timestamp,
        session,
        device,
        subtask_progress.subtask_index,
        subtask_progress.subtask_name,
        subtask_progress.total_subtasks,
        subtask_progress.percent_complete,
        subtask_progress.actions_in_subtask,
      )
      subtask_completed != null -> ModelProgressEvent.SubtaskCompleted(
        timestamp,
        session,
        device,
        subtask_completed.subtask_index,
        subtask_completed.subtask_name,
        subtask_completed.duration_ms,
        subtask_completed.actions_taken,
      )
      task_replanned != null -> ModelProgressEvent.TaskReplanned(
        timestamp,
        session,
        device,
        task_replanned.original_subtask,
        task_replanned.block_reason,
        task_replanned.new_subtasks_count,
        task_replanned.replan_number,
      )
      reflection_triggered != null -> ModelProgressEvent.ReflectionTriggered(
        timestamp,
        session,
        device,
        reflection_triggered.reason,
        reflection_triggered.assessment,
        reflection_triggered.suggested_action,
        reflection_triggered.is_on_track,
      )
      backtrack_performed != null -> ModelProgressEvent.BacktrackPerformed(
        timestamp,
        session,
        device,
        backtrack_performed.steps_backtracked,
        backtrack_performed.reason,
      )
      exception_handled != null -> ModelProgressEvent.ExceptionHandled(
        timestamp,
        session,
        device,
        exception_handled.exception_type,
        exception_handled.recovery_action,
        exception_handled.success,
      )
      fact_stored != null -> ModelProgressEvent.FactStored(
        timestamp,
        session,
        device,
        fact_stored.key,
        fact_stored.value_preview,
      )
      fact_recalled != null -> ModelProgressEvent.FactRecalled(
        timestamp,
        session,
        device,
        fact_recalled.key,
        fact_recalled.found,
      )
      execution_started != null -> ModelProgressEvent.ExecutionStarted(
        timestamp,
        session,
        device,
        execution_started.objective,
        AgentImplementation.valueOf(execution_started.agent_implementation),
        execution_started.has_task_plan,
      )
      execution_completed != null -> ModelProgressEvent.ExecutionCompleted(
        timestamp,
        session,
        device,
        execution_completed.success,
        execution_completed.total_duration_ms,
        execution_completed.total_actions,
        execution_completed.error_message,
      )
      else -> error("Binary ProgressEvent omitted its event payload")
    }
  }

  private fun JsonElement.toProtoJson(): ByteString = toString().encodeToByteArray().toByteString()

  private fun ByteString.toJsonElement(): JsonElement = Json.parseToJsonElement(utf8())

  private fun String.toTraceId(): TraceId = Json.decodeFromJsonElement(JsonPrimitive(this))

  fun RpcResult.Failure.toProto(): RpcFailure =
    RpcFailure(
      error_type = errorType.name,
      message = message,
      details = details,
    )

  fun RpcFailure.toModel(method: String?, url: String?): RpcResult.Failure =
    RpcResult.Failure(
      errorType = runCatching { RpcResult.ErrorType.valueOf(error_type) }
        .getOrDefault(RpcResult.ErrorType.UNKNOWN_ERROR),
      message = message,
      details = details,
      method = method,
      url = url,
    )

  private fun TrailblazeImageFormat.toProto(): ImageFormat =
    when (this) {
      TrailblazeImageFormat.PNG -> ImageFormat.IMAGE_FORMAT_PNG
      TrailblazeImageFormat.JPEG -> ImageFormat.IMAGE_FORMAT_JPEG
      TrailblazeImageFormat.WEBP -> ImageFormat.IMAGE_FORMAT_WEBP
    }

  private fun ImageFormat.toModel(): TrailblazeImageFormat =
    when (this) {
      ImageFormat.IMAGE_FORMAT_PNG -> TrailblazeImageFormat.PNG
      ImageFormat.IMAGE_FORMAT_JPEG -> TrailblazeImageFormat.JPEG
      ImageFormat.IMAGE_FORMAT_WEBP -> TrailblazeImageFormat.WEBP
      ImageFormat.IMAGE_FORMAT_UNSPECIFIED -> TrailblazeImageFormat.WEBP
    }

  fun ModelViewHierarchyNode.toProto(): ViewHierarchyNode =
    ViewHierarchyNode(
      node_id = nodeId,
      accessibility_text = accessibilityText,
      x1 = x1,
      y1 = y1,
      x2 = x2,
      y2 = y2,
      center_point = centerPoint,
      checked = checked,
      children = children.map { it.toProto() },
      class_name = className,
      clickable = clickable,
      dimensions = dimensions,
      enabled = enabled,
      focusable = focusable,
      focused = focused,
      hint_text = hintText,
      ignore_bounds_filtering = ignoreBoundsFiltering,
      password = password,
      resource_id = resourceId,
      scrollable = scrollable,
      selected = selected,
      text = text,
    )

  fun ViewHierarchyNode.toModel(): ModelViewHierarchyNode =
    ModelViewHierarchyNode(
      nodeId = node_id,
      accessibilityText = accessibility_text,
      x1 = x1,
      y1 = y1,
      x2 = x2,
      y2 = y2,
      centerPoint = center_point,
      checked = checked,
      children = children.map { it.toModel() },
      className = class_name,
      clickable = clickable,
      dimensions = dimensions,
      enabled = enabled,
      focusable = focusable,
      focused = focused,
      hintText = hint_text,
      ignoreBoundsFiltering = ignore_bounds_filtering,
      password = password,
      resourceId = resource_id,
      scrollable = scrollable,
      selected = selected,
      text = text,
    )

  fun ModelTrailblazeNode.toProto(): TrailblazeNode {
    val accessibility = driverDetail as? DriverNodeDetail.AndroidAccessibility
    val maestro = driverDetail as? DriverNodeDetail.AndroidMaestro
    require(accessibility != null || maestro != null) {
      "Android binary RPC cannot encode driver detail ${driverDetail::class.simpleName}"
    }
    return TrailblazeNode(
      node_id = nodeId,
      ref = ref,
      children = children.map { it.toProto() },
      bounds = bounds?.let { Bounds(it.left, it.top, it.right, it.bottom) },
      android_accessibility = accessibility?.toProto(),
      android_maestro = maestro?.toProto(),
    )
  }

  fun TrailblazeNode.toModel(): ModelTrailblazeNode =
    ModelTrailblazeNode(
      nodeId = node_id,
      ref = ref,
      children = children.map { it.toModel() },
      bounds = bounds?.let { ModelTrailblazeNode.Bounds(it.left, it.top, it.right, it.bottom) },
      driverDetail = when {
        android_accessibility != null -> android_accessibility.toModel()
        android_maestro != null -> android_maestro.toModel()
        else -> error("Binary TrailblazeNode omitted Android driver detail")
      },
    )

  private fun DriverNodeDetail.AndroidAccessibility.toProto(): AndroidAccessibilityDetail =
    AndroidAccessibilityDetail(
      class_name = className,
      resource_id = resourceId,
      unique_id = uniqueId,
      text = text,
      content_description = contentDescription,
      hint_text = hintText,
      labeled_by_text = labeledByText,
      state_description = stateDescription,
      pane_title = paneTitle,
      role_description = roleDescription,
      compose_test_tag = composeTestTag,
      is_enabled = isEnabled,
      is_clickable = isClickable,
      is_checkable = isCheckable,
      is_checked = isChecked,
      is_selected = isSelected,
      is_focused = isFocused,
      is_editable = isEditable,
      is_scrollable = isScrollable,
      is_password = isPassword,
      is_heading = isHeading,
      is_multi_line = isMultiLine,
      input_type = inputType,
      collection_item_info = collectionItemInfo?.let {
        CollectionItemInfo(it.rowIndex, it.rowSpan, it.columnIndex, it.columnSpan, it.isHeading)
      },
      package_name = packageName,
      tooltip_text = tooltipText,
      error = error,
      is_showing_hint_text = isShowingHintText,
      is_content_invalid = isContentInvalid,
      is_visible_to_user = isVisibleToUser,
      is_long_clickable = isLongClickable,
      is_focusable = isFocusable,
      is_text_selectable = isTextSelectable,
      is_important_for_accessibility = isImportantForAccessibility,
      drawing_order = drawingOrder,
      max_text_length = maxTextLength,
      actions = actions,
      collection_info = collectionInfo?.let {
        CollectionInfo(it.rowCount, it.columnCount, it.isHierarchical)
      },
      range_info = rangeInfo?.let { RangeInfo(it.type, it.min, it.max, it.current) },
    )

  private fun AndroidAccessibilityDetail.toModel(): DriverNodeDetail.AndroidAccessibility =
    DriverNodeDetail.AndroidAccessibility(
      className = class_name,
      resourceId = resource_id,
      uniqueId = unique_id,
      text = text,
      contentDescription = content_description,
      hintText = hint_text,
      labeledByText = labeled_by_text,
      stateDescription = state_description,
      paneTitle = pane_title,
      roleDescription = role_description,
      composeTestTag = compose_test_tag,
      isEnabled = is_enabled,
      isClickable = is_clickable,
      isCheckable = is_checkable,
      isChecked = is_checked,
      isSelected = is_selected,
      isFocused = is_focused,
      isEditable = is_editable,
      isScrollable = is_scrollable,
      isPassword = is_password,
      isHeading = is_heading,
      isMultiLine = is_multi_line,
      inputType = input_type,
      collectionItemInfo = collection_item_info?.let {
        DriverNodeDetail.AndroidAccessibility.CollectionItemInfo(
          it.row_index,
          it.row_span,
          it.column_index,
          it.column_span,
          it.is_heading,
        )
      },
      packageName = package_name,
      tooltipText = tooltip_text,
      error = error,
      isShowingHintText = is_showing_hint_text,
      isContentInvalid = is_content_invalid,
      isVisibleToUser = is_visible_to_user,
      isLongClickable = is_long_clickable,
      isFocusable = is_focusable,
      isTextSelectable = is_text_selectable,
      isImportantForAccessibility = is_important_for_accessibility,
      drawingOrder = drawing_order,
      maxTextLength = max_text_length,
      actions = actions,
      collectionInfo = collection_info?.let {
        DriverNodeDetail.AndroidAccessibility.CollectionInfo(
          it.row_count,
          it.column_count,
          it.is_hierarchical,
        )
      },
      rangeInfo = range_info?.let {
        DriverNodeDetail.AndroidAccessibility.RangeInfo(it.type, it.min, it.max, it.current)
      },
    )

  private fun DriverNodeDetail.AndroidMaestro.toProto(): AndroidMaestroDetail =
    AndroidMaestroDetail(
      text = text,
      resource_id = resourceId,
      accessibility_text = accessibilityText,
      class_name = className,
      hint_text = hintText,
      clickable = clickable,
      enabled = enabled,
      focused = focused,
      checked = checked,
      selected = selected,
      focusable = focusable,
      scrollable = scrollable,
      password = password,
    )

  private fun AndroidMaestroDetail.toModel(): DriverNodeDetail.AndroidMaestro =
    DriverNodeDetail.AndroidMaestro(
      text = text,
      resourceId = resource_id,
      accessibilityText = accessibility_text,
      className = class_name,
      hintText = hint_text,
      clickable = clickable,
      enabled = enabled,
      focused = focused,
      checked = checked,
      selected = selected,
      focusable = focusable,
      scrollable = scrollable,
      password = password,
    )

  private fun ByteArray?.toByteStringOrEmpty(): ByteString =
    this?.toByteString() ?: ByteString.EMPTY

  private fun ByteString.toByteArrayOrNull(): ByteArray? =
    if (size == 0) null else toByteArray()
}
