package xyz.block.trailblaze.model

import kotlinx.serialization.Serializable

/**
 * Controls whether playback and recording use the new [TrailblazeNodeSelector]-based path
 * or the legacy Maestro [TrailblazeElementSelector] path.
 *
 * Applies to Maestro-based drivers (iOS Maestro, Android Maestro).
 * Android Accessibility has its own execution path and is unaffected.
 */
@Serializable
enum class NodeSelectorMode {
  /** Try nodeSelector first; fall back to legacy Maestro if it returns null. */
  PREFER_NODE_SELECTOR,

  /** Always use the legacy Maestro command path. Ignores nodeSelector even if present. */
  FORCE_LEGACY,

  /** Always try nodeSelector first (converting legacy selectors if needed); fall back to legacy if the driver cannot handle it. */
  FORCE_NODE_SELECTOR;

  companion object {
    val DEFAULT = PREFER_NODE_SELECTOR
  }
}