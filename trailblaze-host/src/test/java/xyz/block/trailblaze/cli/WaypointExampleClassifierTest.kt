package xyz.block.trailblaze.cli

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Behavior of the pure device-classifier helpers that key a waypoint's example SET:
 * the filename infix derivation and the filename-safety guard. These decide whether an
 * example pair lands at `<base>.example.json` (unlabeled default) or
 * `<base>.example.<classifier>.json` (per form factor), so they're the contract the capture +
 * validate sides agree on.
 */
class WaypointExampleClassifierTest {

  @Test
  fun `null or blank classifier yields the unlabeled example infix`() {
    assertEquals("example", WaypointCaptureExampleCommand.exampleInfix(null))
    assertEquals("example", WaypointCaptureExampleCommand.exampleInfix(""))
    assertEquals("example", WaypointCaptureExampleCommand.exampleInfix("   "))
  }

  @Test
  fun `a classifier produces a dotted example infix`() {
    assertEquals("example.android-phone", WaypointCaptureExampleCommand.exampleInfix("android-phone"))
    assertEquals("example.ios-ipad", WaypointCaptureExampleCommand.exampleInfix("ios-ipad"))
    assertEquals("example.android-foldable", WaypointCaptureExampleCommand.exampleInfix("android-foldable"))
  }

  @Test
  fun `valid classifiers are alphanumeric plus dash and underscore`() {
    assertTrue(WaypointCaptureExampleCommand.isValidClassifier("android-phone"))
    assertTrue(WaypointCaptureExampleCommand.isValidClassifier("ios_iphone"))
    assertTrue(WaypointCaptureExampleCommand.isValidClassifier("androidTablet37"))
  }

  @Test
  fun `classifiers that would break the example filename are rejected`() {
    // empty — nothing to key on
    assertFalse(WaypointCaptureExampleCommand.isValidClassifier(""))
    // a dot would collide with the `.example.<classifier>.json` segment parsing
    assertFalse(WaypointCaptureExampleCommand.isValidClassifier("android.phone"))
    // a path separator would escape the waypoint directory
    assertFalse(WaypointCaptureExampleCommand.isValidClassifier("android/phone"))
    // whitespace
    assertFalse(WaypointCaptureExampleCommand.isValidClassifier("android phone"))
  }
}
