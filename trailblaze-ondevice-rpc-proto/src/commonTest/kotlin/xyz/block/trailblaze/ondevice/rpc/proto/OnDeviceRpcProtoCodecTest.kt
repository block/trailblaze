package xyz.block.trailblaze.ondevice.rpc.proto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.api.TrailblazeImageFormat
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.agent.ExecutionState
import xyz.block.trailblaze.agent.ExecutionStatus
import xyz.block.trailblaze.agent.TrailblazeProgressEvent
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.ImageTokenFormula
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.RunYamlResponse
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetExecutionStatusResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.SubscribeToProgressResponse
import xyz.block.trailblaze.model.NodeSelectorMode
import xyz.block.trailblaze.model.TrailblazeConfig

class OnDeviceRpcProtoCodecTest {

  @Test
  fun `run yaml request and response round trip without model JSON`() {
    val device = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)
    val traceId = TraceId.generate(TraceId.Companion.TraceOrigin.MCP)
    val request = RunYamlRequest(
      testName = "checkout",
      yaml = "- tapOn: Pay now",
      trailFilePath = "trails/checkout.trail.yaml",
      targetAppName = "example",
      useRecordedSteps = true,
      trailblazeDeviceId = device,
      trailblazeLlmModel = TrailblazeLlmModel(
        trailblazeLlmProvider = TrailblazeLlmProvider("provider", "Provider", "description"),
        modelId = "model",
        inputCostPerOneMillionTokens = 1.25,
        outputCostPerOneMillionTokens = 4.5,
        cachedInputCostPerOneMillionTokens = 0.5,
        imageTokenFormula = ImageTokenFormula.OPENAI_TILE,
        contextLength = 128_000,
        maxOutputTokens = 8_192,
        capabilityIds = listOf("tools", "image"),
        defaultTemperature = 0.2,
        screenshotScalingConfig = ScreenshotScalingConfig(
          maxDimension1 = 1200,
          maxDimension2 = 600,
          imageFormat = TrailblazeImageFormat.JPEG,
          compressionQuality = 0.7f,
        ),
      ),
      config = TrailblazeConfig(
        sendSessionStartLog = false,
        sendSessionEndLog = false,
        overrideSessionId = SessionId("session"),
        selfHeal = true,
        browserHeadless = false,
        nodeSelectorMode = NodeSelectorMode.FORCE_NODE_SELECTOR,
        preferHostAgent = false,
        captureNetworkTraffic = false,
      ),
      referrer = TrailblazeReferrer("test", "Test"),
      traceId = traceId,
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      agentImplementation = AgentImplementation.TRAILBLAZE_RUNNER,
      memorySnapshot = mapOf("email" to "person@example.com"),
      maxLlmCalls = 9,
      initialMemorySeeds = mapOf("name" to "Ada"),
      initialMemorySensitiveSeeds = mapOf("pin" to "1234"),
      sensitiveMemoryKeys = listOf("pin"),
      initialArgs = mapOf("count" to "3"),
      argsSnapshot = mapOf("enabled" to "true"),
      sensitiveArgNames = listOf("token"),
    )
    val response = RunYamlResponse(
      sessionId = SessionId("session"),
      success = true,
      memorySnapshot = mapOf("result" to "done"),
      memoryDeletions = listOf("old"),
      toolMessage = "ok",
      toolStructuredContent = buildJsonObject { put("count", 3) },
      onDeviceToolLogCount = 1,
      sensitiveMemoryKeys = listOf("secret"),
    )

    val decodedRequest = OnDeviceRpcProtoCodec.run { request.toProto().toModel() }
    val decodedResponse = OnDeviceRpcProtoCodec.run { response.toProto().toModel() }

    assertEquals(request, decodedRequest)
    assertEquals(response, decodedResponse)
  }

  @Test
  fun `progress and status variants round trip`() {
    val session = SessionId("progress")
    val device = TrailblazeDeviceId("device", TrailblazeDevicePlatform.ANDROID)
    val events = listOf<TrailblazeProgressEvent>(
      TrailblazeProgressEvent.StepStarted(1, session, device, 0, "Open app", 50, 2),
      TrailblazeProgressEvent.StepCompleted(2, session, device, 0, true, 60, true),
      TrailblazeProgressEvent.SubtaskProgress(3, session, device, 1, "Pay", 2, 50, 3),
      TrailblazeProgressEvent.SubtaskCompleted(4, session, device, 1, "Pay", 70, 4),
      TrailblazeProgressEvent.TaskReplanned(5, session, device, "Pay", "blocked", 2, 1),
      TrailblazeProgressEvent.ReflectionTriggered(6, session, device, "loop", "retry", "back", false),
      TrailblazeProgressEvent.BacktrackPerformed(7, session, device, 1, "retry"),
      TrailblazeProgressEvent.ExceptionHandled(8, session, device, "POPUP", "dismiss", true),
      TrailblazeProgressEvent.FactStored(9, session, device, "total", "42"),
      TrailblazeProgressEvent.FactRecalled(10, session, device, "total", true),
      TrailblazeProgressEvent.ExecutionStarted(
        11,
        session,
        device,
        "Checkout",
        AgentImplementation.TRAILBLAZE_RUNNER,
        true,
      ),
      TrailblazeProgressEvent.ExecutionCompleted(12, session, device, true, 100, 8),
    )
    val progress = SubscribeToProgressResponse(events, hasMore = true, lastEventTimestamp = 12)
    val status = GetExecutionStatusResponse(
      status = ExecutionStatus(
        sessionId = session,
        deviceId = device,
        state = ExecutionState.RUNNING,
        objective = "Checkout",
        agentImplementation = AgentImplementation.TRAILBLAZE_RUNNER,
        progressPercent = 50,
        currentStep = "Pay",
        completedSteps = 1,
        totalSteps = 2,
        actionsExecuted = 4,
        elapsedMs = 100,
        estimatedRemainingMs = 50,
        exceptionsHandled = 1,
        memoryFactCount = 2,
        lastUpdated = 12,
      ),
      found = true,
    )

    assertEquals(progress, OnDeviceRpcProtoCodec.run { progress.toProto().toModel() })
    assertEquals(status, OnDeviceRpcProtoCodec.run { status.toProto().toModel() })
  }

  @Test
  fun `screen request round trips every transport option`() {
    val original = GetScreenStateRequest(
      includeScreenshot = false,
      screenshotMaxDimension1 = 900,
      screenshotMaxDimension2 = 450,
      screenshotImageFormat = TrailblazeImageFormat.JPEG,
      screenshotCompressionQuality = 0.55f,
      includeAnnotatedScreenshot = false,
      includeAllElements = true,
      requireAndroidAccessibilityService = true,
      includeTree = false,
    )

    val decoded = OnDeviceRpcProtoCodec.run { original.toProto().toModel() }

    assertEquals(original, decoded)
  }

  @Test
  fun `accessibility screen response preserves raw images and rich tree fields`() {
    val detail = DriverNodeDetail.AndroidAccessibility(
      className = "android.widget.Button",
      resourceId = "example:id/continue",
      uniqueId = "stable-id",
      text = "Continue",
      contentDescription = "Continue checkout",
      hintText = "hint",
      labeledByText = "Action",
      stateDescription = "Ready",
      paneTitle = "Checkout",
      roleDescription = "Button",
      composeTestTag = "continue",
      isEnabled = true,
      isClickable = true,
      isCheckable = true,
      isChecked = true,
      isSelected = true,
      isFocused = true,
      isEditable = true,
      isScrollable = true,
      isPassword = true,
      isHeading = true,
      isMultiLine = true,
      inputType = 33,
      collectionItemInfo = DriverNodeDetail.AndroidAccessibility.CollectionItemInfo(1, 2, 3, 4, true),
      packageName = "example",
      tooltipText = "tooltip",
      error = "error",
      isShowingHintText = true,
      isContentInvalid = true,
      isVisibleToUser = true,
      isLongClickable = true,
      isFocusable = true,
      isTextSelectable = true,
      isImportantForAccessibility = true,
      drawingOrder = 7,
      maxTextLength = 99,
      actions = listOf("ACTION_CLICK", "ACTION_FOCUS"),
      collectionInfo = DriverNodeDetail.AndroidAccessibility.CollectionInfo(5, 6, true),
      rangeInfo = DriverNodeDetail.AndroidAccessibility.RangeInfo(1, 0f, 100f, 42f),
    )
    val node = TrailblazeNode(
      nodeId = 12,
      ref = "b12",
      children = listOf(
        TrailblazeNode(
          nodeId = 13,
          bounds = TrailblazeNode.Bounds(1, 2, 30, 40),
          driverDetail = detail.copy(text = "Child"),
        ),
      ),
      bounds = TrailblazeNode.Bounds(0, 0, 100, 200),
      driverDetail = detail,
    )
    val original = GetScreenStateResponse(
      viewHierarchy = ViewHierarchyTreeNode(
        nodeId = 8,
        accessibilityText = "Continue checkout",
        x1 = 0,
        y1 = 1,
        x2 = 100,
        y2 = 201,
        centerPoint = "50,101",
        checked = true,
        children = listOf(ViewHierarchyTreeNode(nodeId = 9, text = "Child")),
        className = "android.widget.Button",
        clickable = true,
        dimensions = "100x200",
        enabled = true,
        focusable = true,
        focused = true,
        hintText = "hint",
        ignoreBoundsFiltering = true,
        password = true,
        resourceId = "example:id/continue",
        scrollable = true,
        selected = true,
        text = "Continue",
      ),
      screenshotBase64 = null,
      annotatedScreenshotBase64 = null,
      deviceWidth = 1080,
      deviceHeight = 1920,
      trailblazeNodeTree = node,
      driverMigrationTreeNode = node.copy(nodeId = 99),
      pageContextSummary = "example/.CheckoutActivity",
      deviceClassifiers = listOf("android", "phone"),
      capturedAtDeviceMs = 1_753_000_000_123L,
    ).apply {
      screenshotBytes = byteArrayOf(1, 2, 3, 4)
      annotatedScreenshotBytes = byteArrayOf(9, 8, 7)
    }

    val proto = OnDeviceRpcProtoCodec.run { original.toProto() }
    val encoded = OnDeviceRpcProtoCodec.encode(RpcResponseEnvelope(request_id = 7, get_screen_state = proto))
    val decoded = OnDeviceRpcProtoCodec.run {
      OnDeviceRpcProtoCodec.decodeResponse(encoded).get_screen_state!!.toModel()
    }

    assertContentEquals(original.screenshotBytes, decoded.screenshotBytes)
    assertContentEquals(original.annotatedScreenshotBytes, decoded.annotatedScreenshotBytes)
    assertNull(decoded.screenshotBase64)
    assertNull(decoded.annotatedScreenshotBase64)
    assertEquals(original.viewHierarchy, decoded.viewHierarchy)
    assertEquals(original.deviceWidth, decoded.deviceWidth)
    assertEquals(original.deviceHeight, decoded.deviceHeight)
    assertEquals(original.pageContextSummary, decoded.pageContextSummary)
    assertEquals(original.deviceClassifiers, decoded.deviceClassifiers)
    assertEquals(original.trailblazeNodeTree, decoded.trailblazeNodeTree)
    assertEquals(original.driverMigrationTreeNode, decoded.driverMigrationTreeNode)
    assertEquals(original.capturedAtDeviceMs, decoded.capturedAtDeviceMs)
    assertIs<DriverNodeDetail.AndroidAccessibility>(decoded.trailblazeNodeTree!!.driverDetail)
  }

  @Test
  fun `instrumentation tree preserves Android Maestro detail`() {
    val detail = DriverNodeDetail.AndroidMaestro(
      text = "Save",
      resourceId = "example:id/save",
      accessibilityText = "Save changes",
      className = "android.widget.Button",
      hintText = "Save",
      clickable = true,
      enabled = true,
      focused = true,
      checked = true,
      selected = true,
      focusable = true,
      scrollable = true,
      password = true,
    )
    val original = GetScreenStateResponse(
      viewHierarchy = ViewHierarchyTreeNode(),
      screenshotBase64 = null,
      deviceWidth = 100,
      deviceHeight = 200,
      trailblazeNodeTree = TrailblazeNode(nodeId = 1, driverDetail = detail),
    )

    val decoded = OnDeviceRpcProtoCodec.run { original.toProto().toModel() }

    assertEquals(detail, decoded.trailblazeNodeTree?.driverDetail)
  }
}
