package xyz.block.trailblaze.host.yaml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.block.trailblaze.host.yaml.DesktopYamlRunner.Companion.unresolvedTargetForDevice

/**
 * Whose app ids are the fallback's, when a trail's `config.target` named nothing this installation
 * carries.
 *
 * The fact is recorded once for the session, but it is not true of every device in it: a
 * multi-device member can declare its own `target:`, and that member's app ids come from there.
 * Treating the session-wide fact as true of all of them skips work on a device whose app is
 * exactly the app it runs.
 */
class DesktopYamlRunnerUnresolvedTargetPerDeviceTest {

  @Test
  fun `a session whose declared target resolved says nothing about any device`() {
    assertNull(unresolvedTargetForDevice(sessionUnresolvedTarget = null, deviceHasOwnTarget = false))
    assertNull(unresolvedTargetForDevice(sessionUnresolvedTarget = null, deviceHasOwnTarget = true))
  }

  @Test
  fun `a device that took the session fallback carries the unresolved target`() {
    assertEquals(
      "some-target-this-install-lacks",
      unresolvedTargetForDevice(
        sessionUnresolvedTarget = "some-target-this-install-lacks",
        deviceHasOwnTarget = false,
      ),
    )
  }

  @Test
  fun `a member that declared its own target is unaffected by the session's`() {
    // The regression this guards: one flag read for every binding meant a companion with a working
    // `target:` was passed over because the session-wide `config.target` did not resolve.
    assertNull(
      unresolvedTargetForDevice(
        sessionUnresolvedTarget = "some-target-this-install-lacks",
        deviceHasOwnTarget = true,
      ),
    )
  }
}
