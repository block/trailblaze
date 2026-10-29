package xyz.block.trailblaze.maestro

import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import maestro.DeviceInfo
import maestro.Maestro
import maestro.Driver
import maestro.TreeNode
import maestro.device.Platform
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.viewmatcher.matching.ViewHierarchyOnlyDriver
import xyz.block.trailblaze.utils.Ext.asJsonObject

class OrchestraRunnerLaunchRedactionTest {
  @Test
  fun `batch driver log masks reused launch secret while input receives original text`() {
    var delivered: String? = null
    val delegate = Proxy.newProxyInstance(Driver::class.java.classLoader, arrayOf(Driver::class.java)) {
        _, method, arguments ->
      when (method.name) {
        "inputText" -> { delivered = arguments?.firstOrNull() as String; null }
        else -> error("Unexpected driver method: ${method.name}")
      }
    } as Driver
    val screen = object : ScreenState {
      override val screenshotBytes: ByteArray? = null
      override val deviceWidth: Int = 390
      override val deviceHeight: Int = 844
      override val viewHierarchy = ViewHierarchyTreeNode()
      override val trailblazeDevicePlatform = TrailblazeDevicePlatform.IOS
      override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
    }
    val logs = mutableListOf<TrailblazeLog>()
    val driver = LoggingDriver(
      delegate, { screen },
      TrailblazeLogger(logEmitter = { logs += it }, screenStateLogger = { "" }),
      TrailblazeSessionProvider { TrailblazeSession(SessionId("fixture"), Clock.System.now()) },
    ).forMaestroBatch(listOf(MaestroCommand(LaunchAppCommand(
      appId = "com.example.app", launchArguments = mapOf("pin" to "123"),
    ))))

    driver.inputText("PIN=123")

    assertEquals("PIN=123", delivered)
    val action = logs.filterIsInstance<TrailblazeLog.AgentDriverLog>().single().action
    assertEquals("PIN=<redacted>", (action as AgentDriverAction.EnterText).text)
  }

  @Test
  fun `short launch credential is masked inside later text but not an app id`() {
    val command = buildJsonObject {
      putJsonObject("inputTextCommand") { put("text", "PIN=123") }
      putJsonObject("launchAppCommand") { put("appId", "com.example.app123") }
      putJsonObject("stopAppCommand") { put("appId", "123") }
      put("numericCopy", 123)
    }

    val redacted = command.withKnownMaestroLaunchValuesRedacted(listOf("123"), preserveReferences = false)

    assertEquals(JsonPrimitive("PIN=<redacted>"), redacted["inputTextCommand"]?.jsonObject?.get("text"))
    assertEquals(JsonPrimitive("com.example.app<redacted>"), redacted["launchAppCommand"]?.jsonObject?.get("appId"))
    assertEquals(JsonPrimitive("<redacted>"), redacted["stopAppCommand"]?.jsonObject?.get("appId"))
    assertEquals(JsonPrimitive("<redacted>"), redacted["numericCopy"])
    val eraseText = buildJsonObject { put("charactersToErase", 1) }
    assertEquals(JsonPrimitive("<redacted>"), eraseText.withKnownMaestroLaunchValuesRedacted(
      listOf("1"), preserveReferences = false,
    )["charactersToErase"])
  }

  @Test
  fun `unresolved reference shaped command text cannot exempt a launch credential`() {
    val authored = buildJsonObject { putJsonObject("inputTextCommand") { put("text", "{{session_secret}}") } }
    val unresolved = authored.withKnownMaestroLaunchValuesRedacted(
      listOf("session_secret"), preserveReferences = true, executed = authored,
    )
    val resolved = authored.withKnownMaestroLaunchValuesRedacted(
      listOf("session_secret"), preserveReferences = true,
      executed = buildJsonObject { putJsonObject("inputTextCommand") { put("text", "session_secret") } },
    )

    assertEquals(JsonPrimitive("<redacted>"), unresolved["inputTextCommand"]?.jsonObject?.get("text"))
    assertEquals(JsonPrimitive("{{session_secret}}"), resolved["inputTextCommand"]?.jsonObject?.get("text"))

    val shortValue = buildJsonObject { putJsonObject("inputTextCommand") { put("text", "{{args.oauth2Token}}") } }
    assertEquals(
      JsonPrimitive("{{args.oauth2Token}}"),
      shortValue.withKnownMaestroLaunchValuesRedacted(
        listOf("2"), preserveReferences = true, executed = shortValue,
      )["inputTextCommand"]?.jsonObject?.get("text"),
    )
  }

  @Test
  fun `blanked unknown Maestro reference cannot preserve a launch secret`() {
    val authoredCommand = buildJsonObject {
      putJsonObject("inputTextCommand") { put("text", "\${TOKEN-secret123}") }
    }
    val executedCommand = buildJsonObject {
      putJsonObject("inputTextCommand") { put("text", "") }
    }
    assertEquals(
      JsonPrimitive("<redacted>"),
      authoredCommand.withKnownMaestroLaunchValuesRedacted(
        listOf("secret123"), preserveReferences = true, executed = executedCommand,
      )["inputTextCommand"]?.jsonObject?.get("text"),
    )

    val authoredLaunch = buildJsonObject {
      putJsonObject("launchApp") { putJsonObject("arguments") { put("credential", "\${TOKEN-secret123}") } }
    }
    val executedLaunch = buildJsonObject {
      putJsonObject("launchApp") { putJsonObject("arguments") { put("credential", "") } }
    }
    assertEquals(
      JsonPrimitive("<redacted>"),
      authoredLaunch.withAuthoredMaestroLaunchArgumentsRedacted(executedLaunch)
        ["launchApp"]?.jsonObject?.get("arguments")?.jsonObject?.get("credential"),
    )
  }

  @Test
  fun `repeated launch values do not corrupt a proven authored reference`() {
    val authored = buildJsonObject {
      putJsonObject("inputTextCommand") { put("text", "{{session_secret}} value=session_secret") }
    }
    val executed = buildJsonObject {
      putJsonObject("inputTextCommand") { put("text", "bound value=session_secret") }
    }
    assertEquals(
      JsonPrimitive("{{session_secret}} value=<redacted>"),
      authored.withKnownMaestroLaunchValuesRedacted(
        listOf("session_secret", "session_secret"), preserveReferences = true, executed = executed,
      )["inputTextCommand"]?.jsonObject?.get("text"),
    )
  }

  @Test
  fun `unresolved launch reference is a literal when another argument resolves`() {
    val authored = buildJsonObject {
      putJsonObject("launchApp") {
        putJsonObject("arguments") {
          put("credential", "{{session_secret}}")
          put("other", "{{args.other}}")
        }
      }
    }
    val executed = buildJsonObject {
      putJsonObject("launchApp") {
        putJsonObject("arguments") {
          put("other", "hello")
          put("credential", "{{session_secret}}")
        }
      }
    }

    val literals = maestroAuthoredLaunchLiterals(authored, executed)
    assertEquals(listOf("{{session_secret}}"), literals)
    val later = buildJsonObject { putJsonObject("inputTextCommand") { put("text", "{{session_secret}}") } }
    assertEquals(
      JsonPrimitive("<redacted>"),
      later.withKnownMaestroLaunchValuesRedacted(literals, preserveReferences = true, executed = later)
        ["inputTextCommand"]?.jsonObject?.get("text"),
    )
  }

  @Test
  fun `batch scrub does not rewrite incidental booleans or two character fragments`() {
    val command = buildJsonObject {
      putJsonObject("inputTextCommand") { put("text", "Sent") }
      put("clearState", true)
      put("shortExact", "en")
    }

    val redacted = command.withKnownMaestroLaunchValuesRedacted(
      listOf("true", "en"), preserveReferences = false,
    )

    assertEquals(JsonPrimitive("Sent"), redacted["inputTextCommand"]?.jsonObject?.get("text"))
    assertEquals(JsonPrimitive(true), redacted["clearState"])
    assertEquals(JsonPrimitive("<redacted>"), redacted["shortExact"])
  }

  @Test
  fun `legacy command logs mask a launch credential reused by later input text`() = runBlocking {
    val commands = MaestroYamlParser.parseYaml("""
      - launchApp: {appId: com.example.app1, arguments: {credential: fixture-session-secret, debug: 1}}
      - inputText: fixture-session-secret
      - inputText: Item 1
    """.trimIndent()).map(::MaestroCommand)
    val logs = mutableListOf<TrailblazeLog>()
    val maestro = Maestro(driver = ViewHierarchyOnlyDriver(
      rootTreeNode = TreeNode(),
      deviceInfo = DeviceInfo(platform = Platform.IOS, widthPixels = 390, heightPixels = 844,
        widthGrid = 390, heightGrid = 844),
    ))

    OrchestraRunner.runCommands(
      maestro = maestro, commands = commands, traceId = null,
      trailblazeLogger = TrailblazeLogger(logEmitter = { logs += it }, screenStateLogger = { "" }),
      sessionProvider = TrailblazeSessionProvider {
        TrailblazeSession(sessionId = SessionId("fixture"), startTime = Clock.System.now())
      },
      screenStateProvider = null,
      orchestraFactory = { callbacks -> object : OrchestraRunner.OrchestraExecutor {
        override suspend fun execute(commands: List<MaestroCommand>): Boolean {
          callbacks.onCommandComplete(0, commands.single())
          return true
        }
      } },
    )

    val commandLogs = logs.filterIsInstance<TrailblazeLog.MaestroCommandLog>()
    assertEquals(3, commandLogs.size)
    assertFalse(commandLogs.toString().contains("fixture-session-secret"))
    assertContains(commandLogs.toString(), "com.example.app1")
    // A one-character launch flag must not poison unrelated command text.
    assertContains(commandLogs.toString(), "Item 1")
  }

  @Test
  fun `legacy launch logs and errors redact arguments while execution receives real values`() = runBlocking {
    val secret = "fixture-session-secret"
    val launch = LaunchAppCommand(
      appId = "com.example.app",
      launchArguments = mapOf("credential" to secret, "numericCredential" to 47391),
    )
    val nested = MaestroCommand(MaestroYamlParser.parseYaml(
      """
      - runFlow:
          commands:
            - launchApp:
                appId: com.example.app
                arguments: {credential: $secret, numericCredential: 47391}
      """.trimIndent(),
    ).single())
    assertContains(nested.asJsonObject().toString(), "sourceInfo")
    assertContains(nested.asJsonObject().toString(), secret)
    val maestro = Maestro(
      driver = ViewHierarchyOnlyDriver(
        rootTreeNode = TreeNode(),
        deviceInfo = DeviceInfo(
          platform = Platform.IOS, widthPixels = 390, heightPixels = 844,
          widthGrid = 390, heightGrid = 844,
        ),
      ),
    )
    for (command in listOf(MaestroCommand(launch), nested)) for (success in listOf(true, false)) {
      val logs = mutableListOf<TrailblazeLog>()
      val result = OrchestraRunner.runCommands(
        maestro = maestro, commands = listOf(command), traceId = null,
        trailblazeLogger = TrailblazeLogger(
          logEmitter = { logs += it }, screenStateLogger = { "" },
        ),
        sessionProvider = TrailblazeSessionProvider {
          TrailblazeSession(sessionId = SessionId("fixture"), startTime = Clock.System.now())
        },
        screenStateProvider = null,
        orchestraFactory = { callbacks ->
          object : OrchestraRunner.OrchestraExecutor {
            override suspend fun execute(commands: List<MaestroCommand>): Boolean {
              assertContains(commands.single().asJsonObject().toString(), secret)
              if (success) callbacks.onCommandComplete(0, commands.single())
              else callbacks.onCommandFailed(0, commands.single(), IllegalStateException("launch failed: $secret"))
              return success
            }
          }
        },
      )
      val log = logs.filterIsInstance<TrailblazeLog.MaestroCommandLog>().single()
      assertEquals(success, log.successful)
      assertContains(log.maestroCommandJsonObj.toString(), "com.example.app")
      assertContains(log.maestroCommandJsonObj.toString(), "credential")
      if (command.asCommand() is LaunchAppCommand) assertEquals(
        JsonPrimitive("<redacted>"),
        log.maestroCommandJsonObj["launchAppCommand"]?.jsonObject?.get("launchArguments")?.jsonObject?.get("numericCredential"),
      )
      assertFalse(log.toString().contains(secret))
      assertFalse(log.toString().contains("47391"))
      assertFalse(result.toString().contains(secret))
    }
  }
}
