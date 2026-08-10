package xyz.block.trailblaze.host.screenstate

import xyz.block.trailblaze.api.AnnotationElement
import xyz.block.trailblaze.api.CompactScreenElements
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.api.toViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.axe.AxeCli
import xyz.block.trailblaze.host.axe.AxeJsonMapper
import xyz.block.trailblaze.host.axe.AxeViewportClamp
import java.nio.file.Files
import java.nio.file.Paths

/**
 * [ScreenState] for iOS Simulators driven by the AXe CLI.
 *
 * Parallel to [HostMaestroDriverScreenState] for the Maestro/XCUITest path. Captures the
 * accessibility tree via `axe describe-ui` (JSON is AXe's default output), parses it into
 * a [TrailblazeNode] tree with [xyz.block.trailblaze.api.DriverNodeDetail.IosAxe] detail,
 * and (lazily) captures a PNG screenshot via `axe screenshot`. Everything is lazy so CLI
 * flows that only need the tree (snapshot / `fast` mode) never pay for the screenshot.
 */
class AxeScreenState(
  private val udid: String,
  override val deviceWidth: Int,
  override val deviceHeight: Int,
  // Injectable for tests only — production always shells out to the AXe CLI.
  private val describeUi: () -> AxeCli.Result = { AxeCli.describeUi(udid) },
) : ScreenState {

  override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.IOS
  override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()

  /** Raw TrailblazeNode tree straight from AXe — no refs yet. */
  private val parsedTree: TrailblazeNode? by lazy {
    val res = describeUi()
    if (!res.success) {
      System.err.println("[AxeScreenState] axe describe-ui failed: ${res.stderr.trim()}")
      null
    } else {
      try {
        AxeJsonMapper.parse(res.stdout)
      } catch (e: Exception) {
        System.err.println("[AxeScreenState] axe describe-ui produced unparseable JSON: ${e.message}")
        null
      }
    }
  }

  /**
   * [parsedTree] with off-viewport nodes pruned — the ONLY tree this screen state exposes.
   * `axe describe-ui` spans the whole scroll content, while the Maestro/XCUITest path never
   * sees below-fold content in the first place (XCTest doesn't materialize it) and filters
   * the stragglers via `filterOutOfBounds`. Every consumer of this state — the compact
   * element list and its refs, `findMatches` / waypoint matching over [trailblazeNodeTree],
   * the Maestro-shaped [viewHierarchy] — must therefore see only on-screen content, or
   * off-viewport elements match on IOS_AXE in exactly the flows the clamp exists to fix
   * (e.g. a sub-10%-visible edge straddler earning a ref whose tap falls back to a blind
   * off-screen coordinate tap).
   *
   * When the clamp prunes EVERY node (transient zero-size frames, a synthetic multi-app
   * root with no bounds), fall back to the unclamped tree — Maestro parity again:
   * `ViewHierarchy.from` keeps the unfiltered tree when `filterOutOfBounds` filters
   * everything (`filtered ?: it`).
   */
  private val clampedTree: TrailblazeNode? by lazy {
    val parsed = parsedTree ?: return@lazy null
    val clamped = AxeViewportClamp.clamp(parsed, deviceWidth, deviceHeight)
    if (clamped == null) {
      System.err.println(
        "[AxeScreenState] viewport clamp pruned every node — falling back to the unclamped tree (Maestro parity)",
      )
    }
    clamped ?: parsed
  }

  /**
   * Compact element list built once over [clampedTree]. Feeds both the text representation
   * (for LLM prompts and snapshot output) and the ref mapping that gets stamped onto
   * [trailblazeNodeTree] so tools like `tap ref=e964` can find their target.
   *
   * Baseline rendering — no bounds / offscreen / all-elements detail, matching the default
   * [CompactScreenElements.buildForIos] / [CompactScreenElements.buildForAndroid] behavior.
   * Callers that need those details (e.g. `trailblaze snapshot --bounds`) re-render the tree
   * externally via [CompactScreenElements.buildForIosAxe] with the desired
   * [xyz.block.trailblaze.api.SnapshotDetail] set.
   */
  private val compactElements: CompactScreenElements? by lazy {
    val tree = clampedTree ?: return@lazy null
    CompactScreenElements.buildForIosAxe(
      tree = tree,
      screenHeight = deviceHeight,
      screenWidth = deviceWidth,
    )
  }

  /**
   * [clampedTree] with refs applied from [compactElements]. Consumers (e.g.
   * `TapTrailblazeTool`) look up nodes by ref — without the refs stamped on, `tap ref=e964`
   * can't find the element even though the snapshot output shows the ref. Selector
   * consumers (`findMatches`, waypoint matching) resolve against this tree too, so it must
   * be the clamped one: the host driver's equivalent carries no below-fold content, and an
   * unclamped tree here would let off-viewport elements match on IOS_AXE only.
   *
   * Lazy because `applyRefsToTree` is O(n) over the tree and multiple consumers
   * (tool dispatch, logging, SoM annotation) all read this on the hot path.
   */
  override val trailblazeNodeTree: TrailblazeNode? by lazy {
    val tree = clampedTree ?: return@lazy null
    compactElements?.applyRefsToTree(tree) ?: tree
  }

  /**
   * The Maestro-shaped tree, built from the same clamped tree: on the Maestro/XCUITest
   * path this hierarchy comes pre-filtered to on-screen nodes (maestro-client's
   * `ViewHierarchy.from` applies `filterOutOfBounds`), and consumers rely on that — e.g.
   * `scrollUntilTextIsVisible`'s manual loop picks the first match from this tree, so an
   * unclamped below-fold duplicate would hijack the scroll target.
   */
  override val viewHierarchy: ViewHierarchyTreeNode by lazy {
    trailblazeNodeTree?.toViewHierarchyTreeNode()
      ?: error("AxeScreenState: axe describe-ui did not produce a usable view hierarchy")
  }

  override val viewHierarchyTextRepresentation: String? by lazy { compactElements?.text }

  override val annotationElements: List<AnnotationElement>? by lazy {
    compactElements?.buildAnnotationElements()
  }

  override val screenshotBytes: ByteArray? by lazy {
    val tmp = Files.createTempFile("axe-screen-", ".png")
    val res = AxeCli.screenshot(udid, outputPath = tmp.toAbsolutePath().toString())
    if (!res.success) {
      System.err.println("[AxeScreenState] axe screenshot failed: ${res.stderr.trim()}")
      Files.deleteIfExists(tmp)
      null
    } else {
      try {
        Files.readAllBytes(Paths.get(tmp.toAbsolutePath().toString()))
      } finally {
        Files.deleteIfExists(tmp)
      }
    }
  }
}
