package xyz.block.trailblaze.revyl

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsAll
import assertk.assertions.isTrue
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.revyl.tools.RevylNativeAssertTool
import xyz.block.trailblaze.revyl.tools.RevylNativeBackTool
import xyz.block.trailblaze.revyl.tools.RevylNativeDoubleTapTool
import xyz.block.trailblaze.revyl.tools.RevylNativeNavigateTool
import xyz.block.trailblaze.revyl.tools.RevylNativePressKeyTool
import xyz.block.trailblaze.revyl.tools.RevylNativeSwipeTool
import xyz.block.trailblaze.revyl.tools.RevylNativeTapTool
import xyz.block.trailblaze.revyl.tools.RevylNativeTypeTool
import xyz.block.trailblaze.revyl.tools.RevylToolSetIds
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog

/**
 * Regression test pinning the Revyl driver's LLM tool surface to the YAML catalog.
 * The `revyl_core` and `revyl_verification` toolsets are the authoritative source;
 * these assertions fail if the YAML drifts away from the Kotlin tool classes.
 */
class RevylNativeToolSetTest {

  @Test
  fun `revyl_core resolves every interaction tool`() {
    val resolved =
      TrailblazeToolSetCatalog.resolveForDriver(
        driverType = TrailblazeDriverType.REVYL_ANDROID,
        requestedIds = listOf("revyl_core"),
      )
    assertThat(resolved.toolClasses).containsAll(
      RevylNativeTapTool::class,
      RevylNativeDoubleTapTool::class,
      RevylNativeTypeTool::class,
      RevylNativeSwipeTool::class,
      RevylNativeNavigateTool::class,
      RevylNativeBackTool::class,
      RevylNativePressKeyTool::class,
    )
  }

  @Test
  fun `revyl_verification resolves the assert tool`() {
    val resolved =
      TrailblazeToolSetCatalog.resolveForDriver(
        driverType = TrailblazeDriverType.REVYL_IOS,
        requestedIds = listOf("revyl_verification"),
      )
    assertThat(resolved.toolClasses).contains(RevylNativeAssertTool::class)
  }

  @Test
  fun `full LLM toolset is a superset of core and verification`() {
    val full =
      TrailblazeToolSetCatalog.resolveForDriver(
        driverType = TrailblazeDriverType.REVYL_ANDROID,
        requestedIds = RevylToolSetIds.ALL,
      ).toolClasses
    val core =
      TrailblazeToolSetCatalog.resolveForDriver(
        TrailblazeDriverType.REVYL_ANDROID, listOf("revyl_core"),
      ).toolClasses
    val verification =
      TrailblazeToolSetCatalog.resolveForDriver(
        TrailblazeDriverType.REVYL_ANDROID, listOf("revyl_verification"),
      ).toolClasses
    assertThat(full.containsAll(core)).isTrue()
    assertThat(full.containsAll(verification)).isTrue()
  }
}
