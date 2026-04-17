package xyz.block.trailblaze.model

/**
 * Marker for Set-of-Mark screenshot annotations.
 *
 * Set-of-mark is always enabled. [xyz.block.trailblaze.api.ScreenState.annotatedScreenshotBytes]
 * draws numbered overlays on interactable elements so the LLM can reference them by ID.
 *
 * Which elements are annotated is controlled by [xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter].
 */
object SetOfMarkConfig
