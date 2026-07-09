package xyz.block.trailblaze.trailrunner

import java.io.File
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.IosHostSimctlUtils
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils.runProcess
import xyz.block.trailblaze.util.isMacOs

/**
 * Extracts an iOS app's launcher icon into a PNG matching
 * [xyz.block.trailblaze.config.TargetIconConvention.iosIconPath], by decoding the app's compiled
 * asset catalog (`Assets.car`) via the private CoreUI.framework — the same framework
 * Xcode/Simulator.app/Finder use internally to render `.car` renditions, so decoding stays
 * correct across whatever pixel-compression scheme a given Xcode toolchain produced the catalog
 * with (that's CoreUI's problem to solve, not ours). Also yields the highest-fidelity source
 * available — the 1024x1024 App Store marketing rendition.
 *
 * The only intended trigger for this is [notifyIconExtractionTriggerCandidates] — an explicit
 * Edit Target save, never a routine `trailblaze run` / daemon-startup / target-discovery path.
 * macOS-only; a no-op elsewhere (iOS Simulators only exist on a Mac).
 */
internal object IosAppIconExtractor {

  private val nativeCacheDir = File(System.getProperty("user.home") ?: ".", ".trailblaze/cache/native")
  private val helperBinary = File(nativeCacheDir, "extract_ios_app_icon")
  private const val HELPER_RESOURCE_PATH = "native/ios/ExtractIosAppIcon.m"

  /**
   * Extracts [bundleId]'s launcher icon from whichever booted Simulator has it installed, writing
   * it to [outFile]. Returns true on success.
   *
   * Best-effort by design: no booted simulator with this bundle id installed, no custom AppIcon
   * configured (no `Assets.car` / no `CFBundleIconName` — e.g. a minimal test target that never
   * configured one), or a compile/decode failure all return false with a diagnostic log rather
   * than throwing. This must never break the Edit Target save it's triggered from.
   */
  fun extractIconIfPossible(bundleId: String, outFile: File): Boolean {
    if (!isMacOs()) return false
    val appPath = findInstalledAppBundle(bundleId)
    if (appPath == null) {
      Console.log("[IosAppIconExtractor] no booted simulator has $bundleId installed — skipping")
      return false
    }
    val carFile = File(appPath, "Assets.car")
    if (!carFile.isFile) {
      Console.log("[IosAppIconExtractor] $bundleId has no Assets.car — no custom app icon to extract")
      return false
    }
    val iconName = readIconName(File(appPath, "Info.plist"))
    if (iconName == null) {
      Console.log("[IosAppIconExtractor] $bundleId's Info.plist has no CFBundleIconName — no custom app icon to extract")
      return false
    }
    val helper = ensureHelperCompiled() ?: return false
    outFile.parentFile?.mkdirs()
    val result = TrailblazeProcessBuilderUtils.createProcessBuilder(
      listOf(helper.absolutePath, carFile.absolutePath, iconName, outFile.absolutePath),
    ).runProcess {}
    if (!result.isSuccess) {
      Console.log("[IosAppIconExtractor] extraction failed for $bundleId: ${result.fullOutput}")
      return false
    }
    Console.log("[IosAppIconExtractor] wrote ${outFile.absolutePath} for $bundleId (icon '$iconName')")
    return true
  }

  /**
   * Tries every currently booted simulator (not the `booted` alias — ambiguous with more than one
   * booted) and returns the first whose `get_app_container` call resolves [bundleId] to a real
   * `.app` bundle.
   */
  private fun findInstalledAppBundle(bundleId: String): File? =
    IosHostSimctlUtils.listBootedDeviceIds().firstNotNullOfOrNull { deviceId ->
      IosHostSimctlUtils.getAppBundlePath(deviceId, bundleId)
    }

  /**
   * Reads the primary app icon's name from [infoPlist]: the per-platform
   * `CFBundleIcons:CFBundlePrimaryIcon:CFBundleIconName` a standard iOS AppIcon asset-catalog set
   * produces, falling back to the plain top-level `CFBundleIconName` some app shapes (e.g. a Mac
   * Catalyst target) populate instead.
   */
  private fun readIconName(infoPlist: File): String? {
    if (!infoPlist.isFile) return null
    return readPlistValue(infoPlist, ":CFBundleIcons:CFBundlePrimaryIcon:CFBundleIconName")
      ?: readPlistValue(infoPlist, ":CFBundleIconName")
  }

  private fun readPlistValue(plist: File, keyPath: String): String? {
    val result = TrailblazeProcessBuilderUtils.createProcessBuilder(
      listOf("/usr/libexec/PlistBuddy", "-c", "Print $keyPath", plist.absolutePath),
    ).runProcess {}
    if (!result.isSuccess) return null
    return result.outputLines.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
  }

  /**
   * Compiles the bundled Objective-C helper ([HELPER_RESOURCE_PATH], shipped as a classpath
   * resource so this works for a packaged `trailblaze` binary, not just a source checkout) on
   * first use or after the resource content changes, caching the binary under [nativeCacheDir].
   *
   * `synchronized` because [extractIconIfPossible] can run concurrently for several bundle ids
   * declared on the same iOS platform (one background dispatch per id — see
   * `notifyIconExtractionTriggerCandidates`); without a lock, two callers hitting a cold cache at
   * once would race writing the same source file and compiling the same output binary path.
   */
  @Synchronized
  private fun ensureHelperCompiled(): File? {
    val sourceBytes = javaClass.classLoader.getResourceAsStream(HELPER_RESOURCE_PATH)?.use { it.readBytes() }
    if (sourceBytes == null) {
      Console.log("[IosAppIconExtractor] missing bundled resource $HELPER_RESOURCE_PATH")
      return null
    }
    nativeCacheDir.mkdirs()
    val sourceFile = File(nativeCacheDir, "ExtractIosAppIcon.m")
    val sourceChanged = !sourceFile.isFile || !sourceFile.readBytes().contentEquals(sourceBytes)
    if (sourceChanged) sourceFile.writeBytes(sourceBytes)
    if (helperBinary.isFile && !sourceChanged) return helperBinary

    Console.log("[IosAppIconExtractor] compiling icon-extraction helper (first use)...")
    val result = TrailblazeProcessBuilderUtils.createProcessBuilder(
      listOf(
        "xcrun", "clang", "-fobjc-arc", "-O2",
        "-framework", "Foundation", "-framework", "AppKit",
        "-F", "/System/Library/PrivateFrameworks", "-framework", "CoreUI",
        sourceFile.absolutePath, "-o", helperBinary.absolutePath,
      ),
    ).runProcess {}
    if (!result.isSuccess) {
      Console.log("[IosAppIconExtractor] failed to compile extraction helper: ${result.fullOutput}")
      return null
    }
    return helperBinary
  }
}
