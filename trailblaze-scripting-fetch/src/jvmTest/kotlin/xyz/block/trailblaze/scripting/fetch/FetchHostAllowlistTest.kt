package xyz.block.trailblaze.scripting.fetch

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure unit tests for the allow-list decision — no engine, no socket. [FetchHostAllowlist.isAllowed]
 * is a string-in → boolean, which is exactly the kind of branch that's worth pinning directly
 * rather than only through the end-to-end fetch path.
 */
class FetchHostAllowlistTest {

  @Test
  fun `localhostOnly permits loopback hosts and denies everything else`() {
    val allowlist = FetchHostAllowlist.localhostOnly()
    // Loopback forms a request URL's host can take.
    assertTrue(allowlist.isAllowed("localhost"))
    assertTrue(allowlist.isAllowed("127.0.0.1"))
    assertTrue(allowlist.isAllowed("::1"))
    assertTrue(allowlist.isAllowed("[::1]"))
    assertTrue(allowlist.isAllowed("device.localhost"), "*.localhost is loopback per the WHATWG/RFC convention")
    // Case-insensitive.
    assertTrue(allowlist.isAllowed("LOCALHOST"))
    // The open internet is denied — the replay-determinism guard.
    assertFalse(allowlist.isAllowed("example.com"))
    assertFalse(allowlist.isAllowed("api.internal"))
    // 0.0.0.0 is the wildcard bind address, NOT loopback — must be denied.
    assertFalse(allowlist.isAllowed("0.0.0.0"))
    // Restrictive -> drives OkHttpFetchExtension to disable redirect-following.
    assertFalse(allowlist.allowsAllHosts)
  }

  @Test
  fun `allowHosts permits the named hosts case-insensitively plus loopback by default`() {
    val allowlist = FetchHostAllowlist.allowHosts(setOf("api.internal"))
    assertTrue(allowlist.isAllowed("api.internal"))
    assertTrue(allowlist.isAllowed("API.INTERNAL"), "host matching is case-insensitive")
    assertTrue(allowlist.isAllowed("localhost"), "loopback is included by default")
    assertFalse(allowlist.isAllowed("other.host"))
  }

  @Test
  fun `allowHosts can exclude loopback when includeLocalhost is false`() {
    val allowlist = FetchHostAllowlist.allowHosts(setOf("api.internal"), includeLocalhost = false)
    assertTrue(allowlist.isAllowed("api.internal"))
    assertFalse(allowlist.isAllowed("localhost"))
    assertFalse(allowlist.isAllowed("127.0.0.1"))
  }

  @Test
  fun `allowAll permits any host`() {
    val allowlist = FetchHostAllowlist.allowAll()
    assertTrue(allowlist.isAllowed("example.com"))
    assertTrue(allowlist.isAllowed("localhost"))
    assertTrue(allowlist.isAllowed("anything.at.all"))
    // Unrestricted -> OkHttpFetchExtension keeps standard redirect-following.
    assertTrue(allowlist.allowsAllHosts)
  }
}
