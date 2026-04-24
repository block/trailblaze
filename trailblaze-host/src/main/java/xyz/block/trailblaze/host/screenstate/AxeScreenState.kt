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
) : ScreenState {

  override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.IOS
  override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()

  /** Raw TrailblazeNode tree straight from AXe — no refs yet. */
  private val parsedTree: TrailblazeNode? by lazy {
    val res = AxeCli.describeUi(udid)
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
   * Compact element list built once over [parsedTree]. Feeds both the text representation
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
    val tree = parsedTree ?: return@lazy null
    CompactScreenElements.buildForIosAxe(
      tree = tree,
      screenHeight = deviceHeight,
      screenWidth = deviceWidth,
    )
  }

  /**
   * Tree with refs applied from [compactElements]. Consumers (e.g. `TapTrailblazeTool`)
   * look up nodes by ref — without the refs stamped on, `tap ref=e964` can't find
   * the element even though the snapshot output shows the ref.
   *
   * Lazy because `applyRefsToTree` is O(n) over the tree and multiple consumers
   * (tool dispatch, logging, SoM annotation) all read this on the hot path.
   */
  override val trailblazeNodeTree: TrailblazeNode? by lazy {
    val tree = parsedTree ?: return@lazy null
    compactElements?.applyRefsToTree(tree) ?: tree
  }

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
