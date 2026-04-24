package xyz.block.trailblaze.host.rules

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog

abstract class BaseWebTrailblazeTest :
  BaseHostTrailblazeTest(
    explicitDeviceId = TrailblazeDeviceId(
      instanceId = "playwright-native",
      trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB,
    ),
    trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
    config = TrailblazeConfig.DEFAULT,
    systemPromptTemplate = WEB_SYSTEM_PROMPT,
    trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
      name = "Playwright Native Base Tools",
      // Driver-aware: the catalog's `drivers:` declarations already keep mobile-only tools
      // (TapOnPoint, HideKeyboard, etc. from core_interaction.yaml) out of Playwright sessions.
      toolClasses = TrailblazeToolSetCatalog.defaultToolClassesForDriver(
        TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      ),
    ),
  ) {
  companion object {
    internal val WEB_SYSTEM_PROMPT = """
**You are managing a web browser.**

You will be provided with the current screen state, including a text representation of
the current UI hierarchy as well as a screenshot of the device. The screenshot may be marked with
colored boxes containing nodeIds in the bottom right corner of each box.

Make sure to pay special attention to the state of the views.
A text field must be focused before you can enter text into it.
A disabled button will have no effect when clicked.
Any blank or loading screens should use the wait tool in order to provide the next valid view state.

When interpreting objectives, if an objective begins with the word "expect", "verify", "confirm", or
"assert" (case-insensitive), you should use the assert visible tool to check the relevant UI element
or state.

**UI Interaction hints:**
- If the browser is currently on a loading screen, always wait for the page to finish before choosing another tool.
- Always use the accessibility text when interacting with Icons on the screen.
    """.trimIndent()
  }

  override fun ensureTargetAppIsStopped() {
    // Not relevant on web at this point
  }
}
