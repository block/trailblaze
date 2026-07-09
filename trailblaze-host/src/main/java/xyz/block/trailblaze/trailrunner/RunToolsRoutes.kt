package xyz.block.trailblaze.trailrunner

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.config.TargetIconConvention
import xyz.block.trailblaze.config.YamlBackedHostAppTarget
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.config.WorkspaceConfigDirHolder
import xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.toolcalls.getAgentToolboxForDriver
import xyz.block.trailblaze.toolcalls.toolName
import xyz.block.trailblaze.util.Console

// Serves the per-target app icon (the Android/iOS launcher icon or web favicon) the web UI shows
// next to a target. Resolution order (GitHub block/trailblaze issue 200):
//   1. The workspace-provided icon — the target's explicit `icon:` field, else the filename
//      convention under the shared icons folder (`assets/icons/android_<app_id>.png` /
//      `assets/icons/ios_<bundle_id>.png` / `favicon_<host>.png`). This is what lets a fresh /
//      open-source workspace light up its target list just by dropping files into
//      `assets/icons/`, with no per-target authoring.
//   2. The bundled `app_icon_<id>.png` classpath resource the desktop app ships (see
//      BlockAppTargets.BlockAppIconProvider) — the internal-build fallback.
// Nothing resolves → 404 (the UI falls back to a generic glyph).
//
// An optional `?platform=android|ios|web` query param scopes step 1 to that platform's explicit
// `PlatformConfig.icon` (checked ahead of the target-level `icon:`) and that platform's convention
// only. Omitting `platform` reproduces the exact pre-existing target-level behavior (android, ios,
// and web conventions all considered together), so this is backward compatible.
//
// A platform can declare more than one `app_ids` entry (e.g. a prod build alongside an internal/
// staging one with visually distinct artwork). An optional `?appId=` query param picks which
// declared id's convention icon to resolve for android or iOS, overriding the default of the
// first declared id; a device row can pass the exact package/bundle id it found installed
// (`DeviceAppDto.appId`) so a prod vs. internal build on two connected devices show their own icon
// instead of both falling back to the same first-declared one. Omitting `appId` reproduces the
// pre-existing firstOrNull() behavior.
// Per-installed-app label + launcher icon for the Create Target form's "Browse installed apps"
// picker. Split in two so the dropdown can render immediately and enrich progressively:
//   - GET /api/installed-app-badge  → JSON {label, hasIcon}; runs the (possibly slow, cached)
//     extraction — Android pulls the APK once per build and reads aapt2 badging, iOS decodes the
//     app bundle's Assets.car. One row's fetch never blocks the others' rendering.
//   - GET /api/installed-app-icon   → the cached PNG (extracting if needed), 404 when the app has
//     no extractable raster icon; the row's <img> onError falls back to the platform glyph.
internal fun Route.installedAppBadgeRoutes() {
  fun io.ktor.server.application.ApplicationCall.badgeParams(): Triple<xyz.block.trailblaze.devices.TrailblazeDevicePlatform, String, String>? {
    val platform = request.queryParameters["platform"]?.let { xyz.block.trailblaze.devices.TrailblazeDevicePlatform.fromString(it) } ?: return null
    // Device instance ids: emulator-5560, iOS UDIDs (hex + dashes), tcp devices (host:port).
    val device = request.queryParameters["device"]?.trim()
      ?.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c in "._:-" } } ?: return null
    // Same reverse-DNS charset the app-icon route accepts for appId.
    val appId = request.queryParameters["appId"]?.trim()
      ?.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c in "._-" } } ?: return null
    return Triple(platform, device, appId)
  }

  get("$PATH_BASE/api/installed-app-badge") {
    val (platform, device, appId) = call.badgeParams() ?: run {
      call.respond(HttpStatusCode.NotFound)
      return@get
    }
    val badge = withContext(Dispatchers.IO) { InstalledAppBadges.resolve(platform, device, appId) }
    call.respondText(
      JSON.encodeToString(InstalledAppBadgeDto.serializer(), InstalledAppBadgeDto(label = badge.label, hasIcon = badge.iconFile != null)),
      ContentType.Application.Json,
    )
  }

  get("$PATH_BASE/api/installed-app-icon") {
    val (platform, device, appId) = call.badgeParams() ?: run {
      call.respond(HttpStatusCode.NotFound)
      return@get
    }
    val badge = withContext(Dispatchers.IO) { InstalledAppBadges.resolve(platform, device, appId) }
    val bytes = badge.iconFile?.let { f -> withContext(Dispatchers.IO) { runCatching { f.readBytes() }.getOrNull() } }
    if (bytes == null) {
      call.respond(HttpStatusCode.NotFound)
      return@get
    }
    // Cache key already carries the app build (version in the file name), so a long TTL is safe.
    call.response.headers.append("Cache-Control", "max-age=86400")
    call.respondBytes(bytes = bytes, contentType = sniffImageContentType(bytes), status = HttpStatusCode.OK)
  }
}

/**
 * Sniffs the image MIME from magic bytes rather than trusting a filename extension. Needed here
 * because [InstalledAppBadges]' Android extraction path writes a launcher icon's raw bytes
 * (PNG **or** WebP — whatever format the APK shipped) under a `.png`-suffixed cache filename; a
 * client that honors a wrong `Content-Type: image/png` header over the real bytes (some
 * non-browser or strict webviews do) would fail to decode it. Mirrors the identical fix already
 * applied to the recording-mirror image route ([TrailRunnerRecordingService]) for the same
 * root cause — WebP mislabeled as PNG rendering blank in a strict native WKWebView.
 */
internal fun sniffImageContentType(bytes: ByteArray): ContentType = when {
  bytes.size >= 8 &&
    bytes[0].toInt() and 0xFF == 0x89 && bytes[1].toInt() == 'P'.code &&
    bytes[2].toInt() == 'N'.code && bytes[3].toInt() == 'G'.code -> ContentType.Image.PNG
  bytes.size >= 12 &&
    bytes[0].toInt() == 'R'.code && bytes[1].toInt() == 'I'.code &&
    bytes[2].toInt() == 'F'.code && bytes[3].toInt() == 'F'.code &&
    bytes[8].toInt() == 'W'.code && bytes[9].toInt() == 'E'.code &&
    bytes[10].toInt() == 'B'.code && bytes[11].toInt() == 'P'.code -> ContentType.parse("image/webp")
  else -> ContentType.Image.PNG
}

internal fun Route.appIconRoutes(deps: TrailRunnerDeps) {
  get("$PATH_BASE/api/app-icon/{target}") {
    val target = call.parameters["target"]?.trim()?.lowercase().orEmpty()
    // Only simple ids — never let the id compose a path into another resource.
    if (target.isEmpty() || !target.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
      call.respond(HttpStatusCode.NotFound)
      return@get
    }
    val platform = call.request.queryParameters["platform"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    // Same permissive-but-non-pathological charset as an Android application id / iOS bundle id
    // (both reverse-DNS dotted) — this only ever composes a convention filename (see
    // TargetIconConvention.androidIconPath/iosIconPath), and the canonical-containment guard in
    // resolveWorkspaceIconFile is the real escape defense. A malformed value is simply treated as
    // absent (falls through to the existing appId-less resolution — platform icon, target icon,
    // or the bundled classpath fallback), not rejected outright — this filter exists to keep a
    // crafted appId from ever reaching the filename convention, not to change the response for a
    // bad query param.
    val appId = call.request.queryParameters["appId"]?.trim()
      ?.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' } }

    // 1. Workspace icon (explicit `icon:` field or filename convention).
    val workspaceIcon = withContext(Dispatchers.IO) { resolveWorkspaceIconFile(deps, target, platform, appId) }
    if (workspaceIcon != null) {
      val bytes = withContext(Dispatchers.IO) {
        runCatching { workspaceIcon.readBytes() }
          .onFailure {
            Console.log("[RunToolsRoutes] failed to read workspace icon ${workspaceIcon.absolutePath}: ${it.message}")
          }
          .getOrNull()
      }
      if (bytes != null) {
        call.response.headers.append("Cache-Control", "max-age=86400")
        val contentType = MIME_BY_EXTENSION[workspaceIcon.extension.lowercase()] ?: ContentType.Image.PNG
        call.respondBytes(bytes = bytes, contentType = contentType, status = HttpStatusCode.OK)
        return@get
      }
    }

    // 2. Bundled classpath icon.
    val bytes = withContext(Dispatchers.IO) {
      TrailRunnerEndpoint::class.java.classLoader.getResourceAsStream("app_icon_$target.png")
        ?.use { it.readAllBytes() }
    }
    if (bytes == null) {
      call.respond(HttpStatusCode.NotFound)
      return@get
    }
    call.response.headers.append("Cache-Control", "max-age=86400")
    call.respondBytes(bytes = bytes, contentType = ContentType.Image.PNG, status = HttpStatusCode.OK)
  }
}

/**
 * Resolves the on-disk icon file for [targetId] from the active workspace, or null when the
 * target isn't YAML-backed, no icon path resolves, or the resolved file doesn't exist.
 *
 * Uses [TargetIconConvention] to turn an explicit `icon:` field (or, absent one, an Android app
 * id / web base URL) into a workspace-relative path, then resolves that path against the
 * workspace's `trails/config/` directory (falling back to the workspace root) with a
 * canonical-containment check so a crafted path can't escape the workspace.
 *
 * When [platform] is null, this reproduces the original target-level resolution exactly: the
 * target's explicit [xyz.block.trailblaze.config.AppTargetYamlConfig.icon], else the Android
 * convention, else the iOS convention, else the web convention. When [platform] is given, that
 * platform's [PlatformConfig.icon][xyz.block.trailblaze.config.PlatformConfig.icon] is checked
 * first (ahead of the target-level icon), and the convention fallback is scoped to that platform
 * only.
 *
 * [explicitAppId], when given AND it is one of the android/iOS platform's (possibly multiple)
 * declared [PlatformConfig.appIds][xyz.block.trailblaze.config.PlatformConfig.appIds], overrides
 * which declared id the convention keys off — see [platformScopedIconInputs] /
 * [resolveExplicitAndroidAppId] / [resolveExplicitIosBundleId]. An [explicitAppId] the target
 * doesn't actually declare is treated as absent (same as a malformed value), so this endpoint
 * can't be used to probe a convention icon for an arbitrary id unrelated to the target's real
 * config. Absent or not-declared, this falls back to `appIds.firstOrNull()`, the pre-existing
 * behavior.
 */
internal fun resolveWorkspaceIconFile(deps: TrailRunnerDeps, targetId: String, platform: String? = null, explicitAppId: String? = null): File? {
  val target = deps.deviceManager?.availableAppTargets
    ?.firstOrNull { it.id.equals(targetId, ignoreCase = true) } as? YamlBackedHostAppTarget
    ?: return null
  val config = target.config
  // Candidate base dirs, most-specific first: the workspace `trails/config/` dir (where authored
  // config lives) then the workspace root (its grandparent), so an author can anchor `assets/` at
  // either spot.
  val configDir = WorkspaceConfigDirHolder.resolver()
  val bases = listOfNotNull(configDir, configDir?.parentFile?.parentFile)
  val declaredAndroidAppIds = config.platforms?.get("android")?.appIds.orEmpty()
  val declaredIosBundleIds = config.platforms?.get("ios")?.appIds.orEmpty()
  val scoped = platformScopedIconInputs(
    configIcon = config.icon,
    platformIcon = platform?.let { config.platforms?.get(it)?.icon },
    androidAppId = declaredAndroidAppIds.firstOrNull(),
    iosBundleId = declaredIosBundleIds.firstOrNull(),
    webBaseUrl = config.platforms?.get("web")?.baseUrl,
    platform = platform,
    explicitAndroidAppId = resolveExplicitAndroidAppId(explicitAppId, declaredAndroidAppIds),
    explicitIosBundleId = resolveExplicitIosBundleId(explicitAppId, declaredIosBundleIds),
  )
  return resolveWorkspaceIconFile(
    configIcon = scoped.icon,
    androidAppId = scoped.androidAppId,
    iosBundleId = scoped.iosBundleId,
    webBaseUrl = scoped.webBaseUrl,
    baseDirs = bases,
  )
}

/**
 * Gates a caller-supplied [explicitAppId] on actually being one of the platform's
 * [declaredAppIds] — a value that passed the charset filter but names an id the target never
 * declared is treated as absent (same precedent as a malformed value) rather than honored, so the
 * `?appId=` param can't be used to fish for a convention icon (`android_<id>.png`) unrelated to
 * what the target actually configures. Pure so it's unit-testable with plain inputs.
 */
internal fun resolveExplicitAndroidAppId(explicitAppId: String?, declaredAppIds: List<String>): String? =
  explicitAppId?.takeIf { it in declaredAppIds }

/** iOS counterpart to [resolveExplicitAndroidAppId] — same gating, keyed off declared bundle ids. */
internal fun resolveExplicitIosBundleId(explicitAppId: String?, declaredBundleIds: List<String>): String? =
  explicitAppId?.takeIf { it in declaredBundleIds }

/**
 * Computes the icon-convention inputs to use for [platform]: that platform's explicit icon wins
 * over the target-level [configIcon], and the android/iOS/web convention fallback (see
 * [TargetIconConvention]) is restricted to the requested platform only. `platform == null`
 * reproduces the original target-level behavior unchanged (all three conventions considered, no
 * platform icon).
 *
 * [explicitAndroidAppId] / [explicitIosBundleId], when non-null, win over [androidAppId] /
 * [iosBundleId] (the caller's default, typically `appIds.firstOrNull()`) — this is how a caller
 * that knows exactly which of a platform's several declared app/bundle ids it wants (e.g. the
 * exact package/bundle a connected device has installed) picks that one instead of always getting
 * the first declared id. Like [androidAppId] / [iosBundleId] themselves, each only applies when
 * [platform] is null or matches that platform's key.
 *
 * Pure decision logic extracted from [resolveWorkspaceIconFile] so it's unit-testable without a
 * live [TrailRunnerDeps] / [YamlBackedHostAppTarget].
 */
internal fun platformScopedIconInputs(
  configIcon: String?,
  platformIcon: String?,
  androidAppId: String?,
  iosBundleId: String?,
  webBaseUrl: String?,
  platform: String?,
  explicitAndroidAppId: String? = null,
  explicitIosBundleId: String? = null,
): PlatformScopedIconInputs =
  PlatformScopedIconInputs(
    icon = platformIcon ?: configIcon,
    androidAppId = if (platform == null || platform == "android") (explicitAndroidAppId ?: androidAppId) else null,
    iosBundleId = if (platform == null || platform == "ios") (explicitIosBundleId ?: iosBundleId) else null,
    webBaseUrl = if (platform == null || platform == "web") webBaseUrl else null,
  )

/** Named result of [platformScopedIconInputs] — reads clearer at call sites than a raw 4-tuple. */
internal data class PlatformScopedIconInputs(
  val icon: String?,
  val androidAppId: String?,
  val iosBundleId: String?,
  val webBaseUrl: String?,
)

/**
 * Pure resolution: turns a target's icon config into an on-disk file, or null when nothing
 * resolves. Splits out the side-effect-free logic — [TargetIconConvention] path resolution plus a
 * canonical-containment probe across [baseDirs] — from the device-manager / workspace lookups in
 * the [deps]-taking overload above, so it can be unit-tested with plain inputs.
 */
internal fun resolveWorkspaceIconFile(
  configIcon: String?,
  androidAppId: String?,
  webBaseUrl: String?,
  baseDirs: List<File>,
  iosBundleId: String? = null,
): File? {
  val relPath = TargetIconConvention.resolveIconPath(
    explicitIcon = configIcon,
    appId = androidAppId,
    startUrl = webBaseUrl,
    iosBundleId = iosBundleId,
  ) ?: return null
  for (base in baseDirs) {
    resolveContainedFile(base, relPath)?.let { return it }
  }
  return null
}

/**
 * Resolves [rel] under [base] and returns it only when it exists and stays inside [base]
 * (canonical-path containment guard against `..` / absolute-path escapes).
 */
private fun resolveContainedFile(base: File, rel: String): File? {
  if (rel.isEmpty()) return null
  val f = File(base, rel)
  val baseCanon = runCatching { base.canonicalPath }
    .onFailure { Console.log("[RunToolsRoutes] failed to canonicalize base dir ${base.absolutePath}: ${it.message}") }
    .getOrNull() ?: return null
  val fCanon = runCatching { f.canonicalPath }
    .onFailure { Console.log("[RunToolsRoutes] failed to canonicalize candidate path ${f.absolutePath}: ${it.message}") }
    .getOrNull() ?: return null
  if (fCanon != baseCanon && !fCanon.startsWith(baseCanon + File.separator)) return null
  return if (f.isFile) f else null
}

internal fun Route.runToolsRoutes(deps: TrailRunnerDeps) {
  // Given a target app id and the device's driver, return the toolsets — and the tools
  // inside each — that actually register for a run against that target. This mirrors the
  // agent's session-start composition: the target's declared `tool_sets:` for the driver,
  // plus every `always_enabled` toolset, filtered to driver-compatible entries — the same
  // catalog + `getDeclaredToolSetIdsForDriver` the runtime uses. Resolution is fully static
  // (no device connection needed); the daemon's `availableAppTargets` already carries the
  // discovered targets.
  get("$PATH_BASE/api/run-tools") {
    val targetId = call.request.queryParameters["target"]?.trim().orEmpty()
    val driverParam = call.request.queryParameters["driver"]?.trim().orEmpty()
    val platformParam = call.request.queryParameters["platform"]?.trim().orEmpty()
    call.respondText(
      text = JSON.encodeToString(RunToolsResponse.serializer(), buildRunToolsResponse(deps, targetId, driverParam, platformParam)),
      contentType = ContentType.Application.Json,
    )
  }
}

/**
 * The toolsets (and the tools inside each) that register for a run against `targetId` on the device's
 * `driver` — the shared source for both the REST `GET /api/run-tools` route and the
 * `GetRunToolsRequest` RPC handler. Mirrors the agent's session-start composition. Empty `driver` /
 * `platform` are handled the same way the query-param route did (driver preferred, platform fallback).
 */
internal suspend fun buildRunToolsResponse(
  deps: TrailRunnerDeps,
  targetId: String,
  driverParam: String,
  platformParam: String,
): RunToolsResponse =
  withContext(Dispatchers.IO) {
    val target = deps.deviceManager?.availableAppTargets?.firstOrNull { it.id == targetId }
    val driverType = resolveDriverType(driverParam, platformParam)
    if (target == null || driverType == null) {
      RunToolsResponse(
        target = targetId,
        driver = driverParam.ifEmpty { platformParam },
        resolved = false,
        toolsets = emptyList(),
      )
    } else {
        val declaredIds = runCatching { target.getDeclaredToolSetIdsForDriver(driverType) }
          .getOrDefault(emptyList())
        val catalog = runCatching { TrailblazeToolSetCatalog.defaultEntries() }
          .getOrDefault(emptyList())
        // Authoritative set of class/YAML tool names that actually register for this
        // target+driver at session start: getAgentToolboxForDriver applies surface_to_llm
        // filtering, drops YAML tools with no config, removes the target's excluded_tools,
        // and folds in the target's own custom tools. We use it to filter each toolset's
        // tools so the tab matches what the agent sees (not the static catalog union).
        // Scripted (.ts) tools live outside this set, so they're kept as-is per toolset.
        val registered: Set<String> = runCatching {
          val tb = target.getAgentToolboxForDriver(driverType)
          tb.toolClasses.map { it.toolName().toolName }.toSet() + tb.yamlToolNames.map { it.toolName }.toSet()
        }.getOrDefault(emptySet())
        // Empty only if resolution failed; then don't filter (degrade to the catalog union)
        // rather than show nothing.
        val keep = { name: String -> registered.isEmpty() || name in registered }

        val coveredClassYaml = mutableSetOf<String>()
        val toolsets = catalog
          .filter { it.isCompatibleWith(driverType) && (it.alwaysEnabled || it.id in declaredIds) }
          .sortedWith(compareByDescending<ToolSetCatalogEntry> { it.alwaysEnabled }.thenBy { it.id })
          .map { entry ->
            val classYaml = (entry.toolClasses.map { it.toolName().toolName } + entry.yamlToolNames.map { it.toolName })
              .filter(keep)
            coveredClassYaml += classYaml
            val scripted = entry.scriptedToolNames.map { it.toolName }
            RunToolSetDto(
              id = entry.id,
              description = entry.description,
              alwaysEnabled = entry.alwaysEnabled,
              tools = (classYaml + scripted).distinct().sorted(),
            )
          }
          // Drop toolsets that surface nothing for this run (e.g. framework-only sets whose
          // tools are all surface_to_llm=false) — the tab is about what the agent can use.
          .filter { it.tools.isNotEmpty() }
          .toMutableList()

        // Tools that register for this target but belong to no declared/always toolset —
        // typically the target's own `tools:` (custom/inline) entries. Surface them so the
        // tab doesn't silently drop what the agent will actually have.
        val other = (registered - coveredClassYaml).sorted()
        if (other.isNotEmpty()) {
          toolsets += RunToolSetDto(
            id = "target-tools",
            description = "Tools this target registers directly, outside its shared toolsets.",
            alwaysEnabled = false,
            tools = other,
          )
        }

        RunToolsResponse(
          target = targetId,
          driver = driverType.name,
          resolved = true,
          toolsets = toolsets,
        )
      }
    }

// Prefer the device's exact driver (e.g. ANDROID_ONDEVICE_ACCESSIBILITY); fall back to any
// driver on the requested platform so the toolset-compatibility filter still resolves when
// only a platform string is known.
private fun resolveDriverType(driverParam: String, platformParam: String): TrailblazeDriverType? {
  if (driverParam.isNotEmpty()) {
    TrailblazeDriverType.entries.firstOrNull { it.name.equals(driverParam, ignoreCase = true) }
      ?.let { return it }
  }
  if (platformParam.isNotEmpty()) {
    return TrailblazeDriverType.entries.firstOrNull { it.platform.name.equals(platformParam, ignoreCase = true) }
  }
  return null
}
