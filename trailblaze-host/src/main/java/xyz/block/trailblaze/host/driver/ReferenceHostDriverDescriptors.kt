package xyz.block.trailblaze.host.driver

import xyz.block.trailblaze.host.android.AndroidAccessibilityHostDriverDescriptor
import xyz.block.trailblaze.host.android.AndroidInstrumentationHostDriverDescriptor
import xyz.block.trailblaze.host.android.AndroidTestHostDriverDescriptor
import xyz.block.trailblaze.host.compose.ComposeHostDriverDescriptor
import xyz.block.trailblaze.host.ios.IosAxeHostDriverDescriptor
import xyz.block.trailblaze.host.ios.IosHostDriverDescriptor
import xyz.block.trailblaze.host.playwright.PlaywrightElectronHostDriverDescriptor
import xyz.block.trailblaze.host.playwright.PlaywrightNativeHostDriverDescriptor
import xyz.block.trailblaze.host.revyl.RevylHostDriverDescriptor

/**
 * Every driver the framework ships, as the open-source distribution plugs them in.
 *
 * Exists so one distribution's descriptor set is nameable from a test — the reference distribution
 * is expected to offer every driver in `TrailblazeDriverType`, and `ReferenceHostDriverDescriptorsTest`
 * holds it to that. A downstream distribution still writes its own set, and is entitled to leave a
 * driver out, which
 * is the point of registration being per-app: this is the full menu, not a default anyone inherits.
 */
object ReferenceHostDriverDescriptors {

  /**
   * A fresh set each call, because descriptors are not required to be stateless — a shared
   * singleton set would hand two app configs the same instances and make one app's live sessions
   * reachable from the other.
   */
  fun all(): Set<HostDriverDescriptor> = setOf(
    RevylHostDriverDescriptor(),
    ComposeHostDriverDescriptor(),
    PlaywrightNativeHostDriverDescriptor(),
    PlaywrightElectronHostDriverDescriptor(),
    IosHostDriverDescriptor(),
    IosAxeHostDriverDescriptor(),
    AndroidAccessibilityHostDriverDescriptor(),
    AndroidInstrumentationHostDriverDescriptor(),
    AndroidTestHostDriverDescriptor(),
  )
}
