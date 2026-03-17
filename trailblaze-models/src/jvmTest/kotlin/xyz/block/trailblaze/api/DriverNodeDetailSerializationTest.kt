package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class DriverNodeDetailSerializationTest {

  private val json = Json { prettyPrint = false }

  // ======================================================================
  // IosMaestro serialization
  // ======================================================================

  @Test
  fun `IosMaestro round-trip serialization`() {
    val detail: DriverNodeDetail = DriverNodeDetail.IosMaestro(
      text = "Submit",
      resourceId = "btn_submit",
      accessibilityText = "Submit button",
      className = "UIButton",
      hintText = "Tap to submit",
      clickable = true,
      enabled = true,
      focused = false,
      checked = false,
      selected = true,
      focusable = false,
      scrollable = false,
      password = false,
      visible = true,
      ignoreBoundsFiltering = false,
    )

    val serialized = json.encodeToString(DriverNodeDetail.serializer(), detail)
    val deserialized = json.decodeFromString(DriverNodeDetail.serializer(), serialized)

    assertIs<DriverNodeDetail.IosMaestro>(deserialized)
    assertEquals("Submit", deserialized.text)
    assertEquals("btn_submit", deserialized.resourceId)
    assertEquals("Submit button", deserialized.accessibilityText)
    assertEquals("UIButton", deserialized.className)
    assertEquals("Tap to submit", deserialized.hintText)
    assertEquals(true, deserialized.clickable)
    assertEquals(true, deserialized.enabled)
    assertEquals(false, deserialized.focused)
    assertEquals(false, deserialized.checked)
    assertEquals(true, deserialized.selected)
    assertEquals(false, deserialized.focusable)
    assertEquals(false, deserialized.scrollable)
    assertEquals(false, deserialized.password)
    assertEquals(true, deserialized.visible)
    assertEquals(false, deserialized.ignoreBoundsFiltering)
  }

  @Test
  fun `IosMaestro serializes with correct discriminator`() {
    val detail: DriverNodeDetail = DriverNodeDetail.IosMaestro(text = "World")
    val serialized = json.encodeToString(DriverNodeDetail.serializer(), detail)
    val jsonObj = json.decodeFromString(JsonObject.serializer(), serialized)
    assertEquals("iosMaestro", jsonObj["type"]?.jsonPrimitive?.content)
  }

  @Test
  fun `IosMaestro with default values round-trips`() {
    val detail: DriverNodeDetail = DriverNodeDetail.IosMaestro()
    val serialized = json.encodeToString(DriverNodeDetail.serializer(), detail)
    val deserialized = json.decodeFromString(DriverNodeDetail.serializer(), serialized)

    assertIs<DriverNodeDetail.IosMaestro>(deserialized)
    assertEquals(true, deserialized.enabled)
    assertEquals(false, deserialized.clickable)
    assertEquals(true, deserialized.visible)
    assertEquals(false, deserialized.ignoreBoundsFiltering)
  }

  // ======================================================================
  // TrailblazeNode with new variants serializes correctly
  // ======================================================================

  @Test
  fun `TrailblazeNode with IosMaestro detail round-trips`() {
    val node = TrailblazeNode(
      nodeId = 1,
      bounds = TrailblazeNode.Bounds(left = 10, top = 20, right = 300, bottom = 400),
      children = emptyList(),
      driverDetail = DriverNodeDetail.IosMaestro(
        text = "Hello",
        resourceId = "greeting",
        visible = false,
      ),
    )

    val serialized = json.encodeToString(TrailblazeNode.serializer(), node)
    val deserialized = json.decodeFromString(TrailblazeNode.serializer(), serialized)

    assertIs<DriverNodeDetail.IosMaestro>(deserialized.driverDetail)
    val detail = deserialized.driverDetail as DriverNodeDetail.IosMaestro
    assertEquals("Hello", detail.text)
    assertEquals("greeting", detail.resourceId)
    assertEquals(false, detail.visible)
  }

  // ======================================================================
  // DriverNodeMatch serialization
  // ======================================================================

  @Test
  fun `IosMaestro match round-trips`() {
    val match: DriverNodeMatch = DriverNodeMatch.IosMaestro(
      textRegex = "Submit",
      resourceIdRegex = "btn_submit",
      selected = true,
    )

    val serialized = json.encodeToString(DriverNodeMatch.serializer(), match)
    val deserialized = json.decodeFromString(DriverNodeMatch.serializer(), serialized)

    assertIs<DriverNodeMatch.IosMaestro>(deserialized)
    assertEquals("Submit", deserialized.textRegex)
    assertEquals("btn_submit", deserialized.resourceIdRegex)
    assertEquals(true, deserialized.selected)
  }

  // ======================================================================
  // TrailblazeNodeSelector with new variants
  // ======================================================================

  @Test
  fun `TrailblazeNodeSelector with iosMaestro field round-trips`() {
    val selector = TrailblazeNodeSelector(
      iosMaestro = DriverNodeMatch.IosMaestro(resourceIdRegex = "test_id"),
    )

    val serialized = json.encodeToString(TrailblazeNodeSelector.serializer(), selector)
    val deserialized = json.decodeFromString(TrailblazeNodeSelector.serializer(), serialized)

    assertIs<DriverNodeMatch.IosMaestro>(deserialized.driverMatch)
    assertEquals("test_id", (deserialized.driverMatch as DriverNodeMatch.IosMaestro).resourceIdRegex)
  }

  @Test
  fun `withMatch dispatches IosMaestro correctly`() {
    val match = DriverNodeMatch.IosMaestro(textRegex = "Test")
    val selector = TrailblazeNodeSelector.withMatch(match)

    assertEquals(match, selector.iosMaestro)
    assertEquals(null, selector.androidAccessibility)
    assertEquals(null, selector.androidMaestro)
    assertEquals(null, selector.web)
    assertEquals(null, selector.compose)
  }
}
