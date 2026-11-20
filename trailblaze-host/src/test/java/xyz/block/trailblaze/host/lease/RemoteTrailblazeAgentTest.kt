package xyz.block.trailblaze.host.lease

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import maestro.orchestra.BackPressCommand
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.LogEmitter
import xyz.block.trailblaze.logs.client.SessionSecrets
import xyz.block.trailblaze.logs.client.ScreenStateLogger
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.model.ResolvedTarget
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TakeSnapshotTool
import xyz.block.trailblaze.utils.ElementComparator
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** What a borrower's agent sends to the lender, and what it keeps on this machine. */
class RemoteTrailblazeAgentTest {

  private val device = TrailblazeDeviceId("8F2C-SIM@pool:31900", TrailblazeDevicePlatform.IOS)
  private val sent = mutableListOf<RemoteDeviceCall>()
  private val logs = mutableListOf<TrailblazeLog>()
  private val memory = AgentMemory().apply { remember("user", "sam") }

  /** The lender's answer to each call. */
  private var reply: (RemoteDeviceCall) -> RemoteDeviceReply = {
    RemoteDeviceReply(toolResult = RemoteToolResult(success = true, message = "done"))
  }

  private fun agent(resolvedTarget: ResolvedTarget? = null) = RemoteTrailblazeAgent(
    device = device,
    driverType = TrailblazeDriverType.IOS_HOST,
    call = { sent += it; reply(it) },
    trailblazeLogger = TrailblazeLogger(logEmitter = LogEmitter { logs += it }, screenStateLogger = ScreenStateLogger { "" }),
    trailblazeDeviceInfoProvider = {
      TrailblazeDeviceInfo(trailblazeDeviceId = device, trailblazeDriverType = TrailblazeDriverType.IOS_HOST, widthPixels = 1170, heightPixels = 2532)
    },
    sessionProvider = TrailblazeSessionProvider { TrailblazeSession(sessionId = SessionId("borrowed"), startTime = Clock.System.now()) },
    memory = memory,
    resolvedTarget = resolvedTarget,
  )

  private fun run(vararg tools: xyz.block.trailblaze.toolcalls.TrailblazeTool) = runBlocking {
    agent().runTrailblazeTools(tools = tools.toList(), elementComparator = NoElementComparator)
  }

  private fun sentTool(call: RemoteDeviceCall) = createTrailblazeYaml().decodeTools(call.toolYaml!!).single().trailblazeTool

  @Test
  fun `a device tool goes to the lender whole, with memory filled in, and is logged here`() {
    run(InputTextTrailblazeTool(text = "hi {{user}}"))
    val call = sent.single()
    assertEquals(RemoteDeviceCall.Op.RUN_TOOL, call.op)
    assertEquals(device.toFullyQualifiedDeviceId(), call.device)
    assertEquals("hi sam", assertIs<InputTextTrailblazeTool>(sentTool(call)).text)
    assertEquals(listOf("inputText"), logs.filterIsInstance<TrailblazeLog.TrailblazeToolLog>().map { it.toolName })
  }

  @Test
  fun `each call names the run's target, so the lender runs that target's tools`() {
    val target = ResolvedTarget(target = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget, deviceId = device)
    val agent = agent(resolvedTarget = target)
    reply = { RemoteDeviceReply(toolResult = RemoteToolResult(success = true), screenState = null) }
    runBlocking { agent.runTrailblazeTools(tools = listOf(InputTextTrailblazeTool(text = "x")), elementComparator = NoElementComparator) }
    runCatching { agent.screenStateProvider() }
    assertEquals(listOf(RemoteDeviceCall.Op.RUN_TOOL, RemoteDeviceCall.Op.SCREEN_STATE), sent.map { it.op })
    assertEquals(listOf(target.id, target.id), sent.map { it.target })
  }

  @Test
  fun `a call carries the run's memory with its secrets marked, so the lender keeps them redacted`() {
    memory.rememberSensitive("pin", "4921-7730")
    SessionSecrets.register(SessionId("borrowed"), listOf("signed-in-password"))

    run(InputTextTrailblazeTool(text = "{{pin}}"))

    val call = sent.single()
    assertEquals(mapOf("user" to "sam", "pin" to "4921-7730"), call.memory)
    assertEquals(listOf("pin"), call.sensitiveMemoryKeys)
    assertTrue("4921-7730" in call.secrets && "signed-in-password" in call.secrets, "secrets sent: ${call.secrets}")
  }

  @Test
  fun `what a tool remembers on the lender is in this run's memory after it`() {
    memory.remember("stale", "old")
    reply = {
      RemoteDeviceReply(
        toolResult = RemoteToolResult(success = true),
        memory = mapOf("user" to "sam", "total" to "$12.50", "otp" to "991204"),
        memoryDeletions = listOf("stale"),
        sensitiveMemoryKeys = listOf("otp"),
      )
    }

    run(InputTextTrailblazeTool(text = "hi"))

    assertEquals("$12.50", memory.variables["total"])
    assertEquals("991204", memory.variables["otp"])
    assertTrue("otp" in memory.sensitiveKeys, "a secret the lender remembered stays secret here")
    assertTrue(!memory.has("stale"), "a key the tool deleted is gone here too")
  }

  @Test
  fun `a named snapshot is taken here from the lender's screen and lands in this session`() {
    reply = { call ->
      when (call.op) {
        RemoteDeviceCall.Op.SCREEN_STATE -> RemoteDeviceReply(
          screenState = RemoteScreenState(
            platform = TrailblazeDevicePlatform.IOS,
            deviceWidth = 1170,
            deviceHeight = 2532,
            viewHierarchy = ViewHierarchyTreeNode(text = "Checkout"),
            screenshotBase64 = java.util.Base64.getEncoder().encodeToString(PNG_SIGNATURE + ByteArray(64)),
          ),
        )
        else -> error("the snapshot must not be sent to the lender: ${call.op}")
      }
    }
    val agent = agent()

    val result = runBlocking {
      agent.runTrailblazeTools(
        tools = listOf(TakeSnapshotTool(screenName = "checkout")),
        elementComparator = NoElementComparator,
        screenStateProvider = agent.screenStateProvider,
      ).result
    }

    assertIs<TrailblazeToolResult.Success>(result)
    assertEquals(listOf(RemoteDeviceCall.Op.SCREEN_STATE), sent.map { it.op })
    val snapshot = logs.filterIsInstance<TrailblazeLog.TrailblazeSnapshotLog>().single()
    assertEquals("checkout", snapshot.displayName)
    assertTrue(snapshot.screenshotFile.endsWith(".png"), "the lender's screenshot is saved here: ${snapshot.screenshotFile}")
  }

  @Test
  fun `a host-local tool runs on this machine and sends nothing`() {
    var ranHere = false
    val tool = object : HostLocalExecutableTrailblazeTool {
      override val advertisedToolName = "host_tool"
      override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
        ranHere = true
        return TrailblazeToolResult.Success()
      }
    }
    run(tool)
    assertTrue(ranHere)
    assertTrue(sent.isEmpty(), "$sent")
  }

  @Test
  fun `a tool that fails on the lender fails here with the lender's message`() {
    reply = { RemoteDeviceReply(toolResult = RemoteToolResult(success = false, message = "no field focused")) }
    val result = run(InputTextTrailblazeTool(text = "x")).result
    assertTrue(assertIs<TrailblazeToolResult.Error>(result).errorMessage.contains("no field focused"), result.toString())
  }

  @Test
  fun `a lender that can't be reached ends the run instead of retrying a device that may be gone`() {
    reply = { throw IllegalStateException("android/8F2C-SIM@pool:31900 is no longer lent to this machine", IOException("gone")) }
    val result = run(InputTextTrailblazeTool(text = "x")).result
    assertIs<TrailblazeToolResult.Error.FatalError>(result)
    assertTrue(result.errorMessage.contains("no longer lent"), result.errorMessage)
  }

  @Test
  fun `raw Maestro commands are refused instead of reaching the lender`() {
    val result = runBlocking { agent().runMaestroCommands(listOf(BackPressCommand()), null) }
    assertContains(assertIs<TrailblazeToolResult.Error>(result).errorMessage, "BackPressCommand")
    assertTrue(sent.isEmpty())
  }

  @Test
  fun `the screen is the lender's capture`() {
    val shot = byteArrayOf(1, 2, 3)
    reply = {
      RemoteDeviceReply(
        screenState = RemoteScreenState.of(
          object : ScreenState {
            override val screenshotBytes = shot
            override val deviceWidth = 1170
            override val deviceHeight = 2532
            override val viewHierarchy = ViewHierarchyTreeNode(text = "Sign in")
            override val trailblazeDevicePlatform = TrailblazeDevicePlatform.IOS
            override val deviceClassifiers = emptyList<xyz.block.trailblaze.devices.TrailblazeDeviceClassifier>()
          },
        ),
      )
    }
    val screen = agent().screenStateProvider()
    assertEquals(RemoteDeviceCall.Op.SCREEN_STATE, sent.single().op)
    assertEquals(shot.toList(), screen.screenshotBytes?.toList())
    assertEquals("Sign in", screen.viewHierarchy.text)
    assertEquals(1170, screen.deviceWidth)
  }

  @Test
  fun `results and screens survive the trip over the wire`() {
    val json = Json { ignoreUnknownKeys = true }
    fun roundTrip(result: TrailblazeToolResult) =
      json.decodeFromString(RemoteToolResult.serializer(), json.encodeToString(RemoteToolResult.serializer(), RemoteToolResult.of(result))).toToolResult()

    val output = buildJsonObject { put("count", JsonPrimitive(2)) }
    assertEquals(TrailblazeToolResult.Success(message = "ok", structuredContent = output), roundTrip(TrailblazeToolResult.Success(message = "ok", structuredContent = output)))
    assertIs<TrailblazeToolResult.Error.FatalError>(roundTrip(TrailblazeToolResult.Error.FatalError("device gone")))
    val thrown = assertIs<TrailblazeToolResult.Error.ExceptionThrown>(
      roundTrip(TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "boom", stackTrace = "at x")),
    )
    assertEquals("boom", thrown.errorMessage)
  }

  private object NoElementComparator : ElementComparator {
    override fun getElementValue(prompt: String) = null
    override fun evaluateBoolean(statement: String) = error("unused")
    override fun evaluateString(query: String) = error("unused")
    override fun extractNumberFromString(input: String): Double? = null
  }

  private companion object {
    val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
  }
}
