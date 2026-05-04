package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeElementSelector

class ConvertElementSelectorToNodeSelectorTest {

  @Test
  fun `maps textRegex to AndroidAccessibility textRegex`() {
    val selector = TrailblazeElementSelector(textRegex = "Submit")
    val result = MaestroCommandConverter.convertElementSelectorToNodeSelector(selector)

    val match = assertIs<DriverNodeMatch.AndroidAccessibility>(result.driverMatch)
    assertEquals("Submit", match.textRegex)
  }

  @Test
  fun `maps idRegex to resourceIdRegex`() {
    val selector = TrailblazeElementSelector(idRegex = "com\\.example:id/btn_ok")
    val result = MaestroCommandConverter.convertElementSelectorToNodeSelector(selector)

    val match = assertIs<DriverNodeMatch.AndroidAccessibility>(result.driverMatch)
    assertEquals("com\\.example:id/btn_ok", match.resourceIdRegex)
  }

  @Test
  fun `maps boolean state flags`() {
    val selector = TrailblazeElementSelector(
      textRegex = "Item",
      enabled = true,
      selected = false,
      checked = true,
      focused = false,
    )
    val result = MaestroCommandConverter.convertElementSelectorToNodeSelector(selector)

    val match = assertIs<DriverNodeMatch.AndroidAccessibility>(result.driverMatch)
    assertEquals(true, match.isEnabled)
    assertEquals(false, match.isSelected)
    assertEquals(true, match.isChecked)
    assertEquals(false, match.isFocused)
  }

  @Test
  fun `maps index from string to int`() {
    val selector = TrailblazeElementSelector(textRegex = "Item", index = "2")
    val result = MaestroCommandConverter.convertElementSelectorToNodeSelector(selector)

    assertEquals(2, result.index)
  }

  @Test
  fun `maps float index via toDoubleOrNull toInt`() {
    val selector = TrailblazeElementSelector(textRegex = "Item", index = "3.0")
    val result = MaestroCommandConverter.convertElementSelectorToNodeSelector(selector)

    assertEquals(3, result.index)
  }

  @Test
  fun `invalid index string maps to null`() {
    val selector = TrailblazeElementSelector(textRegex = "Item", index = "not-a-number")
    val result = MaestroCommandConverter.convertElementSelectorToNodeSelector(selector)

    assertNull(result.index)
  }

  @Test
  fun `maps spatial relationships recursively`() {
    val selector = TrailblazeElementSelector(
      textRegex = "Target",
      below = TrailblazeElementSelector(textRegex = "Above anchor"),
      above = TrailblazeElementSelector(textRegex = "Below anchor"),
      leftOf = TrailblazeElementSelector(textRegex = "Right anchor"),
      rightOf = TrailblazeElementSelector(textRegex = "Left anchor"),
    )
    val result = MaestroCommandConverter.convertElementSelectorToNodeSelector(selector)

    assertNotNull(result.below)
    val belowMatch = assertIs<DriverNodeMatch.AndroidAccessibility>(result.below!!.driverMatch)
    assertEquals("Above anchor", belowMatch.textRegex)

    assertNotNull(result.above)
    assertNotNull(result.leftOf)
    assertNotNull(result.rightOf)
  }

  @Test
  fun `maps hierarchy - childOf`() {
    val selector = TrailblazeElementSelector(
      textRegex = "OK",
      childOf = TrailblazeElementSelector(idRegex = "com\\.example:id/dialog"),
    )
    val result = MaestroCommandConverter.convertElementSelectorToNodeSelector(selector)

    assertNotNull(result.childOf)
    val parentMatch = assertIs<DriverNodeMatch.AndroidAccessibility>(result.childOf!!.driverMatch)
    assertEquals("com\\.example:id/dialog", parentMatch.resourceIdRegex)
  }

  @Test
  fun `maps hierarchy - containsChild`() {
    val selector = TrailblazeElementSelector(
      idRegex = "com\\.example:id/card",
      containsChild = TrailblazeElementSelector(textRegex = "Title"),
    )
    val result = MaestroCommandConverter.convertElementSelectorToNodeSelector(selector)

    assertNotNull(result.containsChild)
    val childMatch =
      assertIs<DriverNodeMatch.AndroidAccessibility>(result.containsChild!!.driverMatch)
    assertEquals("Title", childMatch.textRegex)
  }

  @Test
  fun `maps hierarchy - containsDescendants`() {
    val selector = TrailblazeElementSelector(
      idRegex = "com\\.example:id/card",
      containsDescendants = listOf(
        TrailblazeElementSelector(textRegex = "Title"),
        TrailblazeElementSelector(textRegex = "Subtitle"),
      ),
    )
    val result = MaestroCommandConverter.convertElementSelectorToNodeSelector(selector)

    assertNotNull(result.containsDescendants)
    assertEquals(2, result.containsDescendants!!.size)
  }

  @Test
  fun `empty selector produces null driverMatch`() {
    val selector = TrailblazeElementSelector()
    val result = MaestroCommandConverter.convertElementSelectorToNodeSelector(selector)

    assertNull(result.driverMatch)
  }

  @Test
  fun `size and traits and css are dropped`() {
    // These fields have no equivalent in DriverNodeMatch.AndroidAccessibility
    val selector = TrailblazeElementSelector(
      textRegex = "Item",
      css = "div.class",
    )
    val result = MaestroCommandConverter.convertElementSelectorToNodeSelector(selector)

    // textRegex should still map, css is silently dropped
    val match = assertIs<DriverNodeMatch.AndroidAccessibility>(result.driverMatch)
    assertEquals("Item", match.textRegex)
  }
}
