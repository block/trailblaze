package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A boolean constraint in a selector is tri-state: absent, required-true, or required-false.
 * `description()` is what error messages, logs and the inspector print, so it has to keep those
 * three apart. Rendering `isPassword = false` as "password" tells a reader the opposite of what the
 * selector says, which is worse than printing nothing at all — it sends debugging the wrong way.
 */
class DriverNodeMatchDescriptionTest {

  @Test
  fun `androidView renders a required-false constraint as its negation`() {
    assertEquals("not password", DriverNodeMatch.AndroidView(isPassword = false).description())
    assertEquals("password", DriverNodeMatch.AndroidView(isPassword = true).description())
    assertEquals("", DriverNodeMatch.AndroidView().description())
  }

  @Test
  fun `compose renders required-false constraints as their negations`() {
    assertEquals(
      "not heading, not dialog, not popup",
      DriverNodeMatch.Compose(isHeading = false, isDialog = false, isPopup = false).description(),
    )
    assertEquals(
      "heading, dialog, popup",
      DriverNodeMatch.Compose(isHeading = true, isDialog = true, isPopup = true).description(),
    )
  }

  @Test
  fun `androidAccessibility renders required-false constraints as their negations`() {
    assertEquals(
      "not password, not heading",
      DriverNodeMatch.AndroidAccessibility(isPassword = false, isHeading = false).description(),
    )
  }
}
