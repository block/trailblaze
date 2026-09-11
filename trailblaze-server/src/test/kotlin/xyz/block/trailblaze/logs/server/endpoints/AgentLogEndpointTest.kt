package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatusData
import xyz.block.trailblaze.config.DefaultBehavior
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.model.TraceId.Companion.TraceOrigin
import xyz.block.trailblaze.logs.model.TrailblazeClockDomain
import xyz.block.trailblaze.logs.server.ServerEndpoints.logsServerKtorEndpoints
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.yaml.TrailArgConfig
import xyz.block.trailblaze.yaml.TrailConfig
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.block.trailblaze.util.Console

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
      session = xyz.block.trailblaze.logs.model.SessionId("test-session"),
      timestamp = Clock.System.now(),
    )

    val json = TrailblazeJsonInstance.encodeToString(TrailblazeLog.serializer(), log)
    Console.log("Serialized TrailblazeAgentTaskStatusChangeLog: $json")

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

    Console.log("Testing TrailblazeToolLog JSON: $toolLogJson")

    val response = client.post("/agentlog") {
      contentType(ContentType.Application.Json)
      setBody(toolLogJson)
    }

    Console.log("Response status: ${response.status}")
    Console.log("Response body: ${response.bodyAsText()}")

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
      trailblazeToolResult = TrailblazeToolResult.Success(),
      session = xyz.block.trailblaze.logs.model.SessionId("test-session"),
      timestamp = Clock.System.now(),
      durationMs = 300L,
    )

    val json = TrailblazeJsonInstance.encodeToString(TrailblazeLog.serializer(), log)
    Console.log("Serialized MaestroCommandLog: $json")

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

    Console.log("Testing ObjectiveStartLog JSON: $objectiveStartJson")

    val response = client.post("/agentlog") {
      contentType(ContentType.Application.Json)
      setBody(objectiveStartJson)
    }

    Console.log("Response status: ${response.status}")
    Console.log("Response body: ${response.bodyAsText()}")

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

    Console.log("Testing DelegatingTrailblazeToolLog JSON: $delegatingToolJson")

    val response = client.post("/agentlog") {
      contentType(ContentType.Application.Json)
      setBody(delegatingToolJson)
    }

    Console.log("Response status: ${response.status}")
    Console.log("Response body: ${response.bodyAsText()}")

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

    Console.log("Testing ObjectiveCompleteLog JSON: $objectiveCompleteJson")

    val response = client.post("/agentlog") {
      contentType(ContentType.Application.Json)
      setBody(objectiveCompleteJson)
    }

    Console.log("Response status: ${response.status}")
    Console.log("Response body: ${response.bodyAsText()}")

    assertEquals(HttpStatusCode.OK, response.status)
  }

  @Test
  fun `parameterized trail config with args round-trips through the agentlog endpoint`() = testApplication {
    // Regression pin: a parameterized trail (one whose `config.args` declares typed args) embeds a
    // TrailConfig in SessionStatus.Started, which the device POSTs to /agentlog as JSON. The args
    // serializers used to hard-require a YAML decoder, so this decode 500'd for EVERY parameterized
    // trail — the run passed on-device but its logs never parsed (broken report / UI / history).
    val logsRepo = createTestLogsRepo()
    application {
      logsServerKtorEndpoints(logsRepo)
    }

    val received = mutableListOf<TrailblazeLog>()
    AgentLogEndpoint.setServerReceivedLogsListener { received += it }
    try {
      val trailConfig = TrailConfig(
        title = "Parameterized trail",
        platform = "android",
        args = linkedMapOf(
          "recipient" to TrailArgConfig(type = TrailArgConfig.STRING),
          "retries" to TrailArgConfig(
            type = TrailArgConfig.INTEGER,
            default = DefaultBehavior.Use(JsonPrimitive(3)),
          ),
          "verbose" to TrailArgConfig(
            type = TrailArgConfig.BOOLEAN,
            default = DefaultBehavior.Use(JsonPrimitive(false)),
          ),
        ),
      )
      val log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = SessionStatus.Started(
          trailConfig = trailConfig,
          trailFilePath = "trails/forms/text-input-args.trail.yaml",
          hasRecordedSteps = false,
          testMethodName = "text-input-args",
          testClassName = "text-input-args",
          trailblazeDeviceInfo = TrailblazeDeviceInfo(
            trailblazeDeviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID),
            trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
            widthPixels = 1080,
            heightPixels = 1920,
          ),
        ),
        session = SessionId("test-session-args"),
        timestamp = Clock.System.now(),
      )

      // Encoding always worked — the regression was purely on the server DECODE side.
      val json = TrailblazeJsonInstance.encodeToString(TrailblazeLog.serializer(), log)

      val response = client.post("/agentlog") {
        contentType(ContentType.Application.Json)
        setBody(json)
      }

      assertEquals(HttpStatusCode.OK, response.status)
      assertTrue(response.bodyAsText().contains("Log received and saved"))

      val decoded = received.single() as TrailblazeLog.TrailblazeSessionStatusChangeLog
      assertEquals(
        trailConfig,
        (decoded.sessionStatus as SessionStatus.Started).trailConfig,
        "the parameterized trail's args must survive the JSON log round trip",
      )
    } finally {
      AgentLogEndpoint.setServerReceivedLogsListener {}
    }
  }

  @Test
  fun `a device-clock log is anchored with the host receipt time and a host-clock log is not`() = testApplication {
    // The anchor is what lets a reader derive the device→host offset
    // (offset ≈ hostReceivedAt - (timestamp + durationMs)); a host-clock log needs none, and
    // stamping one would make an already-host-domain timestamp look like it needed correcting.
    val logsRepo = createTestLogsRepo()
    application {
      logsServerKtorEndpoints(logsRepo)
    }

    val received = mutableListOf<TrailblazeLog>()
    AgentLogEndpoint.setServerReceivedLogsListener { received += it }
    try {
      val deviceLog = TrailblazeLog.MaestroCommandLog(
        maestroCommandJsonObj = JsonObject(mapOf("command" to JsonPrimitive("tap"))),
        traceId = TraceId.generate(TraceOrigin.MAESTRO),
        successful = true,
        trailblazeToolResult = TrailblazeToolResult.Success(),
        session = SessionId("device-clock-session"),
        timestamp = Clock.System.now(),
        durationMs = 300L,
        clock = TrailblazeClockDomain.DEVICE,
      )
      val hostLog = deviceLog.copy(session = SessionId("host-clock-session"), clock = null)

      for (log in listOf(deviceLog, hostLog)) {
        val response = client.post("/agentlog") {
          contentType(ContentType.Application.Json)
          setBody(TrailblazeJsonInstance.encodeToString(TrailblazeLog.serializer(), log))
        }
        assertEquals(HttpStatusCode.OK, response.status)
      }

      val (persistedDevice, persistedHost) = received
      assertTrue(
        persistedDevice.hostReceivedAt != null,
        "a device-clock log must carry the host receipt anchor readers derive the offset from",
      )
      // The anchor is added by a polymorphic copy — pin that it didn't cost the log its clock
      // marker (an anchored log with a nulled marker is skipped by every offset reader, silently
      // reverting the feature) or any other field.
      assertEquals(
        TrailblazeClockDomain.DEVICE,
        persistedDevice.clock,
        "anchoring must preserve the device-clock marker the offset readers filter on",
      )
      assertEquals(
        deviceLog,
        (persistedDevice as TrailblazeLog.MaestroCommandLog).copy(hostReceivedAt = null),
        "anchoring must change nothing but hostReceivedAt",
      )
      assertEquals(
        null,
        persistedHost.hostReceivedAt,
        "a host-clock log is already on the host timeline and must be persisted unchanged",
      )
    } finally {
      AgentLogEndpoint.setServerReceivedLogsListener {}
    }
  }

  @Test
  fun `a re-uploaded device-clock log keeps its first anchor`() = testApplication {
    // A log can reach ingestion twice (upload retry, a session re-pushed to another daemon). The
    // FIRST receipt is the measurement — a later receipt time is pure re-delivery latency, and
    // re-stamping would inflate the derived offset by however long the re-upload waited.
    val logsRepo = createTestLogsRepo()
    application {
      logsServerKtorEndpoints(logsRepo)
    }

    val received = mutableListOf<TrailblazeLog>()
    AgentLogEndpoint.setServerReceivedLogsListener { received += it }
    try {
      val firstAnchor = Instant.parse("2026-09-01T10:00:00.500Z")
      val alreadyAnchored = TrailblazeLog.MaestroCommandLog(
        maestroCommandJsonObj = JsonObject(mapOf("command" to JsonPrimitive("tap"))),
        traceId = TraceId.generate(TraceOrigin.MAESTRO),
        successful = true,
        trailblazeToolResult = TrailblazeToolResult.Success(),
        session = SessionId("re-upload-session"),
        timestamp = Instant.parse("2026-09-01T09:59:59.000Z"),
        durationMs = 300L,
        clock = TrailblazeClockDomain.DEVICE,
        hostReceivedAt = firstAnchor,
      )

      val response = client.post("/agentlog") {
        contentType(ContentType.Application.Json)
        setBody(TrailblazeJsonInstance.encodeToString(TrailblazeLog.serializer(), alreadyAnchored))
      }
      assertEquals(HttpStatusCode.OK, response.status)

      assertEquals(
        firstAnchor,
        received.single().hostReceivedAt,
        "a re-upload's later receipt time says nothing new — the first anchor is the measurement",
      )
    } finally {
      AgentLogEndpoint.setServerReceivedLogsListener {}
    }
  }

  @Test
  fun `a legacy log without clock fields decodes as host-clock and gets no anchor`() = testApplication {
    // Logs written before the clock-domain field existed carry neither key. They must decode as
    // "host, or unknown" (never inferred to device — one session legitimately mixes clocks) and
    // must NOT be anchored, so re-ingesting an old session's files leaves them byte-identical.
    val logsRepo = createTestLogsRepo()
    application {
      logsServerKtorEndpoints(logsRepo)
    }

    val received = mutableListOf<TrailblazeLog>()
    AgentLogEndpoint.setServerReceivedLogsListener { received += it }
    try {
      // encodeDefaults=false means a null-clock log encodes byte-identical to a log written
      // before the fields existed — assert that, so this payload really is legacy-shaped.
      val legacyJson = TrailblazeJsonInstance.encodeToString(
        TrailblazeLog.serializer(),
        TrailblazeLog.MaestroCommandLog(
          maestroCommandJsonObj = JsonObject(mapOf("command" to JsonPrimitive("tap"))),
          traceId = TraceId.generate(TraceOrigin.MAESTRO),
          successful = true,
          trailblazeToolResult = TrailblazeToolResult.Success(),
          session = SessionId("legacy-session"),
          timestamp = Instant.parse("2026-09-01T09:59:59.000Z"),
          durationMs = 300L,
        ),
      )
      assertTrue(
        "clock" !in legacyJson && "hostReceivedAt" !in legacyJson,
        "a log without clock metadata must encode without the keys, i.e. legacy-shaped",
      )

      val response = client.post("/agentlog") {
        contentType(ContentType.Application.Json)
        setBody(legacyJson)
      }
      assertEquals(HttpStatusCode.OK, response.status)

      val persisted = received.single()
      assertEquals(null, persisted.clock, "an absent clock field must decode as host-or-unknown")
      assertEquals(null, persisted.hostReceivedAt, "a legacy log must not be anchored on re-ingestion")
    } finally {
      AgentLogEndpoint.setServerReceivedLogsListener {}
    }
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

    Console.log("Invalid JSON response status: ${response.status}")
    Console.log("Invalid JSON response body: ${response.bodyAsText()}")

    assertEquals(HttpStatusCode.BadRequest, response.status)
  }

  @Test
  fun `server keeps serving after a truncated log body`() = testApplication {
    // Regression pin: a truncated /agentlog upload (device connection severed mid-body, e.g. the
    // daemon shut down while a run was in flight) must be that one request's failure — 400, not an
    // unhandled exception — and the very next well-formed log must still be accepted.
    val logsRepo = createTestLogsRepo()
    application {
      logsServerKtorEndpoints(logsRepo)
    }

    val validLog = TrailblazeLog.MaestroCommandLog(
      maestroCommandJsonObj = JsonObject(mapOf("command" to JsonPrimitive("tap"))),
      traceId = TraceId.generate(TraceOrigin.MAESTRO),
      successful = true,
      trailblazeToolResult = TrailblazeToolResult.Success(),
      session = xyz.block.trailblaze.logs.model.SessionId("test-session"),
      timestamp = Clock.System.now(),
      durationMs = 300L,
    )
    val validJson = TrailblazeJsonInstance.encodeToString(TrailblazeLog.serializer(), validLog)
    val truncatedJson = validJson.substring(0, validJson.length / 2)

    val truncatedResponse = client.post("/agentlog") {
      contentType(ContentType.Application.Json)
      setBody(truncatedJson)
    }
    assertEquals(HttpStatusCode.BadRequest, truncatedResponse.status)
    assertTrue(truncatedResponse.bodyAsText().contains("Failed to decode log event"))

    val followUpResponse = client.post("/agentlog") {
      contentType(ContentType.Application.Json)
      setBody(validJson)
    }
    assertEquals(HttpStatusCode.OK, followUpResponse.status)
    assertTrue(followUpResponse.bodyAsText().contains("Log received and saved"))
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

    Console.log("Testing JSON without type discriminator: $jsonWithoutType")

    val response = client.post("/agentlog") {
      contentType(ContentType.Application.Json)
      setBody(jsonWithoutType)
    }

    Console.log("No type discriminator response status: ${response.status}")
    Console.log("No type discriminator response body: ${response.bodyAsText()}")

    // This should fail with the serialization error we found
    assertEquals(HttpStatusCode.BadRequest, response.status)
    assertTrue(response.bodyAsText().contains("Class discriminator was missing"))
  }
}
