package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatusData
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.model.TraceId.Companion.TraceOrigin
import xyz.block.trailblaze.logs.server.ServerEndpoints.logsServerKtorEndpoints
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentLogEndpointTest {

  private fun createTestLogsRepo(): LogsRepo {
    val tempDir = File.createTempFile("test-logs", "").apply {
      delete()
      mkdirs()
    }
    return LogsRepo(tempDir)
  }

  @Test
  fun `test TrailblazeAgentTaskStatusChangeLog serialization`() = testApplication {
    val logsRepo = createTestLogsRepo()
    application {
      logsServerKtorEndpoints(logsRepo)
    }

    val log = TrailblazeLog.TrailblazeAgentTaskStatusChangeLog(
      agentTaskStatus = AgentTaskStatus.Success.ObjectiveComplete(
        llmExplanation = "Test completed successfully",
        statusData = AgentTaskStatusData(
          prompt = "Test prompt",
          taskId = TaskId.generate(),
          callCount = 1,
          taskStartTime = Clock.System.now(),
          totalDurationMs = 1000L,
        ),
      ),
      session = "test-session",
      timestamp = Clock.System.now(),
    )

    val json = TrailblazeJsonInstance.encodeToString(TrailblazeLog.serializer(), log)
    println("Serialized TrailblazeAgentTaskStatusChangeLog: $json")

    val response = client.post("/agentlog") {
      contentType(ContentType.Application.Json)
      setBody(json)
    }

    assertEquals(HttpStatusCode.OK, response.status)
    assertTrue(response.bodyAsText().contains("Log received and saved"))
  }

  @Test
  fun `test TrailblazeToolLog serialization`() = testApplication {
    val logsRepo = createTestLogsRepo()
    application {
      logsServerKtorEndpoints(logsRepo)
    }

    // Create a simple tool log that matches what Android tests might send
    val toolLogJson = """
        {
            "class": "xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeToolLog",
            "timestamp": "${Clock.System.now()}",
            "session": "test-session-123",
            "trailblazeTool": {
                "class": "xyz.block.trailblaze.toolcalls.commands.AssertVisibleWithTextTrailblazeTool",
                "toolName": "assertVisibleWithText",
                "raw": {
                    "text": "test text",
                    "index": 0
                }
            },
            "toolName": "assertVisibleWithText",
            "successful": true,
            "traceId": "test-response-123",
            "durationMs": 500
        }
    """.trimIndent()

    println("Testing TrailblazeToolLog JSON: $toolLogJson")

    val response = client.post("/agentlog") {
      contentType(ContentType.Application.Json)
      setBody(toolLogJson)
    }

    println("Response status: ${response.status}")
    println("Response body: ${response.bodyAsText()}")

    assertEquals(HttpStatusCode.OK, response.status)
  }

  @Test
  fun `test MaestroCommandLog serialization`() = testApplication {
    val logsRepo = createTestLogsRepo()
    application {
      logsServerKtorEndpoints(logsRepo)
    }

    val log = TrailblazeLog.MaestroCommandLog(
      maestroCommandJsonObj = JsonObject(
        mapOf(
          "command" to JsonPrimitive("tap"),
          "point" to JsonPrimitive("100,200"),
        ),
      ),
      traceId = TraceId.generate(TraceOrigin.MAESTRO),
      successful = true,
      trailblazeToolResult = TrailblazeToolResult.Success,
      session = "test-session",
      timestamp = Clock.System.now(),
      durationMs = 300L,
    )

    val json = TrailblazeJsonInstance.encodeToString(TrailblazeLog.serializer(), log)
    println("Serialized MaestroCommandLog: $json")

    val response = client.post("/agentlog") {
      contentType(ContentType.Application.Json)
      setBody(json)
    }

    assertEquals(HttpStatusCode.OK, response.status)
    assertTrue(response.bodyAsText().contains("Log received and saved"))
  }

  @Test
  fun `test ObjectiveStartLog serialization`() = testApplication {
    val logsRepo = createTestLogsRepo()
    application {
      logsServerKtorEndpoints(logsRepo)
    }

    // Test with JSON that might come from Android tests
    val objectiveStartJson = """
        {
            "class": "xyz.block.trailblaze.logs.client.TrailblazeLog.ObjectiveStartLog",
            "timestamp": "${Clock.System.now()}",
            "session": "test-session-789",
            "promptStep": {
                "class": "xyz.block.trailblaze.yaml.DirectionStep",
                "step": "Test objective prompt",
                "recordable": true,
                "recording": null
            }
        }
    """.trimIndent()

    println("Testing ObjectiveStartLog JSON: $objectiveStartJson")

    val response = client.post("/agentlog") {
      contentType(ContentType.Application.Json)
      setBody(objectiveStartJson)
    }

    println("Response status: ${response.status}")
    println("Response body: ${response.bodyAsText()}")

    assertEquals(HttpStatusCode.OK, response.status)
  }

  @Test
  fun `test DelegatingTrailblazeToolLog serialization`() = testApplication {
    val logsRepo = createTestLogsRepo()
    application {
      logsServerKtorEndpoints(logsRepo)
    }

    val delegatingToolJson = """
        {
            "class": "xyz.block.trailblaze.logs.client.TrailblazeLog.DelegatingTrailblazeToolLog",
            "timestamp": "${Clock.System.now()}",
            "session": "test-session-delegating",
            "toolName": "delegatingTool",
            "trailblazeTool": {
                "class": "xyz.block.trailblaze.toolcalls.commands.AssertVisibleWithTextTrailblazeTool",
                "toolName": "assertVisibleWithText",
                "raw": {
                    "text": "delegated text",
                    "index": 1
                }
            },
            "traceId": "delegating-response-123",
            "executableTools": []
        }
    """.trimIndent()

    println("Testing DelegatingTrailblazeToolLog JSON: $delegatingToolJson")

    val response = client.post("/agentlog") {
      contentType(ContentType.Application.Json)
      setBody(delegatingToolJson)
    }

    println("Response status: ${response.status}")
    println("Response body: ${response.bodyAsText()}")

    assertEquals(HttpStatusCode.OK, response.status)
  }

  @Test
  fun `test ObjectiveCompleteLog serialization`() = testApplication {
    val logsRepo = createTestLogsRepo()
    application {
      logsServerKtorEndpoints(logsRepo)
    }

    val objectiveCompleteJson = """
        {
            "class": "xyz.block.trailblaze.logs.client.TrailblazeLog.ObjectiveCompleteLog",
            "timestamp": "${Clock.System.now()}",
            "session": "test-session-complete",
            "promptStep": {
                "class": "xyz.block.trailblaze.yaml.VerificationStep",
                "verify": "Test objective completed successfully",
                "recordable": true,
                "recording": null
            },
            "objectiveResult": {
                "class": "xyz.block.trailblaze.agent.model.AgentTaskStatus.Success.ObjectiveComplete",
                "llmExplanation": "Task completed successfully",
                "statusData": {
                    "prompt": "Complete test objective",
                    "taskId": "task-complete-123",
                    "callCount": 3,
                    "taskStartTime": "${Clock.System.now()}",
                    "totalDurationMs": 5000
                }
            }
        }
    """.trimIndent()

    println("Testing ObjectiveCompleteLog JSON: $objectiveCompleteJson")

    val response = client.post("/agentlog") {
      contentType(ContentType.Application.Json)
      setBody(objectiveCompleteJson)
    }

    println("Response status: ${response.status}")
    println("Response body: ${response.bodyAsText()}")

    assertEquals(HttpStatusCode.OK, response.status)
  }

  @Test
  fun `test invalid JSON handling`() = testApplication {
    val logsRepo = createTestLogsRepo()
    application {
      logsServerKtorEndpoints(logsRepo)
    }

    val invalidJson = """
        {
            "invalidField": "test",
            "missingRequiredFields": true
        }
    """.trimIndent()

    val response = client.post("/agentlog") {
      contentType(ContentType.Application.Json)
      setBody(invalidJson)
    }

    println("Invalid JSON response status: ${response.status}")
    println("Invalid JSON response body: ${response.bodyAsText()}")

    assertEquals(HttpStatusCode.InternalServerError, response.status)
  }

  @Test
  fun `test JSON without type discriminator`() = testApplication {
    val logsRepo = createTestLogsRepo()
    application {
      logsServerKtorEndpoints(logsRepo)
    }

    // This simulates the original error - JSON without proper type discriminator
    val jsonWithoutType = """
        {
            "timestamp": "${Clock.System.now()}",
            "session": "test-session-no-type",
            "trailblazeTool": {
                "class": "xyz.block.trailblaze.toolcalls.commands.AssertVisibleWithTextTrailblazeTool",
                "toolName": "assertVisibleWithText",
                "raw": {
                    "text": "test text"
                }
            },
            "toolName": "assertVisibleWithText",
            "successful": true,
            "durationMs": 500
        }
    """.trimIndent()

    println("Testing JSON without type discriminator: $jsonWithoutType")

    val response = client.post("/agentlog") {
      contentType(ContentType.Application.Json)
      setBody(jsonWithoutType)
    }

    println("No type discriminator response status: ${response.status}")
    println("No type discriminator response body: ${response.bodyAsText()}")

    // This should fail with the serialization error we found
    assertEquals(HttpStatusCode.InternalServerError, response.status)
    assertTrue(response.bodyAsText().contains("Class discriminator was missing"))
  }
}
