package xyz.block.trailblaze.rules

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatus.Success.ObjectiveComplete
import xyz.block.trailblaze.agent.model.PromptRecordingResult
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.logs.client.ObjectiveLogHelper
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper

class TrailblazeRunnerUtil(
  val runTrailblazeTool: (tools: List<TrailblazeTool>) -> TrailblazeToolResult,
  private val trailblazeRunner: TestAgentRunner,
  private val trailblazeLogger: TrailblazeLogger? = null,
  private val sessionProvider: (() -> TrailblazeSession)? = null,
  // Writes back the `usedSelfHeal = true` session copy from logSelfHealInvoked.
  // Without it, the mark is dropped and SessionManager can't emit *WithSelfHeal end statuses.
  private val sessionUpdater: ((TrailblazeSession) -> Unit)? = null,
  /**
   * Optional hook invoked immediately BEFORE each recorded tool's `execute()` runs. The
   * intended use is per-tool screen-state capture for the deterministic Maestro→
   * accessibility selector migration: each recorded tool needs its own pre-fire snapshot
   * so the migration tool can resolve the tool's legacy selector against the exact
   * captured trees the runtime saw at that moment.
   *
   * Off by default — the hook is migration-only and would otherwise add a screenshot +
   * view-hierarchy capture per tool, doubling session-log size and adding latency. The
   * platform-side rule wires it (see `AndroidTrailblazeRule.trailblazeRunnerUtil`) when
   * `trailblaze.captureSecondaryTree` is set.
   *
   * `suspend`-typed because the host-side wiring needs to call suspend RPC functions
   * (`agent.captureScreenState()`) — wrapping those in `runBlocking` from inside the
   * already-suspending `runRecordedTools` loop risks dispatcher deadlocks (especially
   * under single-threaded test dispatchers).
   */
  private val onBeforeRecordedTool: (suspend (TrailblazeTool) -> Unit)? = null,
  /**
   * Optional hook invoked immediately AFTER each recorded tool's `execute()` runs
   * successfully. Fires only on success — failures already short-circuit the recording
   * loop and don't need a follow-up capture.
   *
   * Companion to [onBeforeRecordedTool] for tool classes whose pre-tool screen state is
   * unreliable for the Maestro→accessibility migration. Asserts (e.g.
   * `AssertVisibleBySelectorTrailblazeTool`) can wait up to ~30s for an element to become
   * visible; the pre-tool snapshot fires BEFORE that wait and may catch a mid-transition
   * frame where the asserted element isn't yet in the tree. The post-tool snapshot (taken
   * immediately after the assert succeeds) reliably has the element on screen, so
   * `migrate-trail` can prefer it for assert-class tools when both exist.
   *
   * Off by default — same migration-only gating as [onBeforeRecordedTool]. Hook
   * exceptions are caught by the runner; they must not abort the recording.
   */
  private val onAfterRecordedTool: (suspend (TrailblazeTool) -> Unit)? = null,
) {
  fun runPrompt(
    prompts: List<PromptStep>,
    useRecordedSteps: Boolean,
    selfHeal: Boolean,
    onStepProgress: ((stepIndex: Int, totalSteps: Int, stepText: String) -> Unit)? = null,
  ): TrailblazeToolResult = runBlocking {
    runPromptSuspend(prompts, useRecordedSteps, selfHeal, onStepProgress)
  }

  /**
   * Suspend version of runPrompt that properly handles coroutine cancellation.
   *
   * For each prompt step:
   * - If [useRecordedSteps] is true and the step has a usable recording, the recorded tools
   *   are replayed one-at-a-time. On the first failure, [selfHeal] decides whether to hand
   *   off to [trailblazeRunner]'s AI recovery ([TestAgentRunner.recover]) or to throw.
   * - Otherwise, the step is run with AI via [TestAgentRunner.runSuspend].
   */
  suspend fun runPromptSuspend(
    prompts: List<PromptStep>,
    useRecordedSteps: Boolean,
    selfHeal: Boolean,
    onStepProgress: ((stepIndex: Int, totalSteps: Int, stepText: String) -> Unit)? = null,
  ): TrailblazeToolResult {
    for ((index, prompt) in prompts.withIndex()) {
      onStepProgress?.invoke(index + 1, prompts.size, prompt.prompt)
      if (useRecordedSteps && prompt.canPromptStepUseRecording()) {
        runRecordedPrompt(prompt, selfHeal)
      } else {
        runAiPrompt(prompt)
      }
    }
    return TrailblazeToolResult.Success()
  }

  private suspend fun runRecordedPrompt(prompt: PromptStep, selfHeal: Boolean) {
    val stepStartTime = Clock.System.now()
    val stepTaskId = TaskId.generate()
    emitObjectiveStart(prompt)
    when (val recordingResult = runRecordedTools(prompt.recording!!.tools)) {
      is PromptRecordingResult.Success -> {
        emitObjectiveComplete(prompt, stepTaskId, stepStartTime, success = true, failureReason = null)
      }
      is PromptRecordingResult.Failure -> {
        val failureMessage = recordingResult.failureResult.errorMessageOrToString()
        val failureReason =
          "Recording failed at ${recordingResult.failedTool.name}: $failureMessage"
        // Close the recording-attempt lifecycle with a failed complete BEFORE handing off
        // to self-heal. `trailblazeRunner.recover()` emits its own start/complete pair,
        // so without this every `ObjectiveStartLog` from the recording attempt would be
        // left dangling for downstream progress builders (buildObjectiveProgress pairs
        // completes with the most recent start — a missing complete shows up as a phantom
        // in-progress/failed step in reports).
        emitObjectiveComplete(
          prompt,
          stepTaskId,
          stepStartTime,
          success = false,
          failureReason = failureReason,
        )
        if (!selfHeal) {
          throw TrailblazeException(
            buildString {
              appendLine("Failed to run recording for prompt step:")
              appendLine("  prompt: ${prompt.prompt}")
              appendLine(
                "  recorded tools: ${prompt.recording!!.tools.joinToString(", ") { it.name }}"
              )
              appendLine("  failed tool: ${recordingResult.failedTool.name}")
              appendLine("  failure: $failureMessage")
            },
          )
        }
        markSelfHealUsed(prompt, recordingResult)
        val status = trailblazeRunner.recover(prompt, recordingResult)
        throwIfTerminalFailure(prompt, status)
      }
    }
  }

  private suspend fun runAiPrompt(prompt: PromptStep) {
    val status = trailblazeRunner.runSuspend(prompt)
    throwIfTerminalFailure(prompt, status)
  }

  private fun throwIfTerminalFailure(prompt: PromptStep, status: AgentTaskStatus) {
    // Assign to a `val` to force expression-context exhaustiveness over the sealed
    // AgentTaskStatus hierarchy. A new subtype will then fail the compile instead of silently
    // falling through here as a (wrong) success.
    @Suppress("UNUSED_VARIABLE")
    val exhaustive: Unit = when (status) {
      is ObjectiveComplete -> Unit
      is AgentTaskStatus.Failure ->
        throw TrailblazeException(
          buildString {
            appendLine("Failed to successfully run prompt with AI ${TrailblazeJsonInstance.encodeToString(prompt)}")
            appendLine("Status Type: ${status::class.java.name}")
            appendLine("Status: ${TrailblazeJsonInstance.encodeToString(status)}")
          },
        )
      is AgentTaskStatus.InProgress,
      is AgentTaskStatus.McpScreenAnalysis,
      -> Unit
    }
  }

  private fun markSelfHealUsed(
    prompt: PromptStep,
    recordingResult: PromptRecordingResult.Failure,
  ) {
    val logger = trailblazeLogger ?: return
    val session = try { sessionProvider?.invoke() } catch (_: Exception) { null } ?: return
    val updated = logger.logSelfHealInvoked(session, prompt, recordingResult)
    sessionUpdater?.invoke(updated)
  }

  private suspend fun runRecordedTools(
    tools: List<TrailblazeToolYamlWrapper>
  ): PromptRecordingResult {
    val successfulTools = mutableListOf<TrailblazeToolYamlWrapper>()
    for (tool in tools) {
      // Long recordings should abort promptly on trail cancellation (timeout / user abort).
      currentCoroutineContext().ensureActive()
      // Pre-tool capture hook (off by default; flipped on for Maestro→accessibility migration
      // captures via [InstrumentationArgUtil.shouldCaptureSecondaryTree]). Wrapped in
      // try/catch so a hook failure can't sink the recording — the hook is logging-only.
      // CancellationException is rethrown so trail timeout / user-abort still propagates
      // through the hook layer — the catch is for observability bugs, not flow control.
      try {
        onBeforeRecordedTool?.invoke(tool.trailblazeTool)
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        // Swallow — hook is observational. Surface in logs, not in test results.
        @Suppress("PrintStackTrace") e.printStackTrace()
      }
      val result = runTrailblazeTool(listOf(tool.trailblazeTool))
      if (result is TrailblazeToolResult.Error) {
        return PromptRecordingResult.Failure(
          successfulTools = successfulTools,
          failedTool = tool,
          failureResult = result,
        )
      }
      // Post-tool capture hook (success path only). Same try/catch shape as the pre-hook:
      // hook is observational, must not abort the recording; cancellation propagates so
      // outer trail-timeout / abort still works.
      try {
        onAfterRecordedTool?.invoke(tool.trailblazeTool)
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        @Suppress("PrintStackTrace") e.printStackTrace()
      }
      successfulTools.add(tool)
    }
    return PromptRecordingResult.Success(tools)
  }

  private fun emitObjectiveStart(step: PromptStep) {
    val logger = trailblazeLogger ?: return
    val session = try { sessionProvider?.invoke() } catch (_: Exception) { null } ?: return
    logger.log(session, ObjectiveLogHelper.createStartLog(step, session.sessionId))
  }

  private fun emitObjectiveComplete(
    step: PromptStep,
    taskId: TaskId,
    stepStartTime: kotlinx.datetime.Instant,
    success: Boolean,
    failureReason: String?,
  ) {
    val logger = trailblazeLogger ?: return
    val session = try { sessionProvider?.invoke() } catch (_: Exception) { null } ?: return
    logger.log(
      session,
      ObjectiveLogHelper.createCompleteLog(
        step = step,
        taskId = taskId,
        stepStartTime = stepStartTime,
        sessionId = session.sessionId,
        success = success,
        failureReason = failureReason,
      ),
    )
  }

  private fun PromptStep.canPromptStepUseRecording(): Boolean {
    if (!recordable) return false
    val r = recording ?: return false
    // An auto-satisfied recording is a valid recorded step — `runRecordedTools` with an empty
    // tools list returns Success and the step completes deterministically. A normal recording
    // has non-empty tools.
    return r.autoSatisfied || r.tools.isNotEmpty()
  }

  private fun TrailblazeToolResult.errorMessageOrToString(): String =
    (this as? TrailblazeToolResult.Error)?.errorMessage ?: toString()
}
