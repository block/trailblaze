package xyz.block.trailblaze.toolcalls.commands

import org.junit.Test
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import kotlin.test.assertEquals

/**
 * Unit tests for [recordedNodeSelectorForMaestroPath] — the pure decision of which selector
 * source gets recorded on the Maestro-dispatch path of [TapTrailblazeTool] /
 * [AssertVisibleTrailblazeTool]. Tested directly with plain inputs so every branch is covered
 * without coupling to the selector generators (see the ANDROID mis-target regression tests in
 * TapOnTrailblazeToolTest / AssertVisibleTrailblazeToolTest for the end-to-end contract).
 */
class RecordedNodeSelectorForMaestroPathTest {

  private val legacy = TrailblazeNodeSelector(
    androidMaestro = DriverNodeMatch.AndroidMaestro(textRegex = "Bagel"),
  )

  @Test
  fun `ANDROID always records the TapSelectorV2-derived selector`() {
    // The modern selector is container-shaped but lowers non-blank (resourceId maps to
    // idRegex) — exactly the mis-target case: it must still lose to the legacy-derived one.
    val containerShapedModern = TrailblazeNodeSelector(
      androidMaestro = DriverNodeMatch.AndroidMaestro(
        resourceIdRegex = "com.example:id/library_list_recycler_view",
      ),
    )
    val recorded = recordedNodeSelectorForMaestroPath(
      platform = TrailblazeDevicePlatform.ANDROID,
      modernNodeSelector = containerShapedModern,
      legacyAsNodeSelector = legacy,
    )
    assertEquals(legacy, recorded)
  }

  @Test
  fun `non-ANDROID keeps the modern selector when it lowers to a non-blank Maestro selector`() {
    val modern = TrailblazeNodeSelector(
      iosMaestro = DriverNodeMatch.IosMaestro(textRegex = "Buy Now"),
    )
    val recorded = recordedNodeSelectorForMaestroPath(
      platform = TrailblazeDevicePlatform.IOS,
      modernNodeSelector = modern,
      legacyAsNodeSelector = legacy,
    )
    assertEquals(modern, recorded)
  }

  @Test
  fun `non-ANDROID falls back to the TapSelectorV2-derived selector when the modern one lowers blank`() {
    // Driver-only predicate (classNameRegex) drops during Maestro lowering — recording it
    // would break the Maestro fallback at replay.
    val driverOnlyModern = TrailblazeNodeSelector(
      iosMaestro = DriverNodeMatch.IosMaestro(classNameRegex = "UIButton"),
    )
    val recorded = recordedNodeSelectorForMaestroPath(
      platform = TrailblazeDevicePlatform.IOS,
      modernNodeSelector = driverOnlyModern,
      legacyAsNodeSelector = legacy,
    )
    assertEquals(legacy, recorded)
  }

  @Test
  fun `non-ANDROID falls back to the TapSelectorV2-derived selector when there is no modern selector`() {
    val recorded = recordedNodeSelectorForMaestroPath(
      platform = TrailblazeDevicePlatform.WEB,
      modernNodeSelector = null,
      legacyAsNodeSelector = legacy,
    )
    assertEquals(legacy, recorded)
  }

  @Test
  fun `web keeps the modern selector - its match lowers via ariaName to textRegex`() {
    val modern = TrailblazeNodeSelector(
      web = DriverNodeMatch.Web(ariaNameRegex = "Submit"),
    )
    val recorded = recordedNodeSelectorForMaestroPath(
      platform = TrailblazeDevicePlatform.WEB,
      modernNodeSelector = modern,
      legacyAsNodeSelector = legacy,
    )
    assertEquals(modern, recorded)
  }
}
