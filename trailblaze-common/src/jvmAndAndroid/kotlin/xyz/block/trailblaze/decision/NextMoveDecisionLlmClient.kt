package xyz.block.trailblaze.decision

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import xyz.block.trailblaze.http.CachedTokenCaptureInterceptor
import xyz.block.trailblaze.http.TrailblazeHttpClientFactory
import xyz.block.trailblaze.llm.CachedTokenExtractor
import xyz.block.trailblaze.util.Console

/**
 * Lets a [DecisionEngine] make the agent's next move when it is sure, and the wrapped LLM make every
 * other one.
 *
 * Every step request becomes one choice question over every concrete move: tap or check each element
 * on screen, run each offered tool, or report the step done or impossible (up to 255 options). The
 * engine returns a probability for each, in well under a second. Its sure answers (at or above
 * [threshold], 0.95 by default) can be acted on; anything less goes to the LLM.
 *
 * It acts only on a tap at or above [threshold] or a "done" at or above [doneThreshold] (by
 * default [threshold] too), on a plain action step: never on a verification step (whose "done" needs a passing assertion as evidence) or a step worded as one
 * ("Verify …", "Check …"), never on a step with a condition ("if", "when", "until", "on iOS … on
 * Android …"), never "done" before the step has made a move, and never
 * a tap on an element this step already tapped. Anything else, an engine error, and an engine
 * slower than [engineTimeoutMs] leave the request to the LLM unchanged.
 *
 * [Mode.SHADOW] asks on every step request and always returns the LLM's answer, so a normal run
 * logs what the engine would have done (and whether it would have acted) next to what the LLM did.
 * The engine runs alongside the LLM, so it can hold an answer back by at most [engineTimeoutMs].
 * [Mode.RACE] sends both at once, drops the LLM request when the engine acts first, and returns
 * the LLM's answer as soon as it comes otherwise (saves time; the LLM's input is usually still
 * billed). [Mode.FIRST] asks the engine first and calls the LLM only when it does not act (saves
 * cost; every turn waits for the engine). Use [Mode.FIRST]: with the engine answering in about 0.2s
 * against the LLM's 2s, it ties [Mode.RACE] on time on real phones without paying for dropped
 * requests.
 *
 * [Questions.TREE] asks a decision tree instead, in the same one request (several questions over one
 * screen cost the engine the same time as one): is the step finished, or does the screen need
 * changing or checking; if changing, by a tap, typing or a scroll; then which element, which text the
 * step quotes, or which way. It acts when every answer on the path is sure: a tap at [threshold], a
 * "done" at [doneThreshold] (by default [TREE_THRESHOLD]), a scroll or typing at [TREE_THRESHOLD]. It
 * types only into a focused field the tree is sure is empty, and never the same text twice in a
 * step, and leaves a fourth scroll the same way in a row to the LLM. Taps, scrolls and typing are
 * made on conditional and check-worded steps too, "done" only on a plain action step, and nothing on a verification
 * step. Replayed over recorded app runs, it took a share of the LLM's turns, more than the flat
 * question did, and almost always matched the LLM's move.
 * With [hideTools], a turn it leaves to the LLM shows the LLM only [KEPT_TOOLS] when the tree rules
 * out every other tool: the LLM still makes the move, reading a fraction of the tool descriptions.
 * It is also shown [SHOW_TOOLS], which lists every other tool in a line, so a move the tree did not
 * foresee can still be made with an app's own tool; see [withKeptTools].
 *
 * Each question is logged to the session as a decision request, and a move the engine made is
 * marked [AnsweredWithoutLlm] so the report shows who made it. Enabled with
 * `TRAILBLAZE_DECISION_MOVES`; see [wrapIfEnabled].
 */
class NextMoveDecisionLlmClient(
  private val delegate: LLMClient,
  private val engine: DecisionEngine,
  private val decisionModel: String,
  private val mode: Mode,
  private val threshold: Double,
  /** The step the runner is working on. */
  private val objective: String,
  /** Verification steps are never acted on; see the class docs. */
  private val verification: Boolean,
  /** The screen the LLM is shown, as ref-annotated text; null when there is none yet. */
  private val screenText: () -> String?,
  /** How long a turn waits on the engine before leaving the move to the LLM. */
  private val engineTimeoutMs: Long = DEFAULT_ENGINE_TIMEOUT_MS,
  private val questions: Questions = Questions.FLAT,
  /** With [Questions.TREE] in [Mode.FIRST]: show the LLM only [KEPT_TOOLS] when the tree rules out every other tool. */
  private val hideTools: Boolean = false,
  /** Shown beside [KEPT_TOOLS] when tools are hidden: the target's `always_shown_tools`. */
  private val alwaysShownTools: Set<String> = emptySet(),
  /** With [hideTools]: once this many of the step's turns went to the LLM, show it every tool. Null never does. */
  private val showAllToolsAfter: Int? = null,
  /**
   * How sure a "done" must be, when it should differ from the default: [threshold] for the flat
   * question, [TREE_THRESHOLD] for the tree. A wrong "done" is usually caught by the next step or a
   * later check, while a wrong tap changes the screen; on a trail's last step with no check after it,
   * nothing catches it.
   */
  private val doneThreshold: Double? = null,
) : LLMClient() {

  private val flatDoneThreshold = doneThreshold ?: threshold
  private val treeDoneThreshold = doneThreshold ?: TREE_THRESHOLD

  /** The step's turns left to the LLM so far, for [showAllToolsAfter]. */
  private val llmTurns = AtomicInteger()

  enum class Mode {
    SHADOW,
    RACE,
    FIRST,
  }

  /** How the engine is asked: one choice over every move, or a decision tree. See the class docs. */
  enum class Questions {
    FLAT,
    TREE,
  }

  /** What the engine picked, and whether this client makes that move. */
  internal data class Verdict(
    val option: String,
    val p: Double,
    val act: Boolean,
    val why: String,
    /** The offered tool a tap is made with: `tap` on a phone, `web_click` in a browser. */
    val tapTool: String = TAP,
    /** What a tap's element shows, as the engine was offered it. */
    val label: String? = null,
    /** A scroll or typing: the tool and its arguments, without the reasoning. */
    val call: Pair<String, JsonObject>? = null,
    /** The engine is sure the LLM's move needs none of the tools outside [KEPT_TOOLS]. */
    val keptToolsSuffice: Boolean = false,
  )

  override suspend fun execute(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): Message.Assistant {
    // Every step request offers objectiveStatus; nothing else that reaches an agent's LLM client
    // (element comparison, history compression) does.
    if (tools.none { it.name == OBJECTIVE_STATUS }) return delegate.execute(prompt, model, tools)
    val plain = !verification && !WORDED_AS_A_CHECK.containsMatchIn(objective) && !BRANCHES.containsMatchIn(objective)
    val eligible = if (questions == Questions.TREE) !verification else plain
    // Asking can only pay when the answer may be acted on, or when it is being recorded.
    if (!eligible && mode != Mode.SHADOW) return delegate.execute(prompt, model, tools)
    val history = historyOf(prompt)
    val request =
      runCatching { if (questions == Questions.TREE) treeRequestFor(prompt, history) else requestFor(prompt, tools, history) }
        .getOrNull()
        ?: run {
          // A turn with no text screen is still a turn the LLM took.
          if (mode == Mode.FIRST) stuck()
          return delegate.execute(prompt, model, tools)
        }
    val offered = tools.map { it.name }.toSet()
    val flatOptions = request.questions[QUESTION]?.choiceOptions?.keys.orEmpty()
    fun judged(response: DecisionResponse): Verdict =
      if (questions == Questions.TREE) judgeTree(response, request, offered, history, eligible, plain)
      else judge(response, flatOptions, offered, history, eligible)
    suspend fun ask(): Verdict? = askEngine(request, ::judged)
    return when (mode) {
      // Both start together, and the ask is bounded, so the LLM's answer waits at most the rest of
      // the engine's timeout.
      Mode.SHADOW ->
        coroutineScope {
          val shadow = async { ask() }
          delegate.execute(prompt, model, tools).also { shadow.await() }
        }
      Mode.FIRST -> {
        val verdict = ask()
        if (verdict?.act == true) {
          respond(verdict, request)
        } else {
          // Counted on every LLM turn, hidden tools or not.
          val stuck = stuck()
          if (verdict?.keptToolsSuffice == true && hideTools && !stuck) {
            withKeptTools(prompt, model, tools)
          } else {
            delegate.execute(prompt, model, tools)
          }
        }
      }
      Mode.RACE ->
        coroutineScope {
          val llm = async { delegate.execute(prompt, model, tools) }
          val engineAsk = async { ask() }
          select {
            // The LLM answered first: its move stands, and the engine's answer is no longer needed.
            llm.onAwait { answer ->
              engineAsk.cancel()
              answer
            }
            engineAsk.onAwait { verdict ->
              if (verdict?.act == true) {
                llm.cancel()
                respond(verdict, request, llmRequestSent = true)
              } else {
                llm.await()
              }
            }
          }
        }
    }
  }

  /**
   * Counts a turn left to the LLM, and says whether the step has had more than [showAllToolsAfter]
   * of them. Most steps that finish take few LLM calls, while steps that fail mostly run long, so a
   * step past the limit is likely stuck.
   */
  private fun stuck(): Boolean {
    val turn = llmTurns.incrementAndGet()
    val after = showAllToolsAfter ?: return false
    if (turn == after + 1) Console.log("[DECISION_MOVES] $after LLM turns into the step; showing the LLM every tool")
    return turn > after
  }

  /**
   * The LLM's move, shown [KEPT_TOOLS], [alwaysShownTools] and [SHOW_TOOLS] in place of the rest. A
   * [SHOW_TOOLS] call is answered here and the LLM asked again, with the tools it named, in the same turn: the agent sees
   * only the move, and the turn's token counts are both calls'. The move is tagged with how many tools
   * the LLM was shown ([TOOLS_SHOWN_KEY]) and what it asked for ([TOOLS_ASKED_KEY]), since the session
   * log's offered tools are every tool. Replayed over logged turns where the
   * LLM shown every tool had picked one of the others, it almost always asked for that tool; on
   * ordinary turns it rarely asked.
   */
  private suspend fun withKeptTools(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
    val (kept, others) = tools.partition { it.name in KEPT_TOOLS || it.name in alwaysShownTools }
    if (others.isEmpty()) return delegate.execute(prompt, model, tools)
    Console.log("[DECISION_MOVES] showing the LLM ${kept.size} of ${tools.size} tools, and a list of the rest")
    val first = delegate.execute(prompt, model, kept + showToolsFor(others))
    val ask = first.parts.filterIsInstance<MessagePart.Tool.Call>().firstOrNull { it.tool == SHOW_TOOLS }
      ?: return first.tagged(shown = kept.size + 1, asked = null, cache = cacheUsage(first.metaInfo))
    val asked = runCatching { (json.parseToJsonElement(ask.args.orEmpty()) as JsonObject)["names"] as? JsonPrimitive }
      .getOrNull()?.contentOrNull.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val named = others.filter { it.name in asked }
    // A name that is not one of the listed tools gets every tool, rather than a turn with none of them.
    val (shown, result) =
      if (named.isEmpty()) tools to "None of ${asked.ifEmpty { listOf("those") }.joinToString()} is a listed tool; every tool is callable now."
      else (kept + named) to "Now callable: ${named.joinToString { it.name }}."
    Console.log("[DECISION_MOVES] the LLM asked for ${asked.joinToString()}: $result")
    val shownPrompt = prompt.withMessages {
      it + Message.Assistant(listOf<MessagePart.ResponsePart>(ask), first.metaInfo) +
        Message.User(listOf<MessagePart.RequestPart>(MessagePart.Tool.Result(ask.id, SHOW_TOOLS, result)), RequestMetaInfo(Clock.System.now()))
    }
    val answer = delegate.execute(shownPrompt, model, shown)
    fun sum(a: Int?, b: Int?) = if (a == null && b == null) null else (a ?: 0) + (b ?: 0)
    val (m1, m2) = first.metaInfo to answer.metaInfo
    val (c1, c2) = cacheUsage(m1) to cacheUsage(m2)
    return answer.copy(
      metaInfo = m2.copy(
        totalTokensCount = sum(m1.totalTokensCount, m2.totalTokensCount),
        inputTokensCount = sum(m1.inputTokensCount, m2.inputTokensCount),
        outputTokensCount = sum(m1.outputTokensCount, m2.outputTokensCount),
      ),
    ).tagged(shown = shown.size, asked = asked, cache = CacheUsage(c1.read + c2.read, c1.created + c2.created))
  }

  internal data class CacheUsage(val read: Long, val created: Long)

  /**
   * The cached input tokens a response reports: in its metadata, else as [CachedTokenCaptureInterceptor]
   * captured them. Read here because the session logger looks them up by the move's token counts,
   * which match neither call once two are summed, and skips the lookup once the move has metadata.
   */
  private fun cacheUsage(meta: ResponseMetaInfo): CacheUsage {
    val data = meta.metadata
      ?: CachedTokenCaptureInterceptor.getByTokenCounts(meta.inputTokensCount?.toLong() ?: 0L, meta.outputTokensCount?.toLong() ?: 0L)
    return CacheUsage(CachedTokenExtractor.extractCacheReadTokens(data), CachedTokenExtractor.extractCacheCreationTokens(data))
  }

  /**
   * This move's response metadata plus [TOOLS_SHOWN_KEY], [TOOLS_ASKED_KEY] when the LLM asked, and
   * [cache] under the keys [CachedTokenExtractor] reads first.
   */
  private fun Message.Assistant.tagged(shown: Int, asked: List<String>?, cache: CacheUsage): Message.Assistant =
    copy(
      metaInfo = metaInfo.copy(
        metadata = buildJsonObject {
          metaInfo.metadata?.forEach { (key, value) -> put(key, value) }
          put(CACHE_READ_KEY, cache.read)
          put(CACHE_CREATION_KEY, cache.created)
          put(TOOLS_SHOWN_KEY, shown)
          if (asked != null) put(TOOLS_ASKED_KEY, asked.joinToString(","))
        },
      ),
    )

  private suspend fun askEngine(request: DecisionRequest, judged: (DecisionResponse) -> Verdict): Verdict? {
    val verdict = try {
      withTimeoutOrNull(engineTimeoutMs) {
        val response = engine.decideLogged(request, compactForLog(request), ::topProbabilities) { response ->
          judged(response).let { v ->
            val what = if (mode == Mode.SHADOW) "shadow, would ${if (v.act) "act" else "leave it to the LLM"}"
              else if (v.act) "acted" else "left to the LLM"
            "$what: ${v.option} p=${"%.3f".format(v.p)}${if (v.act) "" else " (${v.why})"}"
          }
        }
        judged(response)
      } ?: run {
        Console.log("[DECISION_MOVES] ${engine.name} took over ${engineTimeoutMs}ms, left to the LLM")
        return null
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Console.log("[DECISION_MOVES] ${engine.name} failed, left to the LLM: ${e.message}")
      return null
    }
    Console.log("[DECISION_MOVES] ${mode.name.lowercase()} ${verdict.option} p=${"%.3f".format(verdict.p)} act=${verdict.act} ${verdict.why}")
    // Shadow mode records what it would have done and never does it.
    return verdict.takeUnless { mode == Mode.SHADOW }
  }

  /** Whether the engine's pick would be acted on, whatever the mode. */
  internal fun judge(
    response: DecisionResponse,
    options: Set<String>,
    offered: Set<String>,
    history: List<Call>,
    eligible: Boolean,
  ): Verdict {
    val answer = response.answers[QUESTION]
    val option = answer?.choice.orEmpty()
    val p = answer?.probabilities?.get(option) ?: 0.0
    val tapTool = TAP_TOOLS.firstOrNull { it in offered }
    val tappedRefs = history.filter { it.tool in TAP_TOOLS }.mapNotNull { it.arg("ref") }.toSet()
    val why =
      when {
        !eligible -> "verification or branching step"
        option !in options -> "not one of the options asked about"
        option == DONE && p < flatDoneThreshold -> "below $flatDoneThreshold"
        option != DONE && p < threshold -> "below $threshold"
        option == DONE && history.isEmpty() -> "done before the step made any move"
        option == DONE && !history.last().succeeded -> "done right after a move that failed"
        option == DONE -> return Verdict(option, p, act = true, why = "sure the step is done")
        !option.startsWith(TAP_PREFIX) -> "only taps and done are acted on"
        tapTool == null -> "tap not offered"
        option.removePrefix(TAP_PREFIX) in tappedRefs -> "this step already tapped it"
        else -> return Verdict(option, p, act = true, why = "sure of the tap", tapTool = tapTool)
      }
    return Verdict(option, p, act = false, why = why)
  }

  /**
   * The request as logged: each tool option by name only. Tool descriptions are the same on every
   * turn and are most of the question's size (about 25 KB with a large toolset); the elements'
   * labels, which say what a pick means, stay.
   */
  private fun compactForLog(request: DecisionRequest): DecisionRequest {
    // The tree offers no tools by description.
    val question = request.questions[QUESTION] ?: return request
    val options = question.choiceOptions.mapValues { (id, text) ->
      if (id.startsWith(TAP_PREFIX) || id.startsWith(ASSERT_PREFIX) || id == DONE || id == DONE_FAIL) text else "Run $id"
    }
    return request.copy(questions = mapOf(QUESTION to DecisionQuestion.choice(question.instructions, options)))
  }

  private fun respond(verdict: Verdict, request: DecisionRequest, llmRequestSent: Boolean = false): Message.Assistant {
    val note = "Decision engine ${engine.name} chose this move (p=${"%.3f".format(verdict.p)}) without an LLM call."
    val call = verdict.call
    val (tool, args) =
      if (verdict.option == DONE) {
        OBJECTIVE_STATUS to buildJsonObject {
          put("status", "COMPLETED")
          put("explanation", note)
        }
      } else if (call != null) {
        // The same next-move shape as a tap's, so the LLM does not repeat the move.
        val (did, again) =
          if (call.first == SWIPE) "Scrolled to reveal content further ${verdict.option.removePrefix(SCROLL_PREFIX)}" to "scrolling"
          else "Typed ${call.second["text"]}" to "typing it"
        call.first to buildJsonObject {
          call.second.forEach { (k, v) -> put(k, v) }
          put(
            "reasoning",
            "$did. Next: read the new screen and do what the step still asks for; if the step asked for " +
              "nothing more, report it complete. Only repeat " +
              "$again if the new screen shows it is still needed. " +
              "(Decision engine ${engine.name} chose this move at p=${"%.3f".format(verdict.p)}, without an LLM call.)",
          )
        }
      } else {
        val ref = verdict.option.removePrefix(TAP_PREFIX)
        verdict.tapTool to buildJsonObject {
          put("ref", ref)
          // The LLM reads this move on its next turn as its own, when the screen has moved on and the
          // ref no longer resolves. Without the element's label it cannot tell the tap already
          // happened, and without its own reasoning's shape (what is next) it can take the tap as
          // outside its plan and repeat it, adding an item to a cart twice. The note is written before
          // the tap runs, so it leaves a failed tap, or a step that wants a second tap, to the LLM.
          val tapped = verdict.label
            ?: request.questions[QUESTION]?.choiceOptions?.get(verdict.option)?.removePrefix("Tap ")
            ?: "[$ref]"
          put(
            "reasoning",
            "Tapped $tapped. Next: read the new screen and do what the step still asks for; if the step " +
              "asked for nothing more than this tap, report it complete. Tapping [$ref] again would tap it " +
              "a second time: do that only if the step asks for a second tap, or this tap's result reports " +
              "a failure. " +
              "(Decision engine ${engine.name} chose this move at p=${"%.3f".format(verdict.p)}, without an LLM call.)",
          )
        }
      }
    return Message.Assistant(
      listOf<MessagePart.ResponsePart>(
        MessagePart.Tool.Call("decision-" + Random.nextLong().toString(16), tool, args.toString())
      ),
      ResponseMetaInfo(
        timestamp = Clock.System.now(),
        modelId = "decision:$decisionModel",
        metadata = AnsweredWithoutLlm.metadata(AnsweredWithoutLlm.DECISION, llmRequestSent),
      ),
    )
  }

  /** One choice over every concrete next move, or null when there is no text screen to choose from. */
  internal fun requestFor(prompt: Prompt, tools: List<ToolDescriptor>, history: List<Call>): DecisionRequest? {
    val screen = screenText()?.takeIf { it.isNotBlank() } ?: return null
    val refs = ScreenRefs.parse(screen).map { it.ref to it.label }
    val options = LinkedHashMap<String, String>()
    options[DONE] = "Report the step's goal as met"
    options[DONE_FAIL] = "Report the step's goal as impossible to meet"
    for (tool in tools.sortedBy { it.name }) {
      if (tool.name in FOLDED_TOOLS) continue
      options[tool.name] = "Run ${tool.name}: ${tool.description.replace(WHITESPACE, " ").take(160)}"
    }
    // Every tap before any check: the cap cuts from the end, a page can have hundreds of elements
    // (a Wikipedia article has over 300 links), and only a tap is ever acted on.
    for ((ref, label) in refs) options["$TAP_PREFIX$ref"] = "Tap [$ref] $label".take(200)
    for ((ref, label) in refs) options["$ASSERT_PREFIX$ref"] = "Check [$ref] $label is visible".take(200)
    val capped = options.entries.take(engine.maxChoiceOptions).associate { it.key to it.value }
    val state = buildJsonObject {
      put("step_objective", stepText(prompt))
      put(
        "actions_taken_so_far_in_this_step",
        JsonArray(history.takeLast(5).map { JsonPrimitive(it.text) }.ifEmpty { listOf(JsonPrimitive("(none yet)")) }),
      )
      put("current_screen", screen.take(MAX_SCREEN_CHARS))
    }
    return DecisionRequest(
      state = state,
      model = decisionModel,
      questions = mapOf(QUESTION to DecisionQuestion.choice(INSTRUCTIONS, capped)),
    )
  }

  /**
   * The decision tree as one request, or null when there is no text screen. Every question is asked
   * whatever the first one's answer; [judgeTree] follows the path the answers take.
   */
  internal fun treeRequestFor(prompt: Prompt, history: List<Call>): DecisionRequest? {
    val screen = screenText()?.takeIf { it.isNotBlank() } ?: return null
    val elements = LinkedHashMap<String, String>()
    for (r in ScreenRefs.parse(screen).take(engine.maxChoiceOptions - 1)) elements[r.ref] = "[${r.ref}] ${r.label}".take(200)
    elements[NONE] = "None of these"
    val quoted = quotedIn(objective)
    val state = buildJsonObject {
      put("step_objective", stepText(prompt))
      put(
        "actions_taken_so_far_in_this_step",
        JsonArray(history.takeLast(5).map { JsonPrimitive(it.text) }.ifEmpty { listOf(JsonPrimitive("(none yet)")) }),
      )
      put("current_screen", screen.take(MAX_SCREEN_CHARS))
      put("step_type", if (verification) "verification" else "action")
    }
    val asked = linkedMapOf(
      TREE_MOVE to DecisionQuestion.choice(TREE_MOVE_INSTRUCTIONS, TREE_MOVE_OPTIONS),
      TREE_CHANGE to DecisionQuestion.choice(TREE_CHANGE_INSTRUCTIONS, TREE_CHANGE_OPTIONS),
      TREE_ELEMENT to DecisionQuestion.choice(TREE_ELEMENT_INSTRUCTIONS, elements),
      TREE_SCROLL to DecisionQuestion.choice(TREE_SCROLL_INSTRUCTIONS, TREE_SCROLL_OPTIONS),
    )
    if (quoted.isNotEmpty()) {
      val texts = LinkedHashMap<String, String>()
      quoted.forEachIndexed { i, q -> texts["q$i"] = "\"$q\"" }
      texts[NONE] = "Some other text"
      asked[TREE_TEXT] = DecisionQuestion.choice(TREE_TEXT_INSTRUCTIONS, texts)
      asked[TREE_FIELD] = DecisionQuestion.choice(TREE_FIELD_INSTRUCTIONS, TREE_FIELD_OPTIONS)
    }
    return DecisionRequest(state = state, model = decisionModel, questions = asked)
  }

  /**
   * Whether the tree's path ends in a move this client makes; see the class docs. Also whether the
   * LLM, if it moves instead, needs any tool outside [KEPT_TOOLS]: unsure of every move, the engine
   * can still be sure the move is not an app shortcut or other tool.
   */
  internal fun judgeTree(
    response: DecisionResponse,
    request: DecisionRequest,
    offered: Set<String>,
    history: List<Call>,
    eligible: Boolean,
    plain: Boolean,
  ): Verdict {
    fun p(question: String, choice: String) = response.answers[question]?.probabilities?.get(choice)
    val pOther = (p(TREE_MOVE, CHANGE) ?: 1.0) * (p(TREE_CHANGE, KIND_OTHER) ?: 1.0)
    // A browser's own tools are not among the kept ones.
    val mobile = TAP in offered
    return treePath(response, request, offered, history, eligible, plain).copy(keptToolsSuffice = mobile && pOther < OTHER_TOOLS_BELOW)
  }

  private fun treePath(
    response: DecisionResponse,
    request: DecisionRequest,
    offered: Set<String>,
    history: List<Call>,
    eligible: Boolean,
    plain: Boolean,
  ): Verdict {
    fun pick(question: String): Pair<String, Double> {
      val answer = response.answers[question]
      val choice = answer?.choice.orEmpty()
      return choice to (answer?.probabilities?.get(choice) ?: 0.0)
    }
    fun offeredIn(question: String, choice: String) = request.questions[question]?.choiceOptions?.containsKey(choice) == true
    val (move, pMove) = pick(TREE_MOVE)
    fun no(option: String, p: Double, why: String) = Verdict(option, p, act = false, why = why)
    if (!eligible) return no(move, pMove, "verification step")
    if (!offeredIn(TREE_MOVE, move)) return no(move, pMove, "not one of the options asked about")
    if (move == FINISHED) {
      return when {
        !plain -> no(DONE, pMove, "done on a conditional or check-worded step")
        pMove < treeDoneThreshold -> no(DONE, pMove, "below $treeDoneThreshold")
        history.isEmpty() -> no(DONE, pMove, "done before the step made any move")
        !history.last().succeeded -> no(DONE, pMove, "done right after a move that failed")
        else -> Verdict(DONE, pMove, act = true, why = "sure the step is done")
      }
    }
    if (move != CHANGE) return no("move:$move", pMove, "only done, taps, scrolls and typing are acted on")
    val (kind, pKind) = pick(TREE_CHANGE)
    if (!offeredIn(TREE_CHANGE, kind)) return no(kind, pKind, "not one of the options asked about")
    val pPath = minOf(pMove, pKind)
    when (kind) {
      KIND_TAP -> {
        val (ref, pRef) = pick(TREE_ELEMENT)
        val option = "$TAP_PREFIX$ref"
        val p = minOf(pPath, pRef)
        val tapTool = TAP_TOOLS.firstOrNull { it in offered }
        val tappedRefs = history.filter { it.tool in TAP_TOOLS }.mapNotNull { it.arg("ref") }.toSet()
        return when {
          ref == NONE || !offeredIn(TREE_ELEMENT, ref) -> no(option, p, "no element picked")
          p < threshold -> no(option, p, "below $threshold")
          tapTool == null -> no(option, p, "tap not offered")
          ref in tappedRefs -> no(option, p, "this step already tapped it")
          else -> Verdict(
            option, p, act = true, why = "sure of the tap", tapTool = tapTool,
            label = request.questions[TREE_ELEMENT]?.choiceOptions?.get(ref),
          )
        }
      }
      KIND_SCROLL -> {
        val (way, pWay) = pick(TREE_SCROLL)
        val option = "$SCROLL_PREFIX$way"
        val p = minOf(pPath, pWay)
        val finger = FINGER_FOR_REVEALING[way]
        // At the end of a list the screen stops changing, and the tree can keep asking for the same scroll.
        val inARow = history.takeLastWhile { it.tool == SWIPE && it.arg("direction") == finger }.size
        return when {
          finger == null -> no(option, p, "not one of the options asked about")
          p < TREE_THRESHOLD -> no(option, p, "below $TREE_THRESHOLD")
          SWIPE !in offered -> no(option, p, "swipe not offered")
          inARow >= MAX_SCROLLS_IN_A_ROW -> no(option, p, "already scrolled this way $inARow times in a row")
          else -> Verdict(option, p, act = true, why = "sure of the scroll", call = SWIPE to buildJsonObject { put("direction", finger) })
        }
      }
      KIND_TYPE -> {
        val (id, pText) = pick(TREE_TEXT)
        // Typing appends, and the screen shows an empty field's hint the way it shows a value.
        val (field, pField) = pick(TREE_FIELD)
        val option = "$TYPE_PREFIX$id"
        val p = minOf(pPath, pText, pField)
        val text = id.removePrefix("q").toIntOrNull()?.takeIf { id.startsWith("q") }?.let { quotedIn(objective).getOrNull(it) }
        val typeTool = TYPE_TOOLS.firstOrNull { it in offered }
        // Typing goes into the focused field and after any text already there.
        val focused = ((request.state as? JsonObject)?.get("current_screen") as? JsonPrimitive)?.contentOrNull.orEmpty().contains(FOCUSED)
        return when {
          TREE_TEXT !in request.questions -> no(option, pPath, "the step quotes no text")
          text == null -> no(option, p, "no quoted text picked")
          p < TREE_THRESHOLD -> no(option, p, "below $TREE_THRESHOLD")
          typeTool == null -> no(option, p, "typing not offered")
          !focused -> no(option, p, "no field is focused")
          field != FIELD_EMPTY -> no(option, p, "the focused field may already hold text")
          history.any { it.tool in TYPE_TOOLS && it.arg("text") == text } -> no(option, p, "this step already typed it")
          else -> Verdict(option, p, act = true, why = "sure of the text", call = typeTool to buildJsonObject { put("text", text) })
        }
      }
      else -> return no(kind, pPath, "only done, taps, scrolls and typing are acted on")
    }
  }

  /** The user's text in this conversation (the step, plus any retry notice), else the runner's objective. */
  private fun stepText(prompt: Prompt): String {
    val text =
      prompt.messages.filterIsInstance<Message.User>().flatMap { it.parts }.filterIsInstance<MessagePart.Text>()
        .joinToString("\n\n") { it.text.trim() }
    return text.ifBlank { objective }.takeLast(MAX_STEP_CHARS)
  }

  /** A move this step made; [succeeded] is false only when its result says it failed. */
  internal class Call(val tool: String, val args: JsonObject?, val succeeded: Boolean = true) {
    val text: String = ("$tool ${args ?: "{}"}".take(200)) + if (succeeded) "" else " (failed)"

    fun arg(name: String): String? = (args?.get(name) as? JsonPrimitive)?.contentOrNull
  }

  private fun historyOf(prompt: Prompt): List<Call> {
    val results = prompt.messages.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>().associateBy { it.id }
    return prompt.messages.filterIsInstance<Message.Assistant>().flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Call>()
      .map { call ->
        val args = runCatching { json.parseToJsonElement(call.args.orEmpty()) as? JsonObject }.getOrNull()
        Call(call.tool, args, succeeded = results[call.id]?.let { !reportsFailure(call.tool, it.output) } ?: true)
      }
  }

  /**
   * Whether a tool result says the move failed. The runner writes `Executed <tool>: Success(…)` for a
   * move that worked, `Executed <tool>: <error>(…)` for one the tool rejected, and
   * `Tool <tool> failed: …` for one that threw, sometimes after a loop warning.
   */
  private fun reportsFailure(tool: String, content: String): Boolean {
    val name = Regex.escape(tool)
    return Regex("""(?m)^(Tool $name failed:|Executed $name: (?!Success))""").containsMatchIn(content)
  }

  override fun llmProvider(): LLMProvider = delegate.llmProvider()

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

  override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = delegate.moderate(prompt, model)

  override fun close() = delegate.close()

  companion object {
    const val MODE_ENV = "TRAILBLAZE_DECISION_MOVES"
    const val THRESHOLD_ENV = "TRAILBLAZE_DECISION_MOVES_THRESHOLD"
    const val DONE_THRESHOLD_ENV = "TRAILBLAZE_DECISION_MOVES_DONE_THRESHOLD"
    const val ENGINE_URL_ENV = "TRAILBLAZE_DECISION_ENGINE_URL"
    const val ENGINE_KEY_ENV = "TRAILBLAZE_DECISION_ENGINE_KEY"
    const val MODEL_ENV = "TRAILBLAZE_DECISION_MODEL"

    /** The hosted API the decision contract comes from, and the key name its docs use. */
    const val DEFAULT_ENGINE_URL = "https://api.typesafe.ai"
    const val DEFAULT_ENGINE_KEY_ENV = "TYPESAFE_API_KEY"
    const val DEFAULT_MODEL = "jev-latest"

    /** Every setting [instrumentationArgs] can hand a device, by the name it goes under. */
    val SETTING_NAMES: List<String> =
      listOf(
        MODE_ENV, THRESHOLD_ENV, QUESTIONS_ENV, HIDE_TOOLS_ENV, SHOW_ALL_TOOLS_AFTER_ENV, DONE_THRESHOLD_ENV, MODEL_ENV, ENGINE_URL_ENV,
        ENGINE_KEY_ENV, DEFAULT_ENGINE_KEY_ENV,
      )
    const val DEFAULT_THRESHOLD = 0.95

    internal const val QUESTION = "next_move"
    internal const val DONE = "done_ok"
    internal const val DONE_FAIL = "done_fail"
    internal const val TAP_PREFIX = "tap:"
    internal const val ASSERT_PREFIX = "assert:"
    private const val TAP = "tap"

    /** `flat` (the default) or `tree`; see [Questions]. */
    const val QUESTIONS_ENV = "TRAILBLAZE_DECISION_MOVES_QUESTIONS"

    /** The tree's bar for a scroll and typing, and for "done" unless the done threshold is set; a tap's is the threshold. */
    const val TREE_THRESHOLD = 0.9
    internal const val TREE_MOVE = "move"
    internal const val TREE_CHANGE = "change"
    internal const val TREE_ELEMENT = "tap"
    internal const val TREE_SCROLL = "scroll"
    internal const val TREE_TEXT = "type"
    internal const val TREE_FIELD = "field"
    internal const val FIELD_EMPTY = "empty"
    internal const val FINISHED = "finished"
    internal const val CHANGE = "change"
    internal const val KIND_TAP = "tap"
    internal const val KIND_SCROLL = "scroll"
    internal const val KIND_TYPE = "type"
    internal const val KIND_OTHER = "other"
    const val HIDE_TOOLS_ENV = "TRAILBLAZE_DECISION_MOVES_HIDE_TOOLS"
    const val SHOW_ALL_TOOLS_AFTER_ENV = "TRAILBLAZE_DECISION_MOVES_SHOW_ALL_TOOLS_AFTER"

    /**
     * The tools the LLM is still shown on a phone when the tree rules out every other one: on recorded app runs
     * the LLM moved with these on nearly every turn, while the rest made up most of the tool
     * descriptions it read.
     */
    val KEPT_TOOLS = setOf(
      "tap", "web_click", "tapOnPoint", "longPress", "type", "inputText", "eraseText", "clearText", "hideKeyboard",
      "swipe", "scrollUntilTextIsVisible", "pressBack", "pressKey", "waitForChange", "assertVisible",
      "assertNotVisibleWithText", "assertWithAi", "assertWaypoint", "requestDetailedViewHierarchy", "takeSnapshot",
      "objectiveStatus",
    )

    /** Lists the tools the LLM is not shown, one line each, and makes the ones it names callable. */
    const val SHOW_TOOLS = "showTools"

    /** Response metadata: how many tools the LLM was shown for the move, on a turn with tools hidden. */
    const val TOOLS_SHOWN_KEY = "trailblaze.toolsShown"

    /** Response metadata: the names the LLM asked [SHOW_TOOLS] for, comma-separated. */
    const val TOOLS_ASKED_KEY = "trailblaze.toolsAsked"
    private const val CACHE_READ_KEY = "cache_read_input_tokens"
    private const val CACHE_CREATION_KEY = "cache_creation_input_tokens"

    internal fun showToolsFor(others: List<ToolDescriptor>): ToolDescriptor {
      val lines = others.joinToString("\n") { tool ->
        val summary = tool.description.trim().lineSequence().first().substringBefore(". ").trimEnd('.')
        "- ${tool.name}: ${summary.take(MAX_TOOL_SUMMARY_CHARS)}"
      }
      return ToolDescriptor(
        name = SHOW_TOOLS,
        description = "More tools exist than the ones you can call now. If one of these fits the next move better " +
          "than your current tools, call showTools with its name (several names comma-separated); they become " +
          "callable on your next turn.\n$lines",
        requiredParameters = listOf(
          ToolParameterDescriptor(name = "names", description = "Names of the tools to make callable, comma-separated", type = ToolParameterType.String),
        ),
      )
    }

    private const val MAX_TOOL_SUMMARY_CHARS = 120

    /** How unlikely the tree must think "another tool" before the LLM is shown only [KEPT_TOOLS]. */
    const val OTHER_TOOLS_BELOW = 0.01
    internal const val NONE = "none"
    private const val SCROLL_PREFIX = "scroll:"
    private const val TYPE_PREFIX = "type:"
    private const val SWIPE = "swipe"

    /**
     * Tools that type [text] into the focused field when given no `ref`, in the order one is picked:
     * `type` is the LLM's typing tool where it is offered, `inputText` where it is not.
     */
    private val TYPE_TOOLS = listOf("type", "inputText")

    /** How a screen's element list marks the field that has input focus. */
    private const val FOCUSED = "[focused]"

    /** Scrolls the same way in a row, after which the tree's next scroll that way is left to the LLM. */
    private const val MAX_SCROLLS_IN_A_ROW = 3

    /** `swipe` takes the finger's direction: content further down comes into view under a swipe up. */
    private val FINGER_FOR_REVEALING = mapOf("down" to "UP", "up" to "DOWN", "right" to "LEFT", "left" to "RIGHT")
    private const val TREE_MOVE_INSTRUCTIONS =
      "A UI test agent is carrying out `step_objective` in a mobile app, one move at a time. " +
        "`actions_taken_so_far_in_this_step` lists its moves in this step (with its own reasoning) and `current_screen` " +
        "is the screen now. `step_type` says whether the step is an action or a verification. What should the agent do next?"
    private val TREE_MOVE_OPTIONS = linkedMapOf(
      FINISHED to "Report the step complete: it has done everything the step asks, and on a verification step a check " +
        "of each thing the step names has passed in this step",
      CHANGE to "Change the screen: tap, type, scroll, wait for loading, go back, or run an app shortcut",
      "look" to "Check the screen without changing it: assert that something the step names is, or is not, shown",
      "impossible" to "Report the step impossible: the app cannot do what it asks",
    )
    private const val TREE_CHANGE_INSTRUCTIONS =
      "A UI test agent carrying out `step_objective` will next change the screen. Which kind of move makes progress on the step?"
    private val TREE_CHANGE_OPTIONS = linkedMapOf(
      KIND_TAP to "Tap an element on the screen",
      KIND_TYPE to "Type text into the focused or named field",
      KIND_SCROLL to "Scroll or swipe to bring something onto the screen",
      "wait" to "Wait for the screen to finish loading or changing",
      "back" to "Press the device Back button",
      KIND_OTHER to "Run an app shortcut or another tool",
    )
    private const val TREE_ELEMENT_INSTRUCTIONS =
      "The agent's next move for `step_objective` is a tap on one element of `current_screen`. Which element?"
    private const val TREE_SCROLL_INSTRUCTIONS =
      "The agent's next move for `step_objective` is to scroll. Which way should the content move to reveal what it needs?"
    private val TREE_SCROLL_OPTIONS = linkedMapOf(
      "down" to "Reveal content further down",
      "up" to "Reveal content further up",
      "right" to "Reveal content further right",
      "left" to "Reveal content further left",
    )
    private const val TREE_TEXT_INSTRUCTIONS = "The agent's next move for `step_objective` is to type text into a field. Which text?"
    private const val TREE_FIELD_INSTRUCTIONS =
      "The agent's next move for `step_objective` is to type into the field marked [focused] in `current_screen`, and " +
        "typing adds to any text already there. Does that field hold text now?"
    private val TREE_FIELD_OPTIONS = linkedMapOf(
      FIELD_EMPTY to "It is empty: it shows nothing, or only its placeholder or label",
      "filled" to "It already holds text, which typing would add to",
    )

    /** Text a step quotes: "…", “…”, or '…' standing as its own word. */
    private val QUOTED = Regex(""""([^"\n]{1,80})"|“([^”\n]{1,80})”|(?:^|[\s(])'([^'\n]{1,80})'(?=[\s.,;:!?)]|$)""")

    internal fun quotedIn(step: String): List<String> =
      QUOTED.findAll(step).map { m -> m.groupValues.drop(1).first { it.isNotEmpty() } }.distinct().toList()

    /** Tools that tap an element by its `ref`, in the order one is picked when several are offered. */
    private val TAP_TOOLS = listOf(TAP, "web_click")
    private const val OBJECTIVE_STATUS = "objectiveStatus"
    private val FOLDED_TOOLS = TAP_TOOLS.toSet() + setOf("assertVisible", OBJECTIVE_STATUS)
    private const val MAX_SCREEN_CHARS = 12_000
    private const val LOGGED_PROBABILITIES = 10

    /** A hosted engine answers in about 0.4s (p90 under 1s); past this the LLM's turn is not held. */
    const val DEFAULT_ENGINE_TIMEOUT_MS = 3_000L
    private const val MAX_STEP_CHARS = 1_200
    private const val INSTRUCTIONS =
      "A UI test agent is working on `step_objective`. Given what it has done so far and the current screen, " +
        "which single move should it make next?"
    private val WHITESPACE = Regex("""\s+""")
    /** A condition the screen may not meet yet. "once" counts only where it opens a clause: "tap it once" is no condition. */
    private val BRANCHES = Regex(
      """\b(if|otherwise|unless|when|whenever|while|until|wait|$PLATFORM_SCOPE_WORDS)\b|(?:^|[.,;:]\s*)once\b""",
      RegexOption.IGNORE_CASE,
    )

    /** A `step:` worded as a check gets no assertion gate, so its "done" would need no evidence. */
    private val WORDED_AS_A_CHECK = Regex("""^\W*(verify|check|confirm|ensure|assert|validate)\b""", RegexOption.IGNORE_CASE)
    private val json = Json { ignoreUnknownKeys = true }

    fun questionsFromEnv(value: String?): Questions =
      if (value?.trim()?.lowercase() == "tree") Questions.TREE else Questions.FLAT

    fun modeFromEnv(value: String? = System.getenv(MODE_ENV)): Mode? =
      when (value?.trim()?.lowercase()) {
        "shadow" -> Mode.SHADOW
        "race" -> Mode.RACE
        "first", "1", "true" -> Mode.FIRST
        else -> null
      }

    /** Whether [url] (unset meaning the default) is the hosted API, the only server its key goes to. */
    private fun isHosted(url: String?): Boolean =
      url.isNullOrBlank() || url.trim().trimEnd('/') == DEFAULT_ENGINE_URL

    /**
     * The decision settings in [env], as instrumentation args for an on-device Android run: the
     * agent there reads its instrumentation args, not the host's environment. Empty unless the mode
     * is on, and the hosted API's key only when the hosted API is the engine.
     */
    fun instrumentationArgs(env: (String) -> String? = System::getenv): Map<String, String> {
      if (modeFromEnv(env(MODE_ENV)) == null) return emptyMap()
      val hosted = isHosted(env(ENGINE_URL_ENV))
      val names = SETTING_NAMES.filter { it != DEFAULT_ENGINE_KEY_ENV || hosted }
      return names.mapNotNull { name -> env(name)?.takeIf { it.isNotBlank() }?.let { name to it } }.toMap()
    }

    /** The answer as logged: the [LOGGED_PROBABILITIES] most likely options, which always include the pick. */
    internal fun topProbabilities(response: DecisionResponse): DecisionResponse =
      response.copy(
        answers = response.answers.mapValues { (_, a) ->
          a.copy(probabilities = a.probabilities?.entries?.sortedByDescending { it.value }?.take(LOGGED_PROBABILITIES)
            ?.associate { it.key to it.value })
        }
      )

    private val engines = HashMap<Pair<String, String?>, DecisionEngine>()

    /**
     * One engine per server and key for the whole process. One attempt, no retries: a turn only
     * waits [DEFAULT_ENGINE_TIMEOUT_MS] for it anyway, and the LLM takes any turn it misses.
     */
    private fun cachedEngine(url: String, key: String?): DecisionEngine =
      synchronized(engines) {
        engines.getOrPut(url to key) {
          SystemOneDecisionEngine(TrailblazeHttpClientFactory.createDefaultHttpClient(10), url, key, maxAttempts = 1)
        }
      }

    /** Set once the settings (or why decisions are off) have been printed, so a run says it once. */
    private val announced = AtomicBoolean(false)

    /**
     * [delegate] wrapped for one agent step when `TRAILBLAZE_DECISION_MOVES` is `shadow`, `race` (or
     * `1`) or `first`; [delegate] itself otherwise, or when the engine has no key. The engine is any
     * server speaking the `/v1/systemone` contract: `TRAILBLAZE_DECISION_ENGINE_URL` (default the
     * hosted API, keyed by `TYPESAFE_API_KEY`), `TRAILBLAZE_DECISION_ENGINE_KEY` to key another
     * server, `TRAILBLAZE_DECISION_MODEL`, `TRAILBLAZE_DECISION_MOVES_THRESHOLD`, and
     * `TRAILBLAZE_DECISION_MOVES_QUESTIONS` (`tree` for the decision tree), `TRAILBLAZE_DECISION_MOVES_HIDE_TOOLS`
     * `TRAILBLAZE_DECISION_MOVES_SHOW_ALL_TOOLS_AFTER` and `TRAILBLAZE_DECISION_MOVES_DONE_THRESHOLD` (by default
     * the threshold for the flat question, [TREE_THRESHOLD] for the tree). [alwaysShownTools] are the target's
     * `always_shown_tools`.
     */
    fun wrapIfEnabled(
      delegate: LLMClient,
      objective: String,
      verification: Boolean,
      screenText: () -> String?,
      env: (String) -> String? = System::getenv,
      alwaysShownTools: Set<String> = emptySet(),
      engineFor: (url: String, key: String?) -> DecisionEngine = ::cachedEngine,
    ): LLMClient {
      val mode = modeFromEnv(env(MODE_ENV)) ?: return delegate
      val url = env(ENGINE_URL_ENV)?.takeIf { it.isNotBlank() } ?: DEFAULT_ENGINE_URL
      // The hosted API's key is only ever sent to the hosted API.
      val key = env(ENGINE_KEY_ENV)?.takeIf { it.isNotBlank() }
        ?: env(DEFAULT_ENGINE_KEY_ENV)?.takeIf { it.isNotBlank() && isHosted(url) }
      if (key == null && isHosted(url)) {
        if (announced.compareAndSet(false, true)) {
          Console.log("[DECISION_MOVES] $MODE_ENV is set but $DEFAULT_ENGINE_KEY_ENV is not; running without decisions")
        }
        return delegate
      }
      fun probability(name: String) = env(name)?.toDoubleOrNull()?.takeIf { it in 0.0..1.0 }
      val threshold = probability(THRESHOLD_ENV) ?: DEFAULT_THRESHOLD
      val doneThreshold = probability(DONE_THRESHOLD_ENV)
      val engine = engineFor(url, key)
      val decisionModel = env(MODEL_ENV)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
      val questions = questionsFromEnv(env(QUESTIONS_ENV))
      val hideTools = env(HIDE_TOOLS_ENV)?.trim()?.lowercase() in setOf("true", "1")
      val showAllToolsAfter = env(SHOW_ALL_TOOLS_AFTER_ENV)?.trim()?.toIntOrNull()?.takeIf { it >= 0 }
      if (announced.compareAndSet(false, true)) {
        Console.log(
          "[DECISION_MOVES] on: mode=${mode.name.lowercase()} questions=${questions.name.lowercase()} hideTools=$hideTools showAllToolsAfter=$showAllToolsAfter " +
            "engine=${engine.name} model=$decisionModel threshold=$threshold doneThreshold=${doneThreshold ?: "default"}",
        )
      }
      return NextMoveDecisionLlmClient(
        delegate = delegate,
        engine = engine,
        decisionModel = decisionModel,
        mode = mode,
        threshold = threshold,
        objective = objective,
        verification = verification,
        screenText = screenText,
        questions = questions,
        hideTools = hideTools,
        alwaysShownTools = alwaysShownTools,
        showAllToolsAfter = showAllToolsAfter,
        doneThreshold = doneThreshold,
      )
    }
  }
}
