package xyz.block.trailblaze.config

/**
 * Filename convention + resolution helper for per-target icons shown in the TrailRunner UI.
 *
 * The convention lets a workspace fill the target list's icons by simply dropping files into the
 * shared icons folder ([ICONS_DIR]) — no per-target authoring required:
 *
 *  - **Android target** → `assets/icons/android_<app_id>.png`
 *    (e.g. `assets/icons/android_com.example.app.png`)
 *  - **iOS target** → `assets/icons/ios_<bundle_id>.png`
 *    (e.g. `assets/icons/ios_com.example.app.png`)
 *  - **Web target** → `assets/icons/favicon_<host>.png`
 *    (e.g. `assets/icons/favicon_example.com.png`)
 *
 * These filenames match exactly what the extraction step produces (Android launcher icon via
 * `aapt`, iOS launcher icon via `IosAppIconExtractor`'s CoreUI-based `.car` decode, web favicon
 * via a favicon service), so populating the folder is enough to light up the first-run empty
 * state.
 *
 * An explicit [AppTargetYamlConfig.icon] always wins over the convention — see [resolveIconPath].
 *
 * Pure and dependency-free so it can be shared between the runtime and the TrailRunner UI.
 */
object TargetIconConvention {
  /** Workspace-relative directory the convention resolves against. */
  const val ICONS_DIR: String = "assets/icons"

  /** Convention path for an Android target's launcher icon, keyed by its application id. */
  fun androidIconPath(appId: String): String = "$ICONS_DIR/android_$appId.png"

  /** Convention path for an iOS target's launcher icon, keyed by its bundle id. */
  fun iosIconPath(bundleId: String): String = "$ICONS_DIR/ios_$bundleId.png"

  /** Convention path for a web target's favicon, keyed by its host (no scheme, no path). */
  fun webIconPath(host: String): String = "$ICONS_DIR/favicon_$host.png"

  /**
   * Extracts the bare host from a start/base URL for use with [webIconPath].
   *
   * Strips an optional scheme (`https://`, `http://`, or scheme-relative `//`), then trims at the
   * first `/`, `?`, or `#`, and drops any `user@` and `:port` suffix. A bracketed IPv6 literal
   * (`[::1]:8080`) keeps its brackets intact — the port is stripped only after the closing `]`, so
   * the colons inside the address aren't mistaken for a port separator. Returns null when no host
   * can be derived (blank input, or a value with no host component). Deliberately lightweight — it
   * avoids a URL/URI dependency so this stays usable in commonMain and on the web UI side.
   */
  fun hostFromUrl(url: String?): String? {
    if (url.isNullOrBlank()) return null
    var s = url.trim()
    val schemeSep = s.indexOf("://")
    s = when {
      schemeSep >= 0 -> s.substring(schemeSep + 3)
      s.startsWith("//") -> s.substring(2)
      else -> s
    }
    // Cut off path / query / fragment.
    val end = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
    if (end >= 0) s = s.substring(0, end)
    // Drop userinfo (user@host).
    s = s.substringAfterLast('@')
    // Drop :port — but not the colons inside a bracketed IPv6 literal.
    s = if (s.startsWith("[")) {
      val closing = s.indexOf(']')
      if (closing >= 0) s.substring(0, closing + 1) else s
    } else {
      s.substringBefore(':')
    }
    return s.ifBlank { null }
  }

  /**
   * Resolves the icon path for a target: the explicit [explicitIcon] if set, otherwise the Android
   * convention (when [appId] is non-null), otherwise the iOS convention (when [iosBundleId] is
   * non-null), otherwise the web convention derived from [startUrl]. Returns null when nothing
   * resolves. The convention paths are relative to the workspace root; the caller is responsible
   * for confirming the file actually exists on disk.
   *
   * [iosBundleId] is deliberately the last parameter: this is a public function, and inserting it
   * before [startUrl] would silently break an existing 3-positional-arg caller
   * (`resolveIconPath(icon, appId, url)`) by reinterpreting its URL argument as an iOS bundle id
   * after recompilation. Appending new parameters after existing ones preserves positional-arg
   * compatibility.
   */
  fun resolveIconPath(
    explicitIcon: String?,
    appId: String? = null,
    startUrl: String? = null,
    iosBundleId: String? = null,
  ): String? {
    if (!explicitIcon.isNullOrBlank()) return explicitIcon
    if (appId != null) return androidIconPath(appId)
    if (iosBundleId != null) return iosIconPath(iosBundleId)
    val host = hostFromUrl(startUrl) ?: return null
    return webIconPath(host)
  }
}
