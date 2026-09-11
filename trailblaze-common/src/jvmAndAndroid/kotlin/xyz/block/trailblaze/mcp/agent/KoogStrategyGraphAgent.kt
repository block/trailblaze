package xyz.block.trailblaze.mcp.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.AIAgentSubgraphBuilderBase
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.HistoryCompressionStrategy
import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.agents.core.dsl.extension.ToolCalls
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMCompressHistory
import ai.koog.agents.core.dsl.extension.nodeLLMRequestOnlyCallingTools
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResultsOnlyCallingTools
import ai.koog.agents.core.dsl.extension.onMessageParts
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.agent.ConditionResult
import ai.koog.agents.ext.agent.RetrySubgraphResult
import ai.koog.agents.ext.agent.subgraphWithRetry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.features.tokenizer.feature.MessageTokenizer
import ai.koog.agents.features.tokenizer.feature.tokenizer
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.tokenizer.SimpleRegexBasedTokenizer
import ai.koog.serialization.JSONPrimitive
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
 * ## Graph shape (v4)
 *
 * The whole loop below sits inside one Koog `subgraphWithRetry` ("attemptObjective") when a
 * [KoogObjectiveRetryPolicy] is supplied — see "Retry after FAILED". Without a policy the loop IS
 * the strategy, exactly as in v3.
 *
 * ```
 *  nodeStart ──▶ prunePreRequest ──▶ nodeLLMRequest(only-calling-tools)
 *                                          │
 *            ┌── {every call is objectiveStatus} ──▶ executeObjectiveStatus ──┬── finished? ──▶ finish
 *            │                                                                │
 *            │                                          (IN_PROGRESS / refused)│
 *            └── {anything else, objectiveStatus batched in included} ──▶ nodeExecuteTools ──▶ prunePreSendResults
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
 * `objectiveStatus`, or by exhausting the per-objective LLM-call budget ([DEFAULT_MAX_LLM_CALLS],
 * enforced by [LlmCallBudgetLlmClient]), which surfaces as a failure.
 *
 * ## Retry after FAILED (Koog `subgraphWithRetry`)
 *
 * `objectiveStatus=FAILED` is the model giving up, and it is sometimes wrong — the control it says
 * is missing is one scroll away. With a [KoogObjectiveRetryPolicy] the loop runs inside Koog's
 * `subgraphWithRetry`: after each attempt the policy judges the captured outcome; on a reject Koog
 * restores the conversation it forked before the attempt, appends the policy's feedback as a user
 * message, and runs the loop again. Only the conversation rewinds — the device stays where the
 * failed attempt left it, and the feedback says so. The retry spends from the same LLM-call budget
 * as the first attempt, so the ceiling on an objective's cost does not move. The policy's rule,
 * default retry count, and kill-switch live on [KoogObjectiveRetryPolicy].
 *
 * The rewound context includes the ADVERTISED TOOL LIST, and the host's tool repo is not rewound —
 * a `setActiveToolSets` the failed attempt made is still in force. The host therefore marks the
 * surface dirty when it grants a retry, and `prunePreRequest` asks [onToolSurfaceRefresh] before the
 * retry's first request so the model sees the repo's real surface, not the pre-switch menu.
 *
 * ## Budget: LLM calls, not graph iterations
 *
 * Koog's `maxAgentIterations` counts *node executions* — every hop in the graph above, including
 * `executeTools` and the in-memory prune nodes — so it is a runaway guard for the graph, not a cost
 * budget. This loop runs three nodes per LLM turn, so `25` iterations bought about eight LLM calls
 * while the legacy runner's `25` meant twenty-five. Callers therefore pass an LLM-call budget
 * ([createInProcess]'s `maxLlmCalls`); the agent counts requests in [LlmCallBudgetLlmClient] and sets
 * Koog's iteration limit to [graphIterationCeiling], far enough above the budget that the LLM counter
 * always trips first.
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
 * ## Runtime toolset switching (progressive disclosure)
 *
 * The LLM starts with a minimal tool surface (the `always_enabled` toolsets) and can widen it
 * mid-run by calling `setActiveToolSets` — a [xyz.block.trailblaze.toolcalls.ConfigTrailblazeTool]
 * that mutates the session's [xyz.block.trailblaze.toolcalls.TrailblazeToolRepo] rather than driving
 * the device. Koog poses two obstacles to this that the legacy MCP path doesn't have, both handled
 * by the host runner ([runPromptsWithKoogStrategyGraph]):
 *
 *  1. **Config tools aren't device tools.** `setActiveToolSets` isn't an `ExecutableTrailblazeTool`,
 *     so routing it through the driver agent throws "Unhandled Trailblaze tool". The host's tool
 *     dispatcher intercepts `ConfigTrailblazeTool` and executes it against the repo instead (mirroring
 *     the legacy `DirectMcpAgent`).
 *  2. **Koog's tool surface is fixed at run start.** The [ToolRegistry] (execution) and the agent's
 *     advertised `tools` descriptors (what the LLM sees) are both snapshotted when the [AIAgent] is
 *     built, so mutating the repo alone changes nothing live. The [onToolSurfaceRefresh] hook closes
 *     this: after a config tool runs, the prune-pre-send node calls it to top up the live registry
 *     (so newly-active tools dispatch) and re-advertise the new descriptor set (so the LLM sees them)
 *     before the next request. When the hook is `null` (any non-host caller) the surface stays fixed.
 *
 * ## Koog 1.0.0 API symbols used
 *
 * - [strategy] (`ai.koog.agents.core.dsl.builder.strategy`) — builds the `AIAgentGraphStrategy<String, String>`.
 * - [nodeLLMRequestOnlyCallingTools], [nodeExecuteTools], [nodeLLMSendToolResultsOnlyCallingTools]
 *   (`ai.koog.agents.core.dsl.extension`) — the predefined nodes. The `OnlyCallingTools` request
 *   variants set `LLMParams.ToolChoice.Required`, matching the legacy runner's forced-tool policy.
 * - [onMessageParts] (`MessagePart.Tool.Call::class`) + `onCondition` + `transformed { ToolCalls(it) }`
 *   — the edge guard that routes a status-only response to the completion branch and everything
 *   else (mixed batches included, unfiltered) to the loop.
 * - `node(name) { input -> ... }` (`ai.koog.agents.core.dsl.builder`) — declares a custom
 *   pass-through node. Its lambda runs with the graph context (`AIAgentGraphContextBase`) as
 *   receiver, so `llm.writeSession { ... }` is reachable from inside it (used by the `prune*` nodes).
 * - `llm.writeSession { prompt = prompt.copy(messages = <pruned>) }`
 *   ([ai.koog.agents.core.agent.context.AIAgentLLMContext.writeSession] →
 *   [ai.koog.agents.core.agent.session.AIAgentLLMWriteSession]) — the write session exposes the live
 *   `prompt` (a [ai.koog.prompt.Prompt] with `getMessages()` / `copy(...)`) as a mutable property.
 *   Rewriting it in place is how the prune nodes drop stale screen state.
 * - `forwardTo` (an infix member on `AIAgentNodeBase`, inherited by the start node) — declares an edge.
 * - Not `onToolCalls`: its per-call predicate fires when ANY call matches and forwards only the
 *   matching calls, so a batch of `assertVisible` + `objectiveStatus` used to take the completion
 *   branch with the assertion silently dropped. The whole-response guard above sees the batch.
 *   (The Koog docs also reference `onAssistantMessage`/`onTextMessage` for a text-termination path
 *   — unused here, since forced tool-calling means the agent never returns plain text.)
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
  private val llmCallBudget: LlmCallBudgetLlmClient,
) {

  /** LLM requests issued so far for the current objective. */
  val llmCallsMade: Int get() = llmCallBudget.llmCallsMade

  /**
   * Runs the agent with the given objective.
   *
   * @param objective The task to accomplish (e.g., "Tap the login button")
   * @return The agent's final assistant message.
   * @throws xyz.block.trailblaze.exception.MaxCallsLimitReachedException when the objective needs
   *   more LLM calls than the budget allows.
   */
  suspend fun run(objective: String): String {
    llmCallBudget.beginObjective(objective)
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
    /**
     * Default per-objective LLM-call budget. Same number, and the same unit, as the legacy
     * `TrailblazeRunner.DEFAULT_MAX_STEPS`, so `--max-llm-calls` means one thing on both agents.
     */
    const val DEFAULT_MAX_LLM_CALLS = 25

    /**
     * Koog graph iterations allowed per budgeted LLM call. The loop's worst case is four node
     * executions per request (`executeTools`, `prunePreSendResults`, `announceCompress`, then the
     * request itself; `compressHistory` is a request of its own), so this leaves the iteration limit
     * as a pure runaway guard that the LLM-call budget always reaches first. Deliberately generous:
     * adding a node to the graph must not silently shrink the budget again.
     */
    const val GRAPH_ITERATIONS_PER_LLM_CALL = 10

    /** Koog `maxAgentIterations` for a given LLM-call budget — see [GRAPH_ITERATIONS_PER_LLM_CALL]. */
    fun graphIterationCeiling(maxLlmCalls: Int): Int = maxLlmCalls * GRAPH_ITERATIONS_PER_LLM_CALL

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
     * Shape: prune → force-request → (objectiveStatus alone → execute → finished? → finish, else
     * loop) | (anything else → execute ALL calls → prune → [compress?] → send → loop). The two
     * `prune*` pass-through nodes keep only the latest screen state in the prompt (see
     * [pruneScreenStateHistory]).
     *
     * Two things that used to end the objective no longer do. A response that batches
     * `objectiveStatus` with other tools runs every call and loops — the model was claiming an
     * outcome before seeing the assertions it asked for, and finishing there dropped those calls
     * unexecuted. And an executed `objectiveStatus` only finishes when [isObjectiveFinished] says so:
     * by default IN_PROGRESS keeps looping (it is, by its own name, not an outcome), and the host
     * substitutes the outcome its dispatcher actually recorded, so a COMPLETED it refused loops too.
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
      /**
       * Hook invoked before each follow-up LLM request (in the prune-pre-send node). Returns the
       * new advertised [ToolDescriptor] list when a `setActiveToolSets`-style [ConfigTrailblazeTool]
       * [xyz.block.trailblaze.toolcalls.ConfigTrailblazeTool] has changed the active toolsets since
       * the last request, or `null` when the surface is unchanged (the common case — the advertised
       * tools are then left as-is). The host implementation also tops up the live tool registry as a
       * side effect so the newly-active tools dispatch. `null` (the default) disables runtime toolset
       * switching entirely, preserving the original fixed-surface behavior for any non-host caller.
       */
      onToolSurfaceRefresh: (() -> List<ToolDescriptor>?)? = null,
      /**
       * Tool descriptors to advertise to the LLM on the FIRST request, overriding the full registry
       * surface. Used to scope a verification step to its assertion/observation tools (mirroring the
       * legacy [xyz.block.trailblaze.toolcalls.TrailblazeToolRepo.getToolDescriptorsForStep]): with no
       * navigation tools advertised, `ToolChoice.Required` can't pick a scroll/tap, so a "verify X"
       * objective can't mutate scroll/nav state and break a following step. `null` (the default)
       * advertises the full registry surface — the original behavior for direction steps.
       */
      initialAdvertisedTools: List<ToolDescriptor>? = null,
      /**
       * Re-runs the loop after a rejected attempt — see "Retry after FAILED" in the class doc. `null`
       * (the default) means one attempt: the loop is the whole strategy, as before retries existed.
       */
      retryPolicy: KoogObjectiveRetryPolicy? = null,
      /**
       * Whether the `objectiveStatus` call that just executed ends the objective. Evaluated AFTER
       * the call ran, so a host dispatcher that declined to record it has already spoken. The
       * default reads the call's own `status` argument ([objectiveStatusReportsTerminalStatus]):
       * COMPLETED and FAILED finish, IN_PROGRESS loops. The host passes its recorded outcome instead.
       * Must be a pure read: Koog evaluates each outgoing edge's condition separately, so this is
       * asked more than once per executed status.
       */
      isObjectiveFinished: (ReceivedToolResults) -> Boolean = ::objectiveStatusReportsTerminalStatus,
    ): AIAgentGraphStrategy<String, String> =
      strategy(name) {
        if (retryPolicy == null) {
          defineObjectiveLoop(compressionStrategy, isHistoryTooBig, objectiveStatusToolName, onToolSurfaceRefresh, initialAdvertisedTools, isObjectiveFinished)
          return@strategy
        }
        // The loop becomes the body of Koog's retry subgraph. Koog forks the conversation before
        // running it and, on Reject, restores that fork, appends the feedback as a user message and
        // runs the body again — so a retried attempt starts from the objective plus the feedback,
        // never from the failed attempt's transcript.
        val attemptObjective by subgraphWithRetry<String, String>(
          condition = {
            when (val verdict = retryPolicy.judgeAttempt()) {
              KoogObjectiveRetryPolicy.Verdict.Accept -> ConditionResult.Approve
              is KoogObjectiveRetryPolicy.Verdict.Reject -> ConditionResult.Reject(verdict.feedback)
            }
          },
          // Koog's `maxRetries` is a cap on ATTEMPTS despite its name: it increments its counter
          // after every attempt, including the first, and retries only while counter < maxRetries.
          // So `maxRetries = 1` would never retry. The policy owns the real retry count.
          maxRetries = retryPolicy.maxRetries + 1,
          // Koog sends a non-null description to the MODEL as a user message before every attempt.
          // The system prompt already says how to finish, and the retry feedback says why we are
          // here; a third voice would only be noise in the transcript.
          conditionDescription = null,
          name = "attemptObjective",
        ) {
          defineObjectiveLoop(compressionStrategy, isHistoryTooBig, objectiveStatusToolName, onToolSurfaceRefresh, initialAdvertisedTools, isObjectiveFinished)
        }
        // Koog reports the last attempt's output plus whether the condition ever approved. The
        // host reads COMPLETED/FAILED from its own dispatcher, not from here, so only the message
        // passes through — an exhausted retry ends the run with a FAILED outcome, it does not throw.
        val unwrapRetry by node<RetrySubgraphResult<String>, String>("unwrapRetry") { it.output }
        edge(nodeStart forwardTo attemptObjective)
        edge(attemptObjective forwardTo unwrapRetry)
        edge(unwrapRetry forwardTo nodeFinish)
      }

    /**
     * The tool-calling loop itself, declared against whichever graph holds it: the strategy directly
     * (no retry) or the retry subgraph. `nodeStart`/`nodeFinish` are the holder's.
     */
    private fun AIAgentSubgraphBuilderBase<String, String>.defineObjectiveLoop(
      compressionStrategy: HistoryCompressionStrategy?,
      isHistoryTooBig: (Prompt) -> Boolean,
      objectiveStatusToolName: String,
      onToolSurfaceRefresh: (() -> List<ToolDescriptor>?)?,
      initialAdvertisedTools: List<ToolDescriptor>?,
      isObjectiveFinished: (ReceivedToolResults) -> Boolean,
    ) {
        // Forced tool-calling on every turn (ToolChoice.Required) — the LLM can't return free text.
        val callLlm by nodeLLMRequestOnlyCallingTools()
        val executeTools by nodeExecuteTools()
        // A dedicated execute node for a lone `objectiveStatus` call: same dispatcher (so the host
        // captures COMPLETED/FAILED and logs it). Its output goes to finish only when
        // [isObjectiveFinished] agrees; otherwise the result is sent back and the loop continues.
        val executeObjectiveStatus by nodeExecuteTools("executeObjectiveStatus")
        val sendToolResults by nodeLLMSendToolResultsOnlyCallingTools()

        // Prune nodes: pass-through nodes that rewrite the live prompt so older screen-bearing tool
        // results give up their payload (see [pruneScreenStateHistory]). They sit immediately
        // before each node that issues an LLM call — which means they run BEFORE the send-results
        // node appends the just-produced results, so the newest results a prune can see are the
        // previous turn's. A request therefore carries two turns of full screen state, not one.
        //
        // [prunePreRequest] is String -> String (it sits between nodeStart and the request node,
        // both of which carry the objective string). [prunePreSendResults] is
        // ReceivedToolResults -> ReceivedToolResults (it sits between nodeExecuteTools and the
        // send-results node, both of which carry the just-produced tool results). Neither node
        // transforms its input value — they only rewrite the side-channel chat history.
        val prunePreRequest by node<String, String>("prunePreRequest") { input ->
          // A retried attempt re-enters here after Koog restored the pre-attempt LLM context — and
          // the advertised tool list lives in that context, so it rewound too. The host's tool repo
          // did not (a toolset switch in the failed attempt is still in force), so the host marks
          // the surface dirty when it grants a retry and this refresh re-advertises from the repo.
          // On a first attempt nothing is dirty and this returns null.
          val refreshedTools = onToolSurfaceRefresh?.invoke()
          llm.writeSession {
            prompt = pruneScreenStateHistory(prompt)
            // Scope the advertised surface for the first request (verification steps → assertion
            // tools only). Persists for the rest of this run unless a config tool re-advertises via
            // onToolSurfaceRefresh; a verify step has no config tool, so the scope holds throughout.
            if (refreshedTools != null) {
              tools = refreshedTools
            } else if (initialAdvertisedTools != null) {
              tools = initialAdvertisedTools
            }
          }
          input
        }
        val prunePreSendResults by node<ReceivedToolResults, ReceivedToolResults>(
          "prunePreSendResults",
        ) { input ->
          // A ConfigTrailblazeTool (e.g. setActiveToolSets) may have just changed the active
          // toolsets in the executeTools node we came from. Ask the host to rebuild the surface:
          // a non-null result is the new advertised tool list (the host also tops up the live tool
          // registry so newly-active tools can dispatch), null means nothing changed. Computed
          // outside the write session since it only touches the repo/registry, not LLM state.
          val refreshedTools = onToolSurfaceRefresh?.invoke()
          llm.writeSession {
            prompt = pruneScreenStateHistory(prompt)
            if (refreshedTools != null) {
              tools = refreshedTools
            }
          }
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

        // A response that is ONLY objectiveStatus takes the completion branch. Anything else —
        // including objectiveStatus batched with other calls — executes every call and loops: the
        // model reported an outcome before seeing the results it asked for in the same turn, and
        // Koog's per-call filter used to drop those other calls unexecuted. Whole-response predicates,
        // not onToolCalls, because onToolCalls matches on ANY call and filters the batch.
        val statusOnly: suspend (List<MessagePart.Tool.Call>) -> Boolean =
          { calls -> isObjectiveStatusOnlyResponse(calls, objectiveStatusToolName) }
        edge(
          callLlm forwardTo executeObjectiveStatus
            onMessageParts MessagePart.Tool.Call::class
            onCondition { statusOnly(it) }
            transformed { ToolCalls(it) },
        )
        edge(
          callLlm forwardTo executeTools
            onMessageParts MessagePart.Tool.Call::class
            onCondition { !statusOnly(it) }
            transformed { ToolCalls(it) },
        )

        // The executed status ends the objective only if the gate says so. IN_PROGRESS (or a report
        // the host refused to record) is sent back like any other tool result and the loop goes on.
        edge(executeObjectiveStatus forwardTo finishAfterObjectiveStatus onCondition { isObjectiveFinished(it) })
        edge(executeObjectiveStatus forwardTo prunePreSendResults onCondition { !isObjectiveFinished(it) })
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

        // The forced follow-up is another tool call, routed exactly as the first one.
        edge(
          sendToolResults forwardTo executeObjectiveStatus
            onMessageParts MessagePart.Tool.Call::class
            onCondition { statusOnly(it) }
            transformed { ToolCalls(it) },
        )
        edge(
          sendToolResults forwardTo executeTools
            onMessageParts MessagePart.Tool.Call::class
            onCondition { !statusOnly(it) }
            transformed { ToolCalls(it) },
        )
    }

    /**
     * True when the model's response consists of nothing but `objectiveStatus` calls — the only
     * shape that takes the completion branch. A mixed batch is an ordinary tool turn.
     */
    internal fun isObjectiveStatusOnlyResponse(
      calls: List<MessagePart.Tool.Call>,
      objectiveStatusToolName: String = OBJECTIVE_STATUS_TOOL_NAME,
    ): Boolean = calls.isNotEmpty() && calls.all { it.tool == objectiveStatusToolName }

    /**
     * The default completion gate: the executed `objectiveStatus` finishes unless it said
     * IN_PROGRESS. Read from the call's own arguments (case-insensitively, as the tool decodes them)
     * so it needs no host state. A status that cannot be read is treated as terminal — the legacy
     * behaviour — rather than trapping the agent in a loop it cannot leave.
     */
    internal fun objectiveStatusReportsTerminalStatus(results: ReceivedToolResults): Boolean =
      results.toolResults.none { it.reportedStatus().equals("IN_PROGRESS", ignoreCase = true) }

    private fun ReceivedToolResult.reportedStatus(): String? =
      (toolArgs.entries["status"] as? JSONPrimitive)?.contentOrNull

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
     * Rewrites [prompt] so older screen payloads stop riding along in every request.
     *
     * The legacy [xyz.block.trailblaze.agent.TrailblazeRunner] needs no equivalent: it never stores
     * screen state in history at all, and its `getLimitedHistory()` is a FIFO count window rather
     * than a latest-screen rule. This is the graph agent's own problem to solve.
     *
     * ## The rule
     *
     * Tool results land in the prompt as [MessagePart.Tool.Result] parts (inside a [Message.User]).
     * Every result in the **last message that carries one** is kept verbatim — a turn can call
     * several tools at once and all of their results are current perception. An *earlier*
     * `Tool.Result` is stripped down to
     * [STALE_SCREEN_PLACEHOLDER] when it either exceeds [STALE_SCREEN_OUTPUT_THRESHOLD_CHARS]
     * characters of text or carries any non-text part. Small text-only earlier results (a tap/swipe
     * confirmation) are left intact so the action trail is preserved.
     *
     * The attachment half of that rule is not a special case of the size half: `output` is a
     * text-only view of `parts`, so an older screenshot with a short caption measures as tiny and
     * would otherwise be kept for the rest of the run. No Trailblaze tool puts an attachment in a
     * result today — screenshots reach the model via [ScreenshotAttachingLlmClient], which attaches
     * them to the user message — so that half is forward-compatibility for Koog 1.2 image results,
     * covered by tests rather than by a live producer.
     *
     * Note this runs one turn behind: the prune nodes sit *before* the send-results node, which is
     * what appends the just-produced results. So a request carries the previous turn's results plus
     * the new ones — bounded at two, and self-correcting on the next pass, but not "only the
     * latest".
     *
     * Message structure (the tool-call / tool-result pairing, message order, roles) is fully
     * preserved — only the content of qualifying older parts changes — so Koog's tool-call
     * bookkeeping stays valid. A stripped result keeps a single text part: any images or files it
     * carried go with the stale payload.
     *
     * Emits one `[KOOG_PRUNE]` debug line per call so a multi-snapshot run can be eyeballed for
     * context staying flat.
     */
    fun pruneScreenStateHistory(prompt: ai.koog.prompt.Prompt): ai.koog.prompt.Prompt {
      val messages = prompt.messages

      // The last message carrying a tool result holds the latest screen state, and EVERY result in
      // it is kept. A turn can call several tools at once, and all of their results are current
      // perception — keying this to the last result *part* would strip a sibling produced by the
      // same turn, which under the attachment rule below means discarding a screenshot on arrival.
      val latestToolResultMessage = messages.indexOfLast { message ->
        message.parts.any { it is MessagePart.Tool.Result }
      }

      if (latestToolResultMessage < 0) {
        // Nothing has produced a tool result yet (e.g. the very first LLM request).
        Console.log("[KOOG_PRUNE] no tool results yet; nothing to prune; prompt msgs=${messages.size}")
        return prompt
      }

      var keptChars = 0
      var strippedCount = 0
      var savedChars = 0
      var droppedNonTextParts = 0
      val prunedMessages = messages.mapIndexed { messageIndex, message ->
        val newParts = message.parts.map { part ->
          if (part !is MessagePart.Tool.Result) return@map part
          // `output` is a text-only view of `parts`, so it cannot see an attachment. Sizing the
          // gate on it alone would keep an older image with a short caption forever — the most
          // expensive thing that can sit in the prompt, held by the cheapest measurement of it.
          val nonTextParts = part.parts.count { it !is MessagePart.Text }
          when {
            messageIndex == latestToolResultMessage -> {
              // Latest screen state — keep verbatim, attachments included.
              keptChars += part.output.length
              part
            }
            nonTextParts > 0 || part.output.length > STALE_SCREEN_OUTPUT_THRESHOLD_CHARS -> {
              // Older, and either large or carrying an attachment → stale screen payload. Replacing
              // every part rather than just the text is what actually drops the image.
              strippedCount++
              // Signed on purpose: swapping a short caption for the placeholder makes the text
              // GROW, and a clamp would report that as zero rather than as the cost it is.
              savedChars += part.output.length - STALE_SCREEN_PLACEHOLDER.length
              droppedNonTextParts += nonTextParts
              part.copy(parts = listOf(MessagePart.Text(STALE_SCREEN_PLACEHOLDER)))
            }
            else -> part // Older, small, text-only → keep (preserves the action trail).
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
          "stripped $strippedCount older (${savedChars}c text delta, $droppedNonTextParts non-text " +
          "parts dropped); prompt msgs=${prunedMessages.size}",
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
     * @param maxLlmCalls Per-objective LLM-call budget (see "Budget" in the class doc). Counted in
     *   requests sent to the model, never in graph iterations; exceeding it throws
     *   [xyz.block.trailblaze.exception.MaxCallsLimitReachedException] from [run].
     * @param onToolSurfaceRefresh Hook for runtime toolset switching — see [buildStrategy]. When a
     *   `setActiveToolSets`-style [xyz.block.trailblaze.toolcalls.ConfigTrailblazeTool] changes the
     *   active toolsets mid-run, this lets the host re-advertise the new tool surface (and top up
     *   the live [toolRegistry] so the new tools dispatch). `null` keeps the fixed-surface behavior.
     * @param initialAdvertisedTools Advertised-tool override for the first request — see [buildStrategy].
     *   Pass a verification step's scoped descriptors so the agent can only assert, not navigate.
     *   `null` advertises the full registry surface.
     * @param instrumentation Per-objective recorder for Koog's own lifecycle events — see
     *   [KoogRunInstrumentation]. Pass one to make a run that throws attributable to the node that
     *   threw; `null` installs no Koog features at all, leaving the agent exactly as it was.
     * @param retryPolicy Re-runs the objective after the model reports FAILED — see "Retry after
     *   FAILED" in the class doc. `null` keeps the single-attempt loop. Ignored when
     *   [KoogObjectiveRetryPolicy.DISABLE_FAILED_RETRY_ENV] is set.
     * @return A ready-to-use agent.
     */
    fun createInProcess(
      llmClient: LLMClient,
      llmModel: TrailblazeLlmModel,
      toolRegistry: ToolRegistry,
      systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
      maxLlmCalls: Int = DEFAULT_MAX_LLM_CALLS,
      onToolSurfaceRefresh: (() -> List<ToolDescriptor>?)? = null,
      initialAdvertisedTools: List<ToolDescriptor>? = null,
      instrumentation: KoogRunInstrumentation? = null,
      retryPolicy: KoogObjectiveRetryPolicy? = null,
      /** See [buildStrategy]'s parameter of the same name. */
      isObjectiveFinished: (ReceivedToolResults) -> Boolean = ::objectiveStatusReportsTerminalStatus,
    ): KoogStrategyGraphAgent {
      // Outermost decorator: a refused request must not reach the screenshot/logging clients below.
      val budgetedClient = LlmCallBudgetLlmClient(delegate = llmClient, maxLlmCalls = maxLlmCalls)
      val agent = buildAgent(
        llmClient = budgetedClient,
        llmModel = llmModel,
        toolRegistry = toolRegistry,
        systemPrompt = systemPrompt,
        maxLlmCalls = maxLlmCalls,
        onToolSurfaceRefresh = onToolSurfaceRefresh,
        initialAdvertisedTools = initialAdvertisedTools,
        instrumentation = instrumentation,
        retryPolicy = retryPolicy,
        isObjectiveFinished = isObjectiveFinished,
      )
      return KoogStrategyGraphAgent(agent, toolRegistry, budgetedClient)
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
      maxLlmCalls: Int,
      onToolSurfaceRefresh: (() -> List<ToolDescriptor>?)? = null,
      initialAdvertisedTools: List<ToolDescriptor>? = null,
      instrumentation: KoogRunInstrumentation? = null,
      retryPolicy: KoogObjectiveRetryPolicy? = null,
      isObjectiveFinished: (ReceivedToolResults) -> Boolean = ::objectiveStatusReportsTerminalStatus,
    ): AIAgent<String, String> {
      val maxAgentIterations = graphIterationCeiling(maxLlmCalls)
      val retryDisabled = System.getenv(KoogObjectiveRetryPolicy.DISABLE_FAILED_RETRY_ENV)
        ?.lowercase() in setOf("1", "true")
      // The console line is the operator's breadcrumb; the instrumentation count is what the session
      // log and the A/B readout see. A run without instrumentation still gets the line.
      val effectiveRetryPolicy = retryPolicy?.takeUnless { retryDisabled }?.withRetryListener { attempt, _ ->
        instrumentation?.recordObjectiveRetry()
        Console.log("[KOOG_RETRY] attempt $attempt reported FAILED — rewinding the conversation and retrying with feedback")
      }
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
          "budget=$maxLlmCalls LLM calls (graph ceiling $maxAgentIterations iterations); " +
          if (compressionDisabled) {
            "history compression DISABLED"
          } else {
            "history compression enabled (threshold=$threshold msgs, keepRecent=$HISTORY_COMPRESSION_KEEP_RECENT_MESSAGES)"
          } +
          when {
            effectiveRetryPolicy != null -> "; retry after FAILED: up to ${effectiveRetryPolicy.maxRetries}"
            retryPolicy != null -> "; retry after FAILED DISABLED via ${KoogObjectiveRetryPolicy.DISABLE_FAILED_RETRY_ENV}"
            else -> "; retry after FAILED: none (no policy)"
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
          onToolSurfaceRefresh = onToolSurfaceRefresh,
          initialAdvertisedTools = initialAdvertisedTools,
          retryPolicy = effectiveRetryPolicy,
          isObjectiveFinished = isObjectiveFinished,
        ),
        toolRegistry = toolRegistry,
        systemPrompt = systemPrompt,
        maxIterations = maxAgentIterations,
        installFeatures = { installInstrumentation(instrumentation) },
      )
    }

    /**
     * Installs the two Koog features the instrumentation needs, or nothing when [instrumentation] is
     * null (no feature installed, so the agent behaves exactly as it did before this existed).
     *
     * `MessageTokenizer` is installed for its estimate of prompt size *before* a request goes out —
     * something the provider's own post-hoc counts can't give, and the measurement a token-based
     * compression gate would have to run on. `EventHandler` is what turns a throw into an
     * attribution: [ai.koog.agents.core.feature.handler.node.NodeExecutionFailedContext] fires on the
     * way out of the failing node, before the exception has unwound past it.
     *
     * Handlers must not throw — Koog invokes them inline on the failing path, so an exception here
     * would replace the failure it was recording. Everything they touch is a non-null field of the
     * event context, except the tokenizer lookup, which is guarded.
     */
    private fun GraphAIAgent.FeatureContext.installInstrumentation(instrumentation: KoogRunInstrumentation?) {
      if (instrumentation == null) return
      install(MessageTokenizer) {
        // Dependency-free approximation, deliberately: this number is a size signal, never a bill.
        tokenizer = SimpleRegexBasedTokenizer()
      }
      handleEvents {
        onLLMCallStarting { eventContext ->
          instrumentation.recordLlmCallStarting(
            runCatching { eventContext.context.tokenizer().tokenCountFor(eventContext.prompt) }.getOrNull(),
          )
        }
        onLLMCallCompleted { instrumentation.recordLlmCallCompleted() }
        onNodeExecutionFailed { eventContext ->
          instrumentation.recordNodeFailure(
            nodeName = eventContext.node.name,
            errorType = eventContext.error::class.simpleName ?: "Throwable",
            errorMessage = eventContext.error.message,
          )
        }
        // Koog turns a failed tool call into a result it feeds back to the model rather than
        // throwing, so these are breadcrumbs the agent usually recovered from — never the cause of
        // whatever eventually threw. [KoogRunInstrumentation] keeps them apart for that reason.
        onToolCallFailed { eventContext ->
          instrumentation.recordRecoveredToolFailure(eventContext.toolName, eventContext.message)
        }
        onToolValidationFailed { eventContext ->
          instrumentation.recordRecoveredToolFailure(eventContext.toolName, eventContext.message)
        }
      }
    }
  }
}
