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
import xyz.block.trailblaze.exception.TrailheadException
import xyz.block.trailblaze.logs.client.ObjectiveLogHelper
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.yaml.DirectionStep
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
  /**
   * Optional bracket that runs a recording's per-tool replay loop inside a shared tool-batch
   * scope, so the whole recording dispatches against ONE execution context + ONE snapshot frame
   * (see [xyz.block.trailblaze.BaseTrailblazeAgent.runInSharedToolBatch]). Wired by the on-device
   * Android rule to `agent::runInSharedToolBatch`.
   *
   * Load-bearing for cross-tool device state that lives on the execution context — most visibly
   * the Android clipboard cache, so a `mobile_setClipboard` → `mobile_pasteClipboard` recording
   * replays correctly instead of the paste reading an empty clipboard from a fresh context.
   *
   * When null, each recorded tool dispatches with its own context (the pre-batch behavior), so
   * callers that don't wire it are unchanged. The per-tool loop — failure attribution, capture
   * hooks, cancellation, per-tool logging — is identical either way; only the context/frame
   * lifetime differs.
   */
  private val sharedToolBatch:
    (suspend (block: suspend () -> PromptRecordingResult) -> PromptRecordingResult)? = null,
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
   *   off to [trailblazeRunner]'s AI recovery ([TestAgentRunner.recover]) or to throw —
   *   except for a [TrailblazeToolResult.Error.FatalError], which always throws.
   *   FatalError's contract is "abort the test immediately": it marks dead infrastructure
   *   (e.g. a wedged on-device server) or broken tooling — not the UI drift self-heal exists
   *   for — and the AI loop already honors it by aborting without an LLM retry
   *   (`TrailblazeKoogLlmClientHelper`), so recorded replay does the same rather than routing
   *   a known-terminal error through AI recovery.
   * - A trailhead step (a lowered `trailhead:`, marked via [DirectionStep.isTrailhead]) also
   *   always throws on failure - a [TrailheadException] - regardless of [selfHeal]: the trail
   *   never reached its starting state, so recovery or continuing would only mask the real
   *   failure behind a later step's unrelated error.
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
      // Rides into every failure message so CI logs can say WHERE the trail died
      // without the reader cross-referencing the trail file.
      val stepLabel = "step ${index + 1} of ${prompts.size}"
      if (useRecordedSteps && prompt.canPromptStepUseRecording()) {
        runRecordedPrompt(prompt, selfHeal, stepLabel)
      } else {
        runAiPrompt(prompt, stepLabel)
      }
    }
    return TrailblazeToolResult.Success()
  }

  private suspend fun runRecordedPrompt(prompt: PromptStep, selfHeal: Boolean, stepLabel: String) {
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
        // A trailhead failure is always terminal, even when selfHeal is requested: the trailhead
        // is the deterministic bootstrap that reaches the trail's starting state, so when it
        // fails the trail never began. Routing it into self-heal recovery (or continuing to the
        // next step) only masks the real failure behind a later, unrelated assertion error.
        if (prompt.isTrailheadStep()) {
          throw TrailheadException(
            headline =
              "tool '${recordingResult.failedTool.name}' could not reach the trail's starting state",
            detail = buildTrailheadFailureDetail(prompt, recordingResult, failureMessage),
          )
        }
        // A FatalError throws even when selfHeal is requested — see the runPromptSuspend kdoc.
        val fatal = recordingResult.failureResult is TrailblazeToolResult.Error.FatalError
        if (!selfHeal || fatal) {
          throw TrailblazeException(
            buildString {
              appendLine("Failed to run recording for prompt step ($stepLabel):")
              appendLine("  prompt: ${prompt.prompt}")
              appendLine(
                "  recorded tools: ${prompt.recording!!.tools.joinToString(", ") { it.name }}"
              )
              appendLine("  failed tool: ${recordingResult.failedTool.name}")
              appendLine("  failure: $failureMessage")
              if (fatal && selfHeal) {
                appendLine("  (fatal tool error — self-heal skipped)")
              }
            },
          )
        }
        markSelfHealUsed(prompt, recordingResult)
        val status = trailblazeRunner.recover(prompt, recordingResult)
        throwIfTerminalFailure(prompt, status, stepLabel)
      }
    }
  }

  private suspend fun runAiPrompt(prompt: PromptStep, stepLabel: String) {
    val status = trailblazeRunner.runSuspend(prompt)
    throwIfTerminalFailure(prompt, status, stepLabel)
  }

  private fun throwIfTerminalFailure(prompt: PromptStep, status: AgentTaskStatus, stepLabel: String) {
    // Assign to a `val` to force expression-context exhaustiveness over the sealed
    // AgentTaskStatus hierarchy. A new subtype will then fail the compile instead of silently
    // falling through here as a (wrong) success.
    @Suppress("UNUSED_VARIABLE")
    val exhaustive: Unit = when (status) {
      is ObjectiveComplete -> Unit
      is AgentTaskStatus.Failure -> {
        val statusDetail = buildString {
          appendLine("Status Type: ${status::class.java.name}")
          appendLine("Status: ${TrailblazeJsonInstance.encodeToString(status)}")
        }
        // Same fail-loudly contract as the recorded path: an NL-only trailhead the AI could not
        // satisfy means the trail never reached its starting state.
        if (prompt.isTrailheadStep()) {
          throw TrailheadException(
            headline = "could not reach the trail's starting state: ${prompt.prompt}",
            detail = statusDetail,
          )
        }
        throw TrailblazeException(
          buildString {
            appendLine("Failed to successfully run prompt with AI ($stepLabel) ${TrailblazeJsonInstance.encodeToString(prompt)}")
            append(statusDetail)
          },
        )
      }
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
    // The per-tool loop is unchanged whether or not batching is wired — only the execution-context
    // lifetime differs. When [sharedToolBatch] is wired, the whole loop runs inside one shared
    // tool-batch scope so every `runTrailblazeTool(listOf(tool))` below reuses ONE context + ONE
    // snapshot frame (see [sharedToolBatch]'s docs). When null, it runs as-is (per-tool contexts).
    val replay: suspend () -> PromptRecordingResult = replay@{
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
          return@replay PromptRecordingResult.Failure(
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
      PromptRecordingResult.Success(tools)
    }
    return sharedToolBatch?.invoke(replay) ?: replay()
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

  private fun PromptStep.canPromptStepUseRecording(): Boolean = recordable && recording != null

  private fun PromptStep.isTrailheadStep(): Boolean = this is DirectionStep && isTrailhead

  /**
   * The detail body under [TrailheadException]'s self-contained first line: the full tool call
   * (name + args) and the underlying failure, for the complete report.
   */
  private fun buildTrailheadFailureDetail(
    prompt: PromptStep,
    recordingResult: PromptRecordingResult.Failure,
    failureMessage: String,
  ): String = buildString {
    appendLine("  trailhead step: ${prompt.prompt}")
    appendLine("  failed tool call: ${recordingResult.failedTool.trailblazeTool}")
    appendLine("  failure: $failureMessage")
    appendLine(
      "The run was aborted before any trail steps ran. Fix the trailhead (or the device state " +
        "it needs) before debugging the trail itself.",
    )
  }

  private fun TrailblazeToolResult.errorMessageOrToString(): String =
    (this as? TrailblazeToolResult.Error)?.errorMessage ?: toString()
}
