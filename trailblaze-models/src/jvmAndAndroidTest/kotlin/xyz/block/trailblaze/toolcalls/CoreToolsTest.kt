package xyz.block.trailblaze.toolcalls

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreToolsTest {

  @Test
  fun `isTapAction recognizes the tap family including longPress`() {
    assertTrue(CoreTools.isTapAction("tap"))
    assertTrue(CoreTools.isTapAction("longPress"), "longPress is a tap-style hold action")
    assertTrue(CoreTools.isTapAction("tapOnPoint"))
    assertTrue(CoreTools.isTapAction("click"))
  }

  @Test
  fun `isTapAction is case-insensitive`() {
    assertTrue(CoreTools.isTapAction("LONGPRESS"))
  }

  @Test
  fun `isTapAction excludes non-tap interactions`() {
    assertFalse(CoreTools.isTapAction("swipe"))
    assertFalse(CoreTools.isTapAction("inputText"))
    assertFalse(CoreTools.isTapAction("pressBack"))
  }
}
