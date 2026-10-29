package xyz.block.trailblaze.host.rules

import assertk.assertThat
import assertk.assertions.contains
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.NoOpPageManager
import xyz.block.trailblaze.toolcalls.ToolName

/**
 * A web session registers the framework scripted tools its toolsets deliver. The scripted-tool
 * launcher registers exactly [xyz.block.trailblaze.toolcalls.TrailblazeToolRepo.allCatalogScriptedToolNames],
 * so a name missing from it fails as an unknown tool when a recorded web step dispatches it.
 */
class BasePlaywrightNativeTestScriptedToolsTest {

  private val rule = BasePlaywrightNativeTest(
    trailblazeDeviceId = TrailblazeDeviceId(
      instanceId = "scripted-tools-test-web",
      trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB,
    ),
    // Adopting a stub manager keeps the constructor from launching a real browser.
    existingBrowserManager = NoOpPageManager(),
    noLogging = true,
  )

  @Test
  fun `a web session registers the recorded-trail conditional`() {
    assertThat(rule.toolRepo.allCatalogScriptedToolNames).contains(ToolName("runIf"))
  }
}
