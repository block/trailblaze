package xyz.block.trailblaze.mcp.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.HistoryCompressionStrategy
import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMCompressHistory
import ai.koog.agents.core.dsl.extension.nodeLLMRequestOnlyCallingTools
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResultsOnlyCallingTools
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.util.Console
import kotlin.reflect.full.findAnnotation

/**
 * Koog AI Agent that owns the reasoning loop via a **custom Koog `strategy { }` graph**.
 *
 * This is the opt-in [xyz.block.trailblaze.mcp.AgentImplementation.KOOG_STRATEGY_GRAPH] entry
 * point. Instead of the library-provided [ai.koog.agents.core.agent.singleRunStrategy], it builds
 * an explicit tool-calling graph. That explicit graph is the seam we'll grow replan / recovery /
 * history-compression nodes into without touching any other agent's behavior.
 *
 * ## How tools are sourced
 *
 * [createInProcess] is the only entry point: tools come from a Trailblaze-owned
 * [ai.koog.agents.core.tools.ToolRegistry] built via
 * `TrailblazeToolRepo.asToolRegistry { <per-call execution context> }`. Tool calls execute
 * in-process against the same executor / logging / session the legacy
 * [xyz.block.trailblaze.agent.TrailblazeRunner] uses, so session logs happen as a side effect.
 * No HTTP, no re-entrancy — safe to run in the same JVM (or on the same device) it drives.
 *
 * (An earlier MCP self-connection overload was removed: it DEADLOCKED whenever the agent shared a
 * JVM with the daemon servicing its tool calls, and had no in-process callers.)
 *
 * ## Graph shape (v3)
 *
 * ```
 *  nodeStart ──▶ prunePreRequest ──▶ nodeLLMRequest(only-calling-tools)
 *                                          │
 *            ┌── onToolCalls{objectiveStatus} ──▶ executeObjectiveStatus ──▶ finish (capture + return)
 *            │
 *            └── onToolCalls{any other tool} ──▶ nodeExecuteTools ──▶ prunePreSendResults
 *                                                                            │
 *                                                     (history too big?) ┌───┴───┐ (no)
 *                                                                   (yes)│       │
 *                                                         compressHistory│       │
 *                                                                        └──▶ nodeLLMSendToolResults(only-calling-tools)
 *                                                                                       │
 *               (same objectiveStatus / other-tool branch as above) ◀──────────────────┘  (loop)
 * ```
 *
 * In words: every LLM turn is forced to call a tool (`ToolChoice.Required`) — the agent never
 * returns free text, exactly like the legacy [xyz.block.trailblaze.agent.TrailblazeRunner]. The
 * reasoning lives in the tool's structured fields, and the agent signals completion by calling the
 * `objectiveStatus` tool. So: prune the chat history (latest screen only), force a tool call; if
 * the call is `objectiveStatus`, execute it (capturing COMPLETED/FAILED) and finish; otherwise
 * execute the tool, prune again so the just-produced tool result is the "latest" full screen state,
 * optionally compress older turns ([nodeLLMCompressHistory], see "History compression" below), feed
 * the results back, and loop. There is no text-termination path — the loop ends only via
 * `objectiveStatus` (or by hitting `maxIterations`, which surfaces as a failure).
 *
 * ## History pruning ("latest screen-state only")
 *
 * A core Trailblaze value prop is that the LLM only ever sees the *latest* view hierarchy, not an
 * accumulation of every screen it has visited — see the legacy [xyz.block.trailblaze.agent.TrailblazeRunner]
 * path, whose `getLimitedHistory()` truncates history to keep context flat. Koog's [AIAgent]
 * accumulates the full message history by default: every tool result (including the large
 * snapshot / view-hierarchy results) is appended as a tool-result message and re-sent on every
 * subsequent LLM call. Left unchecked, a multi-snapshot run sends every historical hierarchy.
 *
 * The two `prune*` pass-through nodes ([buildStrategy]) rewrite the live prompt before each LLM
 * call via [pruneScreenStateHistory] so only the most recent screen-bearing tool result keeps its
 * full payload; older large tool results are replaced with [STALE_SCREEN_PLACEHOLDER]. See
 * [pruneScreenStateHistory] for the exact rule.
 *
 * ## History compression (Koog-native, bounded summary)
 *
 * Pruning keeps the *latest screen* small but leaves every prior turn's action message (tool call +
 * its small result) in the prompt. Over a long objective that message list still grows without
 * bound, which is the "okay but not great" part of the old behavior. Rather than hand-roll a
 * running summary, we lean on Koog's own [nodeLLMCompressHistory]: when the live conversation
 * exceeds [HISTORY_COMPRESSION_MESSAGE_THRESHOLD] messages, the older turns are folded into a
 * Koog-generated TLDR (via [HistoryCompressionStrategy.FromLastNMessages], which keeps the most
 * recent [HISTORY_COMPRESSION_KEEP_RECENT_MESSAGES] messages verbatim and summarizes the rest).
 * The system prompt, the first user (objective) message, and any trailing tool call are preserved
 * by Koog's strategy, so the just-executed tool's result still pairs with its call when
 * [nodeLLMSendToolResultsOnlyCallingTools] appends it — i.e. the latest screen perception is never
 * lost to compression. Compression sits on the loop branch only (the first request is always small), is
 * gated by message count (pruning already caps per-turn size), and runs an extra summarization LLM
 * call when it fires — that call flows through the same logging client as every other request.
 *
 * This is purely the within-objective (single [run]) history. Koog's [AIAgent] seeds each `run()`
 * from a fixed config prompt, so conversation does NOT persist across separate objectives/blocks by
 * design; cross-block continuity would use Koog's persistency/snapshot feature and is intentionally
 * out of scope here. Compression can be disabled (pass a null strategy to [buildStrategy], or set
 * `TRAILBLAZE_KOOG_DISABLE_HISTORY_COMPRESSION=1`), which restores the prune-only v1 loop.
 *
 * ## Koog 1.0.0 API symbols used
 *
 * - [strategy] (`ai.koog.agents.core.dsl.builder.strategy`) — builds the `AIAgentGraphStrategy<String, String>`.
 * - [nodeLLMRequestOnlyCallingTools], [nodeExecuteTools], [nodeLLMSendToolResultsOnlyCallingTools]
 *   (`ai.koog.agents.core.dsl.extension`) — the predefined nodes. The `OnlyCallingTools` request
 *   variants set `LLMParams.ToolChoice.Required`, matching the legacy runner's forced-tool policy.
 * - [onToolCalls] with a per-call predicate (`onToolCalls { it.tool == "objectiveStatus" }`) — the
 *   edge guard that routes the completion tool to the terminal branch vs. every other tool to the loop.
 * - `node(name) { input -> ... }` (`ai.koog.agents.core.dsl.builder`) — declares a custom
 *   pass-through node. Its lambda runs with the graph context (`AIAgentGraphContextBase`) as
 *   receiver, so `llm.writeSession { ... }` is reachable from inside it (used by the `prune*` nodes).
 * - `llm.writeSession { prompt = prompt.copy(messages = <pruned>) }`
 *   ([ai.koog.agents.core.agent.context.AIAgentLLMContext.writeSession] →
 *   [ai.koog.agents.core.agent.session.AIAgentLLMWriteSession]) — the write session exposes the live
 *   `prompt` (a [ai.koog.prompt.Prompt] with `getMessages()` / `copy(...)`) as a mutable property.
 *   Rewriting it in place is how the prune nodes drop stale screen state.
 * - `forwardTo` (an infix member on `AIAgentNodeBase`, inherited by the start node) — declares an edge.
 * - [onToolCalls] (`ai.koog.agents.core.dsl.extension`) — edge guard. The per-call predicate form
 *   (`onToolCalls { it.tool == name }`) fires when any call matches and forwards the filtered calls;
 *   we use it to split the completion tool from every other tool. (The Koog docs also reference
 *   `onAssistantMessage`/`onTextMessage` for a text-termination path — unused here, since forced
 *   tool-calling means the agent never returns plain text.)
 * - [onCondition] — edge guard evaluated against arbitrary session state; used to gate compression.
 * - `nodeStart` / `nodeFinish` — the implicit start/finish nodes exposed by the strategy builder.
 *
 * The base request→execute→send loop mirrors what the library's `singleRunStrategy` wires up
 * internally (verified against agents-core 1.0.0); we spell it out so the forced-tool, completion,
 * prune, and compression nodes can be inserted on the right edges.
 *
 * ## Usage
 *
 * The production entry point is [createInProcess] (no MCP self-connection). [run] is a `suspend`
 * function, so call it from a coroutine / suspend context:
 *
 * ```kotlin
 * // Build the registry from the session's TrailblazeToolRepo, then — inside a suspend function:
 * val agent = KoogStrategyGraphAgent.createInProcess(
 *   llmClient = myLlmClient,
 *   llmModel = myLlmModel,
 *   toolRegistry = myToolRegistry,
 * )
 * val result = agent.run("Tap the login button") // suspend
 * agent.close()
 * ```
 */
class KoogStrategyGraphAgent private constructor(
  private val agent: AIAgent<String, String>,
  private val toolRegistry: ToolRegistry,
) {

  /**
   * Runs the agent with the given objective.
   *
   * @param objective The task to accomplish (e.g., "Tap the login button")
   * @return The agent's final assistant message.
   */
  suspend fun run(objective: String): String {
    return agent.run(objective)
  }

  /**
   * Closes the agent and releases resources. Suspend (not [AutoCloseable]) on purpose: the only
   * caller runs inside a coroutine, and wrapping the underlying suspend `AIAgent.close()` in
   * `runBlocking` from a coroutine thread risks deadlocking a single-threaded dispatcher. Call this
   * from a `try`/`finally` in suspend code instead of `use { }`.
   */
  suspend fun close() {
    agent.close()
  }

  companion object {
    /** Default maximum graph iterations before the agent gives up. */
    const val DEFAULT_MAX_ITERATIONS = 50

    /**
     * Message count above which the running conversation is compressed. A turn is roughly two
     * messages (assistant tool-call + user tool-result), so the default (~30) lets a dozen-ish
     * turns accumulate before the older ones are summarized. Pruning already caps per-turn size, so
     * this gate is intentionally about message *count*, not characters.
     */
    const val HISTORY_COMPRESSION_MESSAGE_THRESHOLD = 30

    /**
     * Number of most-recent messages [HistoryCompressionStrategy.FromLastNMessages] keeps verbatim
     * when compressing; everything older is folded into a TLDR. Sized so the latest screen-bearing
     * result and the last few turns of reasoning survive compression intact.
     */
    const val HISTORY_COMPRESSION_KEEP_RECENT_MESSAGES = 12

    /**
     * Kill-switch env var: when set to `1`/`true`, the agent runs the prune-only v1 loop with no
     * history compression. Read once when the agent is built (consistent with the other JVM-start
     * env vars), not per request.
     */
    const val DISABLE_HISTORY_COMPRESSION_ENV = "TRAILBLAZE_KOOG_DISABLE_HISTORY_COMPRESSION"

    /**
     * Override env var for [HISTORY_COMPRESSION_MESSAGE_THRESHOLD] (the message count that triggers
     * compression). Useful for tuning on slow/fast agents and for exercising the compression branch
     * on a short run. Malformed or non-positive values fall back to the default.
     */
    const val HISTORY_COMPRESSION_THRESHOLD_ENV = "TRAILBLAZE_KOOG_HISTORY_COMPRESSION_THRESHOLD"

    /** Default system prompt for the agent. Mirrors [KoogMcpAgent]'s prompt. */
    private const val DEFAULT_SYSTEM_PROMPT = """You are a mobile UI automation assistant.

When given an objective, analyze the available tools and call the appropriate ones to accomplish the task.

Available tools include:
- viewHierarchy: Get the current UI structure
- getScreenshot: Capture the current screen
- tapOnPoint: Tap at specific coordinates
- inputText: Type text
- swipe: Swipe gesture

Look at the view hierarchy to understand the screen and find element coordinates.
Call tools to interact with the UI until the objective is complete.

Every turn must call a tool — never reply with plain text. Put your reasoning in the tool's
fields. When the objective is fully complete (or cannot be completed), call `objectiveStatus`
with status=COMPLETED (or FAILED) and an explanation; that is the only way to finish."""

    /**
     * The custom tool-calling strategy graph. Factored out (rather than inlined into [create]) so
     * it can be unit-tested and so the node/edge shape reads as the documentation it is.
     *
     * Every LLM turn is forced to call a tool ([nodeLLMRequestOnlyCallingTools] /
     * [nodeLLMSendToolResultsOnlyCallingTools] set `ToolChoice.Required`), matching the legacy
     * runner: the agent never returns free text, and completion is the `objectiveStatus` tool call.
     * Shape: prune → force-request → (objectiveStatus → execute → finish) | (other tool → execute →
     * prune → [compress?] → send → loop). The two `prune*` pass-through nodes keep only the latest
     * screen state in the prompt (see [pruneScreenStateHistory]).
     */
    fun buildStrategy(
      name: String = "trailblaze-koog-strategy-graph",
      /**
       * How to summarize older turns when the conversation grows past [isHistoryTooBig]. `null`
       * disables compression entirely (prune-only loop). Defaults to keeping the most recent
       * [HISTORY_COMPRESSION_KEEP_RECENT_MESSAGES] messages verbatim and TLDR-ing the rest.
       */
      compressionStrategy: HistoryCompressionStrategy? =
        HistoryCompressionStrategy.FromLastNMessages(HISTORY_COMPRESSION_KEEP_RECENT_MESSAGES),
      /** Gate for when to compress, evaluated against the live (already-pruned) prompt. */
      isHistoryTooBig: (Prompt) -> Boolean = { it.messages.size > HISTORY_COMPRESSION_MESSAGE_THRESHOLD },
      /** Name of the completion tool that terminates the loop (derived from its annotation). */
      objectiveStatusToolName: String = OBJECTIVE_STATUS_TOOL_NAME,
    ): AIAgentGraphStrategy<String, String> =
      strategy(name) {
        // Forced tool-calling on every turn (ToolChoice.Required) — the LLM can't return free text.
        val callLlm by nodeLLMRequestOnlyCallingTools()
        val executeTools by nodeExecuteTools()
        // A dedicated execute node for the terminal `objectiveStatus` call: same dispatcher (so the
        // host captures COMPLETED/FAILED and logs it), but its output goes to finish, not the loop.
        val executeObjectiveStatus by nodeExecuteTools("executeObjectiveStatus")
        val sendToolResults by nodeLLMSendToolResultsOnlyCallingTools()

        // Prune nodes: pass-through nodes that rewrite the live prompt so only the latest
        // screen-bearing tool result keeps its full payload (see [pruneScreenStateHistory]).
        // They sit immediately before each node that issues an LLM call, so the prune always
        // happens against the most-recently-appended tool results.
        //
        // [prunePreRequest] is String -> String (it sits between nodeStart and the request node,
        // both of which carry the objective string). [prunePreSendResults] is
        // ReceivedToolResults -> ReceivedToolResults (it sits between nodeExecuteTools and the
        // send-results node, both of which carry the just-produced tool results). Neither node
        // transforms its input value — they only rewrite the side-channel chat history.
        val prunePreRequest by node<String, String>("prunePreRequest") { input ->
          llm.writeSession { prompt = pruneScreenStateHistory(prompt) }
          input
        }
        val prunePreSendResults by node<ReceivedToolResults, ReceivedToolResults>(
          "prunePreSendResults",
        ) { input ->
          llm.writeSession { prompt = pruneScreenStateHistory(prompt) }
          input
        }
        // Terminal transform: executeObjectiveStatus produces ReceivedToolResults; the graph's
        // finish type is String. The actual COMPLETED/FAILED outcome is captured by the host's
        // dispatcher when objectiveStatus executes, so this return value is only a fallback message.
        val finishAfterObjectiveStatus by node<ReceivedToolResults, String>("finishAfterObjectiveStatus") {
          "Objective reported via objectiveStatus."
        }

        // Kick off: prune (no-op on the first pass — nothing to strip yet), then force a tool call.
        edge(nodeStart forwardTo prunePreRequest)
        edge(prunePreRequest forwardTo callLlm)

        // Completion tool → terminal execute → finish. Declared first so it wins if the model ever
        // emits objectiveStatus alongside another call (the transform filters to just this call).
        edge(callLlm forwardTo executeObjectiveStatus onToolCalls { it.tool == objectiveStatusToolName })
        // Any other tool → execute and continue the loop.
        edge(callLlm forwardTo executeTools onToolCalls { it.tool != objectiveStatusToolName })

        edge(executeObjectiveStatus forwardTo finishAfterObjectiveStatus)
        edge(finishAfterObjectiveStatus forwardTo nodeFinish)

        // After executing a normal tool, prune so the just-produced tool result is the "latest"
        // full screen state (older ones stubbed out).
        edge(executeTools forwardTo prunePreSendResults)

        if (compressionStrategy != null) {
          // When the conversation has grown past the threshold, fold the OLDER turns into a
          // Koog-generated TLDR before sending the latest results back. The just-produced tool
          // results aren't in the prompt yet (the send node appends them), so compression never
          // touches the latest screen perception; Koog's strategy also preserves the trailing tool
          // call so the about-to-be-appended result still pairs with it. Both branches end at
          // sendToolResults, which appends the latest results (perception) and requests the LLM.
          val compressHistory by nodeLLMCompressHistory<ReceivedToolResults>(
            name = "compressHistory",
            strategy = compressionStrategy,
          )
          // Pass-through that just logs when the compress branch is taken (parallel to the
          // [KOOG_PRUNE] line), so a long run can be eyeballed for compression actually firing.
          val announceCompress by node<ReceivedToolResults, ReceivedToolResults>("announceCompress") { input ->
            Console.log(
              "[KOOG_COMPRESS] conversation exceeded threshold " +
                "(${llm.readSession { prompt.messages.size }} msgs) — folding older turns into a TLDR",
            )
            input
          }
          edge(prunePreSendResults forwardTo announceCompress onCondition { llm.readSession { isHistoryTooBig(prompt) } })
          edge(announceCompress forwardTo compressHistory)
          edge(prunePreSendResults forwardTo sendToolResults onCondition { llm.readSession { !isHistoryTooBig(prompt) } })
          edge(compressHistory forwardTo sendToolResults)
        } else {
          edge(prunePreSendResults forwardTo sendToolResults)
        }

        // The forced follow-up is another tool call: objectiveStatus → finish, anything else → loop.
        edge(sendToolResults forwardTo executeObjectiveStatus onToolCalls { it.tool == objectiveStatusToolName })
        edge(sendToolResults forwardTo executeTools onToolCalls { it.tool != objectiveStatusToolName })
      }

    /**
     * Registered name of the completion tool ([ObjectiveStatusTrailblazeTool]), read from its
     * `@TrailblazeToolClass` annotation so the graph's terminal-branch guard can't drift from the
     * name the LLM actually sees. Falls back to the literal if reflection ever fails.
     */
    val OBJECTIVE_STATUS_TOOL_NAME: String =
      ObjectiveStatusTrailblazeTool::class.findAnnotation<TrailblazeToolClass>()?.name ?: "objectiveStatus"

    /**
     * Length (in characters) above which an *older* (non-latest) screen-bearing tool result is
     * considered large enough to be worth stripping. Snapshot / view-hierarchy results run to
     * thousands of characters; small results (e.g. a `tapOnPoint` confirmation) stay under this
     * and are left intact so the action trail the LLM reasons over is preserved.
     */
    const val STALE_SCREEN_OUTPUT_THRESHOLD_CHARS = 500

    /** Placeholder substituted for the payload of older (stale) large screen-bearing tool results. */
    const val STALE_SCREEN_PLACEHOLDER =
      "[older screen state omitted to keep context minimal — call a snapshot tool to refresh]"

    /**
     * Rewrites [prompt] so the LLM only ever sees the **latest** screen state, mirroring the
     * intent of the legacy [xyz.block.trailblaze.agent.TrailblazeRunner]'s `getLimitedHistory()`.
     *
     * ## The rule
     *
     * Tool results land in the prompt as [MessagePart.Tool.Result] parts (inside a [Message.User]).
     * Of all such parts across the whole prompt, the **last one in chat order** is the "latest
     * screen state" and is kept verbatim. Any *earlier* `Tool.Result` whose `output` exceeds
     * [STALE_SCREEN_OUTPUT_THRESHOLD_CHARS] characters has its `output` replaced with
     * [STALE_SCREEN_PLACEHOLDER]. Small earlier results (under the threshold — e.g. a tap/swipe
     * confirmation) are left intact so the action trail is preserved; only the big stale screen
     * payloads (snapshots / view hierarchies) are stripped.
     *
     * Message structure (the tool-call / tool-result pairing, message order, roles) is fully
     * preserved — only the `output` string of qualifying older parts changes — so Koog's tool-call
     * bookkeeping stays valid.
     *
     * Emits one `[KOOG_PRUNE]` debug line per call so a multi-snapshot run can be eyeballed for
     * context staying flat.
     */
    fun pruneScreenStateHistory(prompt: ai.koog.prompt.Prompt): ai.koog.prompt.Prompt {
      val messages = prompt.messages

      // Index (into the flattened list of all Tool.Result parts) of the last one — that's the
      // "latest screen state" we keep verbatim. -1 when there are no tool results yet.
      var lastToolResultSeq = -1
      var seq = -1
      messages.forEach { message ->
        message.parts.forEach { part ->
          if (part is MessagePart.Tool.Result) {
            seq++
            lastToolResultSeq = seq
          }
        }
      }

      if (lastToolResultSeq < 0) {
        // Nothing has produced a tool result yet (e.g. the very first LLM request).
        Console.log("[KOOG_PRUNE] no tool results yet; nothing to prune; prompt msgs=${messages.size}")
        return prompt
      }

      var keptChars = 0
      var strippedCount = 0
      var savedChars = 0
      var cursor = -1
      val prunedMessages = messages.map { message ->
        val newParts = message.parts.map { part ->
          if (part !is MessagePart.Tool.Result) return@map part
          cursor++
          when {
            cursor == lastToolResultSeq -> {
              // Latest screen state — keep verbatim.
              keptChars = part.output.length
              part
            }
            part.output.length > STALE_SCREEN_OUTPUT_THRESHOLD_CHARS -> {
              // Older + large → stale screen payload, strip it.
              strippedCount++
              savedChars += part.output.length - STALE_SCREEN_PLACEHOLDER.length
              part.copy(output = STALE_SCREEN_PLACEHOLDER)
            }
            else -> part // Older + small → keep (preserves the action trail).
          }
        }
        // Only rebuild the message when a part actually changed (cheap identity short-circuit).
        if (newParts == message.parts) {
          message
        } else {
          when (message) {
            is Message.User -> message.copy(
              parts = newParts.filterIsInstance<MessagePart.RequestPart>(),
            )
            else -> message // Tool results only ever appear in User messages.
          }
        }
      }

      Console.log(
        "[KOOG_PRUNE] kept latest screen-state ($keptChars chars); " +
          "stripped $strippedCount older (~$savedChars chars saved); " +
          "prompt msgs=${prunedMessages.size}",
      )
      return prompt.copy(messages = prunedMessages)
    }

    /**
     * Creates a native Koog agent driven by the custom [buildStrategy] graph, executing tools
     * **in-process** against a Trailblaze-owned [ToolRegistry] (no MCP self-connection).
     *
     * This is the production entry point used on the host. The caller (a host runner) builds the
     * registry from its session's [xyz.block.trailblaze.toolcalls.TrailblazeToolRepo] via
     * `toolRepo.asToolRegistry { <fresh per-call execution context> }`, so each tool the graph
     * invokes runs through the same executor / logging / session the legacy
     * [xyz.block.trailblaze.agent.TrailblazeRunner] uses — only the reasoning loop differs.
     *
     * There is no self-connection: the agent and the device it drives live in the same JVM, so its
     * tool calls don't re-enter the daemon over HTTP (the pattern that deadlocks an in-process run).
     *
     * @param llmClient The Koog LLM client for completions.
     * @param llmModel The LLM model to use.
     * @param toolRegistry The in-process Koog tool registry whose tools execute Trailblaze tools
     *   against a per-call execution context (built via `TrailblazeToolRepo.asToolRegistry`).
     * @param systemPrompt Custom system prompt (optional).
     * @param maxAgentIterations Maximum graph iterations before giving up.
     * @return A ready-to-use agent.
     */
    fun createInProcess(
      llmClient: LLMClient,
      llmModel: TrailblazeLlmModel,
      toolRegistry: ToolRegistry,
      systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
      maxAgentIterations: Int = DEFAULT_MAX_ITERATIONS,
    ): KoogStrategyGraphAgent {
      val agent = buildAgent(
        llmClient = llmClient,
        llmModel = llmModel,
        toolRegistry = toolRegistry,
        systemPrompt = systemPrompt,
        maxAgentIterations = maxAgentIterations,
      )
      return KoogStrategyGraphAgent(agent, toolRegistry)
    }

    /**
     * Shared [AIAgent] assembly for [createInProcess]. Factored out so the strategy graph, prompt
     * executor, and model assembly stays in one place independent of how the [toolRegistry] is
     * sourced.
     */
    private fun buildAgent(
      llmClient: LLMClient,
      llmModel: TrailblazeLlmModel,
      toolRegistry: ToolRegistry,
      systemPrompt: String,
      maxAgentIterations: Int,
    ): AIAgent<String, String> {
      // Koog 1.0.0 removed SingleLLMPromptExecutor — use MultiLLMPromptExecutor with one
      // (provider, client) entry instead (functionally equivalent for the single-client case).
      val koogModel = llmModel.toKoogLlmModel()
      val promptExecutor = MultiLLMPromptExecutor(llmClient.llmProvider() to llmClient)
      // History compression is on by default; the kill-switch restores the prune-only v1 loop.
      val compressionDisabled = System.getenv(DISABLE_HISTORY_COMPRESSION_ENV)
        ?.lowercase() in setOf("1", "true")
      val rawThreshold = System.getenv(HISTORY_COMPRESSION_THRESHOLD_ENV)
      val threshold = rawThreshold?.toIntOrNull()?.takeIf { it > 0 } ?: HISTORY_COMPRESSION_MESSAGE_THRESHOLD
      // Surface a malformed override instead of silently falling back, so a typo'd env var is debuggable.
      if (rawThreshold != null && rawThreshold.toIntOrNull()?.takeIf { it > 0 } == null) {
        Console.log(
          "[KOOG] ignoring invalid $HISTORY_COMPRESSION_THRESHOLD_ENV='$rawThreshold' " +
            "(expected a positive integer); using default $HISTORY_COMPRESSION_MESSAGE_THRESHOLD",
        )
      }
      Console.log(
        "[KOOG] strategy graph: forced tool-calls (ToolChoice.Required); completion='$OBJECTIVE_STATUS_TOOL_NAME'; " +
          if (compressionDisabled) {
            "history compression DISABLED"
          } else {
            "history compression enabled (threshold=$threshold msgs, keepRecent=$HISTORY_COMPRESSION_KEEP_RECENT_MESSAGES)"
          },
      )
      return AIAgent(
        promptExecutor = promptExecutor,
        llmModel = koogModel,
        strategy = buildStrategy(
          compressionStrategy = if (compressionDisabled) {
            null
          } else {
            HistoryCompressionStrategy.FromLastNMessages(HISTORY_COMPRESSION_KEEP_RECENT_MESSAGES)
          },
          isHistoryTooBig = { it.messages.size > threshold },
        ),
        toolRegistry = toolRegistry,
        systemPrompt = systemPrompt,
        maxIterations = maxAgentIterations,
      )
    }
  }
}
