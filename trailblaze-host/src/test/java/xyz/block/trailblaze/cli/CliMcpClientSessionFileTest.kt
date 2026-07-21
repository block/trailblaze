package xyz.block.trailblaze.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Unit tests for [CliMcpClient] session file read/write helpers. */
class CliMcpClientSessionFileTest {

  // ---------------------------------------------------------------------------
  // readSessionFile
  // ---------------------------------------------------------------------------

  @Test
  fun `readSessionFile returns nulls for non-existent file`() {
    val file = File.createTempFile("session", ".txt").also { it.delete() }
    val (sessionId, targetAppId) = CliMcpClient.readSessionFile(file)
    assertNull(sessionId)
    assertNull(targetAppId)
  }

  @Test
  fun `readSessionFile reads old single-line format`() {
    val file = tempFileWith("abc-123")
    val (sessionId, targetAppId) = CliMcpClient.readSessionFile(file)
    assertEquals("abc-123", sessionId)
    assertNull(targetAppId)
    file.delete()
  }

  @Test
  fun `readSessionFile reads new two-line format`() {
    val file = tempFileWith("abc-123\nsampleapp")
    val (sessionId, targetAppId) = CliMcpClient.readSessionFile(file)
    assertEquals("abc-123", sessionId)
    assertEquals("sampleapp", targetAppId)
    file.delete()
  }

  @Test
  fun `readSessionFile trims whitespace`() {
    val file = tempFileWith("  abc-123  \n  sampleapp  ")
    val (sessionId, targetAppId) = CliMcpClient.readSessionFile(file)
    assertEquals("abc-123", sessionId)
    assertEquals("sampleapp", targetAppId)
    file.delete()
  }

  @Test
  fun `readSessionFile treats empty lines as null`() {
    val file = tempFileWith("")
    val (sessionId, targetAppId) = CliMcpClient.readSessionFile(file)
    assertNull(sessionId)
    assertNull(targetAppId)
    file.delete()
  }

  @Test
  fun `readSessionFile treats blank second line as null targetAppId`() {
    val file = tempFileWith("abc-123\n   ")
    val (sessionId, targetAppId) = CliMcpClient.readSessionFile(file)
    assertEquals("abc-123", sessionId)
    assertNull(targetAppId)
    file.delete()
  }

  // ---------------------------------------------------------------------------
  // writeSessionFile
  // ---------------------------------------------------------------------------

  @Test
  fun `writeSessionFile writes session ID only when targetAppId is null`() {
    val file = File.createTempFile("session", ".txt")
    CliMcpClient.writeSessionFile(file, "abc-123", null)
    assertEquals("abc-123", file.readText())
    file.delete()
  }

  @Test
  fun `writeSessionFile writes both session ID and target app ID`() {
    val file = File.createTempFile("session", ".txt")
    CliMcpClient.writeSessionFile(file, "abc-123", "sampleapp")
    assertEquals("abc-123\nsampleapp", file.readText())
    file.delete()
  }

  @Test
  fun `writeSessionFile writes empty string when session ID is null`() {
    val file = File.createTempFile("session", ".txt")
    CliMcpClient.writeSessionFile(file, null, null)
    assertEquals("", file.readText())
    file.delete()
  }

  // ---------------------------------------------------------------------------
  // Round-trip
  // ---------------------------------------------------------------------------

  @Test
  fun `write then read round-trips with both fields`() {
    val file = File.createTempFile("session", ".txt")
    CliMcpClient.writeSessionFile(file, "sess-42", "myapp")
    val (sessionId, targetAppId) = CliMcpClient.readSessionFile(file)
    assertEquals("sess-42", sessionId)
    assertEquals("myapp", targetAppId)
    file.delete()
  }

  @Test
  fun `write then read round-trips with session only`() {
    val file = File.createTempFile("session", ".txt")
    CliMcpClient.writeSessionFile(file, "sess-42", null)
    val (sessionId, targetAppId) = CliMcpClient.readSessionFile(file)
    assertEquals("sess-42", sessionId)
    assertNull(targetAppId)
    file.delete()
  }

  @Test
  fun `migrateSessionFile preserves session and target under resolved scope`() {
    val port = 61_234
    val suffix = java.util.UUID.randomUUID().toString()
    val provisionalScope = "cli-android-$suffix"
    val resolvedScope = "cli-android-emulator-5554-$suffix"
    val source = CliMcpClient.sessionFile(port, provisionalScope)
    val destination = CliMcpClient.sessionFile(port, resolvedScope)
    try {
      CliMcpClient.writeSessionFile(source, "session-123", "square")

      CliMcpClient.migrateSessionFile(
        port = port,
        fromSessionScope = provisionalScope,
        toSessionScope = resolvedScope,
      )

      assertFalse(source.exists())
      assertEquals("session-123" to "square", CliMcpClient.readSessionFile(destination))
    } finally {
      source.delete()
      destination.delete()
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private fun tempFileWith(content: String): File =
    File.createTempFile("session", ".txt").also { it.writeText(content) }
}
