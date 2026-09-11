package xyz.block.trailblaze.host.turbo

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which of a session's candidate apps turbo attaches to.
 *
 * A target can declare several applicationIds for one platform (debug, internal, release), and
 * only some are signed with a key we have a matching helper for. Picking the wrong one means the
 * session silently runs at normal speed.
 */
class SessionTurboAttacherTest {

  @Test
  fun `picks the first candidate a helper is bundled for`() {
    val chosen = SessionTurboAttacher.chooseAppId(
      candidateAppIds = listOf("com.example.debug", "com.example"),
      isAttachable = { it == "com.example.debug" },
    )
    assertEquals("com.example.debug", chosen)
  }

  @Test
  fun `skips leading candidates with no bundled helper`() {
    // The declared order is the target's priority, but a release build we hold no key for cannot
    // be helped — falling through to the next candidate is the difference between turbo working
    // and turbo silently doing nothing.
    val chosen = SessionTurboAttacher.chooseAppId(
      candidateAppIds = listOf("com.example", "com.example.debug"),
      isAttachable = { it == "com.example.debug" },
    )
    assertEquals("com.example.debug", chosen)
  }

  @Test
  fun `declared order wins when several candidates are bundled`() {
    val chosen = SessionTurboAttacher.chooseAppId(
      candidateAppIds = listOf("com.example.internal", "com.example.debug"),
      isAttachable = { true },
    )
    assertEquals("com.example.internal", chosen)
  }

  @Test
  fun `no bundled helper for any candidate chooses nothing`() {
    assertNull(
      SessionTurboAttacher.chooseAppId(
        candidateAppIds = listOf("com.example", "com.example.beta"),
        isAttachable = { false },
      ),
    )
  }

  @Test
  fun `no candidates at all chooses nothing`() {
    // A target that declares no applicationId for this platform, or a session with no target.
    assertNull(SessionTurboAttacher.chooseAppId(candidateAppIds = emptyList(), isAttachable = { true }))
  }

  @Test
  fun `a blank candidate is never chosen`() {
    // An empty string would resolve to an empty helper flavor and install the wrong build.
    assertNull(SessionTurboAttacher.chooseAppId(candidateAppIds = listOf("", "  "), isAttachable = { true }))
  }

  @Test
  fun `skips a candidate whose helper is bundled but whose app is not installed`() {
    // The case that made "bundled" too weak a test on its own: one target declares a debug and an
    // internal applicationId, this build carries a helper for both, and the device in front of us
    // has only the internal one. Choosing the debug id would fail the attach and lose the speedup
    // on a device that could have had it.
    val installed = setOf("com.example.internal")
    val chosen = SessionTurboAttacher.chooseAppId(
      candidateAppIds = listOf("com.example.debug", "com.example.internal"),
      isAttachable = { it in installed },
    )
    assertEquals("com.example.internal", chosen)
  }

  // --- the off switch ---

  @Test
  fun `turbo on attaches`() {
    assertEquals(
      SessionTurboAttacher.Action.ATTACH,
      SessionTurboAttacher.decide(gateEnabled = true, raceCurrentlyOn = { false }),
    )
  }

  @Test
  fun `turbo off clears a device that is still turboed`() {
    // The whole point of the branch: the switch is device state that survives the session that
    // set it, so `config turbo false` has to actively undo it or it means nothing.
    assertEquals(
      SessionTurboAttacher.Action.CLEAR,
      SessionTurboAttacher.decide(gateEnabled = false, raceCurrentlyOn = { true }),
    )
  }

  @Test
  fun `turbo off leaves a device that was never turboed completely alone`() {
    assertEquals(
      SessionTurboAttacher.Action.NOTHING,
      SessionTurboAttacher.decide(gateEnabled = false, raceCurrentlyOn = { false }),
    )
  }

  @Test
  fun `turbo on never asks the device whether the race is already on`() {
    // Guards the cost: every session on every Android device runs this, and the enabled path must
    // not add an adb round trip to ask a question it does not act on.
    var asked = false
    SessionTurboAttacher.decide(gateEnabled = true, raceCurrentlyOn = { asked = true; true })
    assertFalse(asked)
  }

  // --- two call sites, one session ---

  @AfterTest
  fun resetSessions() = SessionTurboAttacher.clearForTests()

  @Test
  fun `the first call for a session runs`() {
    assertTrue(SessionTurboAttacher.shouldRun("s1", runOverride = null))
  }

  @Test
  fun `a repeat call with no per-run override is free`() {
    SessionTurboAttacher.shouldRun("s1", runOverride = null)
    assertFalse(SessionTurboAttacher.shouldRun("s1", runOverride = null))
  }

  @Test
  fun `an explicit override still applies to a session another call site already set up`() {
    // The bug this guards: session resolution (MCP, Trail Runner, recording screen) has no CLI
    // flag to pass, so when it runs first, plain first-call-wins deduping would drop the
    // `--no-turbo` that the YAML runner passes moments later — silently ignoring the flag whose
    // whole purpose is a clean comparison run on a host with turbo switched on globally.
    SessionTurboAttacher.shouldRun("s1", runOverride = null)
    assertTrue(SessionTurboAttacher.shouldRun("s1", runOverride = false))
  }

  @Test
  fun `an explicit override applies at most once per session`() {
    SessionTurboAttacher.shouldRun("s1", runOverride = null)
    SessionTurboAttacher.shouldRun("s1", runOverride = false)
    assertFalse(SessionTurboAttacher.shouldRun("s1", runOverride = false))
  }

  @Test
  fun `a call with no override cannot undo an override that already applied`() {
    SessionTurboAttacher.shouldRun("s1", runOverride = true)
    assertFalse(SessionTurboAttacher.shouldRun("s1", runOverride = null))
  }

  @Test
  fun `sessions do not share their override budget`() {
    SessionTurboAttacher.shouldRun("s1", runOverride = false)
    assertTrue(SessionTurboAttacher.shouldRun("s2", runOverride = false))
  }

  @Test
  fun `a decline overrules an attach an earlier call site already made`() {
    // Session resolution runs first on the Trail Runner, MCP and recording-replay paths, and it
    // knows only the workspace-default target — so it is the call that attaches to the fallback
    // app. Only the runner's later call knows the trail's declared target did not resolve. Without
    // its own budget that decline is dropped whenever no explicit flag was passed, leaving
    // `TRAILBLAZE_TURBO` / `config turbo true` runs attached to an app the trail never drives.
    assertTrue(SessionTurboAttacher.shouldRun("s1", runOverride = null))
    assertTrue(SessionTurboAttacher.shouldRun("s1", runOverride = null, declining = true))
  }

  @Test
  fun `a decline gets one chance, not one per call`() {
    SessionTurboAttacher.shouldRun("s1", runOverride = null, declining = true)
    assertFalse(SessionTurboAttacher.shouldRun("s1", runOverride = null, declining = true))
  }

  // --- an unresolved declared target ---

  @Test
  fun `declines when the candidates came from a fallback target, and says so`() {
    // The run this guards: a trail whose `config.target` named nothing loaded fell back to the
    // workspace default, so the candidate app was an unrelated installed app. Attaching to it
    // logged "turbo on" for an app the trail never drives, and a benchmark read that as a
    // successful turbo run. `--turbo` is forced on so the decline is what stops the attach, not
    // the gate.
    //
    // The message is built by the attacher, not handed to it, so the assertions below are about
    // what an operator will actually read rather than about a string this test supplied.
    val logged = mutableListOf<String>()
    SessionTurboAttacher.startForSession(
      sessionId = "s-unresolved-target",
      deviceId = TrailblazeDeviceId(
        instanceId = "emulator-does-not-exist",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      ),
      candidateAppIds = listOf("com.example.fallback"),
      runOverride = true,
      unresolvedDeclaredTarget = "sampleapp",
      log = { logged += it },
    )
    val decline = assertNotNull(
      logged.singleOrNull { it.contains("not attaching turbo") },
      "the decline should be logged once: $logged",
    )
    assertTrue(decline.contains("'sampleapp'"), "the decline should name the declared target: $decline")
    assertTrue(decline.contains("com.example.fallback"), "the decline should name the app it skipped: $decline")
    assertFalse(logged.any { it.contains("turbo on for") }, "a declined session must never report turbo on: $logged")
    assertFalse(logged.any { it.contains("no signature-matched helper") }, "a decline is not a missing helper: $logged")
  }

  @Test
  fun `turbo off is decided before any decline, so it cannot report a decline instead`() {
    // `--no-turbo` outranks everything, or it is not a switch: an unresolved target must not
    // change what the off switch does. Asserting the absence of both the decline line and the
    // turbo-on line is the honest bound here — this device does not exist, so what the off switch
    // does to a real device's state is not what this test can observe.
    val logged = mutableListOf<String>()
    SessionTurboAttacher.startForSession(
      sessionId = "s-unresolved-target-off",
      deviceId = TrailblazeDeviceId(
        instanceId = "emulator-does-not-exist",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      ),
      candidateAppIds = listOf("com.example.fallback"),
      runOverride = false,
      unresolvedDeclaredTarget = "sampleapp",
      log = { logged += it },
    )
    assertFalse(logged.any { it.contains("not attaching turbo") }, "off-switch handling comes first: $logged")
    assertFalse(logged.any { it.contains("turbo on for") }, "turbo off must never report turbo on: $logged")
  }

  // --- fail open ---

  @Test
  fun `a device that is not there does not fail the session start`() {
    // The consequence that makes this more than tidiness: `startForSession` runs inside session
    // resolution, so an escaping adb exception failed the session start AND left the device
    // reservation held — rejecting the NEXT run on that device as busy. An accelerator no caller
    // waits on must not be able to fail a run, let alone the run after it.
    val logged = mutableListOf<String>()
    SessionTurboAttacher.startForSession(
      sessionId = "s-absent-device",
      deviceId = TrailblazeDeviceId(
        instanceId = "emulator-does-not-exist",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      ),
      candidateAppIds = listOf("com.example.debug"),
      log = { logged += it },
    )
    // Reaching here at all is the assertion. The log is checked too so a future refactor cannot
    // satisfy it by silently doing nothing on every device.
    assertTrue(
      logged.any { it.contains("turbo setup skipped") },
      "the skip should be explained, not silent: $logged",
    )
  }

  @Test
  fun `a skip we could not verify never claims the run is at normal speed`() {
    // An unreadable device tells us nothing about whether an earlier session left turbo switched
    // on, and the switch outlives the session that set it. Reporting "running at normal speed"
    // there is a claim we have not established — and the one that matters, because the driver's
    // idle request is not app-scoped, so a switch left on can end a wait early on whatever
    // process still holds the detector port.
    val logged = mutableListOf<String>()
    SessionTurboAttacher.startForSession(
      sessionId = "s-unverified-skip",
      deviceId = TrailblazeDeviceId(
        instanceId = "emulator-does-not-exist",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      ),
      candidateAppIds = listOf("com.example.debug"),
      log = { logged += it },
    )
    assertFalse(
      logged.any { it.contains("normal speed") },
      "an unverified skip must not report a normal-speed run: $logged",
    )
  }
}
