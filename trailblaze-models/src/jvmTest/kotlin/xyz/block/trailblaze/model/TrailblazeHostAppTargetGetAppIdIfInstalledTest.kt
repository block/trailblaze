package xyz.block.trailblaze.model

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * Pins [TrailblazeHostAppTarget.getAppIdIfInstalled]'s priority-order semantics — the contract
 * downstream callers depend on:
 *
 * - `MobileDeviceUtils.findInstalledAppIdForTarget` (host-side iOS / generic flow)
 * - downstream `AndroidTrailblazeRule` subclass paths
 * - `DeviceSelectionDialog` previews
 *
 * The function must iterate the **target's** declared list in YAML/declaration order — *not*
 * the device's installed-apps listing — so that when multiple declared ids are installed on
 * the same device the first-declared (most-preferred) wins. Concrete failure mode the absence
 * of this test let through previously: when two declared Android variants of the same app
 * were both installed on the same CI agent (e.g. the production package alongside the dev
 * package), a parallel implementation iterated the device listing as the outer loop. That
 * listing is sourced from `PackageManager.getInstalledApplications(0)`, whose iteration order
 * is unspecified by the platform — so the rule effectively picked whichever id PM happened to
 * surface first rather than the target's declared priority. Downstream tests deterministically
 * failed because the wrong package opened. The Android rule has since been refactored to
 * delegate to this canonical resolver — pinning the canonical behavior here means a future
 * regression to either path fails this test.
 */
class TrailblazeHostAppTargetGetAppIdIfInstalledTest {

  private object MultiVariantTarget :
    TrailblazeHostAppTarget(id = "multi-variant", displayName = "Multi-Variant") {
    // A target declaring three Android variants: dev variant first (preferred for CI),
    // engineering variant second, production third. Order is load-bearing here.
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? =
      when (platform) {
        TrailblazeDevicePlatform.ANDROID -> listOf(
          "com.example.app.dev",
          "com.example.app.eng",
          "com.example.app",
        )
        else -> null
      }

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()
  }

  @Test
  fun `picks first-declared appId when multiple are installed (priority over installed-set order)`() {
    // Both `com.example.app` and `com.example.app.dev` are installed. The function must
    // return the first-DECLARED one (dev), NOT whichever id happens to come up
    // first when iterating the installed set. The exact order of the installed set is
    // determined by the platform (Android `PackageManager.getInstalledApplications(0)`
    // doesn't guarantee any particular order), so the picking logic must not depend on it.
    // This is the regression case the bug surfaced.
    val installed = setOf(
      "com.android.chrome",
      "com.example.app",
      "com.example.app.dev",
      "com.example.unrelated",
    )
    assertEquals(
      "com.example.app.dev",
      MultiVariantTarget.getAppIdIfInstalled(TrailblazeDevicePlatform.ANDROID, installed),
    )
  }

  @Test
  fun `falls through declared list to a later entry when earlier ones are not installed`() {
    // Only `com.example.app.eng` (the second-declared) is installed. The first-declared
    // `com.example.app.dev` isn't on the device, and the third `com.example.app` isn't
    // either — so the resolver walks past missing entries and returns the eng variant.
    val installed = setOf("com.example.app.eng", "com.android.chrome")
    assertEquals(
      "com.example.app.eng",
      MultiVariantTarget.getAppIdIfInstalled(TrailblazeDevicePlatform.ANDROID, installed),
    )
  }

  @Test
  fun `returns null when no declared appId is installed`() {
    // None of the target's declared ids are on the device. Caller is expected to handle
    // null (typically by erroring with a "please install one of …" message).
    val installed = setOf("com.android.chrome", "com.example.unrelated")
    assertNull(MultiVariantTarget.getAppIdIfInstalled(TrailblazeDevicePlatform.ANDROID, installed))
  }

  @Test
  fun `returns null when the platform has no declared appIds`() {
    // MultiVariantTarget declares Android only. iOS lookup returns null regardless of what's
    // "installed" in the test set — there's no declared list to intersect with.
    val installed = setOf("com.example.app.dev")
    assertNull(MultiVariantTarget.getAppIdIfInstalled(TrailblazeDevicePlatform.IOS, installed))
  }

  // --- requireInstalledAppIdForDevice (throwing variant) ---
  //
  // Pins the throw-with-fallback behavior the host (`MobileDeviceUtils.findInstalledAppIdForTarget`)
  // and downstream on-device Android rule paths both delegate to. The priority semantics
  // (first declared wins) are covered by the soft-fail tests above; these focus on the wrapper
  // behavior.

  private object AllowsNotInstalledTarget :
    TrailblazeHostAppTarget(id = "stand-in", displayName = "Stand-In") {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? =
      when (platform) {
        TrailblazeDevicePlatform.ANDROID -> listOf("com.example.standin.primary", "com.example.standin.alt")
        else -> null
      }

    override val allowsAppNotInstalled: Boolean = true

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()
  }

  @Test
  fun `requireInstalledAppIdForDevice returns first declared match`() {
    // First-declared `com.example.app.dev` is installed alongside the third-declared
    // `com.example.app`. The throwing variant has to honor the same priority order the
    // soft-fail variant does — declaration order, not installed-set order.
    val installed = setOf("com.example.app", "com.example.app.dev")
    assertEquals(
      "com.example.app.dev",
      MultiVariantTarget.requireInstalledAppIdForDevice(TrailblazeDevicePlatform.ANDROID, installed),
    )
  }

  @Test
  fun `requireInstalledAppIdForDevice throws when no declared appId is installed`() {
    // None of the target's declared ids are on the device. The throwing variant fails fast
    // with a diagnostic message rather than letting the caller proceed with a null id and
    // bubble a less actionable failure further down the launch chain.
    val installed = setOf("com.android.chrome", "com.example.unrelated")
    val ex = assertFailsWith<IllegalStateException> {
      MultiVariantTarget.requireInstalledAppIdForDevice(TrailblazeDevicePlatform.ANDROID, installed)
    }
    // Diagnostic message must name the target id, the declared ids, and the installed set —
    // the three pieces an oncaller needs to act (install one of the declared ids, or update
    // the target's declared list to include the installed id).
    val msg = ex.message ?: ""
    assertTrue("missing target id in $msg") { "multi-variant" in msg }
    assertTrue("missing declared ids in $msg") { "com.example.app.dev" in msg }
    assertTrue("missing installed set in $msg") { "com.android.chrome" in msg }
  }

  @Test
  fun `requireInstalledAppIdForDevice throws when platform has no declared appIds`() {
    // MultiVariantTarget declares Android only — iOS request has no list to intersect, so
    // even an "installed" set with the right id fails with a "no app ids configured"
    // message. The throwing variant distinguishes this from the no-install-match case so
    // operators don't waste time chasing a missing install when the real issue is a
    // missing declaration.
    val installed = setOf("com.example.app.dev")
    assertFailsWith<IllegalStateException> {
      MultiVariantTarget.requireInstalledAppIdForDevice(TrailblazeDevicePlatform.IOS, installed)
    }
  }

  @Test
  fun `requireInstalledAppIdForDevice falls back to first declared when allowsAppNotInstalled`() {
    // Stand-in targets (e.g. the generic `default` target) opt in via [allowsAppNotInstalled]
    // because their declared id is a placeholder, not a real product package — the absence
    // of any installed match is expected, not a failure. Fallback uses the first declared id
    // so it aligns with the priority semantics of the non-fallback path. Real product
    // targets stay strict (covered by the throw-test above) so a missing install fails fast
    // at rule construction with a clear "please install" error rather than later when
    // something tries to launch the missing package.
    val installed = setOf("com.example.unrelated")
    assertEquals(
      "com.example.standin.primary",
      AllowsNotInstalledTarget.requireInstalledAppIdForDevice(TrailblazeDevicePlatform.ANDROID, installed),
    )
  }
}
