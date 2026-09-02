package xyz.block.trailblaze.inprocess.apk

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every verdict branch, over plain inputs.
 *
 * The contract is the verdict's *status* and the *set of reason codes* it names — those are what a
 * caller gates on and what the fingerprint records. Message wording is asserted only where the
 * message carries a fact a caller acts on (which library, which floor, that a reason is contingent),
 * never as a full-string match.
 */
class ProbeVerdictRulesTest {

  @Test
  fun `a clean app paired with a shell is GO with no reasons`() {
    val verdict = evaluate()
    assertEquals(ProbeStatus.GO, verdict.status)
    assertEquals(emptyList(), verdict.reasons)
  }

  @Test
  fun `without a shell the verdict is capped at INCOMPLETE and never GO`() {
    val verdict = evaluate(shellGiven = false, overlap = null)
    assertEquals(ProbeStatus.INCOMPLETE, verdict.status)
    assertEquals(listOf(ProbeReasonCode.DEX_OVERLAP_UNCHECKED), verdict.reasons.map { it.code })
  }

  @Test
  fun `no launcher activity disqualifies`() {
    val verdict = evaluate(manifest = manifest(launcherActivity = null))
    assertEquals(ProbeStatus.NO_GO, verdict.status)
    assertTrue(ProbeReasonCode.NO_LAUNCHER_ACTIVITY in verdict.reasons.map { it.code })
  }

  @Test
  fun `a provider of any kind is recorded and does not disqualify`() {
    // Measured, not assumed: an instrumented process installs the app's own ContentProviders and
    // runs its androidx.startup initializers before the first test line, on API 34 and API 36. An
    // app that initializes through a provider is therefore an ordinary app, not an unfit one.
    val verdict = evaluate(
      manifest = manifest(
        providers = listOf(
          DeclaredProvider("com.example.MyProvider", "com.example.authority", androidxStartup = false),
          DeclaredProvider("androidx.startup.InitializationProvider", "a.androidx-startup", androidxStartup = true),
        ),
      ),
    )
    assertEquals(ProbeStatus.GO, verdict.status)
    assertEquals(emptyList(), verdict.reasons)
  }

  @Test
  fun `a launcher in another process disqualifies and names the process`() {
    // The launch itself would succeed. What fails is the wait: the harness polls
    // ActivityLifecycleMonitorRegistry, which only sees Activities in its own process, so an
    // unchecked app of this shape times out with nothing naming the cause.
    val verdict = evaluate(manifest = manifest(launcherProcess = ":ui"))
    assertEquals(ProbeStatus.NO_GO, verdict.status)
    val reason = verdict.reasons.single { it.code == ProbeReasonCode.LAUNCHER_IN_OTHER_PROCESS }
    assertTrue(reason.message.contains(":ui"), reason.message)
    assertTrue(reason.message.contains("com.example.MainActivity"), reason.message)
  }

  @Test
  fun `a launcher in the default process does not disqualify`() {
    assertEquals(ProbeStatus.GO, evaluate(manifest = manifest(launcherProcess = null)).status)
  }

  @Test
  fun `an exact era below the floor disqualifies and names both versions`() {
    val verdict = evaluate(
      eras = listOf(era(version = "1.9.0")),
      floor = floor("1.11.4"),
    )
    assertEquals(ProbeStatus.NO_GO, verdict.status)
    val reason = verdict.reasons.single { it.code == ProbeReasonCode.ERA_BELOW_SHELL_FLOOR }
    assertTrue(reason.message.contains("1.9.0"), reason.message)
    assertTrue(reason.message.contains("1.11.4"), reason.message)
  }

  @Test
  fun `an exact era at the floor passes`() {
    val verdict = evaluate(eras = listOf(era(version = "1.11.4")), floor = floor("1.11.4"))
    assertEquals(ProbeStatus.GO, verdict.status)
  }

  @Test
  fun `an upper bound below the floor disqualifies without an exact version`() {
    // The shape a real probed app produced: no packaged version file, so the era is known only as
    // a bound, and the bound alone settles the floor question.
    val verdict = evaluate(
      eras = listOf(era(version = null, belowVersion = "1.10.0")),
      floor = floor("1.11.4"),
    )
    assertEquals(ProbeStatus.NO_GO, verdict.status)
    assertTrue(ProbeReasonCode.ERA_BELOW_SHELL_FLOOR in verdict.reasons.map { it.code })
  }

  @Test
  fun `an upper bound EQUAL to the floor still disqualifies`() {
    // `belowVersion` is strict: "below 1.11.4" means the app is not 1.11.4, so a floor of 1.11.4 is
    // not met. Reading the bound as inclusive reports ERA_UNDETERMINABLE instead — a different
    // reason, a different owner, and a message that tells the reader to pass --declared-deps for a
    // question the dex already answered.
    val verdict = evaluate(
      eras = listOf(era(version = null, belowVersion = "1.11.4")),
      floor = floor("1.11.4"),
    )
    assertEquals(ProbeStatus.NO_GO, verdict.status)
    assertEquals(
      listOf(ProbeReasonCode.ERA_BELOW_SHELL_FLOOR),
      verdict.reasons.map { it.code },
    )
  }

  @Test
  fun `an upper bound ABOVE the floor leaves the floor question open`() {
    // Negative control for the case above: "below 1.12.0" with a floor of 1.11.4 is consistent with
    // both meeting and missing the floor, so the honest answer is undeterminable, not a refusal.
    val verdict = evaluate(
      eras = listOf(era(version = null, belowVersion = "1.12.0")),
      floor = floor("1.11.4"),
    )
    assertEquals(
      listOf(ProbeReasonCode.ERA_UNDETERMINABLE),
      verdict.reasons.map { it.code },
    )
  }

  @Test
  fun `a marker-derived floor failure says the bound is unreliable on a minified APK`() {
    // R8 can strip an unused marker class, which makes an absent marker look like an older library.
    // The reason still fires — it is report-only in the farm gate — but it must not read as proof.
    val verdict = evaluate(
      eras = listOf(era(version = null, belowVersion = "1.10.0")),
      floor = floor("1.11.4"),
    )
    val reason = verdict.reasons.single { it.code == ProbeReasonCode.ERA_BELOW_SHELL_FLOOR }
    assertTrue(reason.message.contains("R8"), reason.message)
  }

  @Test
  fun `a lower bound at or above the floor passes without an exact version`() {
    val verdict = evaluate(
      eras = listOf(era(version = null, atLeastVersion = "1.11.4")),
      floor = floor("1.11.4"),
    )
    assertEquals(ProbeStatus.GO, verdict.status)
  }

  @Test
  fun `an era with no bounds at all is undeterminable, not assumed fine`() {
    val verdict = evaluate(eras = listOf(era(version = null)), floor = floor("1.11.4"))
    assertEquals(ProbeStatus.NO_GO, verdict.status)
    val reason = verdict.reasons.single { it.code == ProbeReasonCode.ERA_UNDETERMINABLE }
    // The message has to tell the caller how to resolve it, or a red build is a dead end.
    assertTrue(reason.message.contains("--declared-deps"), reason.message)
  }

  @Test
  fun `an absent library is not checked against the floor`() {
    val verdict = evaluate(
      eras = listOf(era(version = null, present = false)),
      floor = floor("1.11.4"),
    )
    assertEquals(ProbeStatus.GO, verdict.status)
  }

  @Test
  fun `a library with no floor is not checked`() {
    val verdict = evaluate(eras = listOf(era(version = "0.0.1")), floor = ShellFloor())
    assertEquals(ProbeStatus.GO, verdict.status)
  }

  @Test
  fun `a dex overlap with the shell disqualifies and names a sample class`() {
    val verdict = evaluate(
      overlap = DexOverlap(
        shell = "shell.apk",
        classCount = 2,
        classes = listOf("androidx.compose.ui.Modifier", "androidx.compose.ui.node.Snake"),
        truncated = false,
      ),
    )
    assertEquals(ProbeStatus.NO_GO, verdict.status)
    val reason = verdict.reasons.single { it.code == ProbeReasonCode.DEX_OVERLAP_WITH_SHELL }
    assertTrue(reason.message.contains("androidx.compose.ui.Modifier"), reason.message)
  }

  @Test
  fun `an empty dex overlap does not disqualify`() {
    val verdict = evaluate(
      overlap = DexOverlap(shell = "shell.apk", classCount = 0, classes = emptyList(), truncated = false),
    )
    assertEquals(ProbeStatus.GO, verdict.status)
  }

  @Test
  fun `several disqualifiers are all reported, not just the first`() {
    val verdict = evaluate(
      manifest = manifest(launcherActivity = "com.example.MainActivity", launcherProcess = ":ui"),
      eras = listOf(era(version = "1.0.0")),
      floor = floor("1.11.4"),
      overlap = DexOverlap("shell.apk", 1, listOf("com.example.Shared"), truncated = false),
    )
    assertEquals(ProbeStatus.NO_GO, verdict.status)
    assertEquals(
      setOf(
        ProbeReasonCode.LAUNCHER_IN_OTHER_PROCESS,
        ProbeReasonCode.ERA_BELOW_SHELL_FLOOR,
        ProbeReasonCode.DEX_OVERLAP_WITH_SHELL,
      ),
      verdict.reasons.map { it.code }.toSet(),
    )
  }

  @Test
  fun `the shipped floor refuses an app on the previous Compose Runtime line`() {
    // Over the REAL DEFAULT_DECLARED_SHELL_FLOOR, not a floor the test invents. The shell packages
    // Compose 1.11.x tooling that runs against the app's runtime, so an app still on 1.10.x cannot
    // host it — and with no floor entry for the runtime that app cleared with no era reason at all.
    val verdict = evaluate(
      eras = listOf(
        LibraryEra(
          library = "androidx.compose.runtime:runtime",
          present = true,
          atLeastVersion = "1.10.0",
          belowVersion = "1.11.0",
          source = EraSource.DEX_MARKER,
          evidence = "test",
        ),
      ),
      floor = DEFAULT_DECLARED_SHELL_FLOOR,
    )
    assertEquals(ProbeStatus.NO_GO, verdict.status)
    assertEquals(listOf(ProbeReasonCode.ERA_BELOW_SHELL_FLOOR), verdict.reasons.map { it.code })
  }

  @Test
  fun `the shipped floor clears an app already on the Compose Runtime line the shell needs`() {
    // Negative control, and the one that keeps the floor honest about precision: the app's dex can
    // only say "at least 1.11.0", and that has to be enough. A floor stated at the shell's own patch
    // version instead would report ERA_UNDETERMINABLE here — an ENFORCED code — and fail this app.
    val verdict = evaluate(
      eras = listOf(
        LibraryEra(
          library = "androidx.compose.runtime:runtime",
          present = true,
          atLeastVersion = "1.11.0",
          source = EraSource.DEX_MARKER,
          evidence = "test",
        ),
      ),
      floor = DEFAULT_DECLARED_SHELL_FLOOR,
    )
    assertEquals(ProbeStatus.GO, verdict.status)
  }

  private fun evaluate(
    manifest: ManifestFacts = manifest(),
    eras: List<LibraryEra> = emptyList(),
    floor: ShellFloor = ShellFloor(),
    overlap: DexOverlap? = DexOverlap("shell.apk", 0, emptyList(), truncated = false),
    shellGiven: Boolean = true,
  ) = ProbeVerdictRules.evaluate(manifest, eras, floor, overlap, shellGiven)

  private fun manifest(
    launcherActivity: String? = "com.example.MainActivity",
    launcherProcess: String? = null,
    providers: List<DeclaredProvider> = emptyList(),
  ) = ManifestFacts(
    packageName = "com.example",
    launcherActivity = launcherActivity,
    launcherProcess = launcherProcess,
    debuggable = true,
    providers = providers,
  )

  private fun era(
    version: String?,
    atLeastVersion: String? = null,
    belowVersion: String? = null,
    present: Boolean = true,
  ) = LibraryEra(
    library = LIBRARY,
    present = present,
    version = version,
    atLeastVersion = atLeastVersion,
    belowVersion = belowVersion,
    source = EraSource.DEX_MARKER,
    evidence = "test",
  )

  private fun floor(minVersion: String) = ShellFloor(
    listOf(LibraryFloor(library = LIBRARY, minVersion = minVersion, why = "the test says so")),
  )

  private companion object {
    const val LIBRARY = "androidx.compose.ui:ui"
  }
}
