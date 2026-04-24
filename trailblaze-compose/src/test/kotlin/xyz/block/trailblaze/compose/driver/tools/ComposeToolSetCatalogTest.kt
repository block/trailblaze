package xyz.block.trailblaze.compose.driver.tools

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsAll
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.toolcalls.commands.TakeSnapshotTool

/**
 * Regression test pinning the Compose driver's LLM tool surface to the YAML catalog.
 * The `compose_core` and `compose_verification` toolsets are the authoritative source; these
 * assertions fail if a YAML edit drops a tool name or if `drivers:` stops listing `compose`.
 */
class ComposeToolSetCatalogTest {

  @Test
  fun `compose_core resolves every Compose interaction tool plus takeSnapshot`() {
    val resolved = TrailblazeToolSetCatalog.resolveForDriver(
      driverType = TrailblazeDriverType.COMPOSE,
      requestedIds = listOf("compose_core"),
    )
    assertThat(resolved.toolClasses).containsAll(
      ComposeClickTool::class,
      ComposeTypeTool::class,
      ComposeScrollTool::class,
      ComposeWaitTool::class,
      ComposeRequestDetailsTool::class,
      // takeSnapshot is class-backed in trailblaze-common but authored into compose_core.yaml
      // because the Compose driver needs it alongside the interaction tools (observation.yaml
      // restricts itself to android/ios drivers).
      TakeSnapshotTool::class,
    )
  }

  @Test
  fun `compose_verification resolves both visibility assertions`() {
    val resolved = TrailblazeToolSetCatalog.resolveForDriver(
      driverType = TrailblazeDriverType.COMPOSE,
      requestedIds = listOf("compose_verification"),
    )
    assertThat(resolved.toolClasses).containsAll(
      ComposeVerifyTextVisibleTool::class,
      ComposeVerifyElementVisibleTool::class,
    )
  }

  @Test
  fun `full compose toolset picks up meta objectiveStatus via always_enabled`() {
    val resolved = TrailblazeToolSetCatalog.resolveForDriver(
      driverType = TrailblazeDriverType.COMPOSE,
      requestedIds = ComposeToolSetIds.ALL,
    )
    val simpleNames = resolved.toolClasses.mapNotNull { it.simpleName }.toSet()
    assertThat(simpleNames).contains("ObjectiveStatusTrailblazeTool")
  }
}
