package xyz.block.trailblaze.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.model.SessionId

/**
 * Pins the storage semantics of [SessionTargetRegistry] — the in-memory home
 * for per-Trailblaze-session target overrides. Each test exercises a single
 * invariant the production callers depend on:
 *
 * - **`set/get` round-trip**: a value written for a session id reads back.
 * - **Cross-session isolation**: writes against session A don't affect B.
 *   (The original cross-device-target-contamination bug landed because the
 *   first shape was keyed by device alone — keep the keying invariant pinned.)
 * - **Per-device isolation within a session**: a session shared by a named
 *   cast keeps one override per device, so `--target` on the start device
 *   does not re-target the rest of the cast.
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
  private val device = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)
  private val otherDevice = TrailblazeDeviceId("emulator-5556", TrailblazeDevicePlatform.ANDROID)

  @Test
  fun `set then get returns the stored target`() {
    val registry = SessionTargetRegistry()
    registry.set(sessionA, device, "myapp")
    assertEquals("myapp", registry.get(sessionA, device))
  }

  @Test
  fun `get returns null when no target is set for the session`() {
    val registry = SessionTargetRegistry()
    assertNull(registry.get(sessionA, device))
  }

  @Test
  fun `set on one session does not leak to another session`() {
    // Direct guard for the original cross-device contamination shape — the
    // registry is keyed by SessionId, so writes against one session id are
    // invisible to lookups against any other.
    val registry = SessionTargetRegistry()
    registry.set(sessionA, device, "myapp")
    assertNull(
      registry.get(sessionB, device),
      "writes against session A must not leak to session B",
    )
  }

  @Test
  fun `set with null target clears any existing override`() {
    val registry = SessionTargetRegistry()
    registry.set(sessionA, device, "myapp")
    registry.set(sessionA, device, null)
    assertNull(registry.get(sessionA, device))
  }

  @Test
  fun `set with empty string clears any existing override`() {
    // The MCP tool's "pass empty string to clear" semantic — and the CLI's
    // `--target=clear` (after the `clear` keyword is mapped to empty) — both
    // bottom out here. The registry must not store the empty string verbatim
    // because downstream code (tool dispatch, findById) would fail in
    // surprising ways with a literally-empty target id.
    val registry = SessionTargetRegistry()
    registry.set(sessionA, device, "myapp")
    registry.set(sessionA, device, "")
    assertNull(registry.get(sessionA, device))
  }

  @Test
  fun `set with whitespace-only string clears any existing override`() {
    val registry = SessionTargetRegistry()
    registry.set(sessionA, device, "myapp")
    registry.set(sessionA, device, "   ")
    assertNull(registry.get(sessionA, device))
  }

  @Test
  fun `set with blank target on an unset session is a no-op`() {
    val registry = SessionTargetRegistry()
    registry.set(sessionA, device, null)
    registry.set(sessionA, device, "")
    registry.set(sessionA, device, "   ")
    assertNull(registry.get(sessionA, device))
    assertTrue(registry.snapshot().isEmpty(), "blank-target writes must not populate the map")
  }

  @Test
  fun `clear removes an existing override`() {
    val registry = SessionTargetRegistry()
    registry.set(sessionA, device, "myapp")
    registry.clear(sessionA)
    assertNull(registry.get(sessionA, device))
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
    assertNull(registry.get(sessionA, device))
  }

  @Test
  fun `clear on session A leaves session B's override intact`() {
    val registry = SessionTargetRegistry()
    registry.set(sessionA, device, "myapp")
    registry.set(sessionB, device, "otherapp")
    registry.clear(sessionA)
    assertNull(registry.get(sessionA, device))
    assertEquals("otherapp", registry.get(sessionB, device))
  }

  @Test
  fun `re-set after clear restores a working override`() {
    val registry = SessionTargetRegistry()
    registry.set(sessionA, device, "myapp")
    registry.clear(sessionA)
    registry.set(sessionA, device, "otherapp")
    assertEquals("otherapp", registry.get(sessionA, device))
  }

  @Test
  fun `two devices sharing one session keep separate overrides`() {
    // A named cast shares one Trailblaze session. The start device's `--target` is its own; the
    // companion resolves against whatever it was given, else the daemon-wide default.
    val registry = SessionTargetRegistry()
    registry.set(sessionA, device, "seller-app")
    assertNull(registry.get(sessionA, otherDevice), "the companion must not inherit the start device's target")
    registry.set(sessionA, otherDevice, "buyer-app")
    assertEquals("seller-app", registry.get(sessionA, device))
    assertEquals("buyer-app", registry.get(sessionA, otherDevice))
  }

  @Test
  fun `the session-wide read reports the first override set, and clear drops every device`() {
    val registry = SessionTargetRegistry()
    registry.set(sessionA, device, "seller-app")
    registry.set(sessionA, otherDevice, "buyer-app")
    assertEquals("seller-app", registry.get(sessionA))
    registry.clear(sessionA)
    assertNull(registry.get(sessionA))
    assertNull(registry.get(sessionA, otherDevice))
  }

  @Test
  fun `clearing the last device's override forgets the session`() {
    val registry = SessionTargetRegistry()
    registry.set(sessionA, device, "myapp")
    registry.set(sessionA, device, null)
    assertTrue(registry.snapshot().isEmpty(), "an emptied session must not linger as a key")
  }

  @Test
  fun `concurrent writes from many threads do not corrupt the map`() {
    // Many threads racing set/get/clear — every access holds the registry's monitor, but pin
    // the behavior so a future refactor to an unguarded container would break here.
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
            registry.set(sessionId, device, "target-$i")
            registry.get(sessionId, device)
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
    registry.set(sessionA, device, "myapp")
    val snapshot = registry.snapshot()
    registry.set(sessionA, device, "otherapp")
    registry.set(sessionB, device, "thirdapp")
    assertEquals(
      mapOf((sessionA to device) to "myapp"),
      snapshot,
      "snapshot must be immutable to later writes (it's used in tests as a stable assertion target)",
    )
  }
}
