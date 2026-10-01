package xyz.block.trailblaze.decision

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.datetime.Clock
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatusData
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.llm.LlmRequestUsageAndCost
import xyz.block.trailblaze.llm.LlmUsageAndCostExt.computeUsageSummary
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.logs.model.TraceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock as KotlinClock

class AnsweredWithoutLlmTest {

  @Test
  fun `a request a decision answered is not counted or costed as an LLM call`() {
    val logs = listOf(requestLog(answeredBy = null, cost = 0.25), requestLog(answeredBy = AnsweredWithoutLlm.DECISION, cost = 1.0))
    assertEquals(listOf(logs[0]), AnsweredWithoutLlm.modelRequests(logs))
    val summary = logs.computeUsageSummary()!!
    assertEquals(1, summary.totalRequestCount)
    assertEquals(1_000.0, summary.averageDurationMillis)
    assertEquals(0.25, summary.totalCostInUsDollars)
  }

  @Test
  fun `a session whose every request a decision answered has no LLM usage`() {
    assertNull(listOf(requestLog(answeredBy = AnsweredWithoutLlm.DECISION, cost = 0.0)).computeUsageSummary())
  }

  private fun requestLog(answeredBy: String?, cost: Double) = TrailblazeLog.TrailblazeLlmRequestLog(
    agentTaskStatus = AgentTaskStatus.InProgress(
      statusData = AgentTaskStatusData(
        taskId = TaskId.generate(),
        prompt = "prompt",
        callCount = 1,
        taskStartTime = Clock.System.now(),
        totalDurationMs = 1L,
      ),
    ),
    viewHierarchy = ViewHierarchyTreeNode(),
    instructions = "instructions",
    trailblazeLlmModel = MODEL,
    llmMessages = emptyList(),
    llmResponse = listOf(
      Message.Assistant(
        content = "answer",
        metaInfo = ResponseMetaInfo(
          timestamp = KotlinClock.System.now(),
          metadata = answeredBy?.let { AnsweredWithoutLlm.metadata(it) },
        ),
      ),
    ),
    actions = emptyList(),
    llmRequestUsageAndCost = LlmRequestUsageAndCost(
      trailblazeLlmModel = MODEL,
      inputTokens = 100,
      outputTokens = 10,
      promptCost = cost,
      completionCost = 0.0,
    ),
    screenshotFile = null,
    durationMs = if (answeredBy == null) 1_000L else 1L,
    session = SessionId("s"),
    timestamp = Clock.System.now(),
    traceId = TraceId.generate(TraceId.Companion.TraceOrigin.LLM),
    deviceHeight = 1920,
    deviceWidth = 1080,
  )

  private companion object {
    val MODEL = TrailblazeLlmModel(
      trailblazeLlmProvider = TrailblazeLlmProvider(id = "test", display = "Test"),
      modelId = "test-model",
      inputCostPerOneMillionTokens = 0.0,
      outputCostPerOneMillionTokens = 0.0,
      contextLength = 1L,
      maxOutputTokens = 1L,
      capabilityIds = emptyList(),
    )
  }
}
