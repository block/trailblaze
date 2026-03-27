package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

class TrailblazeElementSelectorToNodeSelectorTest {

  @Test
  fun `iOS - converts text and id`() {
    val selector = TrailblazeElementSelector(
      textRegex = "Submit",
      idRegex = "submit_btn",
    )
    val result = selector.toTrailblazeNodeSelector(TrailblazeDevicePlatform.IOS)
    val match = result.iosMaestro
    assertNotNull(match)
    assertEquals("Submit", match.textRegex)
    assertEquals("submit_btn", match.resourceIdRegex)
  }

  @Test
  fun `iOS - converts state booleans`() {
    val selector = TrailblazeElementSelector(
      textRegex = "Tab",
      focused = true,
      selected = false,
    )
    val result = selector.toTrailblazeNodeSelector(TrailblazeDevicePlatform.IOS)
    val match = result.iosMaestro
    assertNotNull(match)
    assertEquals(true, match.focused)
    assertEquals(false, match.selected)
  }

  @Test
  fun `iOS - drops enabled and checked`() {
    val selector = TrailblazeElementSelector(
      textRegex = "Button",
      enabled = true,
      checked = false,
    )
    val result = selector.toTrailblazeNodeSelector(TrailblazeDevicePlatform.IOS)
    val match = result.iosMaestro
    assertNotNull(match, "IosMaestro match should be created from textRegex")
    // IosMaestro has no enabled/checked fields — they are dropped
  }

  @Test
  fun `Android - converts text, id, and all state booleans`() {
    val selector = TrailblazeElementSelector(
      textRegex = "OK",
      idRegex = "confirm_btn",
      enabled = true,
      focused = false,
      selected = true,
      checked = false,
    )
    val result = selector.toTrailblazeNodeSelector(TrailblazeDevicePlatform.ANDROID)
    val match = result.androidMaestro
    assertNotNull(match)
    assertEquals("OK", match.textRegex)
    assertEquals("confirm_btn", match.resourceIdRegex)
    assertEquals(true, match.enabled)
    assertEquals(false, match.focused)
    assertEquals(true, match.selected)
    assertEquals(false, match.checked)
  }

  @Test
  fun `converts index from string to int`() {
    val selector = TrailblazeElementSelector(index = "3")
    val result = selector.toTrailblazeNodeSelector(TrailblazeDevicePlatform.IOS)
    assertEquals(3, result.index)
  }

  @Test
  fun `handles float index string`() {
    val selector = TrailblazeElementSelector(index = "2.0")
    val result = selector.toTrailblazeNodeSelector(TrailblazeDevicePlatform.ANDROID)
    assertEquals(2, result.index)
  }

  @Test
  fun `null index stays null`() {
    val selector = TrailblazeElementSelector(textRegex = "Hello")
    val result = selector.toTrailblazeNodeSelector(TrailblazeDevicePlatform.IOS)
    assertNull(result.index)
  }

  @Test
  fun `recursive spatial relationships`() {
    val belowSelector = TrailblazeElementSelector(textRegex = "Header")
    val selector = TrailblazeElementSelector(
      textRegex = "Content",
      below = belowSelector,
    )
    val result = selector.toTrailblazeNodeSelector(TrailblazeDevicePlatform.IOS)
    assertNotNull(result.below)
    assertEquals("Header", result.below?.iosMaestro?.textRegex)
  }

  @Test
  fun `recursive childOf`() {
    val parentSelector = TrailblazeElementSelector(idRegex = "dialog")
    val selector = TrailblazeElementSelector(
      textRegex = "OK",
      childOf = parentSelector,
    )
    val result = selector.toTrailblazeNodeSelector(TrailblazeDevicePlatform.ANDROID)
    assertNotNull(result.childOf)
    assertEquals("dialog", result.childOf?.androidMaestro?.resourceIdRegex)
  }

  @Test
  fun `containsDescendants converts recursively`() {
    val selector = TrailblazeElementSelector(
      idRegex = "list",
      containsDescendants = listOf(
        TrailblazeElementSelector(textRegex = "Item 1"),
        TrailblazeElementSelector(textRegex = "Item 2"),
      ),
    )
    val result = selector.toTrailblazeNodeSelector(TrailblazeDevicePlatform.IOS)
    assertEquals(2, result.containsDescendants?.size)
    assertEquals("Item 1", result.containsDescendants?.get(0)?.iosMaestro?.textRegex)
    assertEquals("Item 2", result.containsDescendants?.get(1)?.iosMaestro?.textRegex)
  }

  @Test
  fun `empty selector produces null driver match`() {
    val selector = TrailblazeElementSelector()
    val result = selector.toTrailblazeNodeSelector(TrailblazeDevicePlatform.IOS)
    assertNull(result.iosMaestro)
    assertNull(result.androidMaestro)
  }

  @Test
  fun `web platform produces null driver matches`() {
    val selector = TrailblazeElementSelector(textRegex = "Hello")
    val result = selector.toTrailblazeNodeSelector(TrailblazeDevicePlatform.WEB)
    assertNull(result.iosMaestro)
    assertNull(result.androidMaestro)
  }

  @Test
  fun `roundtrip iOS - surviving fields are preserved`() {
    val original = TrailblazeNodeSelector(
      iosMaestro = DriverNodeMatch.IosMaestro(
        textRegex = "Submit",
        resourceIdRegex = "submit_btn",
        focused = true,
      ),
    )
    val legacy = original.toTrailblazeElementSelector()
    val roundTripped = legacy.toTrailblazeNodeSelector(TrailblazeDevicePlatform.IOS)

    assertEquals("Submit", roundTripped.iosMaestro?.textRegex)
    assertEquals("submit_btn", roundTripped.iosMaestro?.resourceIdRegex)
    assertEquals(true, roundTripped.iosMaestro?.focused)
  }

  @Test
  fun `roundtrip Android - surviving fields are preserved`() {
    val original = TrailblazeNodeSelector(
      androidMaestro = DriverNodeMatch.AndroidMaestro(
        textRegex = "OK",
        resourceIdRegex = "btn_ok",
        enabled = true,
        checked = false,
      ),
    )
    val legacy = original.toTrailblazeElementSelector()
    val roundTripped = legacy.toTrailblazeNodeSelector(TrailblazeDevicePlatform.ANDROID)

    assertEquals("OK", roundTripped.androidMaestro?.textRegex)
    assertEquals("btn_ok", roundTripped.androidMaestro?.resourceIdRegex)
    assertEquals(true, roundTripped.androidMaestro?.enabled)
    assertEquals(false, roundTripped.androidMaestro?.checked)
  }
}
