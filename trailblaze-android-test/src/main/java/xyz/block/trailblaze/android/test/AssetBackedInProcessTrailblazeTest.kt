package xyz.block.trailblaze.android.test

import xyz.block.trailblaze.model.TrailblazeHostAppTarget

/**
 * [LaunchedAppTrailblazeTest] whose target is discovered from the APK's assets instead of named in
 * Kotlin — the base class a **generic** shell APK needs.
 *
 * Every other in-process base class can name its target because it is compiled for one app:
 *
 * ```kotlin
 * override val hostAppTarget by lazy { AssetBackedHostAppTarget.fromAsset("trails/config/targets/myapp.yaml") }
 * ```
 *
 * A shell built once and post-processed per app cannot — the id belongs to an adopter who does not
 * exist yet when the shell is built. So the target config is whatever
 * `trailblaze inprocess make-test-apk` injected, found by
 * [InjectedTrailAssets.injectedTargetConfigAssetPath].
 *
 * No target config means no scripted tools, which is the correct behaviour for an adopter who
 * injected only trails: the framework primitives still run.
 */
abstract class AssetBackedInProcessTrailblazeTest : LaunchedAppTrailblazeTest() {

  override val hostAppTarget: TrailblazeHostAppTarget? by lazy {
    InjectedTrailAssets.injectedTargetConfigAssetPath()
      ?.let { AssetBackedHostAppTarget.fromAsset(it) }
  }
}
