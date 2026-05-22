package xyz.block.trailblaze.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the router's "push vs replace vs no-op" decisions so the next person
 * to touch [decideHashWrite] (or add a new [WasmRoute] writer to `main.kt`)
 * gets a fast signal if they break the browser Back semantics on the
 * single-session report path.
 *
 * Cases cover the live flows in `TrailblazeApp`:
 *  - fresh mount on `/` (empty hash → REPLACE `#all`),
 *  - single-session auto-advance (`#all` → REPLACE `#session/<id>`),
 *  - explicit click from overview (`#all` → PUSH `#session/<id>`),
 *  - in-page Back arrow from session detail (`#session/<id>` → PUSH `#all`),
 *  - hash already at target (NoOp, even when REPLACE was requested — the
 *    hashchange listener path),
 *  - [WasmRoute.Devices] mapping (not exercised by the report router today
 *    but defined on the route, so it stays pinned).
 */
class HashWriteDecisionTest {

  @Test
  fun freshMountOnRoot_homeWithEmptyHash_emitsReplaceAll() {
    val command = decideHashWrite(
      currentHash = "",
      change = RouteChange(WasmRoute.Home, HistoryMode.REPLACE),
    )
    assertEquals(HashWriteCommand.Replace("all"), command)
  }

  @Test
  fun singleSessionAutoAdvance_fromAllHash_emitsReplaceSession() {
    val command = decideHashWrite(
      currentHash = "all",
      change = RouteChange(WasmRoute.SessionDetail("abc-123"), HistoryMode.REPLACE),
    )
    assertEquals(HashWriteCommand.Replace("session/abc-123"), command)
  }

  @Test
  fun explicitClickFromOverview_emitsPushSession() {
    val command = decideHashWrite(
      currentHash = "all",
      change = RouteChange(WasmRoute.SessionDetail("abc-123"), HistoryMode.PUSH),
    )
    assertEquals(HashWriteCommand.Push("session/abc-123"), command)
  }

  @Test
  fun inPageBackFromSession_emitsPushAll() {
    val command = decideHashWrite(
      currentHash = "session/abc-123",
      change = RouteChange(WasmRoute.Home, HistoryMode.PUSH),
    )
    assertEquals(HashWriteCommand.Push("all"), command)
  }

  @Test
  fun hashAlreadyMatches_emitsNoOp_whenReplaceRequested() {
    val command = decideHashWrite(
      currentHash = "session/abc-123",
      change = RouteChange(WasmRoute.SessionDetail("abc-123"), HistoryMode.REPLACE),
    )
    assertEquals(HashWriteCommand.NoOp, command)
  }

  @Test
  fun hashAlreadyMatches_emitsNoOp_whenPushRequested() {
    val command = decideHashWrite(
      currentHash = "all",
      change = RouteChange(WasmRoute.Home, HistoryMode.PUSH),
    )
    assertEquals(HashWriteCommand.NoOp, command)
  }

  @Test
  fun devicesRoute_mapsToDevicesHash() {
    val command = decideHashWrite(
      currentHash = "",
      change = RouteChange(WasmRoute.Devices, HistoryMode.PUSH),
    )
    assertEquals(HashWriteCommand.Push("devices"), command)
  }

  @Test
  fun parseHash_emptyString_returnsHome() {
    assertEquals(WasmRoute.Home, parseHash(""))
  }

  @Test
  fun parseHash_all_returnsHome() {
    // `#all` is the overview marker — anything that\'s not `session/...` or
    // `devices` falls back to Home so old bookmarks keep working.
    assertEquals(WasmRoute.Home, parseHash("all"))
  }

  @Test
  fun parseHash_sessionPrefix_returnsSessionDetail() {
    assertEquals(WasmRoute.SessionDetail("abc-123"), parseHash("session/abc-123"))
  }

  @Test
  fun parseHash_devices_returnsDevices() {
    assertEquals(WasmRoute.Devices, parseHash("devices"))
  }

  @Test
  fun parseHash_roundTripsThroughRouteToHash() {
    // routeToHash → parseHash should be the identity on every WasmRoute, so
    // dead/asymmetric branches in either function show up as a test failure.
    val routes = listOf(WasmRoute.Home, WasmRoute.SessionDetail("xyz"), WasmRoute.Devices)
    for (route in routes) {
      assertEquals(route, parseHash(routeToHash(route)), "round-trip failed for $route")
    }
  }
}
