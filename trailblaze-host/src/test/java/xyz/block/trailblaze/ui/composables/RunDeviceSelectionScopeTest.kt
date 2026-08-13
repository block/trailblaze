package xyz.block.trailblaze.ui.composables

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * Pins the two halves of the Run Configuration dialog's selection that a slow app probe can
 * corrupt: what the selection becomes when a device's inventory finally lands, and what the
 * selection is scoped to.
 *
 * Both exist because the selection is PERSISTED (`lastSelectedDeviceInstanceIds`) on every change,
 * so anything that re-derives it from the persisted list can narrow that list for good.
 */
class RunDeviceSelectionScopeTest {

  private val android = TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    instanceId = "emulator-5554",
    description = "Pixel emulator",
  )
  private val ios = TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
    instanceId = "SIM-UUID",
    description = "iPhone simulator",
  )

  private val all = listOf(android, ios)
  private val lastUsedBoth = listOf(android.instanceId, ios.instanceId)

  private fun resolve(
    currentSelection: Set<TrailblazeConnectedDeviceSummary>,
    eligible: Set<TrailblazeConnectedDeviceSummary>,
    userEdited: Boolean,
    seed: List<String> = lastUsedBoth,
  ) = selectionAfterEligibilityChange(
    currentSelection = currentSelection,
    availableDevices = all,
    seedDeviceInstanceIds = seed,
    trailPlatforms = null,
    eligibleDeviceIds = eligible.map { it.trailblazeDeviceId }.toSet(),
    userEditedSelection = userEdited,
  )

  @Test
  fun `before the user picks, a late probe adds the device it just made eligible`() {
    // The iOS probe is the slow one (a full `simctl listapps`), so the dialog opens with only
    // Android eligible and must still restore the full last-used pair once iOS lands.
    assertEquals(
      setOf(android, ios),
      resolve(currentSelection = setOf(android), eligible = setOf(android, ios), userEdited = false),
    )
  }

  @Test
  fun `after the user picks, a late probe never re-adds what they unchecked`() {
    // The regression this guards: re-seeding here would resurrect iOS from the persisted list
    // and silently undo the uncheck.
    assertEquals(
      setOf(android),
      resolve(currentSelection = setOf(android), eligible = setOf(android, ios), userEdited = true),
    )
  }

  @Test
  fun `after the user picks, a device that became ineligible is dropped`() {
    assertEquals(
      setOf(android),
      resolve(currentSelection = setOf(android, ios), eligible = setOf(android), userEdited = true),
    )
  }

  @Test
  fun `two distinct target instances with the same id are the same selection scope`() {
    // TrailblazeHostAppTarget doesn't override equals, so it compares by identity. Keying the
    // seed/guard/selection on the object would reset them whenever the settings layer handed back
    // a freshly-built instance for the same target — re-seeding from the persisted list and
    // overwriting whatever the user had checked. Keyed by id, that can't happen.
    assertEquals(
      SelectionScopeKey(target("acme"), trailPlatforms = null),
      SelectionScopeKey(target("acme"), trailPlatforms = null),
    )
  }

  @Test
  fun `the selection scope changes when the target app or trail platforms change`() {
    val base = SelectionScopeKey(target("acme"), trailPlatforms = null)
    assertNotEquals(base, SelectionScopeKey(target("widgets"), trailPlatforms = null))
    assertNotEquals(base, SelectionScopeKey(target("acme"), trailPlatforms = setOf(TrailblazeDevicePlatform.WEB)))
    assertNotEquals(base, SelectionScopeKey(targetApp = null, trailPlatforms = null))
  }

  private fun target(id: String) = object : TrailblazeHostAppTarget(id = id, displayName = id) {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String> = emptyList()
    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()
  }
}
