package xyz.block.trailblaze.playwright

import xyz.block.trailblaze.api.AnnotationElement
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.SnapshotDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode

/**
 * A capture for the session record rather than for a reader: the screenshot, the
 * [TrailblazeNode] tree (its bounds come from one DOM walk), and the legacy [viewHierarchy]
 * without bounds. It leaves out what only the LLM reads — the compact element list and the legacy
 * tree's per-node bounds — which cost about 6s per capture on a CI Mac.
 *
 * Everything is read at construction, so the page is touched only on the thread that builds this.
 */
internal class PlaywrightRecordScreenState(capture: PlaywrightScreenState) : ScreenState by capture {
  override val screenshotBytes: ByteArray? = capture.screenshotBytes
  override val trailblazeNodeTree: TrailblazeNode? = capture.trailblazeNodeTree
  override val viewHierarchy: ViewHierarchyTreeNode =
    PlaywrightAriaSnapshot.ariaSnapshotToViewHierarchy(capture.ariaSnapshotYaml)
  override val viewHierarchyTextRepresentation: String? get() = null
  override fun viewHierarchyTextRepresentation(details: Set<SnapshotDetail>): String? = null
  // The interface default derives this from the capture's own element list, a full rebuild.
  override val pageContextSummary: String? get() = null
  override val annotatedScreenshotBytes: ByteArray? get() = null
  override val annotationElements: List<AnnotationElement>? get() = null
}
