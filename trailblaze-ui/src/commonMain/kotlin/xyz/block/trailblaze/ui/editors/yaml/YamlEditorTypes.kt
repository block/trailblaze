package xyz.block.trailblaze.ui.editors.yaml

import kotlinx.serialization.Serializable

/**
 * Editor mode for the YAML tab - either text-based or visual editor.
 */
@Serializable
enum class YamlEditorMode {
  TEXT,
  VISUAL
}

/**
 * Sub-view mode within the visual editor - Configuration or Steps.
 */
@Serializable
enum class YamlVisualEditorView {
  CONFIG,
  STEPS
}
