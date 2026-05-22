package xyz.block.trailblaze.ui.navigation

/**
 * How a route change should update the browser URL.
 *
 * `Push` adds a new history entry (browser Back walks back through it).
 *
 * `Replace` rewrites the current entry — used for navigations the user didn't
 * initiate (initial mount, single-session auto-advance) so browser Back exits
 * the page rather than landing on an auto-applied marker like `#all`.
 */
enum class HistoryMode { PUSH, REPLACE }

/**
 * Bundles the target [WasmRoute] with the [HistoryMode] that should apply to
 * it. Keeping intent atomic with the route prevents the kind of "flag set
 * before the route assignment, then consumed by an unrelated recomposition"
 * decoupling that a separate signal flag invites.
 */
data class RouteChange(val route: WasmRoute, val mode: HistoryMode)

/** Hash-only URL write the report-viewer router emits. */
sealed interface HashWriteCommand {
  /** URL is already at the target hash; nothing to do. */
  data object NoOp : HashWriteCommand

  /** Replace the current history entry with `#$newHash`. */
  data class Replace(val newHash: String) : HashWriteCommand

  /** Push `#$newHash` onto history as a new entry. */
  data class Push(val newHash: String) : HashWriteCommand
}

/**
 * Canonical hash string for a route.
 *
 * `Home` maps to `"all"` rather than the empty string so a reload preserves
 * the "user is on the overview" intent and the single-session auto-advance
 * doesn't re-fire.
 */
fun routeToHash(route: WasmRoute): String = when (route) {
  is WasmRoute.Home -> "all"
  is WasmRoute.SessionDetail -> "session/${route.sessionId}"
  is WasmRoute.Devices -> "devices"
}

/**
 * Inverse of [routeToHash]. Maps the URL hash (with leading `#` stripped) to
 * its [WasmRoute]. Unknown / empty hashes fall back to [WasmRoute.Home] so
 * old bookmarks and the empty-hash initial load keep working.
 *
 * Kept symmetric with [routeToHash] so future routes only need to be added in
 * one file. The hashchange listener and the initial-route parse both call
 * this helper, so adding a new route can't leave the writer or one parser
 * lagging the other.
 */
fun parseHash(hash: String): WasmRoute = when {
  hash.startsWith("session/") -> WasmRoute.SessionDetail(hash.removePrefix("session/"))
  hash == "devices" -> WasmRoute.Devices
  else -> WasmRoute.Home
}

/**
 * Decide how the URL hash should react to a [RouteChange]. Pure function over
 * the current hash string and the desired change — no DOM, no Compose state
 * — so the router's "push vs replace vs no-op" decisions can be exercised
 * from `jvmTest` without standing up a browser harness.
 */
fun decideHashWrite(currentHash: String, change: RouteChange): HashWriteCommand {
  val newHash = routeToHash(change.route)
  if (currentHash == newHash) return HashWriteCommand.NoOp
  return when (change.mode) {
    HistoryMode.REPLACE -> HashWriteCommand.Replace(newHash)
    HistoryMode.PUSH -> HashWriteCommand.Push(newHash)
  }
}
