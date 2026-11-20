package xyz.block.trailblaze.toolcalls.commands

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import org.junit.Test

/**
 * `takeSnapshot` reports whether it wrote a file only in its message text. The region checks
 * (`sdks/typescript/src/region-check.ts`, `SNAPSHOT_SAVED_MARKER`) read " saved as " as "a file was
 * written", so rewording these messages must keep that phrase in the saved one and out of the others.
 */
class TakeSnapshotToolMessagesTest {
  private val savedMarker = " saved as "

  @Test
  fun `only the saved message says saved as`() {
    assertThat(TakeSnapshotTool.SNAPSHOT_SAVED_MESSAGE.format("screen", "screen.webp")).contains(savedMarker)
    assertThat(TakeSnapshotTool.NO_SCREENSHOT_AVAILABLE_MESSAGE.format("screen")).doesNotContain(savedMarker)
    assertThat(TakeSnapshotTool.NO_SCREEN_STATE_PROVIDER_MESSAGE.format("screen")).doesNotContain(savedMarker)
  }
}
