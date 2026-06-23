package xyz.block.trailblaze.mcp.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import xyz.block.trailblaze.api.ImageFormatDetector
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.util.Console

/**
 * A delegating [LLMClient] wrapper that attaches the **current annotated screenshot** to each
 * tool-calling request the Koog strategy-graph agent makes, giving the model the same set-of-mark
 * visual perception the legacy [xyz.block.trailblaze.agent.TrailblazeRunner] path has.
 *
 * ## Why this exists
 *
 * The opt-in [KoogStrategyGraphAgent] drives the device through Koog's [ai.koog.agents.core.agent.AIAgent],
 * which assembles its own prompt from text tool results — the view hierarchy is appended as text by
 * [runPromptsWithKoogStrategyGraph]'s tool dispatcher, but the prompt is otherwise text-only. The
 * legacy path attaches `ScreenState.annotatedScreenshotBytes` as a vision attachment on every request
 * (see `TrailblazeKoogLlmClientHelper`), so a vision-capable model can reason over the rendered screen
 * (set-of-mark numbering, visual layout) — not just the accessibility text. Without this decorator the
 * Koog agent is blind to anything the a11y tree under-represents, which regresses pass rate on
 * visually-driven screens versus the default agent.
 *
 * This is the screenshot half of closing that gap; [LoggingLlmClient] is the logging half. They
 * compose with this decorator OUTERMOST (it attaches the image first) wrapping [LoggingLlmClient]
 * wrapping the real client. That ordering lets the logger record the post-attachment prompt, so its
 * token breakdown counts the image (the log stores attachments as a type marker, not bytes — no
 * bloat). The real client receives the image last.
 *
 * ## What it does
 *
 * On each [execute] that carries tools (the main agent loop — `ToolChoice.Required` always passes the
 * tool descriptors), it captures the current screen and attaches its annotated screenshot to the
 * **latest user message** (the freshly-appended tool result on a loop turn, or the objective on the
 * first turn — whichever the model is about to reason over). The history-compression summarization
 * call passes no tools, so it's skipped — that request doesn't need a screenshot. Attachment is gated
 * on the model advertising [LLMCapability.Vision.Image]; a text-only model is left untouched.
 *
 * Mutating only the per-call prompt copy (never Koog's stored history) keeps exactly one screenshot
 * in flight: each incoming prompt is image-free, so there's nothing to accumulate. The stale-image
 * strip in [attachScreenshotToLatestUserMessage] is defensive idempotence, not load-bearing.
 *
 * A screenshot is attached to every tool-calling turn (vision input on every turn, like the legacy
 * runner), so it's also the per-turn image-token cost. The [DISABLE_SCREENSHOT_ENV] kill-switch makes
 * the decorator a pure passthrough — set it to A/B the screenshot's effect on pass rate or to cut that
 * cost without a redeploy, consistent with the other KOOG env knobs (history compression, loop detect).
 *
 * Only [execute] (the call the [ai.koog.agents.core.agent.AIAgent] uses) is intercepted; every other
 * [LLMClient] method delegates straight through, mirroring [LoggingLlmClient].
 *
 * @param delegate The real Koog LLM client to delegate every call to.
 * @param screenStateProvider Captures the current screen; its `annotatedScreenshotBytes` is attached.
 * @param trailblazeLlmModel The model — its `capabilityIds` gate whether a screenshot is attached.
 * @param enabled Master switch; defaults to the [DISABLE_SCREENSHOT_ENV] kill-switch. False ⇒ passthrough.
 * @param onRequestEnd Invoked in a `finally` at the end of each [execute]. As the outermost decorator,
 *   this is the per-request boundary — wire it to [SharedScreenStateCapture.clear] so the screen
 *   captured this request (and reused by the inner logging client) is released afterwards and the next
 *   request re-captures fresh. Runs even if the delegate throws. Defaults to no-op.
 */
class ScreenshotAttachingLlmClient(
  private val delegate: LLMClient,
  private val screenStateProvider: () -> ScreenState,
  private val trailblazeLlmModel: TrailblazeLlmModel,
  private val enabled: Boolean = !screenshotsDisabledFromEnv(),
  private val onRequestEnd: () -> Unit = {},
) : LLMClient() {

  /** Read once: whether this model can accept image input at all. A text-only model is never touched. */
  private val modelSupportsVision: Boolean =
    trailblazeLlmModel.capabilityIds.contains(LLMCapability.Vision.Image.id)

  init {
    if (!enabled) {
      Console.log("[KOOG_SCREENSHOT] disabled via $DISABLE_SCREENSHOT_ENV — sending text-only")
    }
  }

  override fun llmProvider(): LLMProvider = delegate.llmProvider()

  override suspend fun execute(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): Message.Assistant = try {
    delegate.execute(
      // Only the tool-calling agent turns get a screenshot; the no-tool history-compression call
      // doesn't. Skipped for text-only models and when the kill-switch is set. A capture/attach
      // failure must never break the agent run — withCurrentScreenshot falls back to the text-only prompt.
      prompt = if (enabled && modelSupportsVision && tools.isNotEmpty()) withCurrentScreenshot(prompt) else prompt,
      model = model,
      tools = tools,
    )
  } finally {
    // End of the per-request window. As the outermost decorator, this is where the shared screen
    // capture is released so it isn't retained between requests (and the next request re-captures
    // fresh). In a finally so it runs even if the delegate throws.
    onRequestEnd()
  }

  private fun withCurrentScreenshot(prompt: Prompt): Prompt = try {
    val bytes = screenStateProvider().annotatedScreenshotBytes
    if (bytes != null && bytes.isNotEmpty()) {
      Console.log("[KOOG_SCREENSHOT] attaching ${bytes.size / 1024}KB annotated screenshot to LLM request")
      attachScreenshotToLatestUserMessage(prompt, bytes)
    } else {
      // Surprising for a vision-capable model — surface it so "why no vision this turn?" is
      // answerable from the run log (parallel to the [KOOG_PRUNE] "nothing to prune" line).
      Console.log("[KOOG_SCREENSHOT] no annotated screenshot available this turn — sending text-only")
      prompt
    }
  } catch (e: kotlinx.coroutines.CancellationException) {
    // Never swallow cancellation — trail timeout / user-abort must propagate. Mirrors the tool
    // dispatcher's catch in KoogStrategyGraphHostRunner.
    throw e
  } catch (e: Exception) {
    Console.error("[KOOG_SCREENSHOT] failed to attach screenshot, sending text-only: ${e.message}")
    prompt
  }

  override suspend fun executeMultipleChoices(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): LLMChoice = delegate.executeMultipleChoices(prompt, model, tools)

  override fun executeStreaming(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): Flow<StreamFrame> = delegate.executeStreaming(prompt, model, tools)

  override suspend fun moderate(
    prompt: Prompt,
    model: LLModel,
  ): ModerationResult = delegate.moderate(prompt = prompt, model = model)

  override fun close() = delegate.close()

  companion object {
    /**
     * Kill-switch env var. When set to `1`/`true` (case-insensitive) the Koog agent runs text-only
     * (no annotated screenshot), matching the [KoogStrategyGraphAgent] env-var conventions. Read once
     * when the client is built (consistent with the other JVM-start KOOG env vars). Use it to A/B the
     * screenshot's effect on pass rate, or to cut per-turn image token cost, without a redeploy.
     */
    const val DISABLE_SCREENSHOT_ENV = "TRAILBLAZE_KOOG_DISABLE_SCREENSHOT"

    /** Resolves the kill-switch from the environment. `1`/`true` (case-insensitive) disables. */
    fun screenshotsDisabledFromEnv(): Boolean =
      System.getenv(DISABLE_SCREENSHOT_ENV)?.lowercase() in setOf("1", "true")
  }
}

/**
 * Returns a copy of [prompt] with [screenshotBytes] attached, as a [MessagePart.Attachment] image, to
 * the **last** [Message.User] — the freshest screen the model is about to reason over. Any image
 * attachment already present on an earlier user message is stripped first, so exactly one screenshot
 * is ever in the prompt (defensive idempotence — the live caller's incoming prompts are image-free).
 *
 * Pure (no Koog session / device / IO) so it's unit-testable without standing up the graph. Returns
 * [prompt] unchanged when there is no user message to anchor to.
 */
internal fun attachScreenshotToLatestUserMessage(
  prompt: Prompt,
  screenshotBytes: ByteArray,
): Prompt {
  val lastUserIndex = prompt.messages.indexOfLast { it is Message.User }
  if (lastUserIndex < 0) return prompt

  val attachment = MessagePart.Attachment(
    source = AttachmentSource.Image(
      content = AttachmentContent.Binary.Bytes(screenshotBytes),
      format = ImageFormatDetector.detectFormat(screenshotBytes).mimeSubtype,
    ),
  )

  val rebuilt = prompt.messages.mapIndexed { index, message ->
    if (message !is Message.User) return@mapIndexed message
    // Drop any carried-over image attachment so only the current screenshot (added to the latest
    // user message below) survives.
    val withoutStaleImages = message.parts.filterNot {
      it is MessagePart.Attachment && it.source is AttachmentSource.Image
    }
    val newParts = if (index == lastUserIndex) withoutStaleImages + attachment else withoutStaleImages
    if (newParts == message.parts) message else message.copy(parts = newParts)
  }
  return prompt.copy(messages = rebuilt)
}
