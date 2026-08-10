package xyz.block.trailblaze.host.ios

import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode

/**
 * Maestro-parity app-permission handling for the AXe driver path.
 *
 * Maestro sets app permissions before EVERY `launchApp` (`Orchestra.launchAppCommand`),
 * defaulting to `all: allow` when the command carries no permissions map. That re-grant is what
 * keeps its REINSTALL launches dialog-free: the clearState reinstall wipes the simulator's TCC
 * grants, and the pre-launch grant immediately restores them. On simulators the map is applied
 * in three legs (`SimctlIOSDevice.setPermissions` + `XCTestIOSDevice.setPermissions`):
 *
 * 1. applesimutils `--setPermissions` for calendar, camera, contacts, faceid, homekit,
 *    medialibrary, microphone, motion, photos, reminders, siri, speech, userTracking —
 *    best-effort, silently skipped when applesimutils isn't installed;
 * 2. `xcrun simctl privacy` for location (`grant`/`revoke`/`reset` `location[-always]`);
 * 3. its XCUITest runner for notifications — NOT a pre-grant: the runner remembers the map and
 *    auto-taps the springboard "Would Like to Send You Notifications" alert whenever a
 *    hierarchy capture sees one (`SystemPermissionHelper` + `PermissionButtonFinder`).
 *
 * The AXe path has neither applesimutils nor an in-app runner, so this object lowers the same
 * permissions vocabulary onto what the host can do directly:
 * - every service `simctl privacy` supports is pre-granted through it ([Plan.privacyCommands]);
 * - notifications keep Maestro's auto-tap semantics — planned by [findNotificationAlertTap]
 *   and executed by [xyz.block.trailblaze.host.axe.AxeDeviceManager] during tree capture;
 * - applesimutils-only services (camera, faceid, homekit, speech, userTracking) can't be
 *   pre-granted from the host and are surfaced in [Plan.skipped] for a diagnostic log line.
 */
object IosSimulatorPermissions {

  /** One `xcrun simctl privacy <udid> <action> <service> <bundleId>` invocation. */
  data class PrivacyCommand(val action: String, val service: String)

  data class Plan(
    /** In dispatch order: an `all` command always runs first so explicit entries override it. */
    val privacyCommands: List<PrivacyCommand>,
    /**
     * "allow" or "deny" when springboard notification alerts should be auto-dismissed
     * (Maestro's XCUITest-runner behavior); null when alerts should be left alone.
     */
    val notificationsValue: String?,
    /** Permission names the AXe path can't service (applesimutils-only) — log, then skip. */
    val skipped: List<String>,
  )

  private const val ALL = "all"
  private const val NOTIFICATIONS = "notifications"
  private const val LOCATION = "location"

  /** Maestro permission names with a direct `simctl privacy` service. */
  private val SIMCTL_SERVICE_BY_PERMISSION = mapOf(
    "calendar" to "calendar",
    "contacts" to "contacts",
    "medialibrary" to "media-library",
    "microphone" to "microphone",
    "motion" to "motion",
    "photos" to "photos",
    "reminders" to "reminders",
    "siri" to "siri",
  )

  /**
   * Maestro permission names only applesimutils can service — `simctl privacy` has no equivalent,
   * so even its `all` service leaves them untouched.
   */
  private val APPLESIMUTILS_ONLY_PERMISSIONS = listOf("camera", "faceid", "homekit", "speech", "userTracking")

  /**
   * Lowers a Maestro `launchApp` permissions map (null = Maestro's `all: allow` default) to the
   * AXe-path [Plan]. `all` maps to simctl's own `all` service (which covers every service simctl
   * supports, location included) and runs first, so explicit per-service entries override it —
   * the same expand-then-override semantics Maestro implements with `putIfAbsent`.
   */
  fun plan(permissions: Map<String, String>?): Plan {
    val effective = permissions ?: mapOf(ALL to "allow")
    val privacyCommands = mutableListOf<PrivacyCommand>()
    val skipped = mutableListOf<String>()
    var notificationsValue: String? = null

    effective[ALL]?.let { value ->
      privacyCommands += PrivacyCommand(privacyAction(ALL, value), ALL)
      notificationsValue = notificationsValue(value)
      // simctl's `all` only covers the services simctl itself supports — report what it did
      // NOT touch so the session log explains a permission dialog appearing despite `all`.
      // Explicit entries are excluded here; the loop below already reports them as skipped.
      skipped += APPLESIMUTILS_ONLY_PERMISSIONS.filterNot(effective::containsKey)
    }

    for ((permission, value) in effective) {
      when {
        permission == ALL -> {} // lowered above, ahead of every explicit entry
        permission == NOTIFICATIONS -> notificationsValue = notificationsValue(value)
        permission == LOCATION -> privacyCommands += locationCommand(value)
        SIMCTL_SERVICE_BY_PERMISSION.containsKey(permission) ->
          privacyCommands += PrivacyCommand(privacyAction(permission, value), SIMCTL_SERVICE_BY_PERMISSION.getValue(permission))
        else -> skipped += permission
      }
    }
    return Plan(privacyCommands, notificationsValue, skipped)
  }

  /**
   * Notifications have no privacy command — allow/deny arm the alert auto-dismiss, unset
   * disarms it. Anything else throws like every other service, instead of silently reading
   * a typo as "leave alerts alone".
   */
  private fun notificationsValue(value: String): String? = when (value) {
    "allow", "deny" -> value
    "unset" -> null
    else -> throw IllegalArgumentException(
      "Permission '$NOTIFICATIONS' can be set to 'allow', 'deny' or 'unset', not '$value'",
    )
  }

  private fun privacyAction(permission: String, value: String): String = when (value) {
    "allow" -> "grant"
    "deny" -> "revoke"
    "unset" -> "reset"
    else -> throw IllegalArgumentException(
      "Permission '$permission' can be set to 'allow', 'deny' or 'unset', not '$value'",
    )
  }

  /** Location values keep Maestro's own vocabulary (`LocalSimulatorUtils.setLocationPermission`). */
  private fun locationCommand(value: String): PrivacyCommand = when (value) {
    "always" -> PrivacyCommand("grant", "location-always")
    "inuse" -> PrivacyCommand("grant", "location")
    "never" -> PrivacyCommand("revoke", "location-always")
    "unset" -> PrivacyCommand("reset", "location-always")
    else -> throw IllegalArgumentException(
      "wrong argument value '$value' was provided for 'location' permission",
    )
  }

  // --- Notification-alert auto-dismiss (Maestro's PermissionButtonFinder, over an AXe tree) ---

  /** The springboard notification-permission alert title fragment Maestro keys on. */
  private const val NOTIFICATION_ALERT_LABEL = "would like to send you notifications"

  /**
   * Center of the button that resolves a springboard notification-permission alert per [value]
   * ("allow"/"deny"), or null when no action is needed (no alert in [root], other values).
   * Mirrors Maestro's `PermissionButtonFinder.findButtonToTap`: allow prefers a button labeled
   * "Allow"/"Continue" then falls back to the second button; deny prefers "Don't Allow"/"Cancel"
   * then falls back to the first.
   *
   * Candidate buttons are scoped to the alert's own subtree (see [alertButtons]). Maestro's
   * finder can only ever see alert buttons (`SystemPermissionHelper` bails unless springboard is
   * the foreground app), but an AXe describe-ui tree can also carry the app under test's buttons —
   * an app button labeled e.g. "Continue" ahead of the alert's "Allow" in DFS order would
   * otherwise win the label preference and take a tap the alert overlay swallows.
   */
  fun findNotificationAlertTap(root: TrailblazeNode, value: String): Pair<Int, Int>? {
    if (value != "allow" && value != "deny") return null
    val alertLabel = root.findFirst { it.axLabel().contains(NOTIFICATION_ALERT_LABEL) } ?: return null
    val buttons = alertButtons(root, alertLabel)
    if (buttons.isEmpty()) return null
    val target = if (value == "allow") {
      buttons.firstOrNull { it.axLabel() == "allow" || it.axLabel() == "continue" }
        ?: buttons.getOrNull(1)
        ?: buttons.first()
    } else {
      buttons.firstOrNull { it.axLabel().contains("don't allow") || it.axLabel() == "cancel" }
        ?: buttons.first()
    }
    return target.centerPoint()
  }

  /**
   * The buttons belonging to the alert that carries [alertLabel]: the subtree of the nearest
   * ancestor of the label that contains any button, never escalating past the label's enclosing
   * application node (the AXe analog of Maestro's springboard-only search), and excluding any
   * button whose pid provably differs from the label's. Empty when the alert itself exposes no
   * buttons — app buttons elsewhere in the tree are never a fallback.
   */
  private fun alertButtons(root: TrailblazeNode, alertLabel: TrailblazeNode): List<TrailblazeNode> {
    val labelPid = alertLabel.axDetail()?.pid
    val labelToRoot = pathToRoot(root, alertLabel) ?: listOf(alertLabel)
    for (ancestor in labelToRoot) {
      val buttons = mutableListOf<TrailblazeNode>()
      collectButtons(ancestor, buttons)
      val alertOwned = buttons.filterNot { button ->
        val buttonPid = button.axDetail()?.pid
        labelPid != null && buttonPid != null && buttonPid != labelPid
      }
      if (alertOwned.isNotEmpty()) return alertOwned
      if (ancestor.isApplicationNode()) break
    }
    return emptyList()
  }

  /** Path from [target] up to [from] (target first), or null when [target] isn't in the subtree. */
  private fun pathToRoot(from: TrailblazeNode, target: TrailblazeNode): List<TrailblazeNode>? {
    if (from === target) return listOf(from)
    for (child in from.children) {
      pathToRoot(child, target)?.let { return it + from }
    }
    return null
  }

  private fun TrailblazeNode.axDetail(): DriverNodeDetail.IosAxe? = driverDetail as? DriverNodeDetail.IosAxe

  /**
   * Lowercased label with curly apostrophes (U+2018/U+2019) normalized to ASCII `'` — iOS
   * renders the deny button as "Don’t Allow" (U+2019), which an ASCII `contains` would miss.
   */
  private fun TrailblazeNode.axLabel(): String = axDetail()?.label?.lowercase()
    ?.replace('‘', '\'')?.replace('’', '\'')
    .orEmpty()

  private fun TrailblazeNode.isApplicationNode(): Boolean =
    axDetail()?.let { it.type == "Application" || it.role == "AXApplication" } == true

  private fun collectButtons(node: TrailblazeNode, out: MutableList<TrailblazeNode>) {
    val detail = node.axDetail()
    if (detail?.type == "Button" || detail?.role == "AXButton") out += node
    node.children.forEach { collectButtons(it, out) }
  }
}
