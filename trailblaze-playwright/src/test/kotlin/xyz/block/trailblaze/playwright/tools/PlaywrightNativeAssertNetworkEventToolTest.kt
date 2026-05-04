package xyz.block.trailblaze.playwright.tools

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.LogEmitter
import xyz.block.trailblaze.logs.client.ScreenStateLogger
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.playwright.network.PlaywrightTestProxies
import xyz.block.trailblaze.playwright.network.WebNetworkCapture
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlaywrightNativeAssertNetworkEventToolTest {

  @get:Rule val tmp: TemporaryFolder = TemporaryFolder()

  @Test
  fun `returns Success when event name appears in request URL`() {
    runBlocking {
      val ctx = PlaywrightTestProxies.FakeBrowserContext()
      val dir = tmp.newFolder()
      WebNetworkCapture.start(ctx.proxy, "s", dir)
      ctx.fireRequest(
        PlaywrightTestProxies.fakeRequest(url = "https://analytics.example.com/track/adjust-stock"),
      )
      WebNetworkCapture.stop(ctx.proxy)

      val result = PlaywrightNativeAssertNetworkEventTool("adjust-stock")
        .executeWithPlaywright(fakePage(ctx.proxy), noOpContext())

      assertIs<TrailblazeToolResult.Success>(result)
    }
  }

  @Test
  fun `returns Success when event name appears in inline request body`() {
    runBlocking {
      val ctx = PlaywrightTestProxies.FakeBrowserContext()
      val dir = tmp.newFolder()
      WebNetworkCapture.start(ctx.proxy, "s", dir)
      ctx.fireRequest(
        PlaywrightTestProxies.fakeRequest(
          method = "POST",
          url = "https://analytics.example.com/track",
          headers = mapOf("content-type" to "application/json"),
          postBody = """{"event":"checkout-started","page":"cart"}""".toByteArray(),
        ),
      )
      WebNetworkCapture.stop(ctx.proxy)

      val result = PlaywrightNativeAssertNetworkEventTool("checkout-started")
        .executeWithPlaywright(fakePage(ctx.proxy), noOpContext())

      assertIs<TrailblazeToolResult.Success>(result)
    }
  }

  @Test
  fun `returns Success when event name appears in on-disk blob body larger than inline limit`() {
    runBlocking {
      val ctx = PlaywrightTestProxies.FakeBrowserContext()
      val dir = tmp.newFolder()
      WebNetworkCapture.start(ctx.proxy, "s", dir)

      // Exceeds INLINE_BODY_LIMIT_BYTES (4 KB) → stored as blobPath, not inlineText.
      val padding = "x".repeat(WebNetworkCapture.INLINE_BODY_LIMIT_BYTES + 512)
      ctx.fireRequest(
        PlaywrightTestProxies.fakeRequest(
          method = "POST",
          url = "https://analytics.example.com/track",
          headers = mapOf("content-type" to "application/json"),
          postBody = """{"event":"blob-signal","pad":"$padding"}""".toByteArray(),
        ),
      )
      WebNetworkCapture.stop(ctx.proxy)

      val result = PlaywrightNativeAssertNetworkEventTool("blob-signal")
        .executeWithPlaywright(fakePage(ctx.proxy), noOpContext())

      assertIs<TrailblazeToolResult.Success>(result)
    }
  }

  @Test
  fun `returns Error when event name is absent from all captured requests`() {
    runBlocking {
      val ctx = PlaywrightTestProxies.FakeBrowserContext()
      val dir = tmp.newFolder()
      WebNetworkCapture.start(ctx.proxy, "s", dir)
      ctx.fireRequest(
        PlaywrightTestProxies.fakeRequest(
          method = "POST",
          url = "https://analytics.example.com/track",
          headers = mapOf("content-type" to "application/json"),
          postBody = """{"event":"unrelated-event"}""".toByteArray(),
        ),
      )
      WebNetworkCapture.stop(ctx.proxy)

      val result = PlaywrightNativeAssertNetworkEventTool("missing-signal", timeoutMs = 200)
        .executeWithPlaywright(fakePage(ctx.proxy), noOpContext())

      assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
      assertTrue(
        result.errorMessage.contains("not found"),
        "error should say 'not found': ${result.errorMessage}",
      )
    }
  }

  @Test
  fun `returns Error immediately when eventName is blank`() {
    runBlocking {
      val ctx = PlaywrightTestProxies.FakeBrowserContext()

      val result = PlaywrightNativeAssertNetworkEventTool("   ")
        .executeWithPlaywright(fakePage(ctx.proxy), noOpContext())

      assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
      assertTrue(
        result.errorMessage.contains("blank"),
        "error should explain blank eventName: ${result.errorMessage}",
      )
    }
  }

  @Test
  fun `returns Error when no capture is active for the page context`() {
    runBlocking {
      // No WebNetworkCapture.start() called — instances map has no entry for this context.
      val ctx = PlaywrightTestProxies.FakeBrowserContext()

      val result = PlaywrightNativeAssertNetworkEventTool("any-event")
        .executeWithPlaywright(fakePage(ctx.proxy), noOpContext())

      assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
      assertTrue(
        result.errorMessage.contains("No network capture"),
        "error should explain capture is missing: ${result.errorMessage}",
      )
    }
  }

  @Test
  fun `returns Success when event arrives after assertion starts (polling)`() {
    runBlocking {
      val ctx = PlaywrightTestProxies.FakeBrowserContext()
      val dir = tmp.newFolder()
      WebNetworkCapture.start(ctx.proxy, "s", dir)

      // Fire the request 300 ms after the tool starts polling.
      launch {
        delay(300)
        ctx.fireRequest(
          PlaywrightTestProxies.fakeRequest(
            method = "POST",
            url = "https://analytics.example.com/track",
            headers = mapOf("content-type" to "application/json"),
            postBody = """{"event":"late-signal"}""".toByteArray(),
          ),
        )
      }

      val result = PlaywrightNativeAssertNetworkEventTool("late-signal", timeoutMs = 2_000)
        .executeWithPlaywright(fakePage(ctx.proxy), noOpContext())

      WebNetworkCapture.stop(ctx.proxy)
      assertIs<TrailblazeToolResult.Success>(result)
    }
  }

  private fun fakePage(browserContext: BrowserContext): Page = Proxy.newProxyInstance(
    Page::class.java.classLoader,
    arrayOf(Page::class.java),
    InvocationHandler { _, method, _ ->
      when (method.name) {
        "context" -> browserContext
        "equals" -> false
        "hashCode" -> System.identityHashCode(this)
        "toString" -> "FakePage"
        else -> error("Unstubbed Page.${method.name}")
      }
    },
  ) as Page

  private fun noOpContext(): TrailblazeToolExecutionContext = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "test",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB,
      ),
      trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      widthPixels = 1280,
      heightPixels = 800,
    ),
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(
        sessionId = SessionId("test-session"),
        startTime = Instant.fromEpochMilliseconds(0),
      )
    },
    trailblazeLogger = TrailblazeLogger(
      logEmitter = LogEmitter { },
      screenStateLogger = ScreenStateLogger { "" },
    ),
    memory = AgentMemory(),
  )
}
