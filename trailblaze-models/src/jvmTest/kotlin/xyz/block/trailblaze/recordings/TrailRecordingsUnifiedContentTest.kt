package xyz.block.trailblaze.recordings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the content-level unified/v1 discriminator [TrailRecordings.isUnifiedTrailContent] — the
 * same `^trail:` rule the coverage scripts use, so CI selection and the coverage reports agree.
 */
class TrailRecordingsUnifiedContentTest {

  @Test
  fun `unified content has a root-level trail key`() {
    val unified =
      """
      config:
        id: myapp/case
        target: myapp
      trail:
        - step: Do the thing
          recording:
            android:
              - tap:
                  x: 1
                  y: 2
      """
        .trimIndent()
    assertTrue(TrailRecordings.isUnifiedTrailContent(unified))
  }

  @Test
  fun `unified content is detected even with leading comment lines`() {
    val unified =
      """
      # NL drift warning emitted by the migrator
      config:
        target: myapp
      trail:
        - step: Do the thing
      """
        .trimIndent()
    assertTrue(TrailRecordings.isUnifiedTrailContent(unified))
  }

  @Test
  fun `v1 list content is not unified`() {
    val v1 =
      """
      - config:
          id: myapp/case
          target: myapp
      - prompts:
          - step: Do the thing
      """
        .trimIndent()
    assertFalse(TrailRecordings.isUnifiedTrailContent(v1))
  }

  @Test
  fun `a nested or trailhead key does not trip the root trail detector`() {
    // `trailhead:` starts with "trail" but is not the `trail:` root key; an indented `trail:`
    // (were it ever to appear) is a value, not the root list-of-steps key.
    val notUnified =
      """
      config:
        target: myapp
      trailhead:
        step: Launch
      """
        .trimIndent()
    // `trail:` root key is absent, so this is not a (valid) unified document by the sniff.
    assertFalse(TrailRecordings.isUnifiedTrailContent(notUnified))
  }

  @Test
  fun `an indented trail key is not a root unified marker`() {
    // The column-0 contract: a `trail:` nested under another key is a value, not the root
    // list-of-steps key, so it must not trip the detector.
    val indentedTrail =
      """
      config:
        target: myapp
        trail: some-value
      """
        .trimIndent()
    assertFalse(TrailRecordings.isUnifiedTrailContent(indentedTrail))
  }

  @Test
  fun `a stub comment-only file is not unified`() {
    assertFalse(TrailRecordings.isUnifiedTrailContent("# stub recording\n"))
  }
}
