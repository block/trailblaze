package xyz.block.trailblaze.host.yaml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which devices of a multi-device session get a capture bridge. Both wrong answers are quiet: a
 * device left unarmed just has no stream in the report, and a device armed against an app that
 * never dials in fails the whole session after the discovery timeout.
 */
class MultiDeviceCaptureSelectionTest {

  private val devices = listOf("seller", "buyer", "kiosk")

  @Test
  fun `no allowlist arms every device, in binding order`() {
    val selection =
      MultiDeviceCaptureSelection.select(devices, allowedNames = emptySet(), nameOf = { it })

    assertEquals(listOf("seller", "buyer", "kiosk"), selection.armed)
    assertTrue(selection.unknownNames.isEmpty())
  }

  @Test
  fun `an allowlist arms only the named devices and keeps binding order`() {
    val selection =
      MultiDeviceCaptureSelection.select(
        devices,
        allowedNames = setOf("kiosk", "seller"),
        nameOf = { it },
      )

    assertEquals(listOf("seller", "kiosk"), selection.armed)
    assertTrue(selection.unknownNames.isEmpty())
  }

  @Test
  fun `a name no device matches is reported instead of silently arming nothing`() {
    // The failure this guards: `TRAILBLAZE_NETWORK_CAPTURE_DEVICES=sellar` arms zero devices, and
    // a session with no capture at all otherwise looks exactly like one with nothing to capture.
    val selection =
      MultiDeviceCaptureSelection.select(
        devices,
        allowedNames = setOf("sellar", "buyer"),
        nameOf = { it },
      )

    assertEquals(listOf("buyer"), selection.armed)
    assertEquals(listOf("sellar"), selection.unknownNames)
  }

  @Test
  fun `an allowlist that matches nothing arms nothing and names every miss`() {
    val selection =
      MultiDeviceCaptureSelection.select(
        devices,
        allowedNames = setOf("register", "printer"),
        nameOf = { it },
      )

    assertTrue(selection.armed.isEmpty())
    assertEquals(listOf("printer", "register"), selection.unknownNames)
  }

  @Test
  fun `parseDeviceNames splits, trims, and drops blanks`() {
    assertEquals(setOf("seller", "buyer"), MultiDeviceCaptureSelection.parseDeviceNames("seller,buyer"))
    assertEquals(
      setOf("seller", "buyer"),
      MultiDeviceCaptureSelection.parseDeviceNames("  seller , buyer  "),
    )
    // A trailing comma or a stray empty entry must not become a name no device can ever match.
    assertEquals(setOf("seller"), MultiDeviceCaptureSelection.parseDeviceNames("seller,"))
    assertEquals(setOf("seller"), MultiDeviceCaptureSelection.parseDeviceNames("seller,,"))
  }

  @Test
  fun `an unset or blank value means no allowlist rather than an empty one`() {
    // Distinct outcomes: no allowlist arms every device, an empty allowlist would arm none.
    assertTrue(MultiDeviceCaptureSelection.parseDeviceNames(null).isEmpty())
    assertTrue(MultiDeviceCaptureSelection.parseDeviceNames("").isEmpty())
    assertTrue(MultiDeviceCaptureSelection.parseDeviceNames("   ").isEmpty())
    assertTrue(MultiDeviceCaptureSelection.parseDeviceNames(",").isEmpty())

    val selection =
      MultiDeviceCaptureSelection.select(
        devices,
        allowedNames = MultiDeviceCaptureSelection.parseDeviceNames("  "),
        nameOf = { it },
      )
    assertEquals(devices, selection.armed)
  }
}
