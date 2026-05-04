package xyz.block.trailblaze.devices

/**
 * Represents a supported Platform.
 *
 * The [hidden] flag marks platforms that exist as wired-up first-class citizens but
 * shouldn't appear in default user-facing surfaces (`device list`, target dropdowns,
 * cross-platform tool catalogs). It's an "available but undemoed" tier — the full
 * `--device <platform>/<id>` plumbing works, the platform participates in exhaustive
 * `when` matches, but listings filter it out unless an `--all`-style flag is passed.
 *
 * Today only `DESKTOP` (the Compose desktop driver) uses this: the capability is real
 * (an RPC server already runs in the desktop app, see `ComposeRpcServer`) but we're not
 * yet committing to it as a documented public surface. Promote by flipping `hidden` to
 * `false` once we are.
 */
enum class TrailblazeDevicePlatform(
  val displayName: String,
  val hidden: Boolean = false,
) {
  ANDROID("Android"),
  IOS("iOS"),
  WEB("Web Browser"),

  /**
   * The host-side Compose desktop window itself, exposed via the
   * [xyz.block.trailblaze.compose.driver.rpc.ComposeRpcServer] that the desktop app runs
   * on `127.0.0.1:52600` (gated by `TrailblazeServerState.AppConfig.enableSelfTestServer`,
   * default `true`). Hidden from default listings; surface via `--all`.
   */
  DESKTOP("Compose Desktop", hidden = true),
  ;

  fun asTrailblazeDeviceClassifier(): TrailblazeDeviceClassifier = TrailblazeDeviceClassifier(this.name.lowercase())

  companion object {
    /**
     * Matches platform names case-insensitively. Accepts both the bare platform name
     * (`web`, `android`) and a fully-qualified `--device <platform>/<instanceId>` form
     * (`web/checkout`, `android/emulator-5562`) — anything before the first `/` segment
     * is what gets compared. Callers that already split off the platform segment
     * themselves keep working unchanged.
     */
    fun fromString(value: String): TrailblazeDevicePlatform? {
      val platformSegment = value.substringBefore('/').trim()
      return entries.find { it.name.equals(platformSegment, ignoreCase = true) }
    }

    /** Default-visible platforms (excludes [hidden] entries like [DESKTOP]). */
    val visibleEntries: List<TrailblazeDevicePlatform> get() = entries.filter { !it.hidden }
  }
}
