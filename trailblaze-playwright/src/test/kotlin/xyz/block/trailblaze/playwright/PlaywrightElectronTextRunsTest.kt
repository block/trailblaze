package xyz.block.trailblaze.playwright

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import org.junit.Test
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.playwright.tools.PlaywrightExecutableTool
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * An app reached over CDP, as Electron is, matches text-recorded selectors too. Its contexts
 * exist before Playwright connects, so the text engine must reach them after the connection.
 */
class PlaywrightElectronTextRunsTest {

  @Test
  fun `a CDP-connected page matches a paragraph by its text`() {
    val port = ServerSocket(0).use { it.localPort }
    val host = Playwright.create()
    try {
      host.chromium().launch(
        BrowserType.LaunchOptions().setHeadless(true).setArgs(listOf("--remote-debugging-port=$port")),
      )
      val manager = PlaywrightElectronBrowserManager("http://127.0.0.1:$port")
      try {
        val count = manager.onPlaywrightThread {
          val page = manager.currentPage
          page.setContent("<!DOCTYPE html><html><body><p>Current value: 0</p></body></html>")
          assertTrue(PlaywrightTextRuns.isRegistered(page))
          assertNotNull(
            PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
              page,
              TrailblazeNodeSelector(
                web = DriverNodeMatch.Web(ariaRole = "paragraph", ariaNameRegex = "Current value: 0"),
              ),
            ),
          ).count()
        }
        assertEquals(1, count)
      } finally {
        manager.close()
      }
    } finally {
      host.close()
    }
  }
}
