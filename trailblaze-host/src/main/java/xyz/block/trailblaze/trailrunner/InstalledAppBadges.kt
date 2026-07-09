package xyz.block.trailblaze.trailrunner

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import xyz.block.trailblaze.device.InstalledApp
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils.runProcess

/**
 * Human label + launcher icon for an installed app, resolved on demand for the Create Target
 * form's "Browse installed apps" picker and cached persistently so only the first look at a given
 * app build pays the extraction cost.
 *
 * Per-platform sources — chosen for what each platform can answer without an app running:
 *  - **iOS**: `simctl listapps` already reports the label (it rides in on [InstalledApp.label]);
 *    the icon comes from [IosAppIconExtractor]'s CoreUI `.car` decode of the app bundle sitting on
 *    the host filesystem.
 *  - **Android (host/adb)**: neither label nor icon is queryable from the package manager — both
 *    live inside the APK. The APK is pulled once (emulator-local transfers are fast; the pull is
 *    keyed by app build so it never repeats for the same version) and `aapt2 dump badging` from
 *    the host's Android SDK yields the label plus the densest raster icon entry, which is then
 *    read straight out of the APK zip. No SDK build-tools on the host → label/icon simply stay
 *    absent, the picker falls back to app ids, and one log line says why.
 *
 * Everything here is best-effort: any failure returns an empty badge rather than throwing, and
 * the picker renders exactly what it rendered before this existed.
 */
internal object InstalledAppBadges {

  /** What the badge endpoints return: either field absent when the platform couldn't answer. */
  data class Badge(val label: String?, val iconFile: File?)

  private val cacheDir = File(System.getProperty("user.home") ?: ".", ".trailblaze/cache/app-badges")

  /**
   * The most recent installed-app inventory per device, memoized by [rememberInventory] each time
   * `buildInstalledAppsResponse` runs. Lets the per-app badge endpoints find `installPath` /
   * version without re-running the device probe per row (10 rows would mean 10 dumpsys calls).
   */
  private val inventoryByDevice = ConcurrentHashMap<String, Map<String, InstalledApp>>()

  /**
   * Serializes extraction. All dropdown rows fire together on modal open; one at a time keeps a
   * big-APK pull from competing with nine siblings for adb bandwidth, and the double-check on the
   * disk cache under the lock means each (app, build) is only ever extracted once.
   */
  private val extractionLock = Any()

  fun rememberInventory(platform: TrailblazeDevicePlatform, deviceId: String, apps: List<InstalledApp>) {
    inventoryByDevice["${platform.name}/$deviceId"] = apps.associateBy { it.appId }
  }

  /** The (iconFile, labelFile) cache slots for one app build. */
  private fun badgeFiles(platform: TrailblazeDevicePlatform, app: InstalledApp): Pair<File, File> {
    val versionKey = sanitize(app.buildNumber ?: app.version ?: "0")
    val iconFile = File(cacheDir, "${platform.name.lowercase()}/${sanitize(app.appId)}-$versionKey.png")
    return iconFile to File(iconFile.parentFile, "${iconFile.nameWithoutExtension}.label")
  }

  /**
   * The already-cached label only — never extracts. Lets the installed-apps LIST response carry
   * labels for warm-cache apps (instant second open) while staying fast on a cold cache.
   */
  fun peekLabel(platform: TrailblazeDevicePlatform, app: InstalledApp): String? {
    app.label?.let { return it }
    val (_, labelFile) = badgeFiles(platform, app)
    return labelFile.takeIf { it.isFile }?.readText()?.ifBlank { null }
  }

  fun resolve(platform: TrailblazeDevicePlatform, deviceId: String, appId: String): Badge {
    val app = inventoryByDevice["${platform.name}/$deviceId"]?.get(appId)
      ?: refreshInventory(platform, deviceId)?.get(appId)
      ?: return Badge(label = null, iconFile = null)
    val (iconFile, labelFile) = badgeFiles(platform, app)
    fun cached(): Badge? = if (iconFile.isFile || labelFile.isFile) {
      Badge(
        label = app.label ?: labelFile.takeIf { it.isFile }?.readText()?.ifBlank { null },
        iconFile = iconFile.takeIf { it.isFile },
      )
    } else {
      null
    }
    cached()?.let { return it }
    return synchronized(extractionLock) {
      cached() ?: when (platform) {
        TrailblazeDevicePlatform.ANDROID -> extractAndroid(deviceId, app, iconFile, labelFile)
        TrailblazeDevicePlatform.IOS -> extractIos(app, iconFile)
        else -> Badge(label = app.label, iconFile = null)
      }
    }
  }

  private fun refreshInventory(platform: TrailblazeDevicePlatform, deviceId: String): Map<String, InstalledApp>? =
    runCatching {
      val apps = xyz.block.trailblaze.host.ios.MobileDeviceUtils.listInstalledAppsDetailed(
        TrailblazeDeviceId(deviceId, platform),
      )
      rememberInventory(platform, deviceId, apps)
      inventoryByDevice["${platform.name}/$deviceId"]
    }.getOrNull()

  // ─── iOS ─────────────────────────────────────────────────────────────────────

  private fun extractIos(app: InstalledApp, iconFile: File): Badge {
    iconFile.parentFile.mkdirs()
    val ok = IosAppIconExtractor.extractIconIfPossible(app.appId, iconFile)
    return Badge(label = app.label, iconFile = iconFile.takeIf { ok && it.isFile })
  }

  // ─── Android ─────────────────────────────────────────────────────────────────

  private fun extractAndroid(deviceId: String, app: InstalledApp, iconFile: File, labelFile: File): Badge {
    val aapt2 = resolveAapt2()
    if (aapt2 == null) {
      Console.log(
        "[InstalledAppBadges] no aapt2 under the host Android SDK build-tools — " +
          "Android app labels/icons unavailable (set ANDROID_HOME to enable)",
      )
      return Badge(label = null, iconFile = null)
    }
    val installPath = app.installPath ?: return Badge(label = null, iconFile = null)
    // The host-path inventory reports the install DIRECTORY (`/data/app/~~…/<pkg>-…/`), not the
    // APK itself — the base split is always `base.apk` inside it.
    val apkPath = if (installPath.endsWith(".apk")) installPath else "${installPath.trimEnd('/')}/base.apk"
    val tmpApk = File.createTempFile("badge-", ".apk")
    try {
      if (!AndroidHostAdbUtils.pullFile(TrailblazeDeviceId(deviceId, TrailblazeDevicePlatform.ANDROID), apkPath, tmpApk)) {
        return Badge(label = null, iconFile = null)
      }
      val badging = TrailblazeProcessBuilderUtils.createProcessBuilder(
        listOf(aapt2.absolutePath, "dump", "badging", tmpApk.absolutePath),
      ).runProcess {}
      // aapt2 exits non-zero on some valid APKs (missing optional chunks) while still printing the
      // lines we need — parse whatever came out rather than gating on the exit code.
      val parsed = parseAaptBadging(badging.outputLines)
      iconFile.parentFile.mkdirs()
      // Always write the label file, even empty: it doubles as the negative cache that stops an
      // app with no label and only adaptive-XML icons from re-pulling its APK on every modal open.
      labelFile.writeText(parsed.label.orEmpty())
      var wroteIcon = false
      runCatching {
        ZipFile(tmpApk).use { zip ->
          // Badging's densest raster when it names one; otherwise (modern APKs list ONLY the
          // adaptive-XML resource for every density) fall back to scanning the zip for the raster
          // mipmaps apps still ship alongside — same stem as the adaptive icon, or ic_launcher.
          val iconEntry = pickBestIconEntry(parsed.iconByDensity)
            ?: pickRasterMipmapFallback(
              entryNames = zip.entries().asSequence().map { it.name }.toList(),
              adaptiveStem = parsed.iconByDensity.values.firstOrNull()
                ?.substringAfterLast('/')?.substringBeforeLast('.'),
            )
          val entry = iconEntry?.let { zip.getEntry(it) } ?: return@use
          // Written as-is (png OR webp bytes) under the .png cache name — every browser decodes
          // <img> payloads by content sniffing, so the extension mismatch is harmless here.
          zip.getInputStream(entry).use { input -> iconFile.outputStream().use { input.copyTo(it) } }
          wroteIcon = true
        }
      }.onFailure { Console.log("[InstalledAppBadges] icon extract failed for ${app.appId}: ${it.message}") }
      // No raster anywhere in the APK (apps that ship only a vector adaptive icon) —
      // rasterize the vector layers ourselves.
      if (!wroteIcon) {
        runCatching {
          val adaptiveEntry = parsed.iconByDensity.entries.sortedByDescending { it.key }
            .map { it.value }.firstOrNull { it.endsWith(".xml") }
          if (adaptiveEntry != null && renderAdaptiveVectorIcon(aapt2, tmpApk, adaptiveEntry, iconFile)) {
            wroteIcon = true
          }
        }.onFailure { Console.log("[InstalledAppBadges] vector icon render failed for ${app.appId}: ${it.message}") }
      }
      return Badge(label = parsed.label, iconFile = iconFile.takeIf { wroteIcon && it.isFile })
    } finally {
      tmpApk.delete()
    }
  }

  /** Label + per-density icon entries from `aapt2 dump badging` output. */
  internal data class AaptBadging(val label: String?, val iconByDensity: Map<Int, String>)

  private val LABEL_LINE = Regex("^application-label:'(.*)'\\s*$")
  private val ICON_LINE = Regex("^application-icon-(\\d+):'(.*)'\\s*$")

  /**
   * Pure parse of `aapt2 dump badging` lines: `application-label:'X'` (the unqualified line — the
   * device-locale variants like `application-label-en:'X'` are ignored) plus every
   * `application-icon-<density>:'res/...'` entry.
   */
  internal fun parseAaptBadging(lines: List<String>): AaptBadging {
    var label: String? = null
    val icons = mutableMapOf<Int, String>()
    lines.forEach { line ->
      LABEL_LINE.find(line)?.let { label = it.groupValues[1].ifBlank { null } }
      ICON_LINE.find(line)?.let { icons[it.groupValues[1].toInt()] = it.groupValues[2] }
    }
    return AaptBadging(label = label, iconByDensity = icons)
  }

  /**
   * The densest RASTER icon entry, or null when every density points at an XML (adaptive-icon)
   * resource — an XML can't be rendered without a full resource pipeline. Modern APKs typically
   * list only the adaptive resource here; [pickRasterMipmapFallback] then finds the raster
   * mipmaps they still ship.
   */
  internal fun pickBestIconEntry(iconByDensity: Map<Int, String>): String? = iconByDensity
    .entries
    .sortedByDescending { it.key }
    .firstOrNull { !it.value.endsWith(".xml") }
    ?.value

  private val MIPMAP_ENTRY = Regex("^res/mipmap-([a-z]+)[^/]*/([^/]+)\\.(png|webp)$")
  private val DENSITY_RANK = mapOf(
    "ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240,
    "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640,
  )

  /**
   * The densest raster launcher mipmap in the APK zip, for APKs whose badging lists only the
   * adaptive-XML icon: prefers entries whose stem matches the adaptive icon's own stem
   * ([adaptiveStem], e.g. `icon.xml` → `icon.webp`), then the conventional `ic_launcher`;
   * `_round` variants rank behind their square siblings.
   */
  internal fun pickRasterMipmapFallback(entryNames: List<String>, adaptiveStem: String?): String? =
    entryNames
      .mapNotNull { name ->
        val m = MIPMAP_ENTRY.find(name) ?: return@mapNotNull null
        val (density, stem) = m.destructured.let { (d, s, _) -> d to s }
        val stemRank = when {
          adaptiveStem != null && stem == adaptiveStem -> 0
          stem == "ic_launcher" -> 1
          adaptiveStem != null && stem == "${adaptiveStem}_round" -> 2
          stem == "ic_launcher_round" -> 3
          else -> return@mapNotNull null
        }
        Triple(name, stemRank, DENSITY_RANK[density] ?: 0)
      }
      .sortedWith(compareBy({ it.second }, { -it.third }))
      .firstOrNull()
      ?.first

  // ─── Vector adaptive icons (no raster anywhere in the APK) ──────────────────

  private val ADAPTIVE_LAYER_REF = Regex("""drawable\(0x[0-9a-f]+\)=@(0x[0-9a-f]+)""")
  private val RESOURCE_FILE_ENTRY = Regex("""\(\) \(file\) (\S+) type=XML""")
  private val RESOURCE_COLOR_VALUE = Regex("""\(\) #([0-9a-f]{8})""")

  /**
   * Renders [adaptiveEntry] (the APK's `<adaptive-icon>` XML) via [AndroidVectorIconRasterizer]:
   * dumps the compiled XML trees with `aapt2 dump xmltree`, resolves the background/foreground
   * drawable references (vector XMLs, or a plain color) through `aapt2 dump resources`, and
   * writes the composed safe-zone crop to [outFile]. Returns false when any layer uses vocabulary
   * the subset renderer doesn't support — the caller keeps the glyph fallback.
   */
  private fun renderAdaptiveVectorIcon(aapt2: File, apk: File, adaptiveEntry: String, outFile: File): Boolean {
    fun xmlTree(entry: String): List<String>? {
      val result = TrailblazeProcessBuilderUtils.createProcessBuilder(
        listOf(aapt2.absolutePath, "dump", "xmltree", "--file", entry, apk.absolutePath),
      ).runProcess {}
      return result.outputLines.takeIf { it.isNotEmpty() }
    }

    val resourceLines: List<String> by lazy {
      TrailblazeProcessBuilderUtils.createProcessBuilder(
        listOf(aapt2.absolutePath, "dump", "resources", apk.absolutePath),
      ).runProcess {}.outputLines
    }

    // `resource 0x7f08xxxx name` followed by either a `(file) res/....xml type=XML` entry or a
    // direct `#aarrggbb` color value on the config lines below it.
    fun resolveResource(resId: String): Pair<String?, Int?>? {
      val idx = resourceLines.indexOfFirst { it.trimStart().startsWith("resource $resId ") }
      if (idx == -1) return null
      resourceLines.drop(idx + 1).take(3).forEach { line ->
        RESOURCE_FILE_ENTRY.find(line)?.let { return it.groupValues[1] to null }
        RESOURCE_COLOR_VALUE.find(line)?.let { return null to it.groupValues[1].toLong(16).toInt() }
      }
      return null
    }

    fun layerFor(ref: String?): AndroidVectorIconRasterizer.VdLayer? {
      val (fileEntry, color) = ref?.let { resolveResource(it) } ?: return null
      if (color != null) return AndroidVectorIconRasterizer.VdLayer.Color(color)
      val lines = fileEntry?.let { xmlTree(it) } ?: return null
      val vector = AndroidVectorIconRasterizer.parseVector(lines) { gradientRef ->
        val (gradientEntry, _) = resolveResource(gradientRef) ?: return@parseVector null
        gradientEntry?.let { xmlTree(it) }
      } ?: return null
      return AndroidVectorIconRasterizer.VdLayer.Vector(vector)
    }

    val adaptiveLines = xmlTree(adaptiveEntry) ?: return false
    var current: String? = null
    var backgroundRef: String? = null
    var foregroundRef: String? = null
    adaptiveLines.forEach { line ->
      val trimmed = line.trimStart()
      if (trimmed.startsWith("E: ")) current = trimmed.removePrefix("E: ").substringBefore(' ')
      ADAPTIVE_LAYER_REF.find(line)?.let { m ->
        when (current) {
          "background" -> backgroundRef = m.groupValues[1]
          "foreground" -> foregroundRef = m.groupValues[1]
        }
      }
    }
    val foreground = layerFor(foregroundRef) ?: return false
    val background = layerFor(backgroundRef)
    val image = AndroidVectorIconRasterizer.renderAdaptiveIcon(background, foreground, sizePx = 288)
    outFile.parentFile.mkdirs()
    return javax.imageio.ImageIO.write(image, "png", outFile)
  }

  /**
   * Newest build-tools' aapt2 under the host Android SDK ($ANDROID_HOME / $ANDROID_SDK_ROOT /
   * the default macOS and Linux install locations). Resolved once — SDK installs don't change
   * under a running daemon.
   */
  private val aapt2: File? by lazy {
    val sdkRoots = listOfNotNull(
      System.getenv("ANDROID_HOME"),
      System.getenv("ANDROID_SDK_ROOT"),
      System.getProperty("user.home")?.let { "$it/Library/Android/sdk" },
      System.getProperty("user.home")?.let { "$it/Android/Sdk" },
    )
    sdkRoots.asSequence()
      .map { File(it, "build-tools") }
      .filter { it.isDirectory }
      .flatMap { buildTools -> buildTools.listFiles().orEmpty().sortedByDescending { it.name }.asSequence() }
      .map { File(it, "aapt2") }
      .firstOrNull { it.canExecute() }
  }

  private fun resolveAapt2(): File? = aapt2

  private fun sanitize(raw: String): String = raw.map { if (it.isLetterOrDigit() || it == '.' || it == '-') it else '_' }.joinToString("")
}
