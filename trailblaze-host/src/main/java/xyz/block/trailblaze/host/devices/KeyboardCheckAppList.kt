package xyz.block.trailblaze.host.devices

/**
 * The app list Maestro's iOS keyboard check sends to the XCTest runner. Maestro's only other reader,
 * the legacy `XCTestIOSDevice.scroll`, is not on Trailblaze's path: swipes go through `scrollV2`,
 * which sends no app list.
 *
 * Maestro lists every installed app (`simctl listapps`, ~0.3s) on each check, and the runner then
 * asks the apps one by one whether they are in the foreground, stopping at the first that is. A
 * target that checks the keyboard on every screen read paid both costs on every read. This keeps
 * the list for [ttlMs] and puts [preferredAppIds] first, so the usual case — the target's own app
 * in front — is one lookup.
 *
 * An app installed within [ttlMs] is missing from the list until it expires; if that app is in
 * front with a keyboard up, the check reports none until then. The target's own apps are always
 * asked about, listed or not, so a trailhead that installs the target is not caught by this: the
 * ones not listed come after the listed ones, so they cost nothing while a listed one is in front.
 * An empty listing (a `simctl listapps` that printed nothing) is never kept, so the next check
 * lists again rather than seeing no apps for [ttlMs].
 *
 * [preferredAppIds] is read on every check, not once: a cached driver outlives a switch between
 * targets that share it, and the ids must follow the target selected now.
 */
internal class KeyboardCheckAppList(
  private val preferredAppIds: () -> List<String>,
  private val ttlMs: Long = DEFAULT_TTL_MS,
  /** Monotonic, so a wall-clock change cannot stretch or shorten [ttlMs]. */
  private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
  private val listInstalledApps: () -> Set<String>,
) : () -> Set<String> {

  private var installed: Set<String>? = null
  private var listedAtMs = 0L

  @Synchronized
  override fun invoke(): Set<String> {
    val now = nowMs()
    val apps = installed?.takeIf { now - listedAtMs < ttlMs }
      ?: listInstalledApps().also {
        installed = it.takeIf { listed -> listed.isNotEmpty() }
        listedAtMs = now
      }
    val preferred = preferredAppIds()
    return LinkedHashSet<String>(apps.size).apply {
      preferred.filterTo(this) { it in apps }
      addAll(preferred)
      addAll(apps)
    }
  }

  companion object {
    const val DEFAULT_TTL_MS = 60_000L
  }
}
