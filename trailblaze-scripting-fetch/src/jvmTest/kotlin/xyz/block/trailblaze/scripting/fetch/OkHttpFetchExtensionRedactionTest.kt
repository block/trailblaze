package xyz.block.trailblaze.scripting.fetch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Pins the URL redaction applied to every `fetch` log breadcrumb. This is the security-load-bearing
 * part of the logging: on-device these lines reach logcat unconditionally (quiet mode is a no-op on
 * Android) and logcat is streamed into the session's `device.log`, which CI uploads as a build
 * artifact — so anything left in the logged URL leaves the device.
 *
 * Tested as a pure function over plain inputs rather than by capturing emitted log output: the JVM
 * `Console` captures `System.out` at class-init, so a stdout-capture test would be class-load-order
 * dependent and would assert on log wording — coupling the test to how the message is formatted
 * instead of to which URL components survive.
 *
 * The first four cases are the exact URLs from the review that found the userinfo/fragment leak.
 */
class OkHttpFetchExtensionRedactionTest {

  private val extension = OkHttpFetchExtension()

  private fun logSafe(url: String): String = extension.logSafeUrl(url.toHttpUrl())

  @Test
  fun `userinfo is stripped on the redacting branch`() {
    assertEquals(
      "https://example.com/path?<redacted>",
      logSafe("https://user:secret@example.com/path?token=abc"),
    )
  }

  @Test
  fun `userinfo is stripped when there is no query at all`() {
    // The no-query branch used to return the URL verbatim, password included.
    assertEquals("https://example.com/path", logSafe("https://user:secret@example.com/path"))
  }

  @Test
  fun `a fragment is stripped`() {
    // An OAuth implicit-flow token rides in the fragment. It is never sent to the server, so it has
    // no diagnostic value in a request breadcrumb either.
    assertEquals("https://example.com/path", logSafe("https://example.com/path#access_token=xyz"))
  }

  @Test
  fun `a fragment alongside a query is stripped and the marker stays well-formed`() {
    // Previously produced `…/path#access_token=xyz?<redacted>` — leaked AND malformed.
    assertEquals(
      "https://example.com/path?<redacted>",
      logSafe("https://example.com/path?a=b#access_token=xyz"),
    )
  }

  @Test
  fun `scheme host port and path survive - the breadcrumb still identifies the endpoint`() {
    assertEquals("http://127.0.0.1:8080/bridge/command", logSafe("http://127.0.0.1:8080/bridge/command"))
  }

  @Test
  fun `a query is marked as redacted rather than silently dropped`() {
    // The marker is what tells a reader parameters existed but were withheld, as opposed to a
    // request that genuinely had none.
    assertEquals("https://example.com/search?<redacted>", logSafe("https://example.com/search?q=hi"))
  }

  @Test
  fun `no secret-bearing component survives for a URL carrying all of them`() {
    val redacted = logSafe("https://user:s3cret@example.com/p?token=abc123#access_token=xyz789")
    listOf("s3cret", "abc123", "xyz789", "token", "access_token").forEach { secret ->
      assertFalse(redacted.contains(secret), "expected '$secret' to be stripped; got: $redacted")
    }
  }
}
