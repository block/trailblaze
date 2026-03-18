package xyz.block.trailblaze.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Classification of the current screen state for exception handling.
 *
 * The screen analyzer detects exceptional states (popups, ads, errors, etc.) that
 * require special handling before continuing toward the objective. This enables
 * robust automation that can recover from common interruptions.
 *
 * Based on Mobile-Agent-v3's exception handling approach (arXiv:2508.15144).
 *
 * Note: Named `ExceptionalScreenState` to avoid collision with `ScreenContext` data class
 * in trailblaze-common which is used for sampling/logging purposes.
 *
 * @see RecoveryAction for suggested recovery strategies
 * @see ScreenAnalysis.screenState
 */
@Serializable
enum class ExceptionalScreenState {
  /** Normal application screen - proceed with objective */
  NORMAL,

  /** Popup dialog (permission request, alert, confirmation, etc.) */
  POPUP_DIALOG,

  /** Interstitial advertisement or promotional content */
  ADVERTISEMENT,

  /** System overlay (notification, low battery warning, incoming call, etc.) */
  SYSTEM_OVERLAY,

  /** Loading state (spinner, skeleton screen, progress indicator) */
  LOADING,

  /** Error state (error message, crash dialog, retry prompt) */
  ERROR_STATE,

  /** Unexpected authentication wall (login required) */
  LOGIN_REQUIRED,

  /** App not responding or frozen state */
  APP_NOT_RESPONDING,

  /** Keyboard is visible and may need dismissal */
  KEYBOARD_VISIBLE,

  /** CAPTCHA or human verification challenge */
  CAPTCHA,

  /** Rate limiting or too many requests error */
  RATE_LIMITED,
}

/**
 * Suggested recovery action for exceptional screen states.
 *
 * When the screen analyzer detects an exceptional state (popup, ad, error, etc.),
 * it provides a recovery action that the outer agent can execute to return to
 * a normal state and continue toward the objective.
 *
 * @see ExceptionalScreenState for the types of exceptional states
 * @see ScreenAnalysis.recoveryAction
 */
@Serializable
sealed interface RecoveryAction {
  /**
   * Dismiss the current popup/dialog by tapping dismiss button or outside.
   *
   * @property dismissTarget Description of what to tap (e.g., "OK button at coordinates 512,600",
   *   "outside dialog", "Cancel button")
   * @property coordinates Optional screen coordinates (x, y) for precise tap location
   */
  @Serializable
  @SerialName("DismissPopup")
  data class DismissPopup(
    val dismissTarget: String = "dismiss button",
    val coordinates: String? = null,
  ) : RecoveryAction

  /**
   * Skip or close an advertisement.
   *
   * @property skipMethod How to skip (e.g., "tap X button", "wait for skip button", "tap close")
   * @property waitSeconds Seconds to wait for skip button to appear (for video ads)
   * @property coordinates Optional screen coordinates for the skip/close button
   */
  @Serializable
  @SerialName("SkipAd")
  data class SkipAd(
    val skipMethod: String = "tap close button",
    val waitSeconds: Int = 0,
    val coordinates: String? = null,
  ) : RecoveryAction

  /**
   * Wait for loading to complete before proceeding.
   *
   * @property maxWaitSeconds Maximum seconds to wait for loading to complete
   * @property checkIntervalMs Interval in milliseconds to re-check screen state
   */
  @Serializable
  @SerialName("WaitForLoading")
  data class WaitForLoading(
    val maxWaitSeconds: Int = 10,
    val checkIntervalMs: Long = 500,
  ) : RecoveryAction

  /**
   * Handle an error state with a specific strategy.
   *
   * @property strategy The recovery strategy (e.g., "tap retry", "go back", "clear and retry")
   * @property retryAction Optional specific action to retry
   * @property retryCoordinates Optional coordinates for retry button tap
   */
  @Serializable
  @SerialName("HandleError")
  data class HandleError(
    val strategy: String,
    val retryAction: String? = null,
    val retryCoordinates: String? = null,
  ) : RecoveryAction

  /**
   * Dismiss system overlay (notification, battery warning, etc.).
   *
   * @property dismissMethod How to dismiss (e.g., "swipe away", "tap dismiss", "press back")
   * @property coordinates Optional screen coordinates for dismiss target
   */
  @Serializable
  @SerialName("DismissOverlay")
  data class DismissOverlay(
    val dismissMethod: String = "swipe away",
    val coordinates: String? = null,
  ) : RecoveryAction

  /**
   * Dismiss the on-screen keyboard.
   *
   * @property dismissMethod How to dismiss (e.g., "press back", "tap outside")
   */
  @Serializable
  @SerialName("DismissKeyboard")
  data class DismissKeyboard(val dismissMethod: String = "press back") : RecoveryAction

  /**
   * Navigate to login screen to handle authentication wall.
   *
   * This is a "blocker" recovery - the agent should record this as a precondition
   * failure rather than attempting to auto-login (security concern).
   *
   * @property loginRequired True if login is required to continue (blocks progress)
   */
  @Serializable
  @SerialName("RequiresLogin")
  data class RequiresLogin(val loginRequired: Boolean = true) : RecoveryAction

  /**
   * Handle CAPTCHA challenge.
   *
   * This is a "blocker" recovery - automated solutions are unreliable.
   * The agent should record this as requiring manual intervention.
   *
   * @property description Description of CAPTCHA type (e.g., "Google reCAPTCHA v2", "SMS verification")
   */
  @Serializable
  @SerialName("HandleCaptcha")
  data class HandleCaptcha(val description: String = "CAPTCHA verification required") : RecoveryAction

  /**
   * Handle rate limiting error.
   *
   * @property waitSeconds Recommended wait time before retrying
   * @property retryStrategy Strategy to recover (e.g., "wait and retry", "use different endpoint")
   */
  @Serializable
  @SerialName("HandleRateLimited")
  data class HandleRateLimited(
    val waitSeconds: Int = 60,
    val retryStrategy: String = "wait and retry",
  ) : RecoveryAction

  /**
   * Force-stop and restart the app due to ANR or frozen state.
   *
   * @property packageId Package ID of the app to restart
   */
  @Serializable
  @SerialName("RestartApp")
  data class RestartApp(val packageId: String) : RecoveryAction

  /**
   * Press back button to escape current state.
   *
   * @property times Number of times to press back
   */
  @Serializable
  @SerialName("PressBack")
  data class PressBack(val times: Int = 1) : RecoveryAction
}

/**
 * Rich analysis result from the inner agent after examining a screen.
 *
 * This is the primary output of the screen analyzer, providing both a recommendation
 * for the next action and contextual information for replanning if needed.
 *
 * @property recommendedTool The name of the tool to execute (e.g., "tap", "input_text")
 * @property recommendedArgs Arguments for the recommended tool as a JSON object
 * @property reasoning Explanation of why this action was recommended
 * @property screenSummary Human-readable description of what's visible on screen
 * @property progressIndicators Signs that progress is being made toward the objective
 * @property potentialBlockers Elements or states that might prevent achieving the objective
 * @property alternativeApproaches Other actions that could be tried if the recommendation fails
 * @property confidence How confident the analyzer is in this recommendation
 * @property objectiveAppearsAchieved True if the objective appears to already be complete
 * @property objectiveAppearsImpossible True if the objective appears impossible from this screen
 */
@Serializable
data class ScreenAnalysis(
  // Primary recommendation
  /** The name of the tool to execute (e.g., "tap", "input_text", "scroll") */
  val recommendedTool: String,
  /** Arguments for the recommended tool as a JSON object */
  val recommendedArgs: JsonObject,
  /** Explanation of why this action was recommended based on the screen state */
  val reasoning: String,

  // Context for replanning
  /** Human-readable description of the current screen state */
  val screenSummary: String,
  /** Signs of progress toward the objective (e.g., "Login button visible", "Form partially filled") */
  val progressIndicators: List<String> = emptyList(),
  /** Elements or states that might block progress (e.g., "Error dialog shown", "Loading spinner") */
  val potentialBlockers: List<String> = emptyList(),
  /** Alternative actions that could be tried if the recommendation fails */
  val alternativeApproaches: List<String> = emptyList(),

  // Confidence signals
  /** How confident the analyzer is in this recommendation */
  val confidence: Confidence,
  /** True if the screen state indicates the objective has been achieved */
  val objectiveAppearsAchieved: Boolean = false,
  /** True if the screen state indicates the objective cannot be achieved */
  val objectiveAppearsImpossible: Boolean = false,

  // Tool management
  /**
   * Suggested tool hint if the analyzer couldn't find an appropriate tool.
   * 
   * When the inner agent determines that none of the provided tools can accomplish
   * the objective, it can suggest which tool set might help:
   * - "NAVIGATION" - needs launchApp, openUrl, scrollUntilVisible
   * - "VERIFICATION" - needs assert tools
   * - "app_login" - needs app-specific login tools
   * - etc.
   *
   * The outer agent can retry with `blaze(goal="...", hint=suggestedHint)`.
   * This keeps the inner agent dumb (it just says what it needs) while the outer
   * agent makes decisions about tool selection.
   */
  val suggestedHint: String? = null,

  // Exception handling (Mobile-Agent-v3 inspired)
  /**
   * Classification of the current screen state for exception handling.
   *
   * When this is not [ExceptionalScreenState.NORMAL], the agent should handle the exceptional
   * state using [recoveryAction] before continuing toward the objective. This enables
   * robust automation that can recover from popups, ads, errors, and other interruptions.
   *
   * Default is [ExceptionalScreenState.NORMAL] for backward compatibility.
   *
   * @see ExceptionalScreenState for all possible states
   * @see recoveryAction for the suggested recovery strategy
   * @see detectionConfidence for the confidence level of this detection
   */
  val screenState: ExceptionalScreenState = ExceptionalScreenState.NORMAL,

  /**
   * Confidence level for the exceptional screen state detection.
   *
   * Indicates how confident the analyzer is that an exceptional state was correctly
   * identified. For example, detecting a permission dialog with high confidence vs.
   * detecting an error state with low confidence.
   *
   * Used by the outer agent to decide whether to trust the recovery action or
   * request additional analysis before attempting recovery.
   *
   * Valid range: 0.0 (no confidence) to 1.0 (complete confidence)
   * Default: 1.0 (high confidence) when [screenState] is [ExceptionalScreenState.NORMAL]
   *
   * @see screenState for the detected exceptional state
   */
  val detectionConfidence: Float = 1.0f,

  /**
   * Suggested recovery action when screen state is not NORMAL.
   *
   * The outer agent should execute this recovery action to return to a normal state
   * before continuing toward the objective. This may involve dismissing dialogs,
   * skipping ads, waiting for loading, or handling errors.
   *
   * Null when [screenState] is [ExceptionalScreenState.NORMAL].
   *
   * @see RecoveryAction for all possible recovery strategies
   * @see detectionConfidence for the confidence in this recovery action
   */
  val recoveryAction: RecoveryAction? = null,
)

/**
 * Confidence level for a screen analysis recommendation.
 *
 * Used by the outer agent to decide whether to execute the recommendation
 * directly or request additional analysis.
 */
@Serializable
enum class Confidence {
  /** Strong match between objective and recommended action. Execute immediately. */
  HIGH,
  /** Reasonable match but some uncertainty. Execute but be ready to retry. */
  MEDIUM,
  /** Low certainty. Consider requesting new analysis with a hint. */
  LOW,
}

/**
 * Raw analysis response deserialized from an LLM tool call's arguments.
 *
 * When the inner agent wraps tools with analysis parameters (via
 * InnerLoopScreenAnalyzer.wrapToolsWithAnalysis),
 * the LLM returns both action parameters (e.g., x, y for tapOnPoint) and analysis metadata
 * (reasoning, confidence, screenState, etc.) in the same tool call arguments.
 *
 * This data class represents the analysis metadata portion. Using
 * `Json { ignoreUnknownKeys = true }` to deserialize, the action parameters are
 * naturally ignored and only the analysis fields are extracted.
 *
 * ## Field type choices
 *
 * - `confidence` and `screenState` are `String?` instead of enum types because LLMs
 *   may return varied casing (e.g., "HIGH", "high", "High"). Conversion to enums
 *   happens in [toScreenAnalysis].
 * - `recoveryAction` is `JsonElement?` because the LLM may return it as a JSON object
 *   OR a JSON string containing JSON. The sealed interface deserialization handles
 *   the object case; the string case needs unwrapping first.
 * - List fields use `List<String>` with defaults. If the LLM sends a single string
 *   instead of an array, `coerceInputValues` falls back to the default empty list.
 *
 * @see ScreenAnalysis for the strongly-typed analysis result used by the outer agent
 */
@Serializable
data class ToolCallAnalysisResponse(
  val reasoning: String = "No reasoning provided",
  val screenSummary: String = "Unknown",
  val confidence: String = "MEDIUM",
  val progressIndicators: List<String> = emptyList(),
  val potentialBlockers: List<String> = emptyList(),
  val alternativeApproaches: List<String> = emptyList(),
  val objectiveAppearsAchieved: Boolean = false,
  val objectiveAppearsImpossible: Boolean = false,
  val suggestedHint: String? = null,
  val screenState: String? = null,
  val recoveryAction: JsonElement? = null,
  val detectionConfidence: Float = 1.0f,
) {
  companion object {
    /**
     * The set of field names in this response, derived from the serializer descriptor.
     *
     * Used to split tool call arguments into analysis metadata vs action parameters.
     * This is the **single source of truth** for which fields are analysis fields —
     * no more maintaining a separate `Set<String>` that can drift out of sync.
     */
    val fieldNames: Set<String> by lazy {
      serializer().descriptor.let { desc ->
        (0 until desc.elementsCount).map { desc.getElementName(it) }.toSet()
      }
    }

    /** Lenient JSON parser for LLM responses. */
    private val lenientJson = Json {
      ignoreUnknownKeys = true
      coerceInputValues = true
      isLenient = true
    }

    /** JSON parser for recovery action deserialization. */
    private val recoveryActionJson = Json {
      ignoreUnknownKeys = true
    }

    /**
     * Deserializes analysis metadata from a tool call's full arguments.
     *
     * Action-specific parameters (x, y, nodeId, etc.) are automatically ignored
     * via `ignoreUnknownKeys = true`. Only analysis fields are extracted.
     *
     * @param arguments The full tool call arguments (action params + analysis metadata)
     * @return The deserialized analysis response, with defaults for any missing fields
     */
    fun fromToolCallArguments(arguments: JsonObject): ToolCallAnalysisResponse {
      return try {
        lenientJson.decodeFromJsonElement(serializer(), arguments)
      } catch (_: Exception) {
        // If deserialization fails completely, return defaults
        ToolCallAnalysisResponse()
      }
    }
  }

  /**
   * Converts this raw response into a strongly-typed [ScreenAnalysis].
   *
   * Handles lenient enum parsing (case-insensitive), recovery action deserialization,
   * and separation of action arguments from analysis metadata.
   *
   * @param toolName The name of the tool that was called (becomes [ScreenAnalysis.recommendedTool])
   * @param allArgs The full tool call arguments (used to extract action-only params)
   * @return A fully populated [ScreenAnalysis]
   */
  fun toScreenAnalysis(toolName: String, allArgs: JsonObject): ScreenAnalysis {
    // Action args = everything that's NOT an analysis field
    val actionArgs = buildJsonObject {
      allArgs.forEach { (key, value) ->
        if (key !in fieldNames) {
          put(key, value)
        }
      }
    }

    return ScreenAnalysis(
      recommendedTool = toolName,
      recommendedArgs = actionArgs,
      reasoning = reasoning,
      screenSummary = screenSummary,
      confidence = Confidence.entries.find { it.name.equals(confidence, ignoreCase = true) }
        ?: Confidence.MEDIUM,
      progressIndicators = progressIndicators,
      potentialBlockers = potentialBlockers,
      alternativeApproaches = alternativeApproaches,
      objectiveAppearsAchieved = objectiveAppearsAchieved,
      objectiveAppearsImpossible = objectiveAppearsImpossible,
      suggestedHint = suggestedHint,
      screenState = screenState?.let { state ->
        ExceptionalScreenState.entries.find { it.name.equals(state, ignoreCase = true) }
      } ?: ExceptionalScreenState.NORMAL,
      detectionConfidence = detectionConfidence.coerceIn(0.0f, 1.0f),
      recoveryAction = parseRecoveryAction(recoveryAction),
    )
  }

  /**
   * Parses a recovery action from a JsonElement.
   *
   * Handles both direct JSON objects and JSON strings containing JSON.
   * Uses kotlinx.serialization with the @SerialName annotations on RecoveryAction subtypes.
   */
  private fun parseRecoveryAction(element: JsonElement?): RecoveryAction? {
    if (element == null) return null

    val jsonObject = when (element) {
      is JsonObject -> element
      is JsonPrimitive -> {
        // LLM may send recovery action as a JSON string containing JSON
        try {
          Json.decodeFromString<JsonObject>(element.content)
        } catch (_: Exception) {
          return null
        }
      }
      else -> return null
    }

    return try {
      // RecoveryAction subtypes have @SerialName matching the "type" discriminator
      recoveryActionJson.decodeFromJsonElement<RecoveryAction>(jsonObject)
    } catch (_: Exception) {
      null
    }
  }
}
