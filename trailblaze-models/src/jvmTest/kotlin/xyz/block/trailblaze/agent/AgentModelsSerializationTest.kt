package xyz.block.trailblaze.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Serialization roundtrip tests for agent data models.
 *
 * Ensures all models can be serialized to JSON and deserialized back
 * without data loss, which is critical for MCP transport.
 */
class AgentModelsSerializationTest {

  private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
  }

  @Test
  fun `ScreenAnalysis serialization roundtrip`() {
    val original = ScreenAnalysis(
      recommendedTool = "tap",
      recommendedArgs = buildJsonObject {
        put("elementId", "login-button")
        put("x", 150)
        put("y", 300)
      },
      reasoning = "The login button is visible and ready to be tapped",
      screenSummary = "Login screen with email/password fields and login button",
      progressIndicators = listOf("Email field filled", "Password field filled"),
      potentialBlockers = listOf("Keyboard may be covering button"),
      alternativeApproaches = listOf("Scroll down to reveal button", "Dismiss keyboard first"),
      confidence = Confidence.HIGH,
      objectiveAppearsAchieved = false,
      objectiveAppearsImpossible = false,
    )

    val serialized = json.encodeToString(original)
    val deserialized = json.decodeFromString<ScreenAnalysis>(serialized)

    assertEquals(original, deserialized)
  }

  @Test
  fun `ScreenAnalysis with minimal fields serialization roundtrip`() {
    val original = ScreenAnalysis(
      recommendedTool = "wait",
      recommendedArgs = buildJsonObject { },
      reasoning = "Screen is loading",
      screenSummary = "Loading spinner visible",
      confidence = Confidence.LOW,
    )

    val serialized = json.encodeToString(original)
    val deserialized = json.decodeFromString<ScreenAnalysis>(serialized)

    assertEquals(original, deserialized)
    assertEquals(emptyList<String>(), deserialized.progressIndicators)
    assertEquals(emptyList<String>(), deserialized.potentialBlockers)
    assertEquals(emptyList<String>(), deserialized.alternativeApproaches)
    assertEquals(false, deserialized.objectiveAppearsAchieved)
    assertEquals(false, deserialized.objectiveAppearsImpossible)
  }

  @Test
  fun `RecommendationContext serialization roundtrip`() {
    val original = RecommendationContext(
      objective = "Log in with test@example.com",
      progressSummary = "Navigated to login screen",
      hint = "Password field is next",
      attemptNumber = 2,
    )

    val serialized = json.encodeToString(original)
    val deserialized = json.decodeFromString<RecommendationContext>(serialized)

    assertEquals(original, deserialized)
  }

  @Test
  fun `RecommendationContext with defaults serialization roundtrip`() {
    val original = RecommendationContext(
      objective = "Open the settings page",
    )

    val serialized = json.encodeToString(original)
    val deserialized = json.decodeFromString<RecommendationContext>(serialized)

    assertEquals(original, deserialized)
    assertEquals(null, deserialized.progressSummary)
    assertEquals(null, deserialized.hint)
    assertEquals(1, deserialized.attemptNumber)
  }

  @Test
  fun `ExecutionResult Success serialization roundtrip`() {
    val original: ExecutionResult = ExecutionResult.Success(
      screenSummaryAfter = "Home screen with user profile visible",
      durationMs = 1500,
    )

    val serialized = json.encodeToString(original)
    val deserialized = json.decodeFromString<ExecutionResult>(serialized)

    assertEquals(original, deserialized)
  }

  @Test
  fun `ExecutionResult Failure serialization roundtrip`() {
    val original: ExecutionResult = ExecutionResult.Failure(
      error = "Element not found: login-button",
      recoverable = true,
    )

    val serialized = json.encodeToString(original)
    val deserialized = json.decodeFromString<ExecutionResult>(serialized)

    assertEquals(original, deserialized)
  }

  @Test
  fun `Confidence enum serialization roundtrip`() {
    for (confidence in Confidence.entries) {
      val serialized = json.encodeToString(confidence)
      val deserialized = json.decodeFromString<Confidence>(serialized)
      assertEquals(confidence, deserialized)
    }
  }

  @Test
  fun `ExceptionalScreenState enum serialization roundtrip`() {
    for (context in ExceptionalScreenState.entries) {
      val serialized = json.encodeToString(context)
      val deserialized = json.decodeFromString<ExceptionalScreenState>(serialized)
      assertEquals(context, deserialized)
    }
  }

  @Test
  fun `ScreenAnalysis with screen state serialization roundtrip`() {
    val original = ScreenAnalysis(
      recommendedTool = "pressBack",
      recommendedArgs = buildJsonObject { },
      reasoning = "Popup dialog detected, dismissing to continue",
      screenSummary = "Permission dialog asking for camera access",
      confidence = Confidence.HIGH,
      screenState = ExceptionalScreenState.POPUP_DIALOG,
      recoveryAction = RecoveryAction.DismissPopup("Deny button"),
    )

    val serialized = json.encodeToString(original)
    val deserialized = json.decodeFromString<ScreenAnalysis>(serialized)

    assertEquals(original, deserialized)
    assertEquals(ExceptionalScreenState.POPUP_DIALOG, deserialized.screenState)
    assertEquals("Deny button", (deserialized.recoveryAction as RecoveryAction.DismissPopup).dismissTarget)
  }

  @Test
  fun `ScreenAnalysis with loading state serialization roundtrip`() {
    val original = ScreenAnalysis(
      recommendedTool = "wait",
      recommendedArgs = buildJsonObject { },
      reasoning = "Loading spinner detected, waiting for content",
      screenSummary = "Loading screen with spinner",
      confidence = Confidence.MEDIUM,
      screenState = ExceptionalScreenState.LOADING,
      recoveryAction = RecoveryAction.WaitForLoading(maxWaitSeconds = 15, checkIntervalMs = 1000),
    )

    val serialized = json.encodeToString(original)
    val deserialized = json.decodeFromString<ScreenAnalysis>(serialized)

    assertEquals(original, deserialized)
    assertEquals(ExceptionalScreenState.LOADING, deserialized.screenState)
    val waitAction = deserialized.recoveryAction as RecoveryAction.WaitForLoading
    assertEquals(15, waitAction.maxWaitSeconds)
    assertEquals(1000, waitAction.checkIntervalMs)
  }

  @Test
  fun `RecoveryAction DismissPopup serialization roundtrip`() {
    val original: RecoveryAction = RecoveryAction.DismissPopup("OK button")
    val serialized = json.encodeToString(original)
    val deserialized = json.decodeFromString<RecoveryAction>(serialized)
    assertEquals(original, deserialized)
  }

  @Test
  fun `RecoveryAction SkipAd serialization roundtrip`() {
    val original: RecoveryAction = RecoveryAction.SkipAd("tap X button", waitSeconds = 5)
    val serialized = json.encodeToString(original)
    val deserialized = json.decodeFromString<RecoveryAction>(serialized)
    assertEquals(original, deserialized)
  }

  @Test
  fun `RecoveryAction WaitForLoading serialization roundtrip`() {
    val original: RecoveryAction = RecoveryAction.WaitForLoading(maxWaitSeconds = 30, checkIntervalMs = 250)
    val serialized = json.encodeToString(original)
    val deserialized = json.decodeFromString<RecoveryAction>(serialized)
    assertEquals(original, deserialized)
  }

  @Test
  fun `RecoveryAction HandleError serialization roundtrip`() {
    val original: RecoveryAction = RecoveryAction.HandleError(
      strategy = "tap retry button",
      retryAction = "refetch data",
    )
    val serialized = json.encodeToString(original)
    val deserialized = json.decodeFromString<RecoveryAction>(serialized)
    assertEquals(original, deserialized)
  }

  @Test
  fun `RecoveryAction DismissOverlay serialization roundtrip`() {
    val original: RecoveryAction = RecoveryAction.DismissOverlay("swipe up")
    val serialized = json.encodeToString(original)
    val deserialized = json.decodeFromString<RecoveryAction>(serialized)
    assertEquals(original, deserialized)
  }

  @Test
  fun `RecoveryAction DismissKeyboard serialization roundtrip`() {
    val original: RecoveryAction = RecoveryAction.DismissKeyboard("tap outside")
    val serialized = json.encodeToString(original)
    val deserialized = json.decodeFromString<RecoveryAction>(serialized)
    assertEquals(original, deserialized)
  }

  @Test
  fun `RecoveryAction RequiresLogin serialization roundtrip`() {
    val original: RecoveryAction = RecoveryAction.RequiresLogin(loginRequired = true)
    val serialized = json.encodeToString(original)
    val deserialized = json.decodeFromString<RecoveryAction>(serialized)
    assertEquals(original, deserialized)
  }

  @Test
  fun `RecoveryAction RestartApp serialization roundtrip`() {
    val original: RecoveryAction = RecoveryAction.RestartApp(packageId = "com.example.app")
    val serialized = json.encodeToString(original)
    val deserialized = json.decodeFromString<RecoveryAction>(serialized)
    assertEquals(original, deserialized)
  }

  @Test
  fun `RecoveryAction PressBack serialization roundtrip`() {
    val original: RecoveryAction = RecoveryAction.PressBack(times = 3)
    val serialized = json.encodeToString(original)
    val deserialized = json.decodeFromString<RecoveryAction>(serialized)
    assertEquals(original, deserialized)
  }

  @Test
  fun `ScreenAnalysis default screen state is NORMAL`() {
    val analysis = ScreenAnalysis(
      recommendedTool = "tap",
      recommendedArgs = buildJsonObject { },
      reasoning = "Tapping button",
      screenSummary = "Normal app screen",
      confidence = Confidence.HIGH,
    )
    assertEquals(ExceptionalScreenState.NORMAL, analysis.screenState)
    assertEquals(null, analysis.recoveryAction)
  }
}
