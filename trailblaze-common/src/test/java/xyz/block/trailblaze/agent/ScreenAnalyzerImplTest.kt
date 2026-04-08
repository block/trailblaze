package xyz.block.trailblaze.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.llm.TrailblazeLlmModels
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolParameterDescriptor

/**
 * Unit tests for [InnerLoopScreenAnalyzer].
 *
 * Tests the screen analyzer's ability to:
 * - Parse valid JSON responses
 * - Handle malformed responses gracefully
 * - Build correct prompts from context and screen state
 * - Extract JSON from markdown code fences
 */
class ScreenAnalyzerImplTest {

  @Test
  fun `analyze returns ScreenAnalysis for valid JSON response`() = runBlocking {
    // Given: LLM returns valid JSON analysis
    val validJsonResponse = """
      {
        "recommendedTool": "tapOnElementByNodeId",
        "recommendedArgs": {"nodeId": 42},
        "reasoning": "The login button is visible and ready to be tapped",
        "screenSummary": "Login screen with email/password fields",
        "progressIndicators": ["Email field visible", "Password field visible"],
        "potentialBlockers": [],
        "alternativeApproaches": ["Could tap by text instead"],
        "confidence": "HIGH",
        "objectiveAppearsAchieved": false,
        "objectiveAppearsImpossible": false
      }
    """.trimIndent()

    val mockSamplingSource = MockSamplingSource(
      textResponses = listOf(validJsonResponse)
    )

    val analyzer = InnerLoopScreenAnalyzer(
      samplingSource = mockSamplingSource,
      model = TrailblazeLlmModels.GPT_4O_MINI,
    )

    // When
    val result = analyzer.analyze(
      context = RecommendationContext(objective = "Tap the login button"),
      screenState = createMockScreenState(),
      availableTools = createMockTools(),
    )

    // Then
    assertEquals("tapOnElementByNodeId", result.recommendedTool)
    assertEquals(42, result.recommendedArgs["nodeId"]?.toString()?.toInt())
    assertEquals("The login button is visible and ready to be tapped", result.reasoning)
    assertEquals("Login screen with email/password fields", result.screenSummary)
    assertEquals(Confidence.HIGH, result.confidence)
    assertFalse(result.objectiveAppearsAchieved)
    assertFalse(result.objectiveAppearsImpossible)
    assertEquals(2, result.progressIndicators.size)
    assertEquals(1, result.alternativeApproaches.size)
  }

  @Test
  fun `analyze returns error when SamplingSource returns text instead of tool call`() = runBlocking {
    // Given: SamplingSource returns text (shouldn't happen with ToolChoice.Required)
    // The mock's JSON parsing fails on markdown, so it falls back to SamplingResult.Text
    val markdownResponse = """
      Here's my analysis:
      
      ```json
      {
        "recommendedTool": "swipe",
        "recommendedArgs": {"direction": "up"},
        "reasoning": "Need to scroll to find the button",
        "screenSummary": "Settings page",
        "confidence": "MEDIUM"
      }
      ```
    """.trimIndent()

    val mockSamplingSource = MockSamplingSource(
      textResponses = listOf(markdownResponse)
    )

    val analyzer = InnerLoopScreenAnalyzer(
      samplingSource = mockSamplingSource,
      model = TrailblazeLlmModels.GPT_4O_MINI,
    )

    // When
    val result = analyzer.analyze(
      context = RecommendationContext(objective = "Find settings"),
      screenState = createMockScreenState(),
      availableTools = createMockTools(),
    )

    // Then: Text responses are treated as errors since ToolChoice.Required is used
    assertEquals("wait", result.recommendedTool)
    assertEquals(Confidence.LOW, result.confidence)
    assertTrue(result.reasoning.contains("text instead of a tool call"))
  }

  @Test
  fun `analyze handles objective appears achieved`() = runBlocking {
    // Given: LLM indicates objective is complete
    val achievedResponse = """
      {
        "recommendedTool": "wait",
        "recommendedArgs": {"seconds": 0},
        "reasoning": "Already on home screen",
        "screenSummary": "Home screen showing main feed",
        "confidence": "HIGH",
        "objectiveAppearsAchieved": true,
        "objectiveAppearsImpossible": false
      }
    """.trimIndent()

    val mockSamplingSource = MockSamplingSource(
      textResponses = listOf(achievedResponse)
    )

    val analyzer = InnerLoopScreenAnalyzer(
      samplingSource = mockSamplingSource,
      model = TrailblazeLlmModels.GPT_4O_MINI,
    )

    // When
    val result = analyzer.analyze(
      context = RecommendationContext(objective = "Navigate to home"),
      screenState = createMockScreenState(),
      availableTools = createMockTools(),
    )

    // Then
    assertTrue(result.objectiveAppearsAchieved)
    assertEquals(Confidence.HIGH, result.confidence)
  }

  @Test
  fun `analyze handles low confidence with blockers`() = runBlocking {
    // Given: LLM has low confidence due to blockers
    val lowConfidenceResponse = """
      {
        "recommendedTool": "wait",
        "recommendedArgs": {"seconds": 2},
        "reasoning": "Loading spinner visible, waiting for content",
        "screenSummary": "Loading screen with spinner",
        "progressIndicators": [],
        "potentialBlockers": ["Loading spinner active", "Content not yet visible"],
        "alternativeApproaches": ["Wait longer", "Refresh the page"],
        "confidence": "LOW",
        "objectiveAppearsAchieved": false,
        "objectiveAppearsImpossible": false
      }
    """.trimIndent()

    val mockSamplingSource = MockSamplingSource(
      textResponses = listOf(lowConfidenceResponse)
    )

    val analyzer = InnerLoopScreenAnalyzer(
      samplingSource = mockSamplingSource,
      model = TrailblazeLlmModels.GPT_4O_MINI,
    )

    // When
    val result = analyzer.analyze(
      context = RecommendationContext(objective = "Click submit"),
      screenState = createMockScreenState(),
      availableTools = createMockTools(),
    )

    // Then
    assertEquals(Confidence.LOW, result.confidence)
    assertEquals(2, result.potentialBlockers.size)
    assertTrue(result.potentialBlockers.contains("Loading spinner active"))
  }

  @Test
  fun `analyze handles malformed JSON gracefully`() = runBlocking {
    // Given: SamplingSource fails to parse and falls back to SamplingResult.Text
    val malformedResponse = """
      {"recommendedTool": "tap", "incomplete json...
    """.trimIndent()

    val mockSamplingSource = MockSamplingSource(
      textResponses = listOf(malformedResponse)
    )

    val analyzer = InnerLoopScreenAnalyzer(
      samplingSource = mockSamplingSource,
      model = TrailblazeLlmModels.GPT_4O_MINI,
    )

    // When
    val result = analyzer.analyze(
      context = RecommendationContext(objective = "Test"),
      screenState = createMockScreenState(),
      availableTools = createMockTools(),
    )

    // Then: Text fallback is treated as an error since ToolChoice.Required is used
    assertEquals("wait", result.recommendedTool)
    assertEquals(Confidence.LOW, result.confidence)
    assertTrue(result.reasoning.contains("text instead of a tool call"))
  }

  @Test
  fun `analyze handles sampling error`() = runBlocking {
    // Given: Sampling source returns an error
    val mockSamplingSource = MockSamplingSource(
      errorMessage = "API rate limit exceeded"
    )

    val analyzer = InnerLoopScreenAnalyzer(
      samplingSource = mockSamplingSource,
      model = TrailblazeLlmModels.GPT_4O_MINI,
    )

    // When
    val result = analyzer.analyze(
      context = RecommendationContext(objective = "Test"),
      screenState = createMockScreenState(),
      availableTools = createMockTools(),
    )

    // Then
    assertEquals("wait", result.recommendedTool)
    assertEquals(Confidence.LOW, result.confidence)
    assertTrue(result.reasoning.contains("API rate limit exceeded"))
  }

  @Test
  fun `analyze includes context in prompt`() = runBlocking {
    // Given: Context with progress summary and hint
    var capturedUserMessage: String? = null
    val mockSamplingSource = object : SamplingSource {
      override suspend fun sampleText(
        systemPrompt: String,
        userMessage: String,
        screenshotBytes: ByteArray?,
        maxTokens: Int,
        traceId: TraceId?,
        screenContext: ScreenContext?,
      ): SamplingResult {
        return SamplingResult.Text("Not used")
      }

      override suspend fun sampleToolCall(
        systemPrompt: String,
        userMessage: String,
        tools: List<TrailblazeToolDescriptor>,
        screenshotBytes: ByteArray?,
        maxTokens: Int,
        traceId: TraceId?,
        screenContext: ScreenContext?,
      ): SamplingResult {
        capturedUserMessage = userMessage
        return SamplingResult.ToolCall(
          toolName = "tapOnElementByNodeId",
          arguments = buildJsonObject {
            put("nodeId", 1)
            put("reasoning", "test")
            put("screenSummary", "test")
            put("confidence", "HIGH")
          }
        )
      }

      override fun isAvailable(): Boolean = true
      override fun description(): String = "Test source"
    }

    val analyzer = InnerLoopScreenAnalyzer(
      samplingSource = mockSamplingSource,
      model = TrailblazeLlmModels.GPT_4O_MINI,
    )

    // When
    analyzer.analyze(
      context = RecommendationContext(
        objective = "Complete the checkout",
        progressSummary = "Added item to cart, now on checkout page",
        hint = "Look for the 'Place Order' button",
        attemptNumber = 2,
      ),
      screenState = createMockScreenState(),
      availableTools = createMockTools(),
    )

    // Then
    val message = requireNotNull(capturedUserMessage) { "User message should have been captured" }
    assertTrue(message.contains("Complete the checkout"))
    assertTrue(message.contains("Added item to cart"))
    assertTrue(message.contains("Place Order"))
    assertTrue(message.contains("attempt #2"))
  }

  @Test
  fun `loadSystemPrompt returns non-empty prompt`() {
    // The system prompt should load successfully (from resource or fallback)
    val prompt = InnerLoopScreenAnalyzer.loadSystemPrompt()
    assertTrue(prompt.isNotBlank())
    // Check for content that exists in the prompt
    assertTrue(prompt.contains("Analyze") || prompt.contains("analyze"))
    assertTrue(prompt.contains("screen") || prompt.contains("Screen"))
  }

  // ==========================================================================
  // Exceptional Screen State Detection Tests (Mobile-Agent-v3 Phase 1)
  // ==========================================================================

  @Test
  fun `analyze detects popup dialog state`() = runBlocking {
    // Given: LLM detects a popup dialog
    val popupResponse = """
      {
        "recommendedTool": "tapOnElementByNodeId",
        "recommendedArgs": {"nodeId": 42},
        "reasoning": "Permission dialog blocking app",
        "screenSummary": "Camera permission dialog visible",
        "confidence": "HIGH",
        "screenState": "POPUP_DIALOG",
        "recoveryAction": {"type": "DismissPopup", "dismissTarget": "Allow button"}
      }
    """.trimIndent()

    val mockSamplingSource = MockSamplingSource(textResponses = listOf(popupResponse))
    val analyzer = InnerLoopScreenAnalyzer(
      samplingSource = mockSamplingSource,
      model = TrailblazeLlmModels.GPT_4O_MINI,
    )

    // When
    val result = analyzer.analyze(
      context = RecommendationContext(objective = "Take a photo"),
      screenState = createMockScreenState(),
      availableTools = createMockTools(),
    )

    // Then
    assertEquals(ExceptionalScreenState.POPUP_DIALOG, result.screenState)
    assertTrue(result.recoveryAction is RecoveryAction.DismissPopup)
    assertEquals("Allow button", (result.recoveryAction as RecoveryAction.DismissPopup).dismissTarget)
  }

  @Test
  fun `analyze detects advertisement state`() = runBlocking {
    // Given: LLM detects an advertisement
    val adResponse = """
      {
        "recommendedTool": "pressBack",
        "recommendedArgs": {},
        "reasoning": "Interstitial ad blocking app content",
        "screenSummary": "Full-screen video ad with skip button",
        "confidence": "HIGH",
        "screenState": "ADVERTISEMENT",
        "recoveryAction": {"type": "SkipAd", "skipMethod": "tap X button", "waitSeconds": 5}
      }
    """.trimIndent()

    val mockSamplingSource = MockSamplingSource(textResponses = listOf(adResponse))
    val analyzer = InnerLoopScreenAnalyzer(
      samplingSource = mockSamplingSource,
      model = TrailblazeLlmModels.GPT_4O_MINI,
    )

    // When
    val result = analyzer.analyze(
      context = RecommendationContext(objective = "Open settings"),
      screenState = createMockScreenState(),
      availableTools = createMockTools(),
    )

    // Then
    assertEquals(ExceptionalScreenState.ADVERTISEMENT, result.screenState)
    assertTrue(result.recoveryAction is RecoveryAction.SkipAd)
    val skipAd = result.recoveryAction as RecoveryAction.SkipAd
    assertEquals("tap X button", skipAd.skipMethod)
    assertEquals(5, skipAd.waitSeconds)
  }

  @Test
  fun `analyze detects loading state`() = runBlocking {
    // Given: LLM detects a loading state
    val loadingResponse = """
      {
        "recommendedTool": "wait",
        "recommendedArgs": {"seconds": 3},
        "reasoning": "Page is still loading",
        "screenSummary": "Loading spinner visible",
        "confidence": "MEDIUM",
        "screenState": "LOADING",
        "recoveryAction": {"type": "WaitForLoading", "maxWaitSeconds": 15}
      }
    """.trimIndent()

    val mockSamplingSource = MockSamplingSource(textResponses = listOf(loadingResponse))
    val analyzer = InnerLoopScreenAnalyzer(
      samplingSource = mockSamplingSource,
      model = TrailblazeLlmModels.GPT_4O_MINI,
    )

    // When
    val result = analyzer.analyze(
      context = RecommendationContext(objective = "View product"),
      screenState = createMockScreenState(),
      availableTools = createMockTools(),
    )

    // Then
    assertEquals(ExceptionalScreenState.LOADING, result.screenState)
    assertTrue(result.recoveryAction is RecoveryAction.WaitForLoading)
    assertEquals(15, (result.recoveryAction as RecoveryAction.WaitForLoading).maxWaitSeconds)
  }

  @Test
  fun `analyze detects error state`() = runBlocking {
    // Given: LLM detects an error state
    val errorResponse = """
      {
        "recommendedTool": "tapOnElementByText",
        "recommendedArgs": {"text": "Retry"},
        "reasoning": "Network error dialog shown",
        "screenSummary": "Error: Something went wrong",
        "confidence": "MEDIUM",
        "screenState": "ERROR_STATE",
        "recoveryAction": {"type": "HandleError", "strategy": "tap retry button"}
      }
    """.trimIndent()

    val mockSamplingSource = MockSamplingSource(textResponses = listOf(errorResponse))
    val analyzer = InnerLoopScreenAnalyzer(
      samplingSource = mockSamplingSource,
      model = TrailblazeLlmModels.GPT_4O_MINI,
    )

    // When
    val result = analyzer.analyze(
      context = RecommendationContext(objective = "Load content"),
      screenState = createMockScreenState(),
      availableTools = createMockTools(),
    )

    // Then
    assertEquals(ExceptionalScreenState.ERROR_STATE, result.screenState)
    assertTrue(result.recoveryAction is RecoveryAction.HandleError)
    assertEquals("tap retry button", (result.recoveryAction as RecoveryAction.HandleError).strategy)
  }

  @Test
  fun `analyze detects login required state`() = runBlocking {
    // Given: LLM detects a login wall
    val loginResponse = """
      {
        "recommendedTool": "wait",
        "recommendedArgs": {},
        "reasoning": "Unexpected login wall blocking progress",
        "screenSummary": "Sign in to continue page",
        "confidence": "HIGH",
        "screenState": "LOGIN_REQUIRED",
        "recoveryAction": {"type": "RequiresLogin", "loginRequired": true}
      }
    """.trimIndent()

    val mockSamplingSource = MockSamplingSource(textResponses = listOf(loginResponse))
    val analyzer = InnerLoopScreenAnalyzer(
      samplingSource = mockSamplingSource,
      model = TrailblazeLlmModels.GPT_4O_MINI,
    )

    // When
    val result = analyzer.analyze(
      context = RecommendationContext(objective = "View profile"),
      screenState = createMockScreenState(),
      availableTools = createMockTools(),
    )

    // Then
    assertEquals(ExceptionalScreenState.LOGIN_REQUIRED, result.screenState)
    assertTrue(result.recoveryAction is RecoveryAction.RequiresLogin)
    assertTrue((result.recoveryAction as RecoveryAction.RequiresLogin).loginRequired)
  }

  @Test
  fun `analyze handles normal screen state`() = runBlocking {
    // Given: LLM indicates normal state (no screenState field)
    val normalResponse = """
      {
        "recommendedTool": "tapOnElementByNodeId",
        "recommendedArgs": {"nodeId": 10},
        "reasoning": "Tap the login button to proceed",
        "screenSummary": "Login screen with form",
        "confidence": "HIGH"
      }
    """.trimIndent()

    val mockSamplingSource = MockSamplingSource(textResponses = listOf(normalResponse))
    val analyzer = InnerLoopScreenAnalyzer(
      samplingSource = mockSamplingSource,
      model = TrailblazeLlmModels.GPT_4O_MINI,
    )

    // When
    val result = analyzer.analyze(
      context = RecommendationContext(objective = "Log in"),
      screenState = createMockScreenState(),
      availableTools = createMockTools(),
    )

    // Then: Default should be NORMAL with no recovery action
    assertEquals(ExceptionalScreenState.NORMAL, result.screenState)
    assertEquals(null, result.recoveryAction)
  }

  @Test
  fun `analyze parses recovery action from JSON string`() = runBlocking {
    // Given: LLM provides recovery action as a JSON-encoded string
    val response = """
      {
        "recommendedTool": "pressBack",
        "recommendedArgs": {},
        "reasoning": "Dismiss keyboard first",
        "screenSummary": "Keyboard visible covering content",
        "confidence": "HIGH",
        "screenState": "KEYBOARD_VISIBLE",
        "recoveryAction": "{\"type\": \"DismissKeyboard\", \"dismissMethod\": \"tap outside\"}"
      }
    """.trimIndent()

    val mockSamplingSource = MockSamplingSource(textResponses = listOf(response))
    val analyzer = InnerLoopScreenAnalyzer(
      samplingSource = mockSamplingSource,
      model = TrailblazeLlmModels.GPT_4O_MINI,
    )

    // When
    val result = analyzer.analyze(
      context = RecommendationContext(objective = "Fill form"),
      screenState = createMockScreenState(),
      availableTools = createMockTools(),
    )

    // Then
    assertEquals(ExceptionalScreenState.KEYBOARD_VISIBLE, result.screenState)
    assertTrue(result.recoveryAction is RecoveryAction.DismissKeyboard)
    assertEquals("tap outside", (result.recoveryAction as RecoveryAction.DismissKeyboard).dismissMethod)
  }

  @Test
  fun `analyze handles system overlay state`() = runBlocking {
    // Given: LLM detects a system overlay
    val overlayResponse = """
      {
        "recommendedTool": "pressBack",
        "recommendedArgs": {},
        "reasoning": "System notification blocking view",
        "screenSummary": "Low battery warning overlay visible",
        "confidence": "HIGH",
        "screenState": "SYSTEM_OVERLAY",
        "recoveryAction": {"type": "DismissOverlay", "dismissMethod": "swipe down"}
      }
    """.trimIndent()

    val mockSamplingSource = MockSamplingSource(textResponses = listOf(overlayResponse))
    val analyzer = InnerLoopScreenAnalyzer(
      samplingSource = mockSamplingSource,
      model = TrailblazeLlmModels.GPT_4O_MINI,
    )

    // When
    val result = analyzer.analyze(
      context = RecommendationContext(objective = "Continue task"),
      screenState = createMockScreenState(),
      availableTools = createMockTools(),
    )

    // Then
    assertEquals(ExceptionalScreenState.SYSTEM_OVERLAY, result.screenState)
    assertTrue(result.recoveryAction is RecoveryAction.DismissOverlay)
    assertEquals("swipe down", (result.recoveryAction as RecoveryAction.DismissOverlay).dismissMethod)
  }

  @Test
  fun `analyze handles app not responding state`() = runBlocking {
    // Given: LLM detects ANR dialog
    val anrResponse = """
      {
        "recommendedTool": "wait",
        "recommendedArgs": {},
        "reasoning": "App not responding dialog shown",
        "screenSummary": "ANR dialog: App isn't responding",
        "confidence": "HIGH",
        "screenState": "APP_NOT_RESPONDING",
        "recoveryAction": {"type": "RestartApp", "packageId": "com.example.app"}
      }
    """.trimIndent()

    val mockSamplingSource = MockSamplingSource(textResponses = listOf(anrResponse))
    val analyzer = InnerLoopScreenAnalyzer(
      samplingSource = mockSamplingSource,
      model = TrailblazeLlmModels.GPT_4O_MINI,
    )

    // When
    val result = analyzer.analyze(
      context = RecommendationContext(objective = "Use app"),
      screenState = createMockScreenState(),
      availableTools = createMockTools(),
    )

    // Then
    assertEquals(ExceptionalScreenState.APP_NOT_RESPONDING, result.screenState)
    assertTrue(result.recoveryAction is RecoveryAction.RestartApp)
    assertEquals("com.example.app", (result.recoveryAction as RecoveryAction.RestartApp).packageId)
  }

  @Test
  fun `analyze handles unknown screen state gracefully`() = runBlocking {
    // Given: LLM provides invalid screen state
    val invalidStateResponse = """
      {
        "recommendedTool": "tap",
        "recommendedArgs": {},
        "reasoning": "Proceed with action",
        "screenSummary": "Normal screen",
        "confidence": "HIGH",
        "screenState": "INVALID_STATE"
      }
    """.trimIndent()

    val mockSamplingSource = MockSamplingSource(textResponses = listOf(invalidStateResponse))
    val analyzer = InnerLoopScreenAnalyzer(
      samplingSource = mockSamplingSource,
      model = TrailblazeLlmModels.GPT_4O_MINI,
    )

    // When
    val result = analyzer.analyze(
      context = RecommendationContext(objective = "Test"),
      screenState = createMockScreenState(),
      availableTools = createMockTools(),
    )

    // Then: Invalid state should default to NORMAL
    assertEquals(ExceptionalScreenState.NORMAL, result.screenState)
    assertEquals(null, result.recoveryAction)
  }

  @Test
  fun `analyze handles malformed recovery action gracefully`() = runBlocking {
    // Given: LLM provides invalid recovery action
    val malformedRecoveryResponse = """
      {
        "recommendedTool": "tap",
        "recommendedArgs": {},
        "reasoning": "Dismiss popup",
        "screenSummary": "Popup visible",
        "confidence": "HIGH",
        "screenState": "POPUP_DIALOG",
        "recoveryAction": "not valid json"
      }
    """.trimIndent()

    val mockSamplingSource = MockSamplingSource(textResponses = listOf(malformedRecoveryResponse))
    val analyzer = InnerLoopScreenAnalyzer(
      samplingSource = mockSamplingSource,
      model = TrailblazeLlmModels.GPT_4O_MINI,
    )

    // When
    val result = analyzer.analyze(
      context = RecommendationContext(objective = "Test"),
      screenState = createMockScreenState(),
      availableTools = createMockTools(),
    )

    // Then: Should detect state but recovery action is null
    assertEquals(ExceptionalScreenState.POPUP_DIALOG, result.screenState)
    assertEquals(null, result.recoveryAction)
  }

  // ==========================================================================
  // Test Helpers
  // ==========================================================================

  /** Creates a list of mock tools for testing - required for the analyzer to work */
  private fun createMockTools(): List<TrailblazeToolDescriptor> = listOf(
    TrailblazeToolDescriptor(
      name = "tapOnElementByNodeId",
      description = "Tap on an element by its node ID",
      requiredParameters = listOf(
        TrailblazeToolParameterDescriptor(name = "nodeId", type = "Int", description = "Node ID to tap")
      ),
    ),
    TrailblazeToolDescriptor(
      name = "tapOnElementByText",
      description = "Tap on an element by its text",
      requiredParameters = listOf(
        TrailblazeToolParameterDescriptor(name = "text", type = "String", description = "Text to find and tap")
      ),
    ),
    TrailblazeToolDescriptor(
      name = "pressBack",
      description = "Press the back button",
    ),
    TrailblazeToolDescriptor(
      name = "wait",
      description = "Wait for a specified time",
      optionalParameters = listOf(
        TrailblazeToolParameterDescriptor(name = "seconds", type = "Int", description = "Seconds to wait")
      ),
    ),
  )

  private fun createMockScreenState(): ScreenState = object : ScreenState {
    override val screenshotBytes: ByteArray? = null
    override val annotatedScreenshotBytes: ByteArray? = null
    override val deviceWidth: Int = 1080
    override val deviceHeight: Int = 2340
    override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode(
      nodeId = 1,
      className = "FrameLayout",
      children = listOf(
        ViewHierarchyTreeNode(
          nodeId = 2,
          text = "Login",
          className = "Button",
          clickable = true,
          centerPoint = "540,1200",
          dimensions = "200x60",
        ),
        ViewHierarchyTreeNode(
          nodeId = 3,
          text = "Email",
          className = "EditText",
          focusable = true,
          centerPoint = "540,800",
          dimensions = "400x60",
        ),
      ),
    )
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
  }

  /**
   * Mock SamplingSource that returns predetermined responses.
   *
   * Parses JSON text responses and converts them to ToolCall results for the
   * InnerLoopScreenAnalyzer which now uses sampleToolCallWithKoogTools.
   */
  private class MockSamplingSource(
    private val textResponses: List<String> = emptyList(),
    private val errorMessage: String? = null,
  ) : SamplingSource {
    private var responseIndex = 0

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun sampleText(
      systemPrompt: String,
      userMessage: String,
      screenshotBytes: ByteArray?,
      maxTokens: Int,
      traceId: TraceId?,
      screenContext: ScreenContext?,
    ): SamplingResult {
      if (errorMessage != null) {
        return SamplingResult.Error(errorMessage)
      }
      if (responseIndex >= textResponses.size) {
        return SamplingResult.Error("No more mock responses")
      }
      return SamplingResult.Text(textResponses[responseIndex++])
    }

    override suspend fun sampleToolCall(
      systemPrompt: String,
      userMessage: String,
      tools: List<TrailblazeToolDescriptor>,
      screenshotBytes: ByteArray?,
      maxTokens: Int,
      traceId: TraceId?,
      screenContext: ScreenContext?,
    ): SamplingResult {
      if (errorMessage != null) {
        return SamplingResult.Error(errorMessage)
      }
      if (responseIndex >= textResponses.size) {
        return SamplingResult.Error("No more mock responses")
      }

      val jsonText = textResponses[responseIndex++]
      return try {
        val jsonObject = json.decodeFromString<JsonObject>(jsonText)
        val toolName = jsonObject["recommendedTool"]?.jsonPrimitive?.content
          ?: "wait"

        // Build arguments: copy all fields from JSON, flattening recommendedArgs to top level
        // The analyzer expects all fields in the arguments, then separates action params from analysis metadata
        val args = buildJsonObject {
          // First, flatten recommendedArgs to top level (these are the action parameters)
          val recommendedArgs = jsonObject["recommendedArgs"]
          if (recommendedArgs is JsonObject) {
            recommendedArgs.forEach { (key, value) ->
              put(key, value)
            }
          }
          
          // Then copy remaining fields (analysis metadata), skipping recommendedTool and recommendedArgs
          jsonObject.forEach { (key, value) ->
            if (key != "recommendedTool" && key != "recommendedArgs") {
              put(key, value)
            }
          }
        }

        SamplingResult.ToolCall(toolName = toolName, arguments = args)
      } catch (_: Exception) {
        SamplingResult.Text(jsonText) // Fallback to text if JSON parsing fails
      }
    }

    override fun isAvailable(): Boolean = true
    override fun description(): String = "MockSamplingSource"
  }
}
