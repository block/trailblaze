package xyz.block.trailblaze.logs.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.block.trailblaze.logs.model.SessionId

/**
 * Names for logs an on-device run writes to Downloads.
 *
 * The device writer deletes any existing file of the same name before writing, so a duplicate
 * name is silent data loss rather than an error — which is what makes these assertions worth
 * having.
 */
class OnDeviceLogFileNameTest {

  private val session = SessionId("MyTest_2026_09_23")

  @Test
  fun `two logs in the same millisecond get different names`() {
    // The case that actually happens: an LLM request emits its tool catalog and the request
    // itself, both stamped with the request's start time. A shared name means the request
    // replaces the catalog it points at, and the pulled session resolves no tool descriptors.
    val catalog = OnDeviceLogFileName.forLog(session, 1_758_512_345_678L)
    val request = OnDeviceLogFileName.forLog(session, 1_758_512_345_678L)

    assertTrue("a log name must be unique: $catalog", catalog != request)
  }

  @Test
  fun `a name carries its session and timestamp and ends in json`() {
    val name = OnDeviceLogFileName.forLog(session, 1_758_512_345_678L)

    assertTrue("session must be recoverable from $name", name.startsWith("${session.value}_"))
    assertTrue("timestamp must be present in $name", name.contains("_1758512345678_"))
    assertTrue("must be a .json file: $name", name.endsWith(".json"))
  }

  @Test
  fun `names written in the same millisecond sort in emission order`() {
    // The host pulls a flat directory; a lexical sort is how emission order is recovered. A bare
    // millisecond cannot order logs written inside the same one, and an unpadded counter would
    // sort 10 before 9.
    val timestamp = 1_758_512_345_678L
    val emitted = (1..12).map { OnDeviceLogFileName.forLog(session, timestamp) }

    assertEquals("a lexical sort must reproduce emission order", emitted, emitted.sorted())
  }

  @Test
  fun `an earlier log sorts before a later one`() {
    val earlier = OnDeviceLogFileName.forLog(session, 1_758_512_345_000L)
    val later = OnDeviceLogFileName.forLog(session, 1_758_512_999_000L)

    assertEquals(listOf(earlier, later), listOf(later, earlier).sorted())
  }

  @Test
  fun `a long session id still produces a writable name`() {
    // Session ids are untruncated and real ones already run past 220 bytes. Adding the counter
    // grew the suffix from 19 bytes to 30, which is enough to push those past the filesystem's
    // per-name limit — where the host write fails outright and Android's MediaStore silently
    // renames the file instead, so the host pulls a log it can no longer match to its name.
    val longSession = SessionId("a".repeat(250))

    val name = OnDeviceLogFileName.forLog(longSession, 1_758_512_345_678L)

    assertTrue(
      "a name must stay under the 255-byte limit, was ${name.toByteArray().size}",
      name.toByteArray(Charsets.UTF_8).size <= 255,
    )
    assertTrue("must still be a .json file: $name", name.endsWith(".json"))
  }

  @Test
  fun `names stay unique across sessions sharing a millisecond`() {
    // One process runs many sessions, and the counter is shared rather than per-session — two
    // sessions writing in the same millisecond still must not collide in the same directory.
    val timestamp = 1_758_512_345_678L
    val first = OnDeviceLogFileName.forLog(SessionId("session_one"), timestamp)
    val second = OnDeviceLogFileName.forLog(SessionId("session_two"), timestamp)

    assertTrue(first != second)
  }
}
