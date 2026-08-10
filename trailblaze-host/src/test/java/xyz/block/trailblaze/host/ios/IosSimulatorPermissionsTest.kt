package xyz.block.trailblaze.host.ios

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.host.ios.IosSimulatorPermissions.PrivacyCommand

/**
 * Locks the Maestro-parity contract of [IosSimulatorPermissions]: which `simctl privacy`
 * commands a launch's permissions map lowers to (and in what order), when notification-alert
 * auto-dismissal is armed, and which button the alert finder picks.
 */
class IosSimulatorPermissionsTest {

  // --- plan(): simctl privacy lowering ---

  @Test
  fun `null permissions default to Maestro's all=allow`() {
    val plan = IosSimulatorPermissions.plan(null)
    assertEquals(listOf(PrivacyCommand("grant", "all")), plan.privacyCommands)
    assertEquals("allow", plan.notificationsValue)
    assertEquals(
      listOf("camera", "faceid", "homekit", "speech", "userTracking"),
      plan.skipped,
      "simctl's `all` can't reach the applesimutils-only services — report them, don't stay silent",
    )
  }

  @Test
  fun `explicit entries run after the all command so they override it`() {
    val plan = IosSimulatorPermissions.plan(mapOf("photos" to "deny", "all" to "allow"))
    assertEquals(
      listOf(PrivacyCommand("grant", "all"), PrivacyCommand("revoke", "photos")),
      plan.privacyCommands,
    )
    assertEquals("allow", plan.notificationsValue)
  }

  @Test
  fun `allow deny and unset map to grant revoke and reset`() {
    val plan = IosSimulatorPermissions.plan(
      mapOf("calendar" to "allow", "microphone" to "deny", "contacts" to "unset"),
    )
    assertEquals(
      listOf(
        PrivacyCommand("grant", "calendar"),
        PrivacyCommand("revoke", "microphone"),
        PrivacyCommand("reset", "contacts"),
      ),
      plan.privacyCommands,
    )
    assertNull(plan.notificationsValue, "no notifications entry and no all entry — leave alerts alone")
  }

  @Test
  fun `medialibrary maps to simctl's media-library service name`() {
    val plan = IosSimulatorPermissions.plan(mapOf("medialibrary" to "allow"))
    assertEquals(listOf(PrivacyCommand("grant", "media-library")), plan.privacyCommands)
  }

  @Test
  fun `location keeps Maestro's always-inuse-never-unset vocabulary`() {
    assertEquals(
      listOf(PrivacyCommand("grant", "location-always")),
      IosSimulatorPermissions.plan(mapOf("location" to "always")).privacyCommands,
    )
    assertEquals(
      listOf(PrivacyCommand("grant", "location")),
      IosSimulatorPermissions.plan(mapOf("location" to "inuse")).privacyCommands,
    )
    assertEquals(
      listOf(PrivacyCommand("revoke", "location-always")),
      IosSimulatorPermissions.plan(mapOf("location" to "never")).privacyCommands,
    )
    assertEquals(
      listOf(PrivacyCommand("reset", "location-always")),
      IosSimulatorPermissions.plan(mapOf("location" to "unset")).privacyCommands,
    )
    assertFailsWith<IllegalArgumentException> {
      IosSimulatorPermissions.plan(mapOf("location" to "allow"))
    }
  }

  @Test
  fun `notifications arm auto-dismiss instead of a privacy command`() {
    val plan = IosSimulatorPermissions.plan(mapOf("notifications" to "allow"))
    assertTrue(plan.privacyCommands.isEmpty())
    assertEquals("allow", plan.notificationsValue)
  }

  @Test
  fun `explicit notifications override the all expansion`() {
    assertEquals(
      "deny",
      IosSimulatorPermissions.plan(mapOf("all" to "allow", "notifications" to "deny")).notificationsValue,
    )
    assertNull(
      IosSimulatorPermissions.plan(mapOf("all" to "allow", "notifications" to "unset")).notificationsValue,
    )
  }

  @Test
  fun `applesimutils-only permissions are reported skipped not silently dropped`() {
    val plan = IosSimulatorPermissions.plan(mapOf("camera" to "allow", "faceid" to "allow"))
    assertTrue(plan.privacyCommands.isEmpty())
    assertEquals(listOf("camera", "faceid"), plan.skipped)
  }

  @Test
  fun `all reports the applesimutils-only services it cannot reach as skipped`() {
    val plan = IosSimulatorPermissions.plan(mapOf("all" to "allow"))
    assertEquals(listOf("camera", "faceid", "homekit", "speech", "userTracking"), plan.skipped)
  }

  @Test
  fun `explicit applesimutils-only entries are not double-reported under all`() {
    val plan = IosSimulatorPermissions.plan(mapOf("all" to "allow", "camera" to "allow"))
    assertEquals(listOf("faceid", "homekit", "speech", "userTracking", "camera"), plan.skipped)
  }

  @Test
  fun `an empty map grants nothing - explicit maps replace the default entirely`() {
    val plan = IosSimulatorPermissions.plan(emptyMap())
    assertTrue(plan.privacyCommands.isEmpty())
    assertNull(plan.notificationsValue)
  }

  @Test
  fun `an invalid value throws like Maestro does`() {
    assertFailsWith<IllegalArgumentException> {
      IosSimulatorPermissions.plan(mapOf("all" to "yes"))
    }
  }

  @Test
  fun `an invalid notifications value throws instead of silently skipping`() {
    // A typo'd value must fail loudly like every other service — silently lowering it to
    // "leave alerts alone" would resurface as an unexplained permission dialog mid-trail.
    assertFailsWith<IllegalArgumentException> {
      IosSimulatorPermissions.plan(mapOf("notifications" to "yes"))
    }
  }

  // --- findNotificationAlertTap(): springboard alert auto-dismiss ---

  private fun node(
    label: String? = null,
    type: String? = null,
    bounds: TrailblazeNode.Bounds? = null,
    pid: Int? = null,
    children: List<TrailblazeNode> = emptyList(),
  ) = TrailblazeNode(
    driverDetail = DriverNodeDetail.IosAxe(label = label, type = type, pid = pid),
    bounds = bounds,
    children = children,
  )

  private fun button(label: String, left: Int, pid: Int? = null) = node(
    label = label,
    type = "Button",
    bounds = TrailblazeNode.Bounds(left = left, top = 500, right = left + 100, bottom = 550),
    pid = pid,
  )

  private fun notificationAlert(vararg buttons: TrailblazeNode) = node(
    children = listOf(
      node(label = "“Example App” Would Like to Send You Notifications"),
      *buttons,
    ),
  )

  @Test
  fun `allow taps the button labeled Allow wherever it sits`() {
    // Allow placed FIRST so a pass can't come from the second-button fallback.
    val tap = IosSimulatorPermissions.findNotificationAlertTap(
      notificationAlert(button("Allow", left = 0), button("Don't Allow", left = 200)),
      "allow",
    )
    assertEquals(50 to 525, assertNotNull(tap))
  }

  @Test
  fun `deny taps the button labeled Don't Allow wherever it sits`() {
    // Don't Allow placed SECOND so a pass can't come from the first-button fallback.
    val tap = IosSimulatorPermissions.findNotificationAlertTap(
      notificationAlert(button("Allow", left = 0), button("Don't Allow", left = 200)),
      "deny",
    )
    assertEquals(250 to 525, assertNotNull(tap))
  }

  @Test
  fun `deny matches the curly-apostrophe Don’t Allow label iOS actually renders`() {
    // iOS renders the deny button with U+2019 (right single quotation mark), not an ASCII
    // apostrophe. Placed SECOND so a pass can't come from the first-button fallback.
    val tap = IosSimulatorPermissions.findNotificationAlertTap(
      notificationAlert(button("Allow", left = 0), button("Don’t Allow", left = 200)),
      "deny",
    )
    assertEquals(250 to 525, assertNotNull(tap))
  }

  @Test
  fun `allow falls back to the second button when no label matches`() {
    val tap = IosSimulatorPermissions.findNotificationAlertTap(
      notificationAlert(button("Nein", left = 0), button("Ja", left = 200)),
      "allow",
    )
    assertEquals(250 to 525, assertNotNull(tap), "Allow is conventionally the second button")
  }

  @Test
  fun `no tap when the tree carries no notification alert`() {
    val appScreen = node(children = listOf(node(label = "Sign In"), button("Continue", left = 0)))
    assertNull(IosSimulatorPermissions.findNotificationAlertTap(appScreen, "allow"))
  }

  @Test
  fun `no tap for values other than allow or deny`() {
    val alert = notificationAlert(button("Allow", left = 200))
    assertNull(IosSimulatorPermissions.findNotificationAlertTap(alert, "unset"))
  }

  // --- alert-subtree scoping: app-owned buttons must never win over the alert's own ---

  @Test
  fun `allow ignores app-owned buttons outside the alert subtree`() {
    // The app's own "Continue" comes FIRST in DFS order and matches the allow label preference;
    // an unscoped search would tap it and the alert overlay would swallow the tap.
    val tree = node(
      children = listOf(
        node(
          type = "Application",
          children = listOf(button("Continue", left = 0), button("Allow", left = 100)),
        ),
        node(
          type = "Application",
          children = listOf(notificationAlert(button("Don't Allow", left = 200), button("Allow", left = 400))),
        ),
      ),
    )
    val tap = IosSimulatorPermissions.findNotificationAlertTap(tree, "allow")
    assertEquals(450 to 525, assertNotNull(tap), "the alert's own Allow must win, not the app's buttons")
  }

  @Test
  fun `deny ignores app-owned buttons outside the alert subtree`() {
    val tree = node(
      children = listOf(
        node(type = "Application", children = listOf(button("Cancel", left = 0))),
        node(
          type = "Application",
          children = listOf(notificationAlert(button("Don't Allow", left = 200), button("Allow", left = 400))),
        ),
      ),
    )
    val tap = IosSimulatorPermissions.findNotificationAlertTap(tree, "deny")
    assertEquals(250 to 525, assertNotNull(tap), "the alert's own Don't Allow must win, not the app's Cancel")
  }

  @Test
  fun `no tap when the only buttons belong to the app not the alert`() {
    // The alert label's application subtree carries no buttons; the app's Continue elsewhere in
    // the tree must not be a fallback — that tap would land under the alert overlay.
    val tree = node(
      children = listOf(
        node(type = "Application", children = listOf(button("Continue", left = 0))),
        node(
          type = "Application",
          children = listOf(node(label = "“Example App” Would Like to Send You Notifications")),
        ),
      ),
    )
    assertNull(IosSimulatorPermissions.findNotificationAlertTap(tree, "allow"))
  }

  @Test
  fun `a button whose pid differs from the alert label's is never a candidate`() {
    // Flat tree with no application boundary: pid is the only signal separating the app's
    // button from the alert's content.
    val tree = node(
      children = listOf(
        button("Continue", left = 0, pid = 4242),
        node(label = "“Example App” Would Like to Send You Notifications", pid = 53),
      ),
    )
    assertNull(IosSimulatorPermissions.findNotificationAlertTap(tree, "allow"))
  }
}
