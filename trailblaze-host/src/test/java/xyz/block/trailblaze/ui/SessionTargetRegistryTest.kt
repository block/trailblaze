package xyz.block.trailblaze.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.logs.model.SessionId

/**
 * Pins the storage semantics of [SessionTargetRegistry] — the in-memory home
 * for per-Trailblaze-session target overrides. Each test exercises a single
 * invariant the production callers depend on:
 *
 * - **`set/get` round-trip**: a value written for a session id reads back.
 * - **Cross-session isolation**: writes against session A don't affect B.
 *   (The original cross-device-target-contamination bug landed in this PR
 *   because the previous shape was keyed by device, not session — keep the
 *   keying invariant pinned.)
 * - **`set(blank)` clears**: empty / whitespace-only / null targets must
 *   never be stored verbatim; treating them as a clear matches the
 *   `--target=""` and `--target=clear` CLI surface that routes through here.
 * - **`clear` is idempotent**: ending an already-ended session must not
 *   throw or surface user-visible noise. All three production cleanup
 *   paths (`endSessionForDevice`, `cancelSessionForDevice`,
 *   `clearEndedSessionFromDevice`) can race and double-call.
 * - **Concurrent writes don't corrupt state**: writes from parallel threads
 *   land safely (the underlying map is `ConcurrentHashMap` but pin it).
 */
class SessionTargetRegistryTest {

  private val sessionA = SessionId("session-a")
  private val sessionB = SessionId("session-b")

  @Test
  fun `set then get returns the stored target`() {
    val registry = SessionTargetRegistry()
    registry.set(sessionA, "myapp")
    assertEquals("myapp", registry.get(sessionA))
  }

  @Test
  fun `get returns null when no target is set for the session`() {
    val registry = SessionTargetRegistry()
    assertNull(registry.get(sessionA))
  }

  @Test
  fun `set on one session does not leak to another session`() {
    // Direct guard for the original cross-device contamination shape — the
    // registry is keyed by SessionId, so writes against one session id are
    // invisible to lookups against any other.
    val registry = SessionTargetRegistry()
    registry.set(sessionA, "myapp")
    assertNull(
      registry.get(sessionB),
      "writes against session A must not leak to session B",
    )
  }

  @Test
  fun `set with null target clears any existing override`() {
    val registry = SessionTargetRegistry()
    registry.set(sessionA, "myapp")
    registry.set(sessionA, null)
    assertNull(registry.get(sessionA))
  }

  @Test
  fun `set with empty string clears any existing override`() {
    // The MCP tool's "pass empty string to clear" semantic — and the CLI's
    // `--target=clear` (after the `clear` keyword is mapped to empty) — both
    // bottom out here. The registry must not store the empty string verbatim
    // because downstream code (tool dispatch, findById) would fail in
    // surprising ways with a literally-empty target id.
    val registry = SessionTargetRegistry()
    registry.set(sessionA, "myapp")
    registry.set(sessionA, "")
    assertNull(registry.get(sessionA))
  }

  @Test
  fun `set with whitespace-only string clears any existing override`() {
    val registry = SessionTargetRegistry()
    registry.set(sessionA, "myapp")
    registry.set(sessionA, "   ")
    assertNull(registry.get(sessionA))
  }

  @Test
  fun `set with blank target on an unset session is a no-op`() {
    val registry = SessionTargetRegistry()
    registry.set(sessionA, null)
    registry.set(sessionA, "")
    registry.set(sessionA, "   ")
    assertNull(registry.get(sessionA))
    assertTrue(registry.snapshot().isEmpty(), "blank-target writes must not populate the map")
  }

  @Test
  fun `clear removes an existing override`() {
    val registry = SessionTargetRegistry()
    registry.set(sessionA, "myapp")
    registry.clear(sessionA)
    assertNull(registry.get(sessionA))
  }

  @Test
  fun `clear on an unset session is a no-op`() {
    // The three production callers (endSessionForDevice,
    // cancelSessionForDevice, clearEndedSessionFromDevice) all clear on
    // session-end; for any given session lifecycle, at least one fires, and
    // for races / explicit-stop-followed-by-end-log-collection both may
    // fire. Idempotence is a hard requirement.
    val registry = SessionTargetRegistry()
    registry.clear(sessionA) // no prior set
    assertNull(registry.get(sessionA))
  }

  @Test
  fun `clear on session A leaves session B's override intact`() {
    val registry = SessionTargetRegistry()
    registry.set(sessionA, "myapp")
    registry.set(sessionB, "otherapp")
    registry.clear(sessionA)
    assertNull(registry.get(sessionA))
    assertEquals("otherapp", registry.get(sessionB))
  }

  @Test
  fun `re-set after clear restores a working override`() {
    val registry = SessionTargetRegistry()
    registry.set(sessionA, "myapp")
    registry.clear(sessionA)
    registry.set(sessionA, "otherapp")
    assertEquals("otherapp", registry.get(sessionA))
  }

  @Test
  fun `concurrent writes from many threads do not corrupt the map`() {
    // Two threads racing set/clear on the same session — the map is
    // ConcurrentHashMap so this is fundamentally safe, but pin the behavior
    // so a future refactor to a non-thread-safe container would break here.
    val registry = SessionTargetRegistry()
    val threadCount = 16
    val iterations = 200
    val executor = Executors.newFixedThreadPool(threadCount)
    val latch = CountDownLatch(threadCount)
    repeat(threadCount) { threadIdx ->
      executor.submit {
        try {
          repeat(iterations) { i ->
            val sessionId = SessionId("session-$threadIdx-$i")
            registry.set(sessionId, "target-$i")
            registry.get(sessionId)
            registry.clear(sessionId)
          }
        } finally {
          latch.countDown()
        }
      }
    }
    val finished = latch.await(10, TimeUnit.SECONDS)
    executor.shutdownNow()
    assertTrue(finished, "concurrent writes must complete within 10s — the registry should not deadlock")
    assertTrue(
      registry.snapshot().isEmpty(),
      "every set was paired with a clear; the map must end empty (got ${registry.snapshot()})",
    )
  }

  @Test
  fun `snapshot returns a defensive copy that does not reflect later mutations`() {
    val registry = SessionTargetRegistry()
    registry.set(sessionA, "myapp")
    val snapshot = registry.snapshot()
    registry.set(sessionA, "otherapp")
    registry.set(sessionB, "thirdapp")
    assertEquals(
      mapOf(sessionA to "myapp"),
      snapshot,
      "snapshot must be immutable to later writes (it's used in tests as a stable assertion target)",
    )
  }
}
