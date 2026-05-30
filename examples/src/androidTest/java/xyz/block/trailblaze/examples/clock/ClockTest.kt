package xyz.block.trailblaze.examples.clock

import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.examples.rules.ExamplesAndroidTrailblazeRule

/**
 * Example test showing how to use Trailblaze with AI to use the Clock app via prompts.
 *
 * Drives `clock/launch-smoke` rather than the richer `clock/set-alarm-730am` trail because
 * the on-device path (this `connectedDebugAndroidTest`) doesn't pre-bundle the workspace
 * trailmap's TypeScript scripted tools (`clock_android_launchApp.ts`) into the APK — the
 * scripted-tool runtime there can only execute pre-bundled JS, not raw `.ts` sources.
 * Host-rpc CI exercises `set-alarm-730am` via `./trailblaze run`, where the daemon
 * bundles those scripted tools through esbuild at session start.
 */
class ClockTest {

  @get:Rule
  val trailblazeRule = ExamplesAndroidTrailblazeRule()

  @Test
  fun launchAndVerifyTabs() = trailblazeRule.runFromAsset("clock/launch-smoke")

}
