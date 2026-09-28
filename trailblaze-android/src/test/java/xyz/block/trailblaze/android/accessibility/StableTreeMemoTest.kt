package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * When a capture may skip the settle gate's quiet window by reusing the last stable verdict. Each
 * refusal here is a case where the screen may be moving, and reusing would capture it mid-change.
 */
class StableTreeMemoTest {

  private val proven = StableTreeMemo(signature = 42L, uiEventCountBeforeSample = 7L, verdict = "stable")

  @Test
  fun `the same tree with no UI events since reuses the verdict`() {
    assertEquals("stable", proven.reusableFor(signature = 42L, uiEventCountBeforeSample = 7L, uiEventCountAfterSample = 7L))
  }

  /** An animation that started since the proof has moved some node's bounds. */
  @Test
  fun `a different tree runs the gate`() {
    assertNull(proven.reusableFor(signature = 43L, uiEventCountBeforeSample = 7L, uiEventCountAfterSample = 7L))
  }

  /** A text or content change can leave the signature alone, but it raises an event. */
  @Test
  fun `a UI event since the proof runs the gate even when the tree looks the same`() {
    assertNull(proven.reusableFor(signature = 42L, uiEventCountBeforeSample = 8L, uiEventCountAfterSample = 8L))
  }

  /** The walk can finish before or after the change it reports, so the sample can't vouch for it. */
  @Test
  fun `a UI event during the sample runs the gate`() {
    assertNull(proven.reusableFor(signature = 42L, uiEventCountBeforeSample = 7L, uiEventCountAfterSample = 8L))
  }

  @Test
  fun `nothing proven yet runs the gate`() {
    val none: StableTreeMemo<String>? = null
    assertNull(none.reusableFor(signature = 42L, uiEventCountBeforeSample = 7L, uiEventCountAfterSample = 7L))
  }
}
