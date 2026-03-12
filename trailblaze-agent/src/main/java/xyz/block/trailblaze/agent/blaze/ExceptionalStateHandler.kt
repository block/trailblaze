package xyz.block.trailblaze.agent.blaze

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.agent.BlazeState
import xyz.block.trailblaze.agent.ExecutionResult
import xyz.block.trailblaze.agent.ExceptionalScreenState
import xyz.block.trailblaze.agent.RecoveryAction
import xyz.block.trailblaze.agent.ScreenAnalysis
import xyz.block.trailblaze.agent.UiActionExecutor
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.CoreTools

/**
 * Handles exceptional screen states (popups, ads, errors, etc.) during blaze exploration.
 *
 * This class encapsulates all recovery logic for non-normal screen states detected by the
 * Screen Analyzer. Each handler attempts to return the app to a normal, actionable state.
 *
 * Extracted from [BlazeGoalPlanner] for modularity — the planner focuses on goal pursuit
 * while this class handles the "obstacle course" of real-world mobile UI.
 *
 * @param executor UI action executor for performing recovery actions on the device
 */
internal class ExceptionalStateHandler(
  private val executor: UiActionExecutor,
) {

  /**
   * Checks the view hierarchy for known blocking dialog patterns and auto-dismisses them
   * before the LLM sees the screen. This saves LLM iterations on predictable popups.
   *
   * Currently detects:
   * - "Discard this event?" / "Discard changes?" dialogs → clicks "Keep editing" / positive button
   *
   * @return true if a dialog was dismissed, false otherwise
   */
  suspend fun autoDismissBlockingDialog(
    screenState: ScreenState,
    traceId: TraceId,
  ): Boolean {
    val allNodes = screenState.viewHierarchy.aggregate()

    // Pattern 1: "Discard this event?" or similar discard confirmation dialogs.
    // These have android:id/message with "Discard" text and android:id/button1 ("Keep editing").
    val discardMessage = allNodes.find {
      it.resourceId == "android:id/message" &&
        it.text?.contains("Discard", ignoreCase = true) == true
    }
    if (discardMessage != null) {
      // Click "Keep editing" (button1) to preserve form data
      val keepEditingButton = allNodes.find {
        it.resourceId == "android:id/button1" && it.centerPoint != null
      }
      if (keepEditingButton != null) {
        val coords = keepEditingButton.centerPoint!!.split(",")
        if (coords.size == 2) {
          val x = coords[0].trim().toDoubleOrNull()?.toInt()
          val y = coords[1].trim().toDoubleOrNull()?.toInt()
          if (x != null && y != null) {
            executor.execute(
              toolName = CoreTools.TAP_ON_POINT,
              args = buildJsonObject {
                put("x", x)
                put("y", y)
              },
              traceId = traceId,
            )
            return true
          }
        }
      }
      // Fallback: press ESCAPE to dismiss the dialog
      executor.execute(
        toolName = CoreTools.PRESS_KEY,
        args = buildJsonObject { put("keyCode", "111") },
        traceId = traceId,
      )
      return true
    }

    return false
  }

  /**
   * Handles exceptional screen states (popups, ads, errors, etc.).
   *
   * When the screen analyzer detects an exceptional state, this method executes
   * the appropriate recovery action to return to a normal state.
   */
  suspend fun handleExceptionalState(
    state: BlazeState,
    analysis: ScreenAnalysis,
    traceId: TraceId,
  ): BlazeState {
    val recoveryAction = analysis.recoveryAction

    return when (analysis.screenState) {
      ExceptionalScreenState.NORMAL -> state // Should not reach here, but handle gracefully

      ExceptionalScreenState.POPUP_DIALOG -> {
        dismissPopupAndRetry(state, recoveryAction as? RecoveryAction.DismissPopup, traceId)
      }

      ExceptionalScreenState.ADVERTISEMENT -> {
        skipAdAndContinue(state, recoveryAction as? RecoveryAction.SkipAd, traceId)
      }

      ExceptionalScreenState.LOADING -> {
        waitForLoadingAndReanalyze(state, recoveryAction as? RecoveryAction.WaitForLoading)
      }

      ExceptionalScreenState.ERROR_STATE -> {
        handleErrorWithRecovery(state, analysis, recoveryAction as? RecoveryAction.HandleError, traceId)
      }

      ExceptionalScreenState.LOGIN_REQUIRED -> {
        recordBlockerAndFail(state, "Unexpected login wall - authentication required to continue")
      }

      ExceptionalScreenState.SYSTEM_OVERLAY -> {
        dismissOverlayAndRetry(state, recoveryAction as? RecoveryAction.DismissOverlay, traceId)
      }

      ExceptionalScreenState.APP_NOT_RESPONDING -> {
        handleAppNotResponding(state, recoveryAction as? RecoveryAction.RestartApp)
      }

      ExceptionalScreenState.KEYBOARD_VISIBLE -> {
        dismissKeyboardAndContinue(state, recoveryAction as? RecoveryAction.DismissKeyboard, traceId)
      }

      ExceptionalScreenState.CAPTCHA -> {
        recordBlockerAndFail(state, "CAPTCHA detected - manual intervention required")
      }

      ExceptionalScreenState.RATE_LIMITED -> {
        recordBlockerAndFail(state, "Rate limited - cannot continue without delay")
      }
    }
  }

  /**
   * Dismisses a popup dialog and continues exploration.
   *
   * Uses ESCAPE key instead of NAVIGATE_BACK because back navigation on form screens
   * (e.g., Calendar event creation) triggers "Discard changes?" dialogs, creating
   * cascading popup problems. ESCAPE dismisses dialogs/popups without side effects.
   *
   * If the recovery action includes specific coordinates (e.g., a "Keep editing" button),
   * we tap those coordinates directly for a more targeted dismissal.
   */
  private suspend fun dismissPopupAndRetry(
    state: BlazeState,
    recovery: RecoveryAction.DismissPopup?,
    traceId: TraceId,
  ): BlazeState {
    val dismissTarget = recovery?.dismissTarget ?: "dismiss button"

    // If recovery includes specific coordinates, tap them (e.g., "Keep editing" button)
    val result = if (recovery?.coordinates != null) {
      val coords = recovery.coordinates!!.split(",").map { it.trim() }
      val x = coords.getOrNull(0)?.toDoubleOrNull()?.toInt()
      val y = coords.getOrNull(1)?.toDoubleOrNull()?.toInt()
      if (coords.size == 2 && x != null && y != null) {
        executor.execute(
          toolName = CoreTools.TAP_ON_POINT,
          args = buildJsonObject {
            put("x", x)
            put("y", y)
          },
          traceId = traceId,
        )
      } else {
        // Fallback to ESCAPE
        executor.execute(
          toolName = CoreTools.PRESS_KEY,
          args = buildJsonObject { put("keyCode", "111") },
          traceId = traceId,
        )
      }
    } else {
      // Use ESCAPE key — dismisses popups/dialogs without triggering form discard
      executor.execute(
        toolName = CoreTools.PRESS_KEY,
        args = buildJsonObject { put("keyCode", "111") },
        traceId = traceId,
      )
    }

    return when (result) {
      is ExecutionResult.Success -> state.copy(
        screenSummary = "Dismissed popup ($dismissTarget), continuing",
      )
      is ExecutionResult.Failure -> state.copy(
        reflectionNotes = state.reflectionNotes +
          "Failed to dismiss popup ($dismissTarget): ${result.error}",
      )
    }
  }

  /**
   * Skips an advertisement and continues exploration.
   */
  private suspend fun skipAdAndContinue(
    state: BlazeState,
    recovery: RecoveryAction.SkipAd?,
    traceId: TraceId,
  ): BlazeState {
    val waitSeconds = recovery?.waitSeconds ?: 0

    // Wait for skip button if needed (for video ads)
    if (waitSeconds > 0) {
      delay(waitSeconds * 1000L)
    }

    // Try to close the ad - use platform-appropriate back navigation
    val result = executor.execute(
      toolName = CoreTools.NAVIGATE_BACK,
      args = JsonObject(emptyMap()),
      traceId = traceId,
    )

    return when (result) {
      is ExecutionResult.Success -> state.copy(
        screenSummary = "Skipped advertisement, continuing",
      )
      is ExecutionResult.Failure -> state.copy(
        reflectionNotes = state.reflectionNotes +
          "Failed to skip advertisement: ${result.error}",
      )
    }
  }

  /**
   * Waits for a loading state to complete and re-analyzes.
   */
  private suspend fun waitForLoadingAndReanalyze(
    state: BlazeState,
    recovery: RecoveryAction.WaitForLoading?,
  ): BlazeState {
    val maxWaitMs = (recovery?.maxWaitSeconds ?: 10) * 1000L
    val checkInterval = recovery?.checkIntervalMs ?: 500L
    var waitedMs = 0L

    // Wait with periodic checks — if the device becomes unreachable, stop early.
    // We don't re-analyze for loading indicators here; the caller's next iteration
    // will capture a fresh screen and decide whether loading is still in progress.
    while (waitedMs < maxWaitMs) {
      delay(checkInterval)
      waitedMs += checkInterval

      // Re-capture screen to verify device is still reachable
      val screenState = executor.captureScreenState()
      if (screenState == null) {
        // Device unreachable — stop waiting, let caller handle
        break
      }
    }

    return state.copy(
      screenSummary = "Waited ${waitedMs}ms for loading, continuing",
      reflectionNotes = state.reflectionNotes +
        "Waited ${waitedMs}ms for loading to complete",
    )
  }

  /**
   * Handles an error state with recovery strategy.
   */
  private suspend fun handleErrorWithRecovery(
    state: BlazeState,
    analysis: ScreenAnalysis,
    recovery: RecoveryAction.HandleError?,
    traceId: TraceId,
  ): BlazeState {
    val strategy = recovery?.strategy ?: "press back"

    return when {
      strategy.contains("retry", ignoreCase = true) -> {
        // Try to find and tap a retry-like button on screen
        val screenState = executor.captureScreenState()
        val allNodes = screenState?.viewHierarchy?.aggregate().orEmpty()
        val retryLabels = listOf("Retry", "Try Again", "Try again")
        val retryButton = allNodes.find { node ->
          node.text != null && retryLabels.any { label ->
            node.text!!.contains(label, ignoreCase = true)
          } && node.centerPoint != null
        }

        if (retryButton != null) {
          val coords = retryButton.centerPoint!!.split(",").map { it.trim() }
          val x = coords.getOrNull(0)?.toDoubleOrNull()?.toInt()
          val y = coords.getOrNull(1)?.toDoubleOrNull()?.toInt()
          val result = if (coords.size == 2 && x != null && y != null) {
            executor.execute(
              toolName = CoreTools.TAP_ON_POINT,
              args = buildJsonObject {
                put("x", x)
                put("y", y)
              },
              traceId = traceId,
            )
          } else {
            executor.execute(
              toolName = CoreTools.NAVIGATE_BACK,
              args = JsonObject(emptyMap()),
              traceId = traceId,
            )
          }
          when (result) {
            is ExecutionResult.Success -> state.copy(
              screenSummary = "Tapped retry button ('${retryButton.text}'), continuing",
            )
            is ExecutionResult.Failure -> recordBlockerAndFail(
              state,
              "Failed to tap retry button: ${result.error}",
            )
          }
        } else {
          // No retry button found — fall back to navigate back
          val result = executor.execute(
            toolName = CoreTools.NAVIGATE_BACK,
            args = JsonObject(emptyMap()),
            traceId = traceId,
          )
          when (result) {
            is ExecutionResult.Success -> state.copy(
              screenSummary = "No retry button found, navigated back from error state",
            )
            is ExecutionResult.Failure -> recordBlockerAndFail(
              state,
              "Failed to recover from error state: ${result.error}",
            )
          }
        }
      }
      strategy.contains("back", ignoreCase = true) -> {
        val result = executor.execute(
          toolName = CoreTools.NAVIGATE_BACK,
          args = JsonObject(emptyMap()),
          traceId = traceId,
        )
        when (result) {
          is ExecutionResult.Success -> state.copy(
            screenSummary = "Navigated back from error state",
          )
          is ExecutionResult.Failure -> recordBlockerAndFail(
            state,
            "Failed to navigate back from error state: ${result.error}",
          )
        }
      }
      else -> {
        // Unknown strategy - record as potential blocker
        state.copy(
          reflectionNotes = state.reflectionNotes +
            "Error state detected: ${analysis.screenSummary}. Strategy: $strategy",
        )
      }
    }
  }

  /**
   * Dismisses a system overlay (notification, etc.) and continues.
   *
   * Uses ESCAPE key for safe dismissal — avoids NAVIGATE_BACK which can trigger
   * form discard dialogs or unintended back navigation.
   */
  private suspend fun dismissOverlayAndRetry(
    state: BlazeState,
    recovery: RecoveryAction.DismissOverlay?,
    traceId: TraceId,
  ): BlazeState {
    val dismissMethod = recovery?.dismissMethod ?: "ESCAPE key"

    // Use ESCAPE key to dismiss overlays — safe, no side effects
    val result = executor.execute(
      toolName = CoreTools.PRESS_KEY,
      args = buildJsonObject { put("keyCode", "111") },
      traceId = traceId,
    )

    return when (result) {
      is ExecutionResult.Success -> state.copy(
        screenSummary = "Dismissed system overlay ($dismissMethod), continuing",
      )
      is ExecutionResult.Failure -> state.copy(
        reflectionNotes = state.reflectionNotes +
          "Failed to dismiss system overlay: ${result.error}",
      )
    }
  }

  /**
   * Handles app not responding by attempting to restart.
   */
  private suspend fun handleAppNotResponding(
    state: BlazeState,
    recovery: RecoveryAction.RestartApp?,
  ): BlazeState {
    // For ANR, we mark as stuck - automated restart could lose important state
    // A more sophisticated implementation could use recovery.packageId to restart
    return recordBlockerAndFail(
      state,
      "App not responding (ANR). Manual intervention may be required. " +
        "Package: ${recovery?.packageId ?: "unknown"}",
    )
  }

  /**
   * Dismisses the on-screen keyboard and continues.
   *
   * Uses KEYCODE_ESCAPE (111) which is the safest keyboard dismissal method:
   * - Doesn't trigger back navigation (unlike BACK/4)
   * - Doesn't submit forms (unlike ENTER/66)
   * - Doesn't accidentally tap interactive elements (unlike coordinate taps)
   * - Works universally across all Android apps and keyboards
   */
  private suspend fun dismissKeyboardAndContinue(
    state: BlazeState,
    recovery: RecoveryAction.DismissKeyboard?,
    traceId: TraceId,
  ): BlazeState {
    // Use ESCAPE key to dismiss keyboard — safe, no side effects
    val result = executor.execute(
      toolName = CoreTools.PRESS_KEY,
      args = buildJsonObject {
        put("keyCode", "111")
      },
      traceId = traceId,
    )

    return when (result) {
      is ExecutionResult.Success -> state.copy(
        screenSummary = "Dismissed keyboard (ESCAPE key), continuing",
      )
      is ExecutionResult.Failure -> state.copy(
        reflectionNotes = state.reflectionNotes +
          "Failed to dismiss keyboard: ${result.error}",
      )
    }
  }
}

/**
 * Records a blocker and marks the exploration as stuck.
 */
internal fun recordBlockerAndFail(state: BlazeState, reason: String): BlazeState {
  return state.copy(
    stuck = true,
    stuckReason = reason,
  )
}
