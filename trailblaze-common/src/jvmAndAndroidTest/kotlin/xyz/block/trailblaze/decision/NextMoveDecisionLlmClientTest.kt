package xyz.block.trailblaze.decision

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.utils.time.KoogClock
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsAll
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import assertk.assertions.startsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.llm.CachedTokenExtractor
import xyz.block.trailblaze.llm.TrailblazeLlmModels
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import kotlin.test.Test

/**
 * Contract of [NextMoveDecisionLlmClient]: which move comes back for a given engine answer, when the
 * LLM is asked instead, and what reaches the session log.
 */
class NextMoveDecisionLlmClientTest {

  /** Answers the next-move question with [pick] at probability [p]; records every request. */
  private class ScriptedEngine(
    private val pick: String?,
    private val p: Double = 0.99,
    private val delayMs: Long = 0,
    /** Never answers: waits until cancelled, and records that it was. */
    private val hang: Boolean = false,
    /** Lets [pick] be an option that was never offered, as a misbehaving server might return. */
    private val offList: Boolean = false,
    override val maxChoiceOptions: Int = DecisionQuestion.MAX_CHOICE_OPTIONS,
  ) : DecisionEngine {
    override val name = "fake-engine"
    val requests = mutableListOf<DecisionRequest>()
    val cancelled = CompletableDeferred<Boolean>()
    var fail = false

    override suspend fun decide(request: DecisionRequest): DecisionResponse {
      requests += request
      if (hang) {
        try {
          awaitCancellation()
        } finally {
          cancelled.complete(true)
        }
      }
      delay(delayMs)
      if (fail) throw DecisionEngineException(503, "overloaded")
      val ids = request.questions.getValue(NextMoveDecisionLlmClient.QUESTION).choiceOptions.keys
      val choice = pick ?: ids.first()
      require(offList || choice in ids) { "scripted pick '$choice' is not among $ids" }
      val rest = (1 - p) / (ids.size - 1)
      val probabilities = (ids + choice).associateWith { if (it == choice) p else rest }
      val answer = DecisionAnswer(DecisionQuestionType.CHOICE, choice = choice, probabilities = probabilities)
      return DecisionResponse("fake-1", mapOf(NextMoveDecisionLlmClient.QUESTION to answer))
    }
  }

  /**
   * The wrapped LLM. [hang] makes it wait until cancelled, so a race can only end by the engine.
   * Call n returns [answers]'s nth, past its end [LLM_ANSWER].
   */
  private class FakeLlm(private val hang: Boolean = false, private val answers: List<Message.Assistant> = emptyList()) : LLMClient() {
    var calls = 0
    var shownTools: List<String> = emptyList()
    val shown = mutableListOf<List<ToolDescriptor>>()
    val prompts = mutableListOf<Prompt>()
    val cancelled = CompletableDeferred<Boolean>()

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
      calls++
      shownTools = tools.map { it.name }
      shown += tools
      prompts += prompt
      if (hang) {
        try {
          awaitCancellation()
        } finally {
          cancelled.complete(true)
        }
      }
      return answers.getOrElse(calls - 1) { LLM_ANSWER }
    }

    override fun llmProvider(): LLMProvider = TrailblazeLlmProvider.NONE_KOOG_LLM_PROVIDER
    override suspend fun moderate(prompt: Prompt, model: LLModel) = ModerationResult(isHarmful = false, categories = emptyMap())
    override fun close() = Unit
  }

  private fun client(
    engine: DecisionEngine,
    llm: LLMClient,
    mode: NextMoveDecisionLlmClient.Mode = NextMoveDecisionLlmClient.Mode.RACE,
    objective: String = "Tap the Items tab",
    verification: Boolean = false,
    screen: String? = SCREEN,
    engineTimeoutMs: Long = HANG_CONTAINMENT_MS,
  ) = NextMoveDecisionLlmClient(llm, engine, "fake", mode, 0.95, objective, verification, { screen }, engineTimeoutMs)

  // The bound only turns a hang into a failure; nothing here is meant to take more than a moment.
  private fun run(client: LLMClient, prompt: Prompt = prompt("Tap the Items tab"), tools: List<ToolDescriptor> = TOOLS) =
    runBlocking { withTimeout(HANG_CONTAINMENT_MS) { client.execute(prompt, MODEL, tools) } }

  @Test
  fun `a sure tap is made without the LLM, which is cancelled in a race`() {
    val llm = FakeLlm(hang = true)
    // The engine answers after the LLM request is under way, so the race has one to cancel.
    val answer = run(client(ScriptedEngine("tap:a12", delayMs = 50), llm))
    assertThat(answer.call().tool).isEqualTo("tap")
    assertThat(answer.call().arg("ref")).isEqualTo("a12")
    // The LLM sees this move after the screen has changed, so it names what was tapped, and reads
    // it as its own: the tap is done, and tapping the element again would repeat it.
    val reasoning = answer.call().arg("reasoning")!!
    assertThat(reasoning).contains("Tapped [a12] Button \"Items\". Next:")
    assertThat(reasoning).contains("Tapping [a12] again would tap it a second time")
    assertThat(AnsweredWithoutLlm.of(answer)).isEqualTo(AnsweredWithoutLlm.DECISION)
    // The dropped request was sent, so the LLM-call budget still counts it.
    assertThat(AnsweredWithoutLlm.llmRequestSent(answer)).isTrue()
    assertThat(llm.calls).isEqualTo(1)
    assertThat(llm.cancelled.isCompleted).isTrue()
  }

  @Test
  fun `a sure done completes the step, and in first mode the LLM is never called`() {
    val llm = FakeLlm()
    val answer = run(
      client(ScriptedEngine(NextMoveDecisionLlmClient.DONE), llm, NextMoveDecisionLlmClient.Mode.FIRST),
      prompt("Tap the Items tab", "tap" to """{"ref":"a12"}"""),
    )
    assertThat(answer.call().tool).isEqualTo("objectiveStatus")
    assertThat(answer.call().arg("status")).isEqualTo("COMPLETED")
    assertThat(AnsweredWithoutLlm.llmRequestSent(answer)).isFalse()
    assertThat(llm.calls).isEqualTo(0)
  }

  @Test
  fun `an unsure pick, a pick that is not a tap or done, or an engine error leaves the move to the LLM`() {
    for (mode in listOf(NextMoveDecisionLlmClient.Mode.RACE, NextMoveDecisionLlmClient.Mode.FIRST)) {
      for (engine in listOf(
        ScriptedEngine("tap:a12", p = 0.9),
        ScriptedEngine("assert:a12"),
        ScriptedEngine("inputText"),
        ScriptedEngine(NextMoveDecisionLlmClient.DONE_FAIL),
        ScriptedEngine("tap:z999", offList = true),
        ScriptedEngine("tap:a12").apply { fail = true },
      )) {
        val llm = FakeLlm()
        assertThat(run(client(engine, llm, mode))).isSameInstanceAs(LLM_ANSWER)
        assertThat(llm.calls).isEqualTo(1)
      }
    }
  }

  @Test
  fun `done before the step has made any move is left to the LLM`() {
    val llm = FakeLlm()
    assertThat(run(client(ScriptedEngine(NextMoveDecisionLlmClient.DONE), llm, NextMoveDecisionLlmClient.Mode.FIRST)))
      .isSameInstanceAs(LLM_ANSWER)
    assertThat(llm.calls).isEqualTo(1)
  }

  @Test
  fun `done right after a move that failed is left to the LLM, and the engine is told it failed`() {
    for (result in listOf(
      "Executed tap: ExceptionThrown(errorMessage=No element [a12])",
      "Loop warning: same move again.\n\nTool tap failed: IllegalStateException: stale ref",
    )) {
      val llm = FakeLlm()
      val engine = ScriptedEngine(NextMoveDecisionLlmClient.DONE)
      val prompt = prompt("Tap the Items tab", "tap" to """{"ref":"a12"}""", results = listOf(result))
      assertThat(run(client(engine, llm, NextMoveDecisionLlmClient.Mode.FIRST), prompt)).isSameInstanceAs(LLM_ANSWER)
      assertThat(llm.calls).isEqualTo(1)
      assertThat(engine.requests.single().state.toString()).contains("(failed)")
    }
  }

  @Test
  fun `an element this step already tapped is not tapped again, even after other moves`() {
    val prompt = prompt("Tap the Items tab", "tap" to """{"ref":"a12"}""", "waitForChange" to "{}")
    assertThat(run(client(ScriptedEngine("tap:a12"), FakeLlm()), prompt)).isSameInstanceAs(LLM_ANSWER)
  }

  @Test
  fun `in a browser a sure tap is a web_click, and an element it clicked is not clicked again`() {
    val webTools = listOf("web_click", "web_navigate", "objectiveStatus").map { ToolDescriptor(it, "$it the thing") }
    fun runWeb(prompt: Prompt) = runBlocking {
      client(ScriptedEngine("tap:a12"), FakeLlm(), NextMoveDecisionLlmClient.Mode.FIRST).execute(prompt, MODEL, webTools)
    }

    val answer = runWeb(prompt("Tap the Items tab"))
    assertThat(answer.call().tool).isEqualTo("web_click")
    assertThat(answer.call().arg("ref")).isEqualTo("a12")
    assertThat(AnsweredWithoutLlm.of(answer)).isEqualTo(AnsweredWithoutLlm.DECISION)

    assertThat(runWeb(prompt("Tap the Items tab", "web_click" to """{"ref":"a12"}""")))
      .isSameInstanceAs(LLM_ANSWER)
  }

  @Test
  fun `in a race, an LLM that answers before the engine is not held back by it`() {
    val engine = ScriptedEngine("tap:a12", hang = true)
    val answer = run(client(engine, FakeLlm()))
    assertThat(answer).isSameInstanceAs(LLM_ANSWER)
    assertThat(engine.cancelled.isCompleted).isTrue()
  }

  @Test
  fun `an engine slower than its timeout leaves the move to the LLM`() {
    for (mode in NextMoveDecisionLlmClient.Mode.entries) {
      val engine = ScriptedEngine("tap:a12", hang = true)
      val llm = FakeLlm()
      assertThat(run(client(engine, llm, mode, engineTimeoutMs = 1))).isSameInstanceAs(LLM_ANSWER)
      assertThat(llm.calls).isEqualTo(1)
      assertThat(engine.cancelled.isCompleted).isTrue()
    }
  }

  @Test
  fun `shadow mode returns the LLM's move when the engine fails`() {
    val llm = FakeLlm()
    val engine = ScriptedEngine("tap:a12").apply { fail = true }
    assertThat(run(client(engine, llm, NextMoveDecisionLlmClient.Mode.SHADOW))).isSameInstanceAs(LLM_ANSWER)
    assertThat(engine.requests.size).isEqualTo(1)
  }

  @Test
  fun `verification and branching steps are not even asked about outside shadow mode`() {
    for (c in listOf(
      { e: ScriptedEngine -> client(e, FakeLlm(), verification = true) },
      { e: ScriptedEngine -> client(e, FakeLlm(), objective = "If a dialog appears tap OK") },
      { e: ScriptedEngine -> client(e, FakeLlm(), objective = "On iOS tap Done") },
      { e: ScriptedEngine -> client(e, FakeLlm(), objective = "On the web, tap Continue") },
      { e: ScriptedEngine -> client(e, FakeLlm(), objective = "On tablets, tap Done") },
      { e: ScriptedEngine -> client(e, FakeLlm(), objective = "When Save is enabled, tap it") },
      { e: ScriptedEngine -> client(e, FakeLlm(), objective = "Scroll until Settings is visible and tap it") },
      { e: ScriptedEngine -> client(e, FakeLlm(), objective = "Once the page loads, tap Next") },
      { e: ScriptedEngine -> client(e, FakeLlm(), objective = "Verify the Stopwatch tab is now selected") },
      { e: ScriptedEngine -> client(e, FakeLlm(), objective = "\"Check\" that Alarm is shown") },
    )) {
      val engine = ScriptedEngine("tap:a12")
      assertThat(run(c(engine))).isSameInstanceAs(LLM_ANSWER)
      assertThat(engine.requests.size).isEqualTo(0)
    }
  }

  @Test
  fun `a step that only names a count, or mentions a check after its action, is still acted on`() {
    for (objective in listOf("Tap the \"Tap Me\" button once", "Tap Save and check the toast")) {
      val answer = run(client(ScriptedEngine("tap:a12"), FakeLlm(), NextMoveDecisionLlmClient.Mode.FIRST, objective = objective))
      assertThat(AnsweredWithoutLlm.of(answer)).isEqualTo(AnsweredWithoutLlm.DECISION)
    }
  }

  @Test
  fun `shadow mode always returns the LLM's move and logs what it would have done`() {
    val engine = ScriptedEngine("tap:a12")
    val logs = mutableListOf<TrailblazeLog>()
    val answer = runBlocking {
      withContext(DecisionLogContext(SessionId("s"), traceId = null) { logs += it }) {
        client(engine, FakeLlm(), NextMoveDecisionLlmClient.Mode.SHADOW).execute(prompt("Tap the Items tab"), MODEL, TOOLS)
      }
    }
    assertThat(answer).isSameInstanceAs(LLM_ANSWER)
    val logged = logs.filterIsInstance<TrailblazeLog.TrailblazeDecisionRequestLog>().single()
    assertThat(logged.outcome!!).startsWith("shadow, would act: tap:a12")
    // The log keeps what the pick means, not every tool's description or every probability.
    val options = logged.request.questions.getValue(NextMoveDecisionLlmClient.QUESTION).choiceOptions
    assertThat(options.getValue("tap:a12")).isEqualTo(engine.requests.single().questions.getValue(NextMoveDecisionLlmClient.QUESTION).choiceOptions.getValue("tap:a12"))
    assertThat(options.getValue("inputText")).isEqualTo("Run inputText")
    val probabilities = logged.response!!.answers.getValue(NextMoveDecisionLlmClient.QUESTION).probabilities!!
    assertThat(probabilities.size).isEqualTo(minOf(10, options.size))
    assertThat(probabilities.keys).contains("tap:a12")
  }

  @Test
  fun `shadow mode still asks about a verification step, and says it would not act`() {
    val engine = ScriptedEngine(NextMoveDecisionLlmClient.DONE)
    val logs = mutableListOf<TrailblazeLog>()
    runBlocking {
      withContext(DecisionLogContext(SessionId("s"), traceId = null) { logs += it }) {
        client(engine, FakeLlm(), NextMoveDecisionLlmClient.Mode.SHADOW, verification = true)
          .execute(prompt("Verify Items is visible"), MODEL, TOOLS)
      }
    }
    assertThat(logs.filterIsInstance<TrailblazeLog.TrailblazeDecisionRequestLog>().single().outcome!!)
      .contains("would leave it to the LLM")
  }

  @Test
  fun `the question offers every element twice, every other tool, and done`() {
    val engine = ScriptedEngine("inputText")
    run(client(engine, FakeLlm()), prompt("Tap the Items tab", "swipe" to "{}"))
    val request = engine.requests.single()
    val options = request.questions.getValue(NextMoveDecisionLlmClient.QUESTION).choiceOptions
    assertThat(options.keys).containsAll("done_ok", "done_fail", "tap:a12", "assert:a12", "tap:b34", "assert:b34", "inputText", "swipe")
    // Taps, checks and completion are the per-element and done options, not bare tools.
    assertThat(options.keys).doesNotContain("tap")
    assertThat(options.keys).doesNotContain("objectiveStatus")
    assertThat(options.getValue("tap:a12")).isEqualTo("Tap [a12] Button \"Items\"")
    val state = request.state as JsonObject
    assertThat(state.getValue("step_objective").jsonPrimitive.content).isEqualTo("Tap the Items tab")
    assertThat(state.getValue("current_screen").jsonPrimitive.content).isEqualTo(SCREEN)
  }

  @Test
  fun `an element whose ref carries a collision suffix is offered like any other`() {
    val engine = ScriptedEngine("inputText")
    run(client(engine, FakeLlm(), screen = "$SCREEN\n[k42b] Button \"Items\""))
    assertThat(engine.requests.single().questions.getValue(NextMoveDecisionLlmClient.QUESTION).choiceOptions.keys)
      .containsAll("tap:k42b", "assert:k42b")
  }

  @Test
  fun `the options are capped at what the engine takes, keeping done first`() {
    val engine = ScriptedEngine("inputText", maxChoiceOptions = 4)
    run(client(engine, FakeLlm()))
    val options = engine.requests.single().questions.getValue(NextMoveDecisionLlmClient.QUESTION).choiceOptions.keys
    assertThat(options.size).isEqualTo(4)
    assertThat(options.first()).isEqualTo(NextMoveDecisionLlmClient.DONE)
  }

  @Test
  fun `when the cap cuts elements, taps outlast checks`() {
    // done, done_fail, inputText, swipe, then room for exactly the two taps.
    val engine = ScriptedEngine("inputText", maxChoiceOptions = 6)
    run(client(engine, FakeLlm()))
    val options = engine.requests.single().questions.getValue(NextMoveDecisionLlmClient.QUESTION).choiceOptions.keys
    assertThat(options).containsAll("tap:a12", "tap:b34")
    assertThat(options.none { it.startsWith(NextMoveDecisionLlmClient.ASSERT_PREFIX) }).isTrue()
  }

  @Test
  fun `a request that is not an agent step, or has no text screen, goes straight to the LLM`() {
    val engine = ScriptedEngine("tap:a12")
    val noStep = runBlocking { client(engine, FakeLlm()).execute(prompt("compare"), MODEL, listOf(ToolDescriptor("tap", "Tap"))) }
    val noScreen = run(client(engine, FakeLlm(), screen = null))
    assertThat(noStep).isSameInstanceAs(LLM_ANSWER)
    assertThat(noScreen).isSameInstanceAs(LLM_ANSWER)
    assertThat(engine.requests.size).isEqualTo(0)
  }

  @Test
  fun `the env picks the mode, and a missing key for the hosted engine leaves the LLM unwrapped`() {
    assertThat(NextMoveDecisionLlmClient.modeFromEnv("shadow")).isEqualTo(NextMoveDecisionLlmClient.Mode.SHADOW)
    assertThat(NextMoveDecisionLlmClient.modeFromEnv("1")).isEqualTo(NextMoveDecisionLlmClient.Mode.FIRST)
    assertThat(NextMoveDecisionLlmClient.modeFromEnv("FIRST")).isEqualTo(NextMoveDecisionLlmClient.Mode.FIRST)
    assertThat(NextMoveDecisionLlmClient.modeFromEnv(null)).isNull()
    val llm = FakeLlm()
    fun wrap(env: Map<String, String>) = NextMoveDecisionLlmClient.wrapIfEnabled(llm, "Tap", false, { SCREEN }, env::get)
    assertThat(wrap(emptyMap())).isSameInstanceAs(llm)
    assertThat(wrap(mapOf("TRAILBLAZE_DECISION_MOVES" to "race"))).isSameInstanceAs(llm)
    assertThat(wrap(mapOf("TRAILBLAZE_DECISION_MOVES" to "race", "TYPESAFE_API_KEY" to "k")) === llm).isFalse()
    // A self-hosted engine needs no key.
    assertThat(wrap(mapOf("TRAILBLAZE_DECISION_MOVES" to "shadow", "TRAILBLAZE_DECISION_ENGINE_URL" to "http://localhost:9")) === llm).isFalse()
  }

  @Test
  fun `the hosted API's key goes only to the hosted API`() {
    val built = mutableListOf<Pair<String, String?>>()
    fun wrap(env: Map<String, String>) = NextMoveDecisionLlmClient.wrapIfEnabled(
      FakeLlm(), "Tap", false, { SCREEN }, env::get,
    ) { url, key -> built += url to key; ScriptedEngine("tap:a12") }
    val on = mapOf("TRAILBLAZE_DECISION_MOVES" to "race", "TYPESAFE_API_KEY" to "hosted-key")
    wrap(on)
    wrap(on + ("TRAILBLAZE_DECISION_ENGINE_URL" to "https://api.typesafe.ai/"))
    wrap(on + ("TRAILBLAZE_DECISION_ENGINE_URL" to "http://localhost:9"))
    wrap(on + ("TRAILBLAZE_DECISION_ENGINE_URL" to "http://localhost:9") + ("TRAILBLAZE_DECISION_ENGINE_KEY" to "own-key"))
    assertThat(built).isEqualTo(
      listOf(
        NextMoveDecisionLlmClient.DEFAULT_ENGINE_URL to "hosted-key",
        "https://api.typesafe.ai/" to "hosted-key",
        "http://localhost:9" to null,
        "http://localhost:9" to "own-key",
      )
    )
  }

  @Test
  fun `an on-device run is handed the decision settings only when decisions are on`() {
    fun args(env: Map<String, String>) = NextMoveDecisionLlmClient.instrumentationArgs(env::get)
    val on = mapOf(
      "TRAILBLAZE_DECISION_MOVES" to "race",
      "TRAILBLAZE_DECISION_MOVES_THRESHOLD" to "0.9",
      "TRAILBLAZE_DECISION_MOVES_DONE_THRESHOLD" to "0.8",
      "TRAILBLAZE_DECISION_MODEL" to "jev-x",
      "TYPESAFE_API_KEY" to "hosted-key",
      "OPENAI_API_KEY" to "unrelated",
    )
    assertThat(args(on - "TRAILBLAZE_DECISION_MOVES")).isEqualTo(emptyMap())
    assertThat(args(on + ("TRAILBLAZE_DECISION_MOVES" to "off"))).isEqualTo(emptyMap())
    assertThat(args(on)).isEqualTo(on - "OPENAI_API_KEY")
    // Another engine gets its own key, never the hosted API's.
    val other = on + ("TRAILBLAZE_DECISION_ENGINE_URL" to "http://10.0.2.2:9") + ("TRAILBLAZE_DECISION_ENGINE_KEY" to "own-key")
    assertThat(args(other)).isEqualTo(other - "OPENAI_API_KEY" - "TYPESAFE_API_KEY")
  }

  @Test
  fun `the threshold comes from the env, and a value that is not a probability falls back to the default`() {
    fun actsAt(p: Double, threshold: String?): Boolean {
      val env = mapOf("TRAILBLAZE_DECISION_MOVES" to "first", "TYPESAFE_API_KEY" to "k") +
        (threshold?.let { mapOf("TRAILBLAZE_DECISION_MOVES_THRESHOLD" to it) } ?: emptyMap())
      val llm = FakeLlm()
      val client = NextMoveDecisionLlmClient.wrapIfEnabled(llm, "Tap the Items tab", false, { SCREEN }, env::get) { _, _ ->
        ScriptedEngine("tap:a12", p = p)
      }
      run(client)
      return llm.calls == 0
    }
    assertThat(actsAt(0.96, null)).isTrue()
    assertThat(actsAt(0.96, "0.99")).isFalse()
    for (bad in listOf("abc", "1.5", "-1")) assertThat(actsAt(0.96, bad)).isTrue()
  }

  /** Answers each tree question with its pick in the turn's map; any other question with its first option, unsure. */
  private class TreeEngine(private vararg val turns: Map<String, Pair<String, Double>>) : DecisionEngine {
    override val name = "fake-engine"
    override val maxChoiceOptions = DecisionQuestion.MAX_CHOICE_OPTIONS
    val requests = mutableListOf<DecisionRequest>()

    override suspend fun decide(request: DecisionRequest): DecisionResponse {
      requests += request
      // Request n is answered from [turns]'s nth, past its end from the last.
      val picks = turns[minOf(requests.size, turns.size) - 1]
      val answers = request.questions.mapValues { (q, question) ->
        val ids = question.choiceOptions.keys
        val (choice, p) = picks[q] ?: (ids.first() to 0.5)
        require(choice in ids) { "scripted $q pick '$choice' is not among $ids" }
        val probabilities = ids.associateWith { if (it == choice) p else (1 - p) / (ids.size - 1) }
        DecisionAnswer(DecisionQuestionType.CHOICE, choice = choice, probabilities = probabilities)
      }
      return DecisionResponse("fake-1", answers)
    }
  }

  private fun tree(
    engine: DecisionEngine,
    llm: LLMClient,
    objective: String = "Tap the Items tab",
    verification: Boolean = false,
    hideTools: Boolean = false,
    screen: String = SCREEN,
    alwaysShownTools: Set<String> = emptySet(),
    showAllToolsAfter: Int? = null,
    doneThreshold: Double? = null,
  ) = NextMoveDecisionLlmClient(
    llm, engine, "fake", NextMoveDecisionLlmClient.Mode.FIRST, 0.95, objective, verification, { screen },
    HANG_CONTAINMENT_MS, NextMoveDecisionLlmClient.Questions.TREE, hideTools, alwaysShownTools, showAllToolsAfter,
    doneThreshold,
  )

  private fun sure(vararg path: Pair<String, String>, p: Double = 0.99) = path.associate { (q, pick) -> q to (pick to p) }

  @Test
  fun `the tree taps when every answer on its path is sure, from one request`() {
    val llm = FakeLlm()
    val engine = TreeEngine(sure("move" to "change", "change" to "tap", "tap" to "a12"))
    val answer = run(tree(engine, llm))
    assertThat(answer.call().tool).isEqualTo("tap")
    assertThat(answer.call().arg("ref")).isEqualTo("a12")
    assertThat(answer.call().arg("reasoning")!!).contains("Tapped [a12] Button \"Items\". Next:")
    assertThat(llm.calls).isEqualTo(0)
    assertThat(engine.requests.single().questions.keys).containsAll("move", "change", "tap", "scroll")
  }

  @Test
  fun `the tree scrolls with the finger direction that brings the content asked for into view`() {
    val llm = FakeLlm()
    val engine = TreeEngine(sure("move" to "change", "change" to "scroll", "scroll" to "down", p = 0.92))
    val answer = run(tree(engine, llm, objective = "Scroll down to Settings"), prompt("Scroll down to Settings"))
    assertThat(answer.call().tool).isEqualTo("swipe")
    assertThat(answer.call().arg("direction")).isEqualTo("UP")
    assertThat(llm.calls).isEqualTo(0)
  }

  @Test
  fun `the tree leaves a fourth scroll the same way in a row to the LLM`() {
    val step = "Scroll down to Settings"
    val engine = TreeEngine(sure("move" to "change", "change" to "scroll", "scroll" to "down"))
    val up = "swipe" to """{"direction":"UP"}"""
    val llm = FakeLlm()
    run(tree(engine, llm, objective = step), prompt(step, up, up, up))
    assertThat(llm.calls).isEqualTo(1)
    // A scroll the other way in between starts the count again.
    val after = FakeLlm()
    val answer = run(tree(engine, after, objective = step), prompt(step, up, "swipe" to """{"direction":"DOWN"}""", up, up))
    assertThat(answer.call().tool).isEqualTo("swipe")
    assertThat(after.calls).isEqualTo(0)
  }

  @Test
  fun `the tree types only text the step quotes`() {
    val step = "Type \"Jane Doe\" into the name field"
    val llm = FakeLlm()
    val engine = TreeEngine(sure("move" to "change", "change" to "type", "type" to "q0", "field" to "empty"))
    val answer = run(tree(engine, llm, objective = step, screen = FOCUSED_SCREEN), prompt(step))
    assertThat(answer.call().tool).isEqualTo("inputText")
    assertThat(answer.call().arg("text")).isEqualTo("Jane Doe")
    assertThat(engine.requests.single().questions.getValue("type").choiceOptions)
      .isEqualTo(mapOf("q0" to "\"Jane Doe\"", "none" to "Some other text"))
    assertThat(engine.requests.single().questions.keys).contains("field")
    // A step that quotes nothing is never typed into.
    val unquoted = FakeLlm()
    run(
      tree(TreeEngine(sure("move" to "change", "change" to "type")), unquoted, objective = "Type a name", screen = FOCUSED_SCREEN),
      prompt("Type a name"),
    )
    assertThat(unquoted.calls).isEqualTo(1)
  }

  @Test
  fun `the tree types only into a focused field it is sure is empty, never the same text twice in a step, and with type where offered`() {
    val step = "Type \"Jane Doe\" into the name field"
    val engine = TreeEngine(sure("move" to "change", "change" to "type", "type" to "q0", "field" to "empty"))
    // Typing goes to the focused field: with none, it could land nowhere or in the wrong one.
    val unfocused = FakeLlm()
    run(tree(engine, unfocused, objective = step), prompt(step))
    assertThat(unfocused.calls).isEqualTo(1)
    // Typing appends, so a second "Jane Doe" would leave "Jane DoeJane Doe".
    val typed = FakeLlm()
    run(tree(engine, typed, objective = step, screen = FOCUSED_SCREEN), prompt(step, "inputText" to """{"text":"Jane Doe"}"""))
    assertThat(typed.calls).isEqualTo(1)
    // The screen shows an empty field's hint the way it shows a value, so the engine is asked.
    val filled = FakeLlm()
    val notEmpty = TreeEngine(sure("move" to "change", "change" to "type", "type" to "q0", "field" to "filled"))
    run(tree(notEmpty, filled, objective = step, screen = FOCUSED_SCREEN), prompt(step))
    assertThat(filled.calls).isEqualTo(1)
    val viaType = FakeLlm()
    val answer = run(
      tree(engine, viaType, objective = step, screen = FOCUSED_SCREEN),
      prompt(step),
      tools = TOOLS + ToolDescriptor("type", "type the text"),
    )
    assertThat(answer.call().tool).isEqualTo("type")
    assertThat(answer.call().arg("text")).isEqualTo("Jane Doe")
    assertThat(viaType.calls).isEqualTo(0)
  }

  @Test
  fun `quoted text is taken from double, curly and standalone single quotes, not apostrophes`() {
    assertThat(NextMoveDecisionLlmClient.quotedIn("Tap 'All add-ons', type \"Jane\" and “Bob”; don't stop"))
      .isEqualTo(listOf("All add-ons", "Jane", "Bob"))
  }

  @Test
  fun `the tree leaves the turn to the LLM when any answer on its path is unsure`() {
    val unsureElement = FakeLlm()
    val picks = sure("move" to "change", "change" to "tap") + ("tap" to ("a12" to 0.94))
    run(tree(TreeEngine(picks), unsureElement))
    assertThat(unsureElement.calls).isEqualTo(1)
    val unsureMove = FakeLlm()
    run(tree(TreeEngine(sure("change" to "scroll", "scroll" to "down") + ("move" to ("change" to 0.85))), unsureMove))
    assertThat(unsureMove.calls).isEqualTo(1)
  }

  @Test
  fun `the tree reports done only on a plain action step`() {
    val tapped = arrayOf("tap" to """{"ref":"a12"}""")
    val plain = FakeLlm()
    val answer = run(tree(TreeEngine(sure("move" to "finished", p = 0.91)), plain), prompt("Tap the Items tab", *tapped))
    assertThat(answer.call().tool).isEqualTo("objectiveStatus")
    assertThat(answer.call().arg("status")).isEqualTo("COMPLETED")
    val conditional = FakeLlm()
    val step = "If the Items tab shows, tap it"
    run(tree(TreeEngine(sure("move" to "finished")), conditional, objective = step), prompt(step, *tapped))
    assertThat(conditional.calls).isEqualTo(1)
  }

  @Test
  fun `the tree taps on a conditional step, and never asks on a verification step`() {
    val step = "If the Items tab shows, tap it"
    val conditional = FakeLlm()
    val answer = run(tree(TreeEngine(sure("move" to "change", "change" to "tap", "tap" to "a12")), conditional, objective = step), prompt(step))
    assertThat(answer.call().tool).isEqualTo("tap")
    val verify = FakeLlm()
    val engine = TreeEngine(sure("move" to "change", "change" to "tap", "tap" to "a12"))
    run(tree(engine, verify, verification = true))
    assertThat(verify.calls).isEqualTo(1)
    assertThat(engine.requests.isEmpty()).isTrue()
  }

  @Test
  fun `with hidden tools on, the LLM sees only the kept tools when the tree rules every other tool out`() {
    val tools = TOOLS + ToolDescriptor("shop_addItemToCart", "add an item")
    // Sure the move is a tap, unsure which element: the LLM moves, and needs no app shortcut.
    val ruledOut = sure("move" to "change", "change" to "tap", p = 0.995)
    val hidden = FakeLlm()
    run(tree(TreeEngine(ruledOut), hidden, hideTools = true), tools = tools)
    assertThat(hidden.calls).isEqualTo(1)
    assertThat(hidden.shownTools).isEqualTo(TOOLS.map { it.name } + "showTools")
    // An app shortcut still possible, or the setting off: every tool is shown.
    val possible = FakeLlm()
    run(tree(TreeEngine(sure("move" to "change", "change" to "tap", p = 0.6)), possible, hideTools = true), tools = tools)
    assertThat(possible.shownTools).isEqualTo(tools.map { it.name })
    val off = FakeLlm()
    run(tree(TreeEngine(ruledOut), off), tools = tools)
    assertThat(off.shownTools).isEqualTo(tools.map { it.name })
    // A browser's tools are not the kept ones, so a browser run is shown every tool.
    val webTools = listOf("web_click", "web_navigate", "objectiveStatus").map { ToolDescriptor(it, "$it the thing") }
    val web = FakeLlm()
    run(tree(TreeEngine(ruledOut), web, hideTools = true), tools = webTools)
    assertThat(web.shownTools).isEqualTo(webTools.map { it.name })
  }

  @Test
  fun `with hidden tools on, the LLM is listed the other tools, and one it asks for is callable in the same turn`() {
    val cart = ToolDescriptor("shop_addItemToCart", "Adds the named item to the cart. Prefer it over tapping.")
    val tools = TOOLS + cart
    val ruledOut = sure("move" to "change", "change" to "tap", p = 0.995)
    val askFor = assistant(
      MessagePart.Tool.Call("ask-1", "showTools", """{"names":"shop_addItemToCart, nope"}"""), tokens = 100 to 5,
      metadata = buildJsonObject { put("cache_read_input_tokens", 80) },
    )
    val move = assistant(
      MessagePart.Tool.Call("move-1", "shop_addItemToCart", """{"item":"Latte"}"""), tokens = 120 to 7,
      metadata = buildJsonObject {
        put("cache", "kept")
        put("cached_tokens", 90)
        put("cache_creation_input_tokens", 10)
      },
    )
    val llm = FakeLlm(answers = listOf(askFor, move))
    val answer = run(tree(TreeEngine(ruledOut), llm, hideTools = true), tools = tools)
    // The first call lists the other tool by its first sentence.
    val list = llm.shown[0].single { it.name == "showTools" }.description
    assertThat(list).contains("- shop_addItemToCart: Adds the named item to the cart")
    assertThat(list).doesNotContain("Prefer it")
    // The ask is answered without the agent, and the LLM is asked again with that tool callable.
    assertThat(llm.calls).isEqualTo(2)
    assertThat(llm.shown[1].map { it.name }).isEqualTo(TOOLS.map { it.name } + "shop_addItemToCart")
    val result = llm.prompts[1].messages.last().parts.filterIsInstance<MessagePart.Tool.Result>().single()
    assertThat(result.id).isEqualTo("ask-1")
    assertThat(result.output).isEqualTo("Now callable: shop_addItemToCart.")
    // The agent gets the move, with the turn's tokens counted from both calls.
    assertThat(answer.call().tool).isEqualTo("shop_addItemToCart")
    assertThat(answer.metaInfo.inputTokensCount).isEqualTo(220)
    assertThat(answer.metaInfo.outputTokensCount).isEqualTo(12)
    // The session log records what the LLM was shown and asked for, beside the response's own metadata.
    val tags = answer.metaInfo.metadata!!
    assertThat(tags["trailblaze.toolsShown"]?.jsonPrimitive?.content).isEqualTo("${TOOLS.size + 1}")
    assertThat(tags["trailblaze.toolsAsked"]?.jsonPrimitive?.content).isEqualTo("shop_addItemToCart,nope")
    assertThat(tags["cache"]?.jsonPrimitive?.content).isEqualTo("kept")
    // So does the cached input of both calls, which pricing the summed counts would otherwise miss.
    assertThat(CachedTokenExtractor.extractCacheReadTokens(tags)).isEqualTo(170L)
    assertThat(CachedTokenExtractor.extractCacheCreationTokens(tags)).isEqualTo(10L)
  }

  @Test
  fun `with hidden tools on, an ask naming no listed tool makes every tool callable, and a move needs no second call`() {
    val tools = TOOLS + ToolDescriptor("shop_addItemToCart", "add an item")
    val ruledOut = sure("move" to "change", "change" to "tap", p = 0.995)
    val unknown = FakeLlm(answers = listOf(assistant(MessagePart.Tool.Call("ask-1", "showTools", """{"names":"teleport"}"""))))
    run(tree(TreeEngine(ruledOut), unknown, hideTools = true), tools = tools)
    assertThat(unknown.calls).isEqualTo(2)
    assertThat(unknown.shown[1].map { it.name }).isEqualTo(tools.map { it.name })
    val moved = FakeLlm(
      answers = listOf(
        assistant(MessagePart.Tool.Call("tap-1", "tap", """{"ref":"a12"}"""), metadata = buildJsonObject { put("cached_tokens", 40) }),
      ),
    )
    val answer = run(tree(TreeEngine(ruledOut), moved, hideTools = true), tools = tools)
    assertThat(moved.calls).isEqualTo(1)
    assertThat(answer.call().tool).isEqualTo("tap")
    assertThat(answer.metaInfo.metadata!!["trailblaze.toolsShown"]?.jsonPrimitive?.content).isEqualTo("${TOOLS.size + 1}")
    assertThat(answer.metaInfo.metadata!!.containsKey("trailblaze.toolsAsked")).isFalse()
    assertThat(CachedTokenExtractor.extractCacheReadTokens(answer.metaInfo.metadata)).isEqualTo(40L)
  }

  @Test
  fun `hidden tools are turned on by true or 1`() {
    val tools = TOOLS + ToolDescriptor("shop_addItemToCart", "add an item")
    fun hides(value: String): Boolean {
      val llm = FakeLlm()
      val env = mapOf(
        "TRAILBLAZE_DECISION_MOVES" to "first", "TRAILBLAZE_DECISION_MOVES_QUESTIONS" to "tree",
        "TRAILBLAZE_DECISION_MOVES_HIDE_TOOLS" to value, "TYPESAFE_API_KEY" to "k",
      )
      val ruledOut = TreeEngine(sure("move" to "change", "change" to "tap", p = 0.995))
      run(NextMoveDecisionLlmClient.wrapIfEnabled(llm, "Tap the Items tab", false, { SCREEN }, env::get) { _, _ -> ruledOut }, tools = tools)
      return llm.shown.single().any { it.name == "showTools" }
    }
    assertThat(hides("true")).isTrue()
    assertThat(hides("1")).isTrue()
    assertThat(hides("false")).isFalse()
  }

  @Test
  fun `with hidden tools on, a tool the target always shows stays callable, and the rest are listed`() {
    val swipe = ToolDescriptor("app_swipe", "Swipes with the app's own margins")
    val cart = ToolDescriptor("shop_addItemToCart", "add an item")
    val ruledOut = sure("move" to "change", "change" to "tap", p = 0.995)
    val llm = FakeLlm()
    run(tree(TreeEngine(ruledOut), llm, hideTools = true, alwaysShownTools = setOf("app_swipe")), tools = TOOLS + swipe + cart)
    assertThat(llm.shownTools).isEqualTo(TOOLS.map { it.name } + "app_swipe" + "showTools")
    val list = llm.shown[0].single { it.name == "showTools" }.description
    assertThat(list).contains("- shop_addItemToCart:")
    assertThat(list).doesNotContain("app_swipe")
  }

  @Test
  fun `with hidden tools on, a step past its LLM-turn limit shows the LLM every tool`() {
    val tools = TOOLS + ToolDescriptor("shop_addItemToCart", "add an item")
    val ruledOut = sure("move" to "change", "change" to "tap", p = 0.995)
    val llm = FakeLlm()
    val client = tree(TreeEngine(ruledOut), llm, hideTools = true, showAllToolsAfter = 2)
    repeat(3) { run(client, tools = tools) }
    assertThat(llm.shown.map { shown -> shown.size }).isEqualTo(listOf(TOOLS.size + 1, TOOLS.size + 1, tools.size))
    // A turn the LLM had every tool on anyway still counts toward the limit.
    val mixed = FakeLlm()
    val engine = TreeEngine(sure("move" to "change", "change" to "tap", p = 0.6), ruledOut)
    val step = tree(engine, mixed, hideTools = true, showAllToolsAfter = 1)
    repeat(2) { run(step, tools = tools) }
    assertThat(mixed.shown.map { shown -> shown.size }).isEqualTo(listOf(tools.size, tools.size))
  }

  @Test
  fun `with hidden tools on, a turn with no text screen counts toward the LLM-turn limit`() {
    val tools = TOOLS + ToolDescriptor("shop_addItemToCart", "add an item")
    val ruledOut = sure("move" to "change", "change" to "tap", p = 0.995)
    val llm = FakeLlm()
    var screen = ""
    val client = NextMoveDecisionLlmClient(
      llm, TreeEngine(ruledOut), "fake", NextMoveDecisionLlmClient.Mode.FIRST, 0.95, "Tap the Items tab", false, { screen },
      HANG_CONTAINMENT_MS, NextMoveDecisionLlmClient.Questions.TREE, hideTools = true, showAllToolsAfter = 1,
    )
    run(client, tools = tools)
    screen = SCREEN
    run(client, tools = tools)
    assertThat(llm.shown.map { shown -> shown.size }).isEqualTo(listOf(tools.size, tools.size))
  }

  private fun assistant(call: MessagePart.Tool.Call, tokens: Pair<Int, Int>? = null, metadata: JsonObject? = null) = Message.Assistant(
    listOf<MessagePart.ResponsePart>(call),
    ResponseMetaInfo(KoogClock.System.now(), inputTokensCount = tokens?.first, outputTokensCount = tokens?.second, metadata = metadata),
  )

  @Test
  fun `done has its own threshold, which defaults to the tap threshold`() {
    fun actsOn(pick: String, p: Double, settings: Map<String, String>): Boolean {
      val env = mapOf("TRAILBLAZE_DECISION_MOVES" to "first", "TYPESAFE_API_KEY" to "k") + settings
      val llm = FakeLlm()
      val client = NextMoveDecisionLlmClient.wrapIfEnabled(llm, "Tap the Items tab", false, { SCREEN }, env::get) { _, _ ->
        ScriptedEngine(pick, p = p)
      }
      // The LLM's answer here is plain text; a move the engine makes is a tool call.
      return run(client, prompt("Tap the Items tab", "tap" to """{"ref":"b34"}""")).parts.any { it is MessagePart.Tool.Call }
    }
    val done = NextMoveDecisionLlmClient.DONE
    val split = mapOf("TRAILBLAZE_DECISION_MOVES_THRESHOLD" to "0.9", "TRAILBLAZE_DECISION_MOVES_DONE_THRESHOLD" to "0.8")
    assertThat(actsOn(done, 0.85, split)).isTrue()
    assertThat(actsOn("tap:a12", 0.85, split)).isFalse()
    assertThat(actsOn("tap:a12", 0.92, split)).isTrue()
    assertThat(actsOn(done, 0.75, split)).isFalse()
    // Unset or not a probability: done needs what a tap needs.
    val tapOnly = mapOf("TRAILBLAZE_DECISION_MOVES_THRESHOLD" to "0.9")
    assertThat(actsOn(done, 0.85, tapOnly)).isFalse()
    assertThat(actsOn(done, 0.92, tapOnly)).isTrue()
    assertThat(actsOn(done, 0.85, tapOnly + ("TRAILBLAZE_DECISION_MOVES_DONE_THRESHOLD" to "abc"))).isFalse()
  }

  @Test
  fun `the tree's done uses the done threshold when it is set, and its own bar otherwise`() {
    fun answer(p: Double, doneThreshold: Double?) =
      run(
        tree(TreeEngine(sure("move" to "finished", p = p)), FakeLlm(), doneThreshold = doneThreshold),
        prompt("Tap the Items tab", "tap" to """{"ref":"b34"}"""),
      )
    fun ended(answer: Message.Assistant) = answer.parts.filterIsInstance<MessagePart.Tool.Call>().singleOrNull()?.tool == "objectiveStatus"
    assertThat(ended(answer(0.85, doneThreshold = 0.8))).isTrue()
    assertThat(ended(answer(0.85, doneThreshold = null))).isFalse()
    assertThat(ended(answer(0.92, doneThreshold = null))).isTrue()
    assertThat(ended(answer(0.92, doneThreshold = 0.95))).isFalse()
  }

  private fun Message.Assistant.call() = parts.filterIsInstance<MessagePart.Tool.Call>().single()

  private fun MessagePart.Tool.Call.arg(name: String) =
    (kotlinx.serialization.json.Json.parseToJsonElement(args) as JsonObject)[name]?.jsonPrimitive?.content

  private companion object {
    const val HANG_CONTAINMENT_MS = 120_000L
    val MODEL = TrailblazeLlmModels.GPT_4O_MINI.toKoogLlmModel()
    val TOOLS = listOf("tap", "assertVisible", "objectiveStatus", "inputText", "swipe").map { ToolDescriptor(it, "$it the thing") }
    val LLM_ANSWER = Message.Assistant(content = "llm", metaInfo = ResponseMetaInfo.create(KoogClock.System))
    val SCREEN = """
      App: com.example.app

      [a12] Button "Items"
      [b34] Tab "Settings" [selected]
    """.trimIndent()
    val FOCUSED_SCREEN = SCREEN + "\n[c56] EditText \"Name\" [focused]"

    /** [results] is each move's tool result, in order; a move past its end gets "ok". */
    fun prompt(step: String, vararg history: Pair<String, String>, results: List<String> = emptyList()): Prompt {
      val meta = RequestMetaInfo.create(KoogClock.System)
      val messages = mutableListOf<Message>(Message.User(content = step, metaInfo = meta))
      history.forEachIndexed { i, (tool, args) ->
        messages += Message.Assistant(
          listOf<MessagePart.ResponsePart>(MessagePart.Tool.Call("c$i", tool, args)),
          ResponseMetaInfo.create(KoogClock.System),
        )
        messages += Message.User(listOf<MessagePart.RequestPart>(MessagePart.Tool.Result("c$i", tool, results.getOrElse(i) { "ok" })), meta)
      }
      return Prompt(messages = messages, id = "test", params = LLMParams(temperature = null, speculation = null, schema = null))
    }
  }
}
