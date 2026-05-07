package xyz.block.trailblaze.cli

import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Tests for [CliCallerContext].
 *
 * The thread-local is the load-bearing fix for `waypoint --target <pack>` working
 * correctly under daemon-forwarded CLI invocations. Without it, the daemon's
 * `Paths.get("")` resolves to the daemon's launch cwd (typically the repo root),
 * NOT the user's shell cwd — and the workspace-anchor walk silently misses the
 * actual workspace. These tests pin the contract:
 *
 *  - Default (no override) returns `Paths.get("")` so direct-JVM CLI invocations
 *    keep working unchanged.
 *  - `withCallerCwd` scopes the override to the executing block.
 *  - Restoring a prior binding works (nested calls, exception unwind).
 *
 * They're intentionally narrow — the integration with `executeForDaemon` and the
 * bash shim is exercised by manual smoke testing in the PR body.
 */
class CliCallerContextTest {

  @Test
  fun `callerCwd returns Paths-get-empty when no thread-local is set`() {
    assertEquals(
      Paths.get(""),
      CliCallerContext.callerCwd(),
      "default must match the previous direct-call behavior so non-daemon JVM " +
        "invocations don't regress",
    )
  }

  @Test
  fun `withCallerCwd pins the caller cwd for the duration of the block`() {
    val caller = Paths.get("/some/user/cwd")
    val seen = CliCallerContext.withCallerCwd(caller) { CliCallerContext.callerCwd() }
    assertEquals(caller, seen)
  }

  @Test
  fun `withCallerCwd restores the prior binding after the block returns`() {
    val outer = Paths.get("/outer")
    val inner = Paths.get("/inner")

    CliCallerContext.withCallerCwd(outer) {
      assertEquals(outer, CliCallerContext.callerCwd())
      CliCallerContext.withCallerCwd(inner) {
        assertEquals(inner, CliCallerContext.callerCwd())
      }
      assertEquals(outer, CliCallerContext.callerCwd(), "inner block must not leak its binding")
    }
    assertEquals(
      Paths.get(""),
      CliCallerContext.callerCwd(),
      "outer block must restore to no-binding default",
    )
  }

  @Test
  fun `withCallerCwd restores prior binding even when the block throws`() {
    val caller = Paths.get("/some/cwd")
    try {
      CliCallerContext.withCallerCwd(caller) {
        throw RuntimeException("simulated failure inside the CLI exec")
      }
    } catch (_: RuntimeException) {
      // expected
    }
    assertEquals(
      Paths.get(""),
      CliCallerContext.callerCwd(),
      "exception path must still restore the binding",
    )
  }

  @Test
  fun `null cwd makes callerCwd fall back to Paths-get-empty`() {
    // Old shims that pre-date the cwd field send no cwd; the daemon parses the
    // request with `cwd = null` and we need that to behave identically to "no
    // override." This is the backward-compat contract.
    val seen = CliCallerContext.withCallerCwd(null) { CliCallerContext.callerCwd() }
    assertEquals(Paths.get(""), seen)
  }

  @Test
  fun `concurrent threads do not leak each other's bindings`() {
    // ThreadLocal is per-thread by definition, but pin the contract anyway —
    // the daemon serializes /cli/exec today, but the helper must remain correct
    // if that constraint is ever relaxed.
    val a = Paths.get("/thread-a")
    val b = Paths.get("/thread-b")

    val seenA = AtomicReference<Path?>()
    val seenB = AtomicReference<Path?>()

    val threadA = Thread {
      CliCallerContext.withCallerCwd(a) {
        Thread.sleep(50) // give thread B time to set its own value
        seenA.set(CliCallerContext.callerCwd())
      }
    }
    val threadB = Thread {
      CliCallerContext.withCallerCwd(b) {
        Thread.sleep(25)
        seenB.set(CliCallerContext.callerCwd())
      }
    }
    threadA.start(); threadB.start()
    threadA.join(); threadB.join()

    assertEquals(a, seenA.get(), "thread A must see its own binding")
    assertEquals(b, seenB.get(), "thread B must see its own binding")
    assertNotEquals(seenA.get(), seenB.get())
  }
}
