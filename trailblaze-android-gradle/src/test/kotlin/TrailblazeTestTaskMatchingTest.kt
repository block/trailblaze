import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which AGP tasks the plugin hooks, per module type.
 *
 * These matchers decide whether the shell codegen and the staged assets are in place before AGP
 * compiles and packages. A family that stops matching does not fail the build — it produces a test
 * APK missing generated shells or missing trail assets, which reads as "no tests found" or as a
 * trail that silently kept its previous contents.
 *
 * The `com.android.test` half (empty infix) exists because that module's single variant IS the test
 * one, so AGP names its tasks `compileDebugKotlin` / `mergeDebugAssets` — nothing an `AndroidTest`
 * matcher would ever see.
 */
class TrailblazeTestTaskMatchingTest {

  @Test
  fun `library module hooks the androidTest compile and lint families`() {
    listOf(
      "compileDebugAndroidTestKotlin",
      "compileDebugAndroidTestJavaWithJavac",
      "generateDebugAndroidTestLintModel",
      "lintAnalyzeDebugAndroidTest",
      "lintReportDebugAndroidTest",
    )
      .forEach { assertTrue(matchesTestCompileOrLintTask(it, "AndroidTest"), it) }
  }

  @Test
  fun `library module leaves the main variant's own tasks alone`() {
    // Hooking these would make every app compile wait on test codegen, and on an application
    // module it would be a configuration cycle.
    listOf("compileDebugKotlin", "generateDebugLintModel", "lintAnalyzeDebug", "lintReportDebug")
      .forEach { assertFalse(matchesTestCompileOrLintTask(it, "AndroidTest"), it) }
  }

  @Test
  fun `test module hooks its main compile and lint families`() {
    listOf(
      "compileDebugKotlin",
      "compileDebugJavaWithJavac",
      "generateDebugLintModel",
      "lintAnalyzeDebug",
      "lintReportDebug",
    )
      .forEach { assertTrue(matchesTestCompileOrLintTask(it, ""), it) }
  }

  @Test
  fun `library module hooks the androidTest asset families`() {
    listOf("mergeDebugAndroidTestAssets", "packageDebugAndroidTestAssets")
      .forEach { assertTrue(matchesTestAssetOrLintTask(it, "AndroidTest"), it) }
    // The app's own assets are not where a test APK's trails go.
    listOf("mergeDebugAssets", "packageDebugAssets")
      .forEach { assertFalse(matchesTestAssetOrLintTask(it, "AndroidTest"), it) }
  }

  @Test
  fun `test module hooks its main asset families`() {
    listOf("mergeDebugAssets", "packageDebugAssets")
      .forEach { assertTrue(matchesTestAssetOrLintTask(it, ""), it) }
  }

  @Test
  fun `neither matcher claims an unrelated task`() {
    listOf("assembleDebug", "test", "dexBuilderDebug", "processDebugManifest").forEach {
      assertFalse(matchesTestCompileOrLintTask(it, "AndroidTest"), it)
      assertFalse(matchesTestCompileOrLintTask(it, ""), it)
      assertFalse(matchesTestAssetOrLintTask(it, "AndroidTest"), it)
      assertFalse(matchesTestAssetOrLintTask(it, ""), it)
    }
  }
}
