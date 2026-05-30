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
 * The thread-local is the load-bearing fix for `waypoint --target <trailmap>` working
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

  // ── withCallerEnv / callerEnv ─────────────────────────────────────────────
  //
  // Parallel coverage for the env-forwarding thread-local. The contract is the
  // load-bearing fix for `trailblaze snapshot` (and other /cli/exec-forwarded
  // subcommands) seeing the user's shell-pinned TRAILBLAZE_DEVICE rather than
  // the daemon's stale captured env. Same save/restore shape as withCallerCwd.

  @Test
  fun `withCallerEnv pins caller env for the duration of the block`() {
    val seen = CliCallerContext.withCallerEnv(mapOf("TRAILBLAZE_DEVICE" to "android/emulator-5556")) {
      CliCallerContext.callerEnv("TRAILBLAZE_DEVICE")
    }
    assertEquals("android/emulator-5556", seen)
  }

  @Test
  fun `withCallerEnv restores the prior binding after the block returns`() {
    val outer = mapOf("TRAILBLAZE_DEVICE" to "outer-device")
    val inner = mapOf("TRAILBLAZE_DEVICE" to "inner-device")

    CliCallerContext.withCallerEnv(outer) {
      assertEquals("outer-device", CliCallerContext.callerEnv("TRAILBLAZE_DEVICE"))
      CliCallerContext.withCallerEnv(inner) {
        assertEquals("inner-device", CliCallerContext.callerEnv("TRAILBLAZE_DEVICE"))
      }
      assertEquals(
        "outer-device",
        CliCallerContext.callerEnv("TRAILBLAZE_DEVICE"),
        "inner block must not leak its binding",
      )
    }
    // Outside any block: fall through to System.getenv. We assert the
    // thread-local is gone (returns whatever System.getenv("TRAILBLAZE_DEVICE")
    // happens to be — we can't pin its absolute value without mutating
    // System env, so we pin the fall-through behavior via a name we KNOW
    // System.getenv doesn't have set).
    assertEquals(
      null,
      CliCallerContext.callerEnv("TRAILBLAZE_DOES_NOT_EXIST_IN_ENV_$TEST_NONCE"),
      "outer block must restore to no-binding → System.getenv fallback returns null for an unset var",
    )
  }

  @Test
  fun `withCallerEnv restores prior binding even when the block throws`() {
    val env = mapOf("TRAILBLAZE_DEVICE" to "device-during-throw")
    try {
      CliCallerContext.withCallerEnv(env) {
        throw RuntimeException("simulated failure inside the CLI exec")
      }
    } catch (_: RuntimeException) {
      // expected
    }
    // After the throw, thread-local must be cleared so callerEnv falls back
    // to System.getenv. For a var we know isn't set, that returns null.
    assertEquals(
      null,
      CliCallerContext.callerEnv("TRAILBLAZE_DOES_NOT_EXIST_IN_ENV_$TEST_NONCE"),
      "exception path must still restore the binding (thread-local cleared, fallback returns null)",
    )
  }

  @Test
  fun `null env makes callerEnv fall back to System-getenv`() {
    // Old shims that pre-date the env field send no env; the daemon parses the
    // request with `env = null` and we need that to behave identically to "no
    // override" — i.e. fall through to System.getenv. This is the backward-
    // compat contract for the daemon-forwarded path.
    val seen = CliCallerContext.withCallerEnv(null) {
      // For a name we know isn't in System.getenv, the fallback returns null.
      CliCallerContext.callerEnv("TRAILBLAZE_DOES_NOT_EXIST_IN_ENV_$TEST_NONCE")
    }
    assertEquals(null, seen)
  }

  @Test
  fun `empty env map does NOT fall back to System-getenv — bash shim is authoritative`() {
    // The bash shim ALWAYS sends env_json (even if just "{}") so the daemon
    // receives an empty map rather than null when no allowlisted vars are
    // set. Critically: that empty map is AUTHORITATIVE — it means "the
    // user has these vars unset in their shell", not "we don't know, ask
    // System.getenv". Without this contract, a forwarded `snapshot` after
    // `eval $(trailblaze device disconnect)` would resurrect the daemon's
    // frozen-at-`app start` TRAILBLAZE_DEVICE and ignore the user's
    // explicit unset. So an unknown key under an empty (but non-null) map
    // must return null, NOT fall through to System.getenv.
    //
    // We use a name we know IS set on the host's System.getenv via the
    // JVM's own env (PATH is always set on any sane shell), so if the
    // fallback were incorrectly invoked we'd get PATH's value back; if
    // the contract holds we get null.
    val seen = CliCallerContext.withCallerEnv(emptyMap()) {
      CliCallerContext.callerEnv("PATH")
    }
    assertEquals(
      null,
      seen,
      "empty (but non-null) thread-local must be treated as authoritative — " +
        "the daemon's System.getenv must not leak through when the bash shim " +
        "explicitly told us the var is unset",
    )
  }

  @Test
  fun `callerEnv prefers thread-local over System-getenv when both could resolve`() {
    // Even if System.getenv had a value (we can't easily set one in-process
    // without reflection), the thread-local must win for the daemon-forwarded
    // path — the daemon's stale captured env is precisely what this thread-
    // local is overriding. We pin the precedence by using a name the daemon's
    // env almost certainly doesn't have (so System.getenv returns null) and
    // setting it explicitly in the thread-local; the thread-local value comes
    // back, proving callerEnv reads the thread-local even when System.getenv
    // would return null.
    val explicit = mapOf("TRAILBLAZE_TEST_PRECEDENCE_$TEST_NONCE" to "from-thread-local")
    val seen = CliCallerContext.withCallerEnv(explicit) {
      CliCallerContext.callerEnv("TRAILBLAZE_TEST_PRECEDENCE_$TEST_NONCE")
    }
    assertEquals("from-thread-local", seen)
  }

  @Test
  fun `callerEnv channel works for TRAILBLAZE_TARGET — not just TRAILBLAZE_DEVICE`() {
    // The `callerEnv` resolver is key-agnostic, but two consumers ride on it
    // (`resolveCliDevice` reads TRAILBLAZE_DEVICE, `envTrailblazeTarget` reads
    // TRAILBLAZE_TARGET) and both must keep working. Without this explicit
    // pin, a future refactor that special-cases TRAILBLAZE_DEVICE could break
    // TRAILBLAZE_TARGET resolution silently — exactly the kind of drift the
    // bash shim's allowlist comment warns about. Pin both names through the
    // same machinery so neither consumer can regress in isolation.
    val env = mapOf(
      "TRAILBLAZE_DEVICE" to "android/emulator-5556",
      "TRAILBLAZE_TARGET" to "sampleapp",
    )
    CliCallerContext.withCallerEnv(env) {
      assertEquals("android/emulator-5556", CliCallerContext.callerEnv("TRAILBLAZE_DEVICE"))
      assertEquals("sampleapp", CliCallerContext.callerEnv("TRAILBLAZE_TARGET"))
    }
  }

  companion object {
    // Random suffix appended to test-only env-var names so they can't collide
    // with anything the host environment happens to set. Picked once per
    // test-class load so all tests in this file see the same name.
    private val TEST_NONCE = (1..16)
      .map { (('A'..'Z') + ('0'..'9')).random() }
      .joinToString("")
  }
}
