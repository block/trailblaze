package xyz.block.trailblaze.device

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Locks the matching contract of `AndroidDeviceCommandExecutor.INSERTED_CONTENT_ID_PATTERN`.
 *
 * The historical regex inside a Kotlin raw string used `\\s` / `\\d`, which raw strings
 * pass through verbatim — so the engine saw literal-backslash escapes and never matched
 * any real `adb shell content insert` output. This test pins the corrected `\s` / `\d`
 * behaviour so the regression can't sneak back in.
 */
class InsertedContentIdPatternTest {

  @Test
  fun `matches contacts raw_contacts insert output`() {
    val output = "Inserted as content://com.android.contacts/raw_contacts/42"
    val id = AndroidDeviceCommandExecutor.INSERTED_CONTENT_ID_PATTERN
      .find(output)?.groupValues?.get(1)
    assertEquals("42", id)
  }

  @Test
  fun `matches multi-segment authority and a long id`() {
    val output = "Inserted as content://media/external_primary/downloads/123456789"
    val id = AndroidDeviceCommandExecutor.INSERTED_CONTENT_ID_PATTERN
      .find(output)?.groupValues?.get(1)
    assertEquals("123456789", id)
  }

  @Test
  fun `returns null when the output is unrelated`() {
    val id = AndroidDeviceCommandExecutor.INSERTED_CONTENT_ID_PATTERN
      .find("error: provider not found")?.groupValues?.get(1)
    assertNull(id)
  }

  @Test
  fun `does not match a uri that is missing the trailing id`() {
    val id = AndroidDeviceCommandExecutor.INSERTED_CONTENT_ID_PATTERN
      .find("Inserted as content://com.android.contacts/raw_contacts/")?.groupValues?.get(1)
    assertNull(id)
  }

  @Test
  fun `finds the id when the URI is embedded in surrounding log noise`() {
    // Realistic adb shell output: logd timestamps before, trailing newline + further log lines after.
    // The regex has no anchors and is consumed via `Regex.find`, so it picks up the first match
    // regardless of surrounding context. Lock that behavior — it's what the production
    // `parseInsertedContentId` call depends on.
    val output = """
      01-15 10:23:45.123  1234  5678 I content : invoking 'insert'
      Inserted as content://com.android.contacts/raw_contacts/42
      01-15 10:23:45.456  1234  5678 D content : done
    """.trimIndent()
    val id = AndroidDeviceCommandExecutor.INSERTED_CONTENT_ID_PATTERN
      .find(output)?.groupValues?.get(1)
    assertEquals("42", id)
  }
}
