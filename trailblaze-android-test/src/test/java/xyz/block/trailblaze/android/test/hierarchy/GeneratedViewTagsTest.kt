package xyz.block.trailblaze.android.test.hierarchy

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A reported tag becomes `[tag=…]` in the agent's hierarchy and a matchable `tagRegex`, so it has
 * to be an identifier a developer chose. Data Binding writes its own bookkeeping into the same
 * field on a large share of the tree; letting those through would bury the real ones.
 */
class GeneratedViewTagsTest {

  @Test
  fun `data binding bookkeeping tags are generated`() {
    assertTrue(isGeneratedViewTag("binding_1"))
    assertTrue(isGeneratedViewTag("layout/activity_main_0"))
    assertTrue(isGeneratedViewTag("layout-land/activity_main_0"))
  }

  @Test
  fun `developer-authored tags are kept`() {
    assertFalse(isGeneratedViewTag("checkout_row"))
    assertFalse(isGeneratedViewTag("binding"))
    assertFalse(isGeneratedViewTag(""))
  }

  /**
   * The exclusion is matched as a whole grammar, not as a prefix. A tag that merely starts the
   * same way is an ordinary name a developer would pick, and silently dropping it would leave the
   * view unselectable by `tagRegex` with nothing on screen to explain why.
   */
  @Test
  fun `tags that only share a prefix with a generated one are kept`() {
    assertFalse(isGeneratedViewTag("layout-header"))
    assertFalse(isGeneratedViewTag("layout_selector"))
    assertFalse(isGeneratedViewTag("layout/header"))
    assertFalse(isGeneratedViewTag("binding_target"))
  }
}
