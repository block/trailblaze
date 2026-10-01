package xyz.block.trailblaze.decision

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
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
import xyz.block.trailblaze.http.TrailblazeHttpClientFactory
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
 * It acts only on a tap or a "done", at or above [threshold], on a plain action step: never on a
 * verification step (whose "done" needs a passing assertion as evidence) or a step worded as one
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
) : LLMClient() {

  enum class Mode {
    SHADOW,
    RACE,
    FIRST,
  }

  /** What the engine picked, and whether this client makes that move. */
  internal class Verdict(
    val option: String,
    val p: Double,
    val act: Boolean,
    val why: String,
    /** The offered tool a tap is made with: `tap` on a phone, `web_click` in a browser. */
    val tapTool: String = TAP,
  )

  override suspend fun execute(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): Message.Assistant {
    // Every step request offers objectiveStatus; nothing else that reaches an agent's LLM client
    // (element comparison, history compression) does.
    if (tools.none { it.name == OBJECTIVE_STATUS }) return delegate.execute(prompt, model, tools)
    val eligible = !verification && !WORDED_AS_A_CHECK.containsMatchIn(objective) && !BRANCHES.containsMatchIn(objective)
    // Asking can only pay when the answer may be acted on, or when it is being recorded.
    if (!eligible && mode != Mode.SHADOW) return delegate.execute(prompt, model, tools)
    val history = historyOf(prompt)
    val request =
      runCatching { requestFor(prompt, tools, history) }.getOrNull()
        ?: return delegate.execute(prompt, model, tools)
    val offered = tools.map { it.name }.toSet()
    suspend fun ask(): Verdict? = askEngine(request, offered, history, eligible)
    return when (mode) {
      // Both start together, and the ask is bounded, so the LLM's answer waits at most the rest of
      // the engine's timeout.
      Mode.SHADOW ->
        coroutineScope {
          val shadow = async { ask() }
          delegate.execute(prompt, model, tools).also { shadow.await() }
        }
      Mode.FIRST -> ask()?.takeIf { it.act }?.let { respond(it, request) } ?: delegate.execute(prompt, model, tools)
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
                respond(verdict, request)
              } else {
                llm.await()
              }
            }
          }
        }
    }
  }

  private suspend fun askEngine(
    request: DecisionRequest,
    offered: Set<String>,
    history: List<Call>,
    eligible: Boolean,
  ): Verdict? {
    val options = request.questions.getValue(QUESTION).choiceOptions.keys
    fun judged(response: DecisionResponse) = judge(response, options, offered, history, eligible)
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
        p < threshold -> "below $threshold"
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
    val question = request.questions.getValue(QUESTION)
    val options = question.choiceOptions.mapValues { (id, text) ->
      if (id.startsWith(TAP_PREFIX) || id.startsWith(ASSERT_PREFIX) || id == DONE || id == DONE_FAIL) text else "Run $id"
    }
    return request.copy(questions = mapOf(QUESTION to DecisionQuestion.choice(question.instructions, options)))
  }

  private fun respond(verdict: Verdict, request: DecisionRequest): Message.Assistant {
    val note = "Decision engine ${engine.name} chose this move (p=${"%.3f".format(verdict.p)}) without an LLM call."
    val (tool, args) =
      if (verdict.option == DONE) {
        OBJECTIVE_STATUS to buildJsonObject {
          put("status", "COMPLETED")
          put("explanation", note)
        }
      } else {
        verdict.tapTool to buildJsonObject {
          put("ref", verdict.option.removePrefix(TAP_PREFIX))
          // The LLM reads this move on its next turn, when the screen has moved on and the ref no
          // longer resolves; without the element's label it cannot tell the tap already happened.
          val tapped = request.questions.getValue(QUESTION).choiceOptions[verdict.option]?.removePrefix("Tap ")
          put("reasoning", if (tapped == null) note else "Tapped $tapped. $note")
        }
      }
    return Message.Assistant(
      listOf<MessagePart.ResponsePart>(
        MessagePart.Tool.Call("decision-" + Random.nextLong().toString(16), tool, args.toString())
      ),
      ResponseMetaInfo(
        timestamp = Clock.System.now(),
        modelId = "decision:$decisionModel",
        metadata = AnsweredWithoutLlm.metadata(AnsweredWithoutLlm.DECISION),
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
    const val ENGINE_URL_ENV = "TRAILBLAZE_DECISION_ENGINE_URL"
    const val ENGINE_KEY_ENV = "TRAILBLAZE_DECISION_ENGINE_KEY"
    const val MODEL_ENV = "TRAILBLAZE_DECISION_MODEL"

    /** The hosted API the decision contract comes from, and the key name its docs use. */
    const val DEFAULT_ENGINE_URL = "https://api.typesafe.ai"
    const val DEFAULT_ENGINE_KEY_ENV = "TYPESAFE_API_KEY"
    const val DEFAULT_MODEL = "jev-latest"

    /** Every setting [instrumentationArgs] can hand a device, by the name it goes under. */
    val SETTING_NAMES: List<String> =
      listOf(MODE_ENV, THRESHOLD_ENV, MODEL_ENV, ENGINE_URL_ENV, ENGINE_KEY_ENV, DEFAULT_ENGINE_KEY_ENV)
    const val DEFAULT_THRESHOLD = 0.95

    internal const val QUESTION = "next_move"
    internal const val DONE = "done_ok"
    internal const val DONE_FAIL = "done_fail"
    internal const val TAP_PREFIX = "tap:"
    internal const val ASSERT_PREFIX = "assert:"
    private const val TAP = "tap"

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
     * server, `TRAILBLAZE_DECISION_MODEL`, and `TRAILBLAZE_DECISION_MOVES_THRESHOLD`.
     */
    fun wrapIfEnabled(
      delegate: LLMClient,
      objective: String,
      verification: Boolean,
      screenText: () -> String?,
      env: (String) -> String? = System::getenv,
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
      val threshold = env(THRESHOLD_ENV)?.toDoubleOrNull()?.takeIf { it in 0.0..1.0 } ?: DEFAULT_THRESHOLD
      val engine = engineFor(url, key)
      val decisionModel = env(MODEL_ENV)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
      if (announced.compareAndSet(false, true)) {
        Console.log("[DECISION_MOVES] on: mode=${mode.name.lowercase()} engine=${engine.name} model=$decisionModel threshold=$threshold")
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
      )
    }
  }
}
