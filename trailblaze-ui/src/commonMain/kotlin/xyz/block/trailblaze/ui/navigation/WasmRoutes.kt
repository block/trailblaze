package xyz.block.trailblaze.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Navigation routes for WASM application.
 * These routes support browser URLs and bookmarking.
 */
@Serializable
sealed interface WasmRoute {

  /**
   * Home route showing the session list
   */
  @Serializable
  data object Home : WasmRoute

  /**
   * Session detail route with session ID as a navigation argument
   *
   * This enables:
   * - Type-safe navigation with compile-time checking
   * - Direct URLs: https://app.com/#/session/abc123
   * - Browser back/forward button support
   * - Bookmarkable session URLs
   */
  @Serializable
  data class SessionDetail(val sessionId: String) : WasmRoute
}
