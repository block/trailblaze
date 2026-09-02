package xyz.block.trailblaze.android.test

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import xyz.block.trailblaze.android.test.tools.AndroidTestExecutableTool
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.createTrailblazeYaml

/**
 * Contract for the trail assets this module's instrumentation tests replay.
 *
 * Every failure below is one a device run reports late and vaguely, or not at all:
 * - A misspelled tool name decodes to a raw `OtherTrailblazeTool` instead of failing, and only
 *   surfaces as an unhelpful dispatch error mid-run.
 * - A misspelled classifier key drops the whole recording, and the step then falls through to the
 *   LLM path — where this driver's runner refuses, one step later than the real mistake.
 * - A driver pin that doesn't name ANDROID_TEST makes the rule reject the trail on device.
 */
class AndroidTestTrailAssetContractTest {

  /** What `AndroidTestLoggingRule.defaultDeviceClassifiers()` reports on a phone emulator. */
  private val phoneClassifiers =
    listOf(TrailblazeDeviceClassifier("android"), TrailblazeDeviceClassifier("phone"))

  private val trailblazeYaml = createTrailblazeYaml()

  /**
   * Every tree of ANDROID_TEST trails in the repo, whichever module ships them.
   *
   * The sample app's are here rather than in a test of their own because they are the same
   * contract: they replay on this driver, and the farm lane that runs them (staged into a
   * `<app>-android-inprocess` APK by `on_demand_android_trails_farm.sh`) has no other check
   * standing between a typo and a device.
   */
  private val trailRoots: List<File> =
    listOf(
      File("src/androidTest/assets/trails"),
      // Same relative path from this module in every checkout that ships both.
      File("../examples/android-sample-app/trails/android-test"),
    )

  private val trailAssets: List<File> =
    trailRoots
      .flatMap { root -> root.walkTopDown().filter { it.isFile && it.name.endsWith(".trail.yaml") } }
      .sortedBy { it.path }

  @Test
  fun `every instrumentation trail asset is replayable on this driver`() {
    trailRoots.forEach { root ->
      assertTrue(
        root.isDirectory,
        "No trail directory at ${root.path}. If it moved, this test is passing vacuously for that " +
          "tree — point it at the new location.",
      )
    }
    assertTrue(
      trailAssets.isNotEmpty(),
      "No trail assets found under ${trailRoots.map { it.path }}. If they moved, this test is " +
        "passing vacuously — point it at the new location.",
    )
    trailAssets.forEach { asset ->
      val trailItems = trailblazeYaml.decodeTrail(asset.readText(), phoneClassifiers)

      val driver = trailblazeYaml.extractTrailConfig(trailItems)?.driver
      assertEquals(
        AndroidTestTrailblazeRule.ANDROID_TEST_DRIVER_NAME,
        driver,
        "${asset.name} resolves `driver: $driver` for an Android phone. The rule itself also " +
          "accepts `${AndroidTestTrailblazeRule.ANDROID_TEST_DRIVER_YAML_KEY}` and ignores case; " +
          "committed assets are held to the one spelling so that grepping for the driver finds " +
          "every trail running on it.",
      )

      val steps = trailItems.flatMap { item ->
        when (item) {
          is TrailYamlItem.PromptsTrailItem -> item.promptSteps
          is TrailYamlItem.TrailheadTrailItem -> listOf(item.trailhead.toPromptStep())
          else -> emptyList()
        }
      }
      assertTrue(steps.isNotEmpty(), "${asset.name} lowered to no steps at all.")

      steps.forEach { step ->
        // Mirrors TrailblazeRunnerUtil's own replay predicate. A step that fails it is handed to
        // the runner, and this driver's runner exists to refuse.
        assertTrue(
          step.isReplayable(),
          "${asset.name} step \"${step.prompt}\" carries no recording for $phoneClassifiers, so " +
            "it would need an LLM. Check the classifier key under `recording:`.",
        )
      }

      val recordedTools = steps.flatMap { it.recording?.tools.orEmpty() }
      assertTrue(recordedTools.isNotEmpty(), "${asset.name} recorded zero tools.")
      recordedTools.forEach { wrapper ->
        if (wrapper.trailblazeTool !is AndroidTestExecutableTool) {
          fail(
            "${asset.name} records `${wrapper.name}`, which decoded to " +
              "${wrapper.trailblazeTool::class.simpleName} rather than a tool this driver can " +
              "dispatch. Unknown tool names decode to a raw tool instead of failing, so this is " +
              "usually a typo.",
          )
        }
      }
    }
  }

  /**
   * Every asset in THIS module has a test method behind it.
   *
   * `build_on_demand_config.py` exempts this module from the on-demand trail planner
   * (`ASSET_TRAIL_MODULES_COVERED_ELSEWHERE`) because its own instrumentation step already replays
   * these trails. An asset with no method to run it makes that claim false in the one way nothing
   * reports: the planner skips it by policy, the device run never names it, and the file reads as
   * covered while never running once.
   *
   * Scoped to this module. The sample app's tree has no source files to find — the
   * `xyz.block.trailblaze.android-gradle` plugin generates a `@Test` shell per trail at build time,
   * so its assets cannot go unclaimed.
   */
  @Test
  fun `every trail asset in this module is claimed by a test method`() {
    val assetRoot = File("src/androidTest/assets/trails")
    val sourceRoot = File("src/androidTest/java")
    val assets =
      assetRoot.walkTopDown().filter { it.isFile && it.name.endsWith(".trail.yaml") }.toList()
    assertTrue(
      assets.isNotEmpty(),
      "No trail assets under ${assetRoot.path}. If they moved, this test is passing vacuously — " +
        "point it at the new location.",
    )

    assets.forEach { asset ->
      val className = asset.parentFile.name
      val methodName = asset.name.removeSuffix(".trail.yaml")
      val source =
        sourceRoot.walkTopDown().firstOrNull { it.isFile && it.name == "$className.kt" }
          ?: fail(
            "${asset.path} names test class `$className`, but no $className.kt exists under " +
              "${sourceRoot.path}. `runFromAsset()` resolves the asset from the running test's " +
              "class and method, so this trail is unreachable.",
          )
      assertTrue(
        source.readText().contains("fun $methodName("),
        "${asset.path} names test method `$methodName`, which ${source.name} does not declare. " +
          "Add `@Test fun $methodName() = runFromAsset()` or delete the asset.",
      )
    }
  }

  private fun PromptStep.isReplayable(): Boolean = recordable && recording != null
}
