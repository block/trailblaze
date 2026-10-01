package xyz.block.trailblaze.playwright

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.ScreenshotScale
import com.microsoft.playwright.options.ScreenshotType
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import xyz.block.trailblaze.api.AnnotationElement
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.SnapshotDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * What a recorded web replay keeps of the page before each action: the accessibility tree with
 * every element's box, from one `ariaSnapshot` call, and optionally a JPEG the browser encodes
 * itself. The tree is the one a live capture builds — same nodes, same `cssSelector` and
 * `dataTestId` — so recorded selectors resolve against it, plus what reading the page needs:
 * each text run's own box, the page's URL and title on the root, and each iframe's content as a
 * tree of its own (see [PlaywrightReplayTree]). The report can show every string on the page and where it sat
 * without paying for the LLM's view of it.
 *
 * Bounds are viewport coordinates, the screenshot's space. Everything is read at construction,
 * so the page is touched only on the thread that builds this.
 *
 * [secrets] are the values `web_fillSecret` typed this session. Each is blanked wherever the tree
 * carries it (a field's value reads as its text), and while any field still holds one there is no
 * screenshot: an OTP or token field renders its value, unlike a password field.
 */
internal class PlaywrightTreeScreenState(
  page: Page,
  override val deviceWidth: Int,
  override val deviceHeight: Int,
  private val browserEngine: BrowserEngine,
  withScreenshot: Boolean,
  secrets: Set<String> = emptySet(),
) : ScreenState {
  private val snapshot: PlaywrightAriaSnapshot.BoxedAriaSnapshot?

  /** When the tree was read: the report shows this capture's video frame from this moment. */
  val capturedAt: Instant
  override val trailblazeNodeTree: TrailblazeNode?

  /** Each iframe's content, beside the page tree rather than in it (see [PlaywrightReplayTree.captureFrames]). */
  val frameTrees: List<TrailblazeNode>
  override val screenshotBytes: ByteArray?

  init {
    capturedAt = Clock.System.now()
    val raw = PlaywrightAriaSnapshot.captureBoxedAriaSnapshot(page)
    // Kept redacted, since [viewHierarchy] is built from it.
    snapshot = if (raw == null || secrets.isEmpty()) raw else PlaywrightAriaSnapshot.BoxedAriaSnapshot(
      raw.lines.map { PlaywrightReplayTree.redactText(it, secrets) },
      raw.boxes,
    )
    val tree = raw?.let { PlaywrightTrailblazeNodeMapper.mapBoxedSnapshot(it, page) }
      ?.let { PlaywrightReplayTree.locateTextRuns(it, page.mainFrame()) }
      ?.let { withPage(it, page) }
    val frames = tree?.let { PlaywrightReplayTree.captureFrames(it, page) }.orEmpty()
    trailblazeNodeTree = tree?.let { PlaywrightReplayTree.redact(it, secrets) }
    frameTrees = frames.map { PlaywrightReplayTree.redact(it, secrets) }
    // The unredacted trees also count: they reach text the page's own text leaves out, like a
    // shadow root's.
    val showsSecret = withScreenshot && secrets.isNotEmpty() &&
      (PlaywrightReplayTree.mentions(listOfNotNull(tree) + frames, secrets) || PlaywrightReplayTree.holdsSecret(page, secrets))
    screenshotBytes = if (withScreenshot && !showsSecret) captureJpeg(page) else null
  }

  /** Derived from the snapshot alone, so building it later never touches the page. */
  override val viewHierarchy: ViewHierarchyTreeNode by lazy {
    snapshot?.let { PlaywrightAriaSnapshot.ariaSnapshotToViewHierarchy(it) }
      ?: ViewHierarchyTreeNode(nodeId = 1, className = "document")
  }

  val hasContent: Boolean get() = snapshot != null || screenshotBytes != null

  override val annotatedScreenshotBytes: ByteArray? get() = null
  override val annotationElements: List<AnnotationElement>? get() = null
  override val viewHierarchyTextRepresentation: String? get() = null
  override fun viewHierarchyTextRepresentation(details: Set<SnapshotDetail>): String? = null
  override val pageContextSummary: String? get() = null
  override val trailblazeDevicePlatform: TrailblazeDevicePlatform get() = TrailblazeDevicePlatform.WEB
  override val deviceClassifiers: List<TrailblazeDeviceClassifier>
    get() = listOf(
      TrailblazeDeviceClassifier(browserEngine.displayName),
      TrailblazeDeviceClassifier(
        when {
          deviceWidth < 768 -> "mobile"
          deviceWidth < 1024 -> "tablet"
          else -> "desktop"
        },
      ),
    )

  /** The document root carries the page's URL and title, so a capture says which page it is. */
  private fun withPage(tree: TrailblazeNode, page: Page): TrailblazeNode {
    val detail = tree.driverDetail as? DriverNodeDetail.Web ?: return tree
    val title = try { page.title() } catch (_: Exception) { null }
    return tree.copy(driverDetail = detail.copy(url = page.url(), title = title?.ifBlank { null }))
  }

  private companion object {
    /**
     * Encoded by the browser at CSS scale, so the JVM never decodes or re-encodes it, and
     * without `animations: disabled`, which fast-forwards the page's own transitions — a
     * pre-action capture must leave the page as the action will find it.
     */
    fun captureJpeg(page: Page): ByteArray? = try {
      page.screenshot(
        Page.ScreenshotOptions()
          .setType(ScreenshotType.JPEG)
          .setQuality(70)
          .setScale(ScreenshotScale.CSS)
          .setTimeout(500.0),
      )
    } catch (_: Exception) {
      null
    }
  }
}

/**
 * What a recorded web replay captures before each action, from `TRAILBLAZE_WEB_REPLAY_CAPTURE`.
 */
enum class WebReplayCapture {
  /** Nothing per action; the session keeps its final capture and its video. */
  OFF,

  /**
   * The boxed accessibility tree only: the default. The report takes each capture's picture from
   * the session's video, so a JPEG per action would add wall time and bytes for the same pixels.
   */
  TREE,

  /** The tree and a browser-encoded JPEG, for a report read without its video (an export). */
  TREE_JPEG,
  ;

  companion object {
    const val ENV = "TRAILBLAZE_WEB_REPLAY_CAPTURE"

    fun fromEnv(value: String? = System.getenv(ENV)): WebReplayCapture =
      when (value?.trim()?.lowercase()) {
        "off", "none", "false" -> OFF
        "tree" -> TREE
        "tree+jpeg", "tree-jpeg", "jpeg" -> TREE_JPEG
        else -> TREE
      }
  }
}
