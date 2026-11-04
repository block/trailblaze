package xyz.block.trailblaze.android.test.tools

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether a recorded scroll centres its target. An unwritten `centerElement` hands the decision to
 * the driver, which centres like the accessibility driver the recordings were made on; an explicit
 * value always wins.
 */
class ScrollOptionSupportTest {

  @Test
  fun `an unwritten centerElement centres like the accessibility driver`() {
    assertTrue(ScrollOptionSupport.centerElement(null))
  }

  @Test
  fun `explicitly declining to centre is honored`() {
    assertFalse(ScrollOptionSupport.centerElement(false))
  }

  @Test
  fun `asking to centre centres`() {
    assertTrue(ScrollOptionSupport.centerElement(true))
  }
}
