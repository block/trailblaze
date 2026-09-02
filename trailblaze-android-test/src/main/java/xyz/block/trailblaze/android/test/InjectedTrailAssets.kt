package xyz.block.trailblaze.android.test

import androidx.test.platform.app.InstrumentationRegistry

/**
 * Reads what a post-processed shell APK carries in its assets — trails and a target config that
 * were injected into an ALREADY-BUILT APK rather than staged by Gradle.
 *
 * A Gradle-built module knows its trails at build time, so the `xyz.block.trailblaze.android-gradle`
 * plugin can emit one `@Test` per trail and each shell calls [AndroidTestTrailblazeTest.runFromAsset]
 * with no argument. A shell APK that is post-processed by `trailblaze inprocess make-test-apk` has
 * no such build step: its trails arrive as zip entries after the dex is sealed, so the shell has to
 * find them at runtime instead.
 *
 * Both conventions are the same asset paths the Gradle path uses
 * (`trails/<ClassName>/<name>.trail.yaml`, `trails/config/targets/<id>.yaml`) — a post-processed
 * APK and a Gradle-built one differ only in who wrote the entries.
 */
object InjectedTrailAssets {

  /** Asset directory the packaging command flattens injected trails into. */
  const val INJECTED_TRAILS_CLASS_DIR: String = "InjectedTrailsLongTest"

  /**
   * Asset directory holding target configs, Gradle-staged or injected.
   *
   * Must match `InProcessShellPackager.TARGETS_ASSET_DIR` in `:trailblaze-host` (which carries the
   * `assets/` prefix a zip entry needs and this AssetManager path does not). That host writes the
   * entries; this reads them, and a rename on one side produces an APK whose injected target is
   * simply not found. SISTER-IMPL-TAG: in-process-targets-asset-dir.
   */
  const val TARGETS_ASSET_DIR: String = "trails/config/targets"

  /**
   * The built-in target `:trailblaze-models` ships as a resource, so EVERY in-process APK carries it
   * whether or not anything was injected. Excluded from discovery: counting it would make "one
   * injected config" indistinguishable from "none", and selecting it would advertise only the
   * platform tool sets that a null target already gives you.
   */
  private const val BUILT_IN_TARGET_ID = "default"

  private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets

  /**
   * Every injected trail asset path, sorted, so a parameterized shell produces the same test order
   * on every run.
   *
   * Empty when nothing was injected — the caller decides whether that is a failure. It is one for a
   * shell whose entire purpose is replaying injected trails, and the message has to say so: an
   * `AssetManager` returns an empty list for a missing directory exactly as it does for an empty
   * one, so "no trails" and "wrong asset path" are indistinguishable here and must not be reported
   * as if they were.
   */
  fun injectedTrailAssetPaths(): List<String> =
    (assets.list("trails/$INJECTED_TRAILS_CLASS_DIR") ?: emptyArray())
      .filter { it.endsWith(".trail.yaml") }
      .sorted()
      .map { "trails/$INJECTED_TRAILS_CLASS_DIR/$it" }

  /**
   * The single target-config asset this APK carries, or null when it carries none.
   *
   * Discovered rather than named by the shell, because the id belongs to the adopting app and the
   * shell is built before any adopter exists. `-e trailblaze.target <id>` picks one when an APK
   * carries several; with several and no argument this throws rather than guessing, since silently
   * choosing a target changes which scripted tools register.
   */
  fun injectedTargetConfigAssetPath(): String? {
    val ids = (assets.list(TARGETS_ASSET_DIR) ?: emptyArray())
      .filter { it.endsWith(".yaml") }
      .map { it.removeSuffix(".yaml") }
      .sorted()
    val requestedId = AndroidTestInstrumentation.stringArg("trailblaze.target")
    if (requestedId != null) {
      // An explicit request may name the built-in target; only discovery excludes it.
      require(requestedId in ids) {
        "-e trailblaze.target $requestedId, but this APK carries no " +
          "$TARGETS_ASSET_DIR/$requestedId.yaml. Present: ${ids.joinToString().ifEmpty { "(none)" }}"
      }
      return "$TARGETS_ASSET_DIR/$requestedId.yaml"
    }
    val injected = ids.filter { it != BUILT_IN_TARGET_ID }
    return when (injected.size) {
      0 -> null
      1 -> "$TARGETS_ASSET_DIR/${injected.single()}.yaml"
      else -> error(
        "This APK carries ${injected.size} injected target configs (${injected.joinToString()}). " +
          "Pick one with -e trailblaze.target <id> — which target registers decides which scripted " +
          "tools exist.",
      )
    }
  }
}
