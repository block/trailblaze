package xyz.block.trailblaze.compose.driver.tools

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.compose.target.ComposeTestTarget
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.util.Console

/** The console is uploaded with the session, so the line a type logs must not carry its text. */
class ComposeTypeToolLogTest {

  private val secret = "Typed-Secret-0451"

  // Any UI access fails the tool after it has logged its line, so no UI is needed.
  private val target = Proxy.newProxyInstance(
    ComposeTestTarget::class.java.classLoader,
    arrayOf(ComposeTestTarget::class.java),
  ) { _, method, _ -> error("target.${method.name} should not be called") } as ComposeTestTarget

  @Test
  fun `typing logs the target but never the text`() {
    val log = captureConsole {
      runBlocking { ComposeTypeTool(text = secret, element = "password field").executeWithCompose(target, stubContext()) }
    }

    assertTrue("Typing into password field" in log, "The type line was not logged, so this proves nothing: $log")
    assertFalse(secret in log, "The type line leaks the text")
  }

  @Test
  fun `a field found by its current text is not named by that text`() {
    val log = captureConsole {
      runBlocking { ComposeTypeTool(text = "new value", existingText = secret).executeWithCompose(target, stubContext()) }
    }

    assertTrue("Typing into" in log, "The type line was not logged, so this proves nothing: $log")
    assertFalse(secret in log, "The type line leaks the field's current text")
  }

  private fun captureConsole(block: () -> Unit): String {
    val captured = ByteArrayOutputStream()
    val fields = listOf("out", "userOut").map { Console::class.java.getDeclaredField(it).apply { isAccessible = true } }
    val originals = fields.map { it.get(Console) }
    val wasQuiet = Console.isQuietMode()
    fields.forEach { it.set(Console, PrintStream(captured, true, Charsets.UTF_8)) }
    if (wasQuiet) Console.disableQuietMode()
    try {
      block()
    } finally {
      fields.zip(originals).forEach { (field, original) -> field.set(Console, original) }
      if (wasQuiet) Console.enableQuietMode()
    }
    return captured.toString(Charsets.UTF_8)
  }

  private fun stubContext() = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(instanceId = "compose-test", trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB),
      trailblazeDriverType = TrailblazeDriverType.COMPOSE,
      widthPixels = 1280,
      heightPixels = 800,
    ),
    sessionProvider = { TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now()) },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
  )
}
