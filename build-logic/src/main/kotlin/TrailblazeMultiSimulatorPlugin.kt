import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test

/**
 * Gradle plugin that configures multi-simulator parallel test execution for iOS tests.
 *
 * When SIMULATOR_COUNT > 1 (set by ios_simulator_boot.sh), this plugin sets maxParallelForks
 * so Gradle forks multiple test workers. Each worker gets a unique `org.gradle.test.worker` ID.
 *
 * Multi-simulator mode is only enabled when there are enough tests to benefit from parallelism.
 * With 2 simulators and ~10% overhead per test, the breakeven is around 3 tests.
 *
 * Device assignment is handled by BaseHostTrailblazeTest which auto-detects connected devices
 * via TrailblazeDeviceService and uses the Gradle worker ID to distribute across them.
 */
class TrailblazeMultiSimulatorPlugin : Plugin<Project> {

  companion object {
    /** Maximum number of parallel simulators. Conservative limit to reduce resource contention. */
    const val MAX_SIMULATORS = 2

    /** Minimum number of test classes needed to enable multi-simulator mode. */
    const val MIN_TESTS_FOR_MULTI_SIM = 3
  }

  override fun apply(project: Project) {
    val simulatorCountProvider = project.providers
      .environmentVariable("SIMULATOR_COUNT")
      .map { it.toIntOrNull()?.coerceIn(1, MAX_SIMULATORS) ?: 1 }
      .orElse(1)

    project.tasks.withType(Test::class.java).configureEach { task ->
      task.doFirst {
        val simulatorCount = simulatorCountProvider.get()

        if (simulatorCount > 1) {
          // Count the test classes that Gradle discovered for this task
          val testCount = task.candidateClassFiles.files.size

          if (testCount >= MIN_TESTS_FOR_MULTI_SIM) {
            task.maxParallelForks = simulatorCount
            project.logger.lifecycle(
              "Multi-simulator mode: running $testCount tests across $simulatorCount simulators"
            )
          } else {
            task.maxParallelForks = 1
            project.logger.lifecycle(
              "Multi-simulator mode: skipped for $testCount tests " +
                "(minimum $MIN_TESTS_FOR_MULTI_SIM required), using single simulator"
            )
          }
        }
      }
    }
  }
}
