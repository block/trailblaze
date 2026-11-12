package xyz.block.trailblaze.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkCaptureRedactionTest {

  @Test
  fun `credential headers keep their key and lose their value`() {
    val headers = NetworkCaptureRedaction.redactHeaders(
      mapOf(
        "Cookie" to "_session=abc",
        "Set-Cookie" to "_session=abc; HttpOnly",
        "Authorization" to "Bearer abc",
        "X-CSRF-Token" to "abc",
        "Accept" to "application/json",
      ),
    )

    assertEquals(
      mapOf(
        "Cookie" to REDACTED_VALUE,
        "Set-Cookie" to REDACTED_VALUE,
        "Authorization" to REDACTED_VALUE,
        "X-CSRF-Token" to REDACTED_VALUE,
        "Accept" to "application/json",
      ),
      headers,
    )
  }

  @Test
  fun `a JSON sign-in body loses its password at any depth and keeps the rest`() {
    val body = NetworkCaptureRedaction.redactBodyText(
      """{"credentials":{"email":"owner@example.com","password":"hunter22"},"step":2}""",
      "application/json",
    )

    assertFalse(body.contains("hunter22"), body)
    assertTrue(body.contains("owner@example.com"), body)
    assertTrue(body.contains("\"step\":2"), body)
  }

  @Test
  fun `a JSON body cut short before it parses is masked whole`() {
    val truncated = """{"sessionToken":"page-made-token-91c2","items":[1,2,"""

    assertEquals(REDACTED_VALUE, NetworkCaptureRedaction.redactBodyText(truncated, "application/json; charset=utf-8"))
  }

  @Test
  fun `a known secret is masked in a body and URL however it is encoded`() {
    val secret = "p@ss word!"
    val form = NetworkCaptureRedaction.redactBodyText("note=p%40ss+word%21", "application/x-www-form-urlencoded", listOf(secret))
    val url = NetworkCaptureRedaction.redactUrl("https://x.test/cb?state=p%40ss%20word!&ok=1", listOf(secret))
    val binary = NetworkCaptureRedaction.redactBody("xx$secret".encodeToByteArray(), "application/x-protobuf", isText = false, listOf(secret))

    assertEquals("note=$REDACTED_VALUE", form)
    assertEquals("https://x.test/cb?state=$REDACTED_VALUE&ok=1", url)
    assertFalse(binary.decodeToString().contains(secret))
  }

  @Test
  fun `credential-named query parameters are masked`() {
    assertEquals(
      "https://x.test/a?access_token=$REDACTED_VALUE&page=2#top",
      NetworkCaptureRedaction.redactUrl("https://x.test/a?access_token=abc&page=2#top"),
    )
  }
}
