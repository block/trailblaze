package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the [DriverNodeDetail.isInteractive] predicate across every variant. Used by
 * the inspector overlay today and the tapOnPoint → ref hit-test in the future, so each
 * driver's interpretation of "interactive" must line up with native conventions.
 */
class DriverNodeDetailIsInteractiveTest {

  // -- Android Accessibility --

  @Test
  fun `AndroidAccessibility clickable is interactive`() {
    val d = DriverNodeDetail.AndroidAccessibility(isClickable = true)
    assertTrue(d.isInteractive)
  }

  @Test
  fun `AndroidAccessibility scrollable is interactive`() {
    val d = DriverNodeDetail.AndroidAccessibility(isScrollable = true)
    assertTrue(d.isInteractive)
  }

  @Test
  fun `AndroidAccessibility plain TextView is not interactive`() {
    val d = DriverNodeDetail.AndroidAccessibility(
      className = "android.widget.TextView",
      text = "Hello",
    )
    assertFalse(d.isInteractive)
  }

  // -- Android Maestro --

  @Test
  fun `AndroidMaestro clickable is interactive`() {
    val d = DriverNodeDetail.AndroidMaestro(clickable = true)
    assertTrue(d.isInteractive)
  }

  @Test
  fun `AndroidMaestro plain node is not interactive`() {
    val d = DriverNodeDetail.AndroidMaestro(text = "Label")
    assertFalse(d.isInteractive)
  }

  // -- iOS Maestro --

  @Test
  fun `IosMaestro clickable is interactive`() {
    val d = DriverNodeDetail.IosMaestro(clickable = true)
    assertTrue(d.isInteractive)
  }

  @Test
  fun `IosMaestro scrollable is interactive`() {
    val d = DriverNodeDetail.IosMaestro(scrollable = true)
    assertTrue(d.isInteractive)
  }

  @Test
  fun `IosMaestro plain node is not interactive`() {
    val d = DriverNodeDetail.IosMaestro(text = "Hello")
    assertFalse(d.isInteractive)
  }

  // -- iOS AXe --

  @Test
  fun `IosAxe AXButton is interactive`() {
    val d = DriverNodeDetail.IosAxe(role = "AXButton", label = "Submit")
    assertTrue(d.isInteractive)
  }

  @Test
  fun `IosAxe AXStaticText is not interactive`() {
    val d = DriverNodeDetail.IosAxe(role = "AXStaticText", label = "Hello")
    assertFalse(d.isInteractive)
  }

  @Test
  fun `IosAxe customActions presence makes any node interactive`() {
    // Contacts-row example — AXStaticText with a Copy-name custom action is tappable
    // via the custom action API even though the role itself isn't in the whitelist.
    val d = DriverNodeDetail.IosAxe(
      role = "AXStaticText",
      label = "mobile",
      value = "(408) 555-5270",
      customActions = listOf("Copy"),
    )
    assertTrue(d.isInteractive)
  }

  @Test
  fun `IosAxe enabled=true alone does NOT make a node interactive`() {
    // Regression guard against the previous behavior where `enabled` was the signal —
    // AXe reports every node as enabled=true, which painted overlays over everything.
    val d = DriverNodeDetail.IosAxe(role = "AXStaticText", enabled = true)
    assertFalse(d.isInteractive)
  }

  @Test
  fun `IosAxe interactive-role whitelist covers the common controls`() {
    val expected = setOf(
      "AXButton", "AXLink", "AXTextField", "AXSecureTextField", "AXSearchField",
      "AXSwitch", "AXSlider", "AXCheckBox", "AXMenuItem", "AXPopUpButton",
      "AXRadioButton", "AXSegmentedControl", "AXStepper", "AXComboBox",
      "AXToolbarButton", "AXBackButton", "AXPickerWheel", "AXTab", "AXCell",
    )
    for (role in expected) {
      val d = DriverNodeDetail.IosAxe(role = role)
      assertTrue(d.isInteractive, "$role should be interactive")
    }
  }

  // -- Compose --

  @Test
  fun `Compose hasClickAction is interactive`() {
    val d = DriverNodeDetail.Compose(hasClickAction = true)
    assertTrue(d.isInteractive)
  }

  @Test
  fun `Compose hasScrollAction is interactive`() {
    val d = DriverNodeDetail.Compose(hasScrollAction = true)
    assertTrue(d.isInteractive)
  }

  @Test
  fun `Compose plain Text is not interactive`() {
    val d = DriverNodeDetail.Compose(text = "Hi")
    assertFalse(d.isInteractive)
  }

  // -- Web --

  @Test
  fun `Web isInteractive field is the source of truth`() {
    assertTrue(DriverNodeDetail.Web(isInteractive = true).isInteractive)
    assertFalse(DriverNodeDetail.Web(isInteractive = false).isInteractive)
  }
}
