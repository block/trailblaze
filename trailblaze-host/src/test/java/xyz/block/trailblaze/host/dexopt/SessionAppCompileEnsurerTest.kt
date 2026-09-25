package xyz.block.trailblaze.host.dexopt

import xyz.block.trailblaze.device.AndroidPackageDump
import xyz.block.trailblaze.device.EnsureAppCompiled.Outcome
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a host session start does about the app under test's ART artifacts, driven through a
 * recording stand-in for the adb-backed check so every test can say which apps the device was asked
 * about and in what order.
 */
class SessionAppCompileEnsurerTest {

  private val android = TrailblazeDeviceId(
    instanceId = "emulator-5554",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  )
  private val ios = TrailblazeDeviceId(
    instanceId = "sim-1",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.IOS,
  )

  private val compiled = AndroidPackageDump.DexoptState(
    listOf(AndroidPackageDump.DexoptEntry(abi = "x86_64", status = "verify", reason = "install", primaryAbi = true)),
  )
  private val broken = AndroidPackageDump.DexoptState(
    listOf(AndroidPackageDump.DexoptEntry(abi = "x86_64", status = "run-from-apk", reason = "unknown", primaryAbi = true)),
  )

  /** Answers per app id, recording the order it was asked in. Unlisted apps read as not installed. */
  private class RecordingEnsure(
    private val answers: Map<String, Outcome>,
    /** Runs while the device call is "in flight", so a test can move a clock across it. */
    private val onCall: () -> Unit = {},
  ) : (String) -> Outcome {
    val asked = mutableListOf<String>()
    override fun invoke(appId: String): Outcome {
      asked += appId
      onCall()
      return answers[appId] ?: Outcome.NotInstalled(appId)
    }
  }

  private val logged = mutableListOf<String>()

  private fun run(
    candidates: List<String>,
    ensure: RecordingEnsure,
    deviceId: TrailblazeDeviceId = android,
    sessionId: String = "s-1",
    gateEnabled: Boolean = true,
    unresolvedDeclaredTarget: String? = null,
    nowMs: Long = 0L,
    // A clock that can move while the device call is in flight — the shape a real timeout has.
    clock: () -> Long = { nowMs },
  ) = SessionAppCompileEnsurer.ensureForSession(
    sessionId = sessionId,
    deviceId = deviceId,
    candidateAppIds = candidates,
    unresolvedDeclaredTarget = unresolvedDeclaredTarget,
    gateEnabled = gateEnabled,
    nowMs = clock,
    log = { logged += it },
    ensure = ensure,
  )

  @AfterTest
  fun reset() = SessionAppCompileEnsurer.clearForTests()

  @Test
  fun `checks the first installed candidate and asks about no other`() {
    // Declared order is the target's priority; a device carrying only the internal build must not
    // have the debug id's absence read as "nothing to do".
    val ensure = RecordingEnsure(mapOf("com.example.internal" to Outcome.AlreadyCompiled(compiled)))
    run(listOf("com.example.debug", "com.example.internal", "com.example"), ensure)
    assertEquals(listOf("com.example.debug", "com.example.internal"), ensure.asked)
    assertEquals(1, logged.size)
    assertTrue("com.example.internal" in logged.single(), logged.single())
  }

  @Test
  fun `an iOS session never touches the device`() {
    val ensure = RecordingEnsure(mapOf("com.example" to Outcome.AlreadyCompiled(compiled)))
    run(listOf("com.example"), ensure, deviceId = ios)
    assertEquals(emptyList(), ensure.asked)
    assertEquals(emptyList(), logged)
  }

  @Test
  fun `the kill switch stops the check before the device is touched`() {
    val ensure = RecordingEnsure(mapOf("com.example" to Outcome.Compiled(broken, compiled, "verify", 1)))
    run(listOf("com.example"), ensure, gateEnabled = false)
    assertEquals(emptyList(), ensure.asked)
    assertEquals(emptyList(), logged)
  }

  @Test
  fun `a repeat call for the same session is free`() {
    val ensure = RecordingEnsure(mapOf("com.example" to Outcome.AlreadyCompiled(compiled)))
    run(listOf("com.example"), ensure, sessionId = "same")
    run(listOf("com.example"), ensure, sessionId = "same")
    assertEquals(listOf("com.example"), ensure.asked)
  }

  @Test
  fun `a second device under the same session id is still checked`() {
    // A multi-device trail reuses one session id across the launch device and every companion;
    // dedup keyed on session id alone would let the first device's call silently starve the rest.
    val ensure = RecordingEnsure(mapOf("com.example" to Outcome.AlreadyCompiled(compiled)))
    val other = TrailblazeDeviceId(instanceId = "emulator-5556", trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID)
    run(listOf("com.example"), ensure, deviceId = android, sessionId = "shared")
    run(listOf("com.example"), ensure, deviceId = other, sessionId = "shared")
    assertEquals(listOf("com.example", "com.example"), ensure.asked)
  }

  @Test
  fun `a trail whose declared target did not resolve is left alone and says why`() {
    // The candidates are the workspace fallback's ids, not the app the trail drives; compiling the
    // fallback would spend up to half a minute on an app the trail never launches.
    val ensure = RecordingEnsure(mapOf("com.example.fallback" to Outcome.Compiled(broken, compiled, "verify", 1)))
    run(listOf("com.example.fallback"), ensure, unresolvedDeclaredTarget = "sampleapp")
    assertEquals(emptyList(), ensure.asked)
    val line = logged.single()
    assertTrue("sampleapp" in line, line)
    assertTrue("com.example.fallback" in line, line)
  }

  @Test
  fun `declining with an unresolved target does not spend the other call site's turn`() {
    // Session resolution and the YAML runner fire for the same session id. If the FIRST of the two
    // to arrive declines (no resolved target yet), the session must still be open for the SECOND —
    // the one with a real target — or that session never gets checked at all.
    val ensure = RecordingEnsure(mapOf("com.example" to Outcome.AlreadyCompiled(compiled)))
    run(listOf("com.example.fallback"), ensure, sessionId = "same", unresolvedDeclaredTarget = "sampleapp")
    run(listOf("com.example"), ensure, sessionId = "same")
    assertEquals(listOf("com.example"), ensure.asked)
    assertEquals(2, logged.size)
  }

  @Test
  fun `an empty candidate list does not spend the other call site's turn either`() {
    val ensure = RecordingEnsure(mapOf("com.example" to Outcome.AlreadyCompiled(compiled)))
    run(emptyList(), ensure, sessionId = "same")
    run(listOf("com.example"), ensure, sessionId = "same")
    assertEquals(listOf("com.example"), ensure.asked)
  }

  @Test
  fun `no installed candidate says so and names them`() {
    val ensure = RecordingEnsure(emptyMap())
    run(listOf("com.example.debug", "com.example"), ensure)
    assertEquals(listOf("com.example.debug", "com.example"), ensure.asked)
    val line = logged.single()
    assertTrue("nothing to compile" in line, line)
    assertTrue("com.example.debug, com.example" in line, line)
  }

  @Test
  fun `blank candidates are never asked about`() {
    val ensure = RecordingEnsure(emptyMap())
    run(listOf("", "  "), ensure)
    assertEquals(emptyList(), ensure.asked)
    assertEquals(emptyList(), logged)
  }

  @Test
  fun `a candidate that is not a valid package name never reaches the device shell`() {
    // candidateAppIds comes from a target manifest — untrusted by the time it gets here — and
    // every shell transport downstream joins its argv into one command string. An id like
    // `com.safe; id` must be refused here, not discovered by whatever runs it.
    val ensure = RecordingEnsure(mapOf("com.example" to Outcome.AlreadyCompiled(compiled)))
    run(listOf("com.safe; id", "com.example"), ensure)
    assertEquals(listOf("com.example"), ensure.asked)
    assertEquals(2, logged.size)
    assertTrue("com.safe; id" in logged[0], logged[0])
  }

  @Test
  fun `a candidate list of only invalid package names checks nothing`() {
    val ensure = RecordingEnsure(emptyMap())
    run(listOf("`reboot`", "\$(whoami)"), ensure)
    assertEquals(emptyList(), ensure.asked)
    assertEquals(2, logged.size)
  }

  @Test
  fun `a repair says what it found, what it did and how long it took`() {
    val ensure = RecordingEnsure(mapOf("com.example" to Outcome.Compiled(broken, compiled, "verify", 27_562)))
    run(listOf("com.example"), ensure)
    val line = logged.single()
    assertTrue(line.startsWith("[dexopt] s-1: com.example had no compiled artifacts on emulator-5554"), line)
    assertTrue("status=run-from-apk" in line, line)
    assertTrue("27562ms" in line, line)
    assertTrue("status=verify" in line, line)
  }

  @Test
  fun `a healthy app is reported in one line`() {
    val ensure = RecordingEnsure(mapOf("com.example" to Outcome.AlreadyCompiled(compiled)))
    run(listOf("com.example"), ensure)
    assertEquals(
      "[dexopt] s-1: com.example already has compiled artifacts on emulator-5554 (x86_64: status=verify reason=install)",
      logged.single(),
    )
  }

  @Test
  fun `a compile that did not take names the manual fix for this device`() {
    val ensure = RecordingEnsure(
      mapOf("com.example" to Outcome.StillUncompiled(broken, broken, "verify", 900, "Error: permission denied")),
    )
    run(listOf("com.example"), ensure)
    val line = logged.single()
    assertTrue("adb -s emulator-5554 shell pm compile -m verify -f com.example" in line, line)
    assertTrue("permission denied" in line, line)
  }

  @Test
  fun `a compile that timed out names the manual fix too`() {
    val ensure = RecordingEnsure(mapOf("com.example" to Outcome.CompileTimedOut(broken, "verify", 300_000)))
    run(listOf("com.example"), ensure)
    val line = logged.single()
    assertTrue("300s" in line, line)
    assertTrue("adb -s emulator-5554 shell pm compile -m verify -f com.example" in line, line)
  }

  @Test
  fun `a dump that never came back is reported and is not read as the app being absent`() {
    val ensure = RecordingEnsure(mapOf("com.example" to Outcome.DumpTimedOut("com.example", 330_000)))
    run(listOf("com.example"), ensure)
    val line = logged.single()
    assertTrue("did not come back" in line, line)
    assertTrue("330s" in line, line)
  }

  @Test
  fun `a device that timed out once is not asked again by a later session`() {
    // The wedge that made the first session's compile time out is still there for the second — a
    // fixed-length wait repeated on every session start against the same broken device is exactly
    // the standing tax this exists to avoid.
    val ensure = RecordingEnsure(mapOf("com.example" to Outcome.CompileTimedOut(broken, "verify", 300_000)))
    run(listOf("com.example"), ensure, sessionId = "s-1")
    run(listOf("com.example"), ensure, sessionId = "s-2")
    assertEquals(listOf("com.example"), ensure.asked)
    assertEquals(2, logged.size)
    assertTrue("skipping" in logged[1], logged[1])
  }

  @Test
  fun `a device is retried once the cooldown has passed`() {
    // A reboot, reconnect or reinstall under the same serial looks identical to this object to a
    // wedged device that never recovered — there is no recovery signal to key off, so a bounded
    // cooldown stands in for one instead of skipping the device until the daemon restarts.
    val ensure = RecordingEnsure(mapOf("com.example" to Outcome.CompileTimedOut(broken, "verify", 300_000)))
    run(listOf("com.example"), ensure, sessionId = "s-1", nowMs = 0L)
    ensure.asked.clear()
    // Still inside the cooldown: skipped without touching the device.
    run(listOf("com.example"), ensure, sessionId = "s-2", nowMs = SessionAppCompileEnsurer.UNREACHABLE_COOLDOWN_MS - 1)
    assertEquals(emptyList(), ensure.asked)
    // Cooldown has now elapsed: the device gets one more chance.
    run(listOf("com.example"), ensure, sessionId = "s-3", nowMs = SessionAppCompileEnsurer.UNREACHABLE_COOLDOWN_MS)
    assertEquals(listOf("com.example"), ensure.asked)
  }

  @Test
  fun `the cooldown starts when the wait failed, not when the session started`() {
    // The failure being recorded IS a timeout, so the device call took most of the cooldown's
    // length to come back. Stamping it with the time the session started would hand the next
    // session an almost-expired cooldown and make it re-pay the same 5-minute wait.
    val compileTimeoutMs = 300_000L
    var clockMs = 0L
    // Entry at 0; the device call does not come back until the compile timeout has elapsed.
    val ensure = RecordingEnsure(
      answers = mapOf("com.example" to Outcome.CompileTimedOut(broken, "verify", compileTimeoutMs)),
      onCall = { clockMs = compileTimeoutMs },
    )
    run(listOf("com.example"), ensure, sessionId = "s-1", clock = { clockMs })
    ensure.asked.clear()

    // A session starting one tick before the cooldown expires, measured from the FAILURE.
    run(
      listOf("com.example"),
      ensure,
      sessionId = "s-2",
      nowMs = compileTimeoutMs + SessionAppCompileEnsurer.UNREACHABLE_COOLDOWN_MS - 1,
    )
    assertEquals(emptyList(), ensure.asked)

    run(
      listOf("com.example"),
      ensure,
      sessionId = "s-3",
      nowMs = compileTimeoutMs + SessionAppCompileEnsurer.UNREACHABLE_COOLDOWN_MS,
    )
    assertEquals(listOf("com.example"), ensure.asked)
  }

  @Test
  fun `a different device is not penalized by another device's timeout`() {
    val ensure = RecordingEnsure(mapOf("com.example" to Outcome.CompileTimedOut(broken, "verify", 300_000)))
    run(listOf("com.example"), ensure, sessionId = "s-1", deviceId = android)
    val otherDevice = TrailblazeDeviceId(instanceId = "emulator-9999", trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID)
    ensure.asked.clear()
    run(listOf("com.example"), ensure, sessionId = "s-2", deviceId = otherDevice)
    assertEquals(listOf("com.example"), ensure.asked)
  }

  @Test
  fun `a device that throws cannot fail the session start`() {
    // A device that dropped off between reservation and session start makes the opening dumpsys
    // throw. Letting that escape would fail the run and strand the device reservation.
    SessionAppCompileEnsurer.startForSession(
      sessionId = "s-gone",
      deviceId = android,
      candidateAppIds = listOf("com.example"),
      log = { logged += it },
      ensure = { throw IllegalStateException("device offline") },
    )
    val line = logged.single()
    assertTrue("skipped" in line, line)
    assertTrue("device offline" in line, line)
  }

  @Test
  fun `a device that threw is also not retried by a later session`() {
    var calls = 0
    val throwing: (String) -> Outcome = { calls++; throw IllegalStateException("device offline") }
    SessionAppCompileEnsurer.startForSession(
      sessionId = "s-gone-1",
      deviceId = android,
      candidateAppIds = listOf("com.example"),
      log = { logged += it },
      ensure = throwing,
    )
    SessionAppCompileEnsurer.startForSession(
      sessionId = "s-gone-2",
      deviceId = android,
      candidateAppIds = listOf("com.example"),
      log = { logged += it },
      ensure = throwing,
    )
    assertEquals(1, calls)
    assertEquals(2, logged.size)
    assertTrue("skipping" in logged[1], logged[1])
  }

  @Test
  fun `the gate is on unless the kill switch is set`() {
    assertTrue(EnsureAppCompiledGate.fromEnv(null))
    assertTrue(EnsureAppCompiledGate.fromEnv(""))
    assertTrue(EnsureAppCompiledGate.fromEnv("0"))
    assertTrue(EnsureAppCompiledGate.fromEnv("false"))
    assertFalse(EnsureAppCompiledGate.fromEnv("1"))
    assertFalse(EnsureAppCompiledGate.fromEnv("true"))
    assertFalse(EnsureAppCompiledGate.fromEnv("TRUE"))
  }
}
