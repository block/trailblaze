package xyz.block.trailblaze.android.test.tools

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Which scroll options this driver refuses.
 *
 * The refusal is what stops a recording from being told it got a scroll it never asked for, so
 * "unwritten" and "explicitly asked for" have to stay distinguishable — an unwritten
 * `centerElement` hands the decision to the driver, and refusing it would reject every ordinary
 * recorded scroll.
 */
class ScrollOptionSupportTest {

  @Test
  fun `an unwritten centerElement is not a request to centre`() {
    assertNull(ScrollOptionSupport.unsupportedCenterElement(null))
  }

  @Test
  fun `explicitly declining to centre is honored`() {
    assertNull(ScrollOptionSupport.unsupportedCenterElement(false))
  }

  @Test
  fun `asking to centre is refused by name`() {
    val refusal = ScrollOptionSupport.unsupportedCenterElement(true)

    assertNotNull(refusal, "This driver cannot centre, so asking it to must be refused")
    assertContains(refusal, "centerElement")
  }
}
