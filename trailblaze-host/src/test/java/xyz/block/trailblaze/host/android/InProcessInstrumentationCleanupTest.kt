package xyz.block.trailblaze.host.android

import kotlin.test.assertEquals
import xyz.block.trailblaze.inprocessidle.InProcessIdle
import org.junit.Test

class InProcessInstrumentationCleanupTest {

  @Test
  fun `reads the attached instrumentations from dumpsys`() {
    val dumpsys = """
      |  Active instrumentation:
      |    Instrumentation #0: ActiveInstrumentation{3dfbff0 {com.example.app.test/androidx.test.runner.AndroidJUnitRunner} 1 procs}
      |  ProcessRecord{1a2b3c 12309:com.example.app/u0a155}
      |    mInstr=ActiveInstrumentation{3dfbff0 {com.example.app.test/androidx.test.runner.AndroidJUnitRunner} 1 procs}
    """.trimMargin()

    assertEquals(
      setOf("com.example.app.test/androidx.test.runner.AndroidJUnitRunner"),
      InProcessInstrumentationCleanup.activeInstrumentations(dumpsys),
    )
  }

  @Test
  fun `the turbo helper is not counted as attached`() {
    val dumpsys = """
      |    mInstr=ActiveInstrumentation{5e1a2f0 {xyz.block.trailblaze.inprocessidle.com.example.app/${InProcessIdle.INSTRUMENTATION_CLASS}} 1 procs}
      |    mInstr=ActiveInstrumentation{3dfbff0 {com.example.app.test/androidx.test.runner.AndroidJUnitRunner} 1 procs}
    """.trimMargin()

    assertEquals(
      setOf("com.example.app.test/androidx.test.runner.AndroidJUnitRunner"),
      InProcessInstrumentationCleanup.activeInstrumentations(dumpsys),
    )
  }

  @Test
  fun `nothing is attached when dumpsys lists no instrumentation`() {
    assertEquals(emptySet(), InProcessInstrumentationCleanup.activeInstrumentations("  ProcessRecord{1a2b3c 1:system/1000}"))
  }

  @Test
  fun `maps each test package to the app it instruments`() {
    val pmList = """
      |instrumentation:com.example.app.test/androidx.test.runner.AndroidJUnitRunner (target=com.example.app)
      |instrumentation:com.example.runner/androidx.test.runner.AndroidJUnitRunner (target=com.example.runner)
    """.trimMargin()

    assertEquals(
      mapOf(
        "com.example.app.test/androidx.test.runner.AndroidJUnitRunner" to "com.example.app",
        "com.example.runner/androidx.test.runner.AndroidJUnitRunner" to "com.example.runner",
      ),
      InProcessInstrumentationCleanup.instrumentationTargets(pmList),
    )
  }

  @Test
  fun `a target that is not a package name never reaches the shell, and does not replace the active one`() {
    val pmList = """
      |instrumentation:com.example.app.test/androidx.test.runner.AndroidJUnitRunner (target=com.example.app)
      |instrumentation:com.example.app.test/.Other (target=x;reboot)
    """.trimMargin()

    assertEquals(
      mapOf("com.example.app.test/androidx.test.runner.AndroidJUnitRunner" to "com.example.app"),
      InProcessInstrumentationCleanup.instrumentationTargets(pmList),
    )
  }

  @Test
  fun `a runner inside its own test package matches whether pm prints it short or in full`() {
    val dumpsys = """
      |    mInstr=ActiveInstrumentation{3dfbff0 {com.example.app.test/com.example.app.test.InProcessRunner} 1 procs}
    """.trimMargin()
    val pmList = """
      |instrumentation:com.example.app.test/.InProcessRunner (target=com.example.app)
    """.trimMargin()

    val active = InProcessInstrumentationCleanup.activeInstrumentations(dumpsys)
    val targets = InProcessInstrumentationCleanup.instrumentationTargets(pmList)

    assertEquals(listOf("com.example.app"), active.mapNotNull { targets[it] })
  }
}
