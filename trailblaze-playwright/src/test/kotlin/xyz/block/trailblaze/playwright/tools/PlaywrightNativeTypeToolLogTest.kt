package xyz.block.trailblaze.playwright.tools

import com.microsoft.playwright.Page
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.util.Console
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The console is uploaded with the session, so the line a type logs must not carry its text. */
class PlaywrightNativeTypeToolLogTest {

  @Test
  fun `typing logs the target but never the text`() {
    val secret = "Typed-Secret-0451"
    // No ref, so the tool logs its line and then fails before it touches the page.
    val page = Proxy.newProxyInstance(Page::class.java.classLoader, arrayOf(Page::class.java)) { _, method, _ ->
      error("page.${method.name} should not be called")
    } as Page

    val log = captureConsole {
      runBlocking { PlaywrightNativeTypeTool(text = secret).executeWithPlaywright(page, stubContext()) }
    }

    assertTrue("Typing into" in log, "The type line was not logged, so this proves nothing: $log")
    assertFalse(secret in log, "The type line leaks the text")
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
      trailblazeDeviceId = TrailblazeDeviceId(instanceId = "web-test", trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB),
      trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      widthPixels = 1280,
      heightPixels = 800,
    ),
    sessionProvider = { TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now()) },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
  )
}
