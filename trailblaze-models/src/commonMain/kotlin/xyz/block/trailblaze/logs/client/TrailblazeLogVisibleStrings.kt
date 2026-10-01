package xyz.block.trailblaze.logs.client

import xyz.block.trailblaze.api.ExtractedString
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.api.VisibleStringExtractor
import kotlin.random.Random

/**
 * Copies a screen-capture log with its readable strings filled in, or returns it unchanged.
 *
 * Called where a log is emitted — the logging rule every on-device and host runner shares, and the
 * host's disk sink as a catch-all for writers that skip the rule — so the strings are read from
 * the tree while it is in hand, and travel with the log over every transport and into every zip.
 *
 * A capture with strings but no screenshot is also given a [TrailblazeLog.AgentDriverLog.captureId]
 * here, since nothing else names it. Stamping it once, as the log is emitted, is what lets every
 * reader agree on it.
 *
 * Idempotent: a log that already carries strings (and its id) is returned as-is, so the device and
 * the host each calling this costs one extraction, not two. A log with no tree is returned as-is
 * too; it has nothing to read.
 *
 * Never throws. The strings are a convenience riding on the log, and a tree the extractor cannot
 * handle must not cost the log itself.
 */
fun TrailblazeLog.withVisibleStrings(): TrailblazeLog = try {
  when (this) {
    is TrailblazeLog.AgentDriverLog -> {
      val strings = visibleStrings ?: readStrings(trailblazeNodeTree, viewHierarchy, deviceWidth, deviceHeight, frameTrees)
      val id = captureId ?: newCaptureId(screenshotFile, strings)
      if (strings === visibleStrings && id === captureId) this else copy(visibleStrings = strings, captureId = id)
    }
    is TrailblazeLog.TrailblazeSnapshotLog ->
      if (visibleStrings != null) this else readStrings(trailblazeNodeTree, viewHierarchy, deviceWidth, deviceHeight)?.let { copy(visibleStrings = it) } ?: this
    is TrailblazeLog.TrailblazeLlmRequestLog -> {
      val strings = visibleStrings ?: readStrings(trailblazeNodeTree, viewHierarchy, deviceWidth, deviceHeight)
      val id = captureId ?: newCaptureId(screenshotFile, strings)
      if (strings === visibleStrings && id === captureId) this else copy(visibleStrings = strings, captureId = id)
    }
    else -> this
  }
} catch (e: Exception) {
  this
}

/**
 * A name for a capture that has strings but no screenshot: its time plus a random suffix, since
 * two such captures can land in the same millisecond. Null when a screenshot names the capture, or
 * when there are no strings to point at.
 */
private fun TrailblazeLog.newCaptureId(screenshotFile: String?, strings: List<ExtractedString>?): String? =
  if (!screenshotFile.isNullOrBlank() || strings == null) {
    null
  } else {
    "capture-${timestamp.toEpochMilliseconds()}-${Random.nextInt().toUInt().toString(16).padStart(8, '0')}"
  }

/**
 * The strings on a capture log, whether it carried them or predates them: the recorded list when
 * present, otherwise read from the log's tree. Null for a log with no tree or one that is not a
 * screen capture.
 */
val TrailblazeLog.visibleStringsOrExtracted: List<ExtractedString>?
  get() = when (this) {
    is TrailblazeLog.AgentDriverLog -> visibleStrings ?: readStrings(trailblazeNodeTree, viewHierarchy, deviceWidth, deviceHeight, frameTrees)
    is TrailblazeLog.TrailblazeSnapshotLog -> visibleStrings ?: readStrings(trailblazeNodeTree, viewHierarchy, deviceWidth, deviceHeight)
    is TrailblazeLog.TrailblazeLlmRequestLog -> visibleStrings ?: readStrings(trailblazeNodeTree, viewHierarchy, deviceWidth, deviceHeight)
    else -> null
  }

/**
 * The richer tree when the capture has one; the legacy Maestro tree only for logs that predate it.
 * A web page's iframe content ([frameTrees]) is read with the page's own strings.
 *
 * A childless legacy tree is the empty placeholder a writer puts in the required field when it
 * captured no hierarchy, so it reads as no tree — null — rather than as a screen with no text.
 */
private fun readStrings(
  tree: TrailblazeNode?,
  legacyTree: ViewHierarchyTreeNode?,
  deviceWidth: Int,
  deviceHeight: Int,
  frameTrees: List<TrailblazeNode>? = null,
): List<ExtractedString>? = when {
  tree != null -> VisibleStringExtractor.extract(tree, deviceWidth, deviceHeight, frameTrees.orEmpty())
  legacyTree != null && legacyTree.children.isNotEmpty() -> VisibleStringExtractor.extract(legacyTree)
  else -> null
}
