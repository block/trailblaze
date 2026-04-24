package xyz.block.trailblaze.playwright.tools

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsAll
import assertk.assertions.isTrue
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog

/**
 * Regression test pinning the Playwright drivers' LLM tool surface to the YAML catalog.
 * The `web_core` and `web_verification` toolsets are the authoritative source; these assertions
 * fail if a YAML edit drops a tool name or omits one of the Playwright driver keys from
 * `drivers:`. The latter is easy to miss because it silently empties the resolved set for the
 * affected driver rather than throwing.
 */
class WebToolSetCatalogTest {

  @Test
  fun `web_core resolves every native Playwright interaction tool`() {
    val resolved = TrailblazeToolSetCatalog.resolveForDriver(
      driverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      requestedIds = listOf("web_core"),
    )
    assertThat(resolved.toolClasses).containsAll(
      PlaywrightNativeNavigateTool::class,
      PlaywrightNativeClickTool::class,
      PlaywrightNativeTypeTool::class,
      PlaywrightNativePressKeyTool::class,
      PlaywrightNativeHoverTool::class,
      PlaywrightNativeSelectOptionTool::class,
      PlaywrightNativeWaitTool::class,
      PlaywrightNativeScrollTool::class,
      PlaywrightNativeSnapshotTool::class,
      PlaywrightNativeRequestDetailsTool::class,
    )
  }

  @Test
  fun `web_verification resolves every assertion tool`() {
    val resolved = TrailblazeToolSetCatalog.resolveForDriver(
      driverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      requestedIds = listOf("web_verification"),
    )
    assertThat(resolved.toolClasses).containsAll(
      PlaywrightNativeVerifyTextVisibleTool::class,
      PlaywrightNativeVerifyElementVisibleTool::class,
      PlaywrightNativeVerifyValueTool::class,
      PlaywrightNativeVerifyListVisibleTool::class,
    )
  }

  @Test
  fun `both PLAYWRIGHT drivers resolve the same web tool surface`() {
    // `BasePlaywrightElectronTest` reuses `WebToolSetIds.ALL` and resolves for
    // PLAYWRIGHT_ELECTRON. That works only if both driver keys are listed in `drivers:` on
    // web_core.yaml and web_verification.yaml. Without this assertion, dropping
    // `playwright-electron` from either YAML would silently zero out the Electron tool surface.
    val nativeClasses = TrailblazeToolSetCatalog
      .resolveForDriver(TrailblazeDriverType.PLAYWRIGHT_NATIVE, WebToolSetIds.ALL)
      .toolClasses
    val electronClasses = TrailblazeToolSetCatalog
      .resolveForDriver(TrailblazeDriverType.PLAYWRIGHT_ELECTRON, WebToolSetIds.ALL)
      .toolClasses
    assertThat(electronClasses.containsAll(nativeClasses)).isTrue()
    assertThat(nativeClasses.containsAll(electronClasses)).isTrue()
  }

  @Test
  fun `full web toolset picks up meta objectiveStatus via always_enabled`() {
    val resolved = TrailblazeToolSetCatalog.resolveForDriver(
      driverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      requestedIds = WebToolSetIds.ALL,
    )
    val names =
      (resolved.toolClasses.map { it.simpleName } + resolved.yamlToolNames.map { it.toolName })
        .filterNotNull()
        .toSet()
    assertThat(names).contains("ObjectiveStatusTrailblazeTool")
  }
}
