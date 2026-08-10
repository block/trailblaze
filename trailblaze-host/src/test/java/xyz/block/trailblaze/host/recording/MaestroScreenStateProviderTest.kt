package xyz.block.trailblaze.host.recording

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import maestro.DeviceInfo
import maestro.TreeNode
import maestro.device.Platform
import org.junit.Test
import xyz.block.trailblaze.viewmatcher.matching.ViewHierarchyOnlyDriver
import kotlin.test.assertEquals

/**
 * Proves the record/tree path enriches with AXe while the 200ms frame loop does not — the frame
 * loop's tree is discarded, so shelling `axe` there was pure cost (the bug Major #2 fixed).
 */
class MaestroScreenStateProviderTest {

  private fun iosProvider(onEnrich: () -> Unit) = MaestroScreenStateProvider(
    driver = ViewHierarchyOnlyDriver(
      rootTreeNode = TreeNode(),
      deviceInfo = DeviceInfo(
        platform = Platform.IOS,
        widthPixels = 400,
        heightPixels = 800,
        widthGrid = 400,
        heightGrid = 800,
      ),
    ),
    driverMutex = Mutex(),
    iosUdid = "SIM-UDID",
    axeEnricher = { tree, _, _, _ ->
      onEnrich()
      tree
    },
  )

  @Test
  fun `frame-loop getScreenState never shells axe`() = runBlocking {
    var axeCalls = 0
    val provider = iosProvider { axeCalls++ }
    provider.getScreenState(includeScreenshot = false, includeTree = true)
    assertEquals(0, axeCalls)
  }

  @Test
  fun `record-path getEnrichedScreenState enriches with axe`() = runBlocking {
    var axeCalls = 0
    val provider = iosProvider { axeCalls++ }
    provider.getEnrichedScreenState(includeScreenshot = false)
    assertEquals(1, axeCalls)
  }
}
