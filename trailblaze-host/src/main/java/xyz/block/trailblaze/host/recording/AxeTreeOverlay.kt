package xyz.block.trailblaze.host.recording

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.host.axe.AxeCli
import xyz.block.trailblaze.host.axe.AxeJsonMapper

/**
 * Enriches a Maestro-captured iOS [TrailblazeNode] tree with nodes from `axe describe-ui`.
 *
 * The XCUITest snapshot Maestro's `contentDescriptor` returns under-represents presented
 * sheets/modals — it collapses a bottom-sheet's subtree to a cluster of boundless, textless
 * center nodes, so selector taps and asserts on that content report "not found". Apple's AX
 * API (via AXe) captures the same frame with full roles, labels, and frames. Overlaying the
 * AXe nodes onto the Maestro tree keeps Maestro as the input channel while making the dropped
 * content resolvable.
 *
 * [mergeAxeIntoMaestroTree] and [selectorReferencesIosAxe] are pure functions over
 * [TrailblazeNode] (no device, no subprocess) so they unit-test directly; [captureAxeTree] is the
 * only impure member and declines cleanly (returns null) whenever `axe` is unavailable or errors.
 */
object AxeTreeOverlay {

  /**
   * An AXe node whose box covers this fraction of the screen is chrome (AXApplication /
   * AXWindow), never the dropped content we're recovering — excluded from the overlay.
   */
  internal const val CHROME_AREA_FRACTION = 0.95

  /**
   * Unions the AXe nodes the Maestro tree is missing into it. AXe sees the whole screen, so the
   * underlying content it shares with Maestro is dropped by a text+bounds match; only the nodes
   * Maestro lacked (the dropped sheet content) are appended, re-numbered to keep ids unique.
   * Chrome (full-screen containers) and textless AXe nodes are never added. Returns the Maestro
   * tree unchanged when AXe adds nothing, and passes either side through when the other is null.
   *
   * Known limitation: an AXe row whose text matches a Maestro node at overlapping bounds is treated
   * as already-present and dropped, so a sheet row colliding with a behind-node isn't recovered.
   */
  fun mergeAxeIntoMaestroTree(
    maestroTree: TrailblazeNode?,
    axeTree: TrailblazeNode?,
    screenWidth: Int,
    screenHeight: Int,
  ): TrailblazeNode? {
    if (axeTree == null) return maestroTree
    if (maestroTree == null) return axeTree

    val maestroNodes = maestroTree.aggregate()
    val maestroContent = maestroNodes.filter { it.contentText() != null && it.bounds != null }
    val screenArea = screenWidth.toLong() * screenHeight

    val additions = axeTree.aggregate().filter { axe ->
      val bounds = axe.bounds ?: return@filter false
      val text = axe.contentText() ?: return@filter false
      axe.hasArea() &&
        !isChrome(bounds, screenArea) &&
        maestroContent.none { it.contentText() == text && it.bounds!!.intersects(bounds) }
    }
    if (additions.isEmpty()) return maestroTree

    val nextId = (maestroNodes.maxOfOrNull { it.nodeId } ?: 0L) + 1
    // Appended AXe nodes are flattened (children dropped): a selector using childOf /
    // containsDescendants over recovered sheet content can't rely on that hierarchy surviving.
    val overlaid = additions.mapIndexed { index, node ->
      node.copy(nodeId = nextId + index, children = emptyList())
    }
    return maestroTree.copy(children = maestroTree.children + overlaid)
  }

  /**
   * Reads the current AXe accessibility tree for [udid], or null when `axe` is absent, errors,
   * or emits unparseable JSON — every failure keeps the caller on the Maestro-only tree. Mirrors
   * [xyz.block.trailblaze.host.screenstate.AxeScreenState]'s parse path.
   */
  fun captureAxeTree(
    udid: String,
    isAvailable: () -> Boolean = AxeCli::isAvailable,
    describeUi: (String) -> AxeCli.Result = AxeCli::describeUi,
  ): TrailblazeNode? {
    if (!isAvailable()) return null
    // Guard the whole read: a subprocess throw (ProcessBuilder.start under fd pressure, a drain
    // timeout) or a parse throw must decline to the Maestro-only tree, never fail the replay step.
    return try {
      val res = describeUi(udid)
      if (!res.success) {
        System.err.println("[AxeTreeOverlay] axe describe-ui failed: ${res.stderr.trim()}")
        null
      } else {
        AxeJsonMapper.parse(res.stdout)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      System.err.println("[AxeTreeOverlay] axe describe-ui errored: ${e.message}")
      null
    }
  }

  /**
   * Shared entry point for every iOS `toTrailblazeNode` capture that needs the overlay: reads the
   * AXe tree for [udid] on [Dispatchers.IO] (it hits Apple's AX API, not the XCUITest HTTP server
   * a driver mutex guards, so it never belongs under that lock) and merges the dropped content into
   * [maestroTree]. Returns [maestroTree] unchanged whenever `axe` is unavailable, errors, or adds
   * nothing — enrichment only ever adds.
   */
  suspend fun enrichIosTree(
    maestroTree: TrailblazeNode?,
    udid: String,
    screenWidth: Int,
    screenHeight: Int,
  ): TrailblazeNode? {
    val axeTree = withContext(Dispatchers.IO) { captureAxeTree(udid) } ?: return maestroTree
    return mergeAxeIntoMaestroTree(
      maestroTree = maestroTree,
      axeTree = axeTree,
      screenWidth = screenWidth,
      screenHeight = screenHeight,
    )
  }

  /**
   * True when [selector] (or any relational sub-selector) matches on the AXe dialect — i.e. it was
   * recorded against a node the overlay contributed. Only these selectors need the live tree
   * re-enriched on replay; a Maestro-dialect selector resolves against the base capture, so a trail
   * that never touched dropped content pays no axe cost.
   */
  fun selectorReferencesIosAxe(selector: TrailblazeNodeSelector?): Boolean {
    if (selector == null) return false
    if (selector.iosAxe != null) return true
    return selectorReferencesIosAxe(selector.below) ||
      selectorReferencesIosAxe(selector.above) ||
      selectorReferencesIosAxe(selector.leftOf) ||
      selectorReferencesIosAxe(selector.rightOf) ||
      selectorReferencesIosAxe(selector.childOf) ||
      selectorReferencesIosAxe(selector.containsChild) ||
      selector.containsDescendants?.any { selectorReferencesIosAxe(it) } == true
  }

  private fun isChrome(bounds: TrailblazeNode.Bounds, screenArea: Long): Boolean {
    if (screenArea <= 0L) return false
    return bounds.width.toLong() * bounds.height >= screenArea * CHROME_AREA_FRACTION
  }

  private fun TrailblazeNode.hasArea(): Boolean {
    val b = bounds ?: return false
    return b.width > 0 && b.height > 0
  }

  /** Best display/match text for a node, dispatched per driver — blank treated as absent. */
  private fun TrailblazeNode.contentText(): String? = when (val detail = driverDetail) {
    is DriverNodeDetail.AndroidAccessibility -> detail.resolveText()
    is DriverNodeDetail.AndroidMaestro -> detail.resolveText()
    is DriverNodeDetail.IosMaestro -> detail.resolveText()
    is DriverNodeDetail.IosAxe -> detail.resolveText()
    is DriverNodeDetail.Compose -> detail.resolveText()
    is DriverNodeDetail.Web -> detail.ariaName
  }?.takeIf { it.isNotBlank() }
}
