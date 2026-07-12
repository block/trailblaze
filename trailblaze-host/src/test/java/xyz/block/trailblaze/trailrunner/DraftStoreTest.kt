package xyz.block.trailblaze.trailrunner

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DraftStoreTest {

  @get:Rule
  val tmp = TemporaryFolder()

  /** Builds `<primary>/drafts/my-draft/` holding [fileNames] and returns the primary root. */
  private fun primaryWithDraft(vararg fileNames: String): File {
    val primary = tmp.newFolder()
    val draftDir = File(primary, "drafts/my-draft").apply { mkdirs() }
    fileNames.forEach { File(draftDir, it).writeText("- prompts:\n  - step: do something\n") }
    return primary
  }

  @Test
  fun `a bare unified trail-yaml reads as a recorded variant`() {
    val drafts = DraftStore.list(primaryWithDraft("blaze.yaml", "trail.yaml"), extras = emptyList())
    val draft = drafts.single()
    assertEquals(listOf("trail.yaml"), draft.variants)
    assertTrue(draft.hasRecordings)
  }

  @Test
  fun `a classifier-named recording reads as a recorded variant`() {
    val drafts = DraftStore.list(primaryWithDraft("blaze.yaml", "android-phone.trail.yaml"), extras = emptyList())
    assertEquals(listOf("android-phone.trail.yaml"), drafts.single().variants)
  }

  @Test
  fun `an NL-only draft has no recorded variants`() {
    // The draft's own blaze.yaml must not read as a recording, or every draft would
    // report hasRecordings.
    val drafts = DraftStore.list(primaryWithDraft("blaze.yaml"), extras = emptyList())
    val draft = drafts.single()
    assertEquals(emptyList(), draft.variants)
    assertFalse(draft.hasRecordings)
  }
}
