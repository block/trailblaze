import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

// A CI failure has to be diagnosable from the build log alone. Gradle's default stops at
// `AssertionFailedError at Foo.kt:42`, dropping the assertion message — the part written for the
// reader — and these jobs upload no test report, so a failure that only reproduces on a loaded
// shared agent leaves nothing behind to investigate.
//
// FULL is the only non-default here. `events = [FAILED]`, `showStackTraces` and `showCauses` are
// already Gradle's defaults, so green runs print exactly what they printed before.
//
// `allprojects`, not `subprojects`: `build-logic` and `trailblaze-android-gradle` are
// single-project composite builds whose tests hang off the root project. Those are the Gradle
// TestKit functional tests, whose failures are the least readable in the repo.
//
// `AbstractTestTask`, not `Test`: KMP's native and JS test tasks (`iosSimulatorArm64Test`,
// `wasmJsBrowserTest`) are not `Test` subtypes and would otherwise be silently left out.
allprojects {
  tasks.withType<AbstractTestTask>().configureEach {
    testLogging {
      exceptionFormat = TestExceptionFormat.FULL
    }
  }
}
