package xyz.block.trailblaze.api

import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * One selector **dialect**: the vocabulary a recorded selector is written in, and the shape of the
 * node tree that vocabulary matches against.
 *
 * A dialect is the join between three things that otherwise only line up by convention:
 *
 *  - a [DriverNodeMatch] variant / [TrailblazeNodeSelector] slot (what the YAML carries),
 *  - a [DriverNodeDetail] variant (what a captured tree carries), and
 *  - the driver whose runtime produces that tree ([nativeDriver]).
 *
 * Migrating a trail from one driver to another is exactly a source-dialect → target-dialect
 * rewrite, so the pieces that used to be hardcoded per pair (which leaf marks a selector as
 * unmigrated, which platform the matcher runs as, which leaf the writer emits) read off this enum
 * instead.
 *
 * Dialects are NOT interchangeable across platforms: no resolver bridge crosses a platform
 * boundary, so a migration pair must sit inside one platform.
 */
enum class SelectorDialect(
  /** The `nodeSelector:` slot key in trail YAML, e.g. `androidAccessibility`. */
  val yamlKey: String,
  /** The platform whose devices can produce and match this dialect. */
  val platform: TrailblazeDevicePlatform,
  /**
   * The driver whose runtime produces a tree in this dialect, or null when no driver does.
   *
   * One-way by design: a dialect names its canonical producer, but a driver can match dialects it
   * does not produce, and [forDriver] is the (wider) inverse — `web` is produced by both
   * Playwright drivers, so only the native one is named here.
   */
  val nativeDriver: TrailblazeDriverType?,
  /**
   * True when this dialect's selectors are resolved by lowering to a [TrailblazeElementSelector]
   * and running Maestro's own filter pipeline, rather than by
   * [TrailblazeNodeSelectorResolver]'s native per-field matching. The two Maestro-derived dialects
   * are the only ones: their recorded selectors were authored against Maestro's lenient
   * (case-insensitive, `resolveText`-collapsed) semantics, so reproducing a recorded match means
   * reproducing that matcher.
   */
  val resolvesViaMaestroPipeline: Boolean,
) {
  ANDROID_ACCESSIBILITY(
    yamlKey = "androidAccessibility",
    platform = TrailblazeDevicePlatform.ANDROID,
    nativeDriver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    resolvesViaMaestroPipeline = false,
  ),
  ANDROID_VIEW(
    yamlKey = "androidView",
    platform = TrailblazeDevicePlatform.ANDROID,
    nativeDriver = TrailblazeDriverType.ANDROID_TEST,
    resolvesViaMaestroPipeline = false,
  ),
  ANDROID_MAESTRO(
    yamlKey = "androidMaestro",
    platform = TrailblazeDevicePlatform.ANDROID,
    nativeDriver = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    resolvesViaMaestroPipeline = true,
  ),
  IOS_MAESTRO(
    yamlKey = "iosMaestro",
    platform = TrailblazeDevicePlatform.IOS,
    nativeDriver = TrailblazeDriverType.IOS_HOST,
    resolvesViaMaestroPipeline = true,
  ),
  IOS_AXE(
    yamlKey = "iosAxe",
    platform = TrailblazeDevicePlatform.IOS,
    nativeDriver = TrailblazeDriverType.IOS_AXE,
    resolvesViaMaestroPipeline = false,
  ),
  WEB(
    yamlKey = "web",
    platform = TrailblazeDevicePlatform.WEB,
    nativeDriver = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
    resolvesViaMaestroPipeline = false,
  ),

  /**
   * The Compose semantics tree. Unlike every other entry, this dialect is not a platform claim —
   * the same vocabulary serves an Android Compose hierarchy and a Compose Multiplatform host — so
   * callers that map a dialect to "which device is this step for" must exclude it rather than
   * trust [platform]. [platform] names its own driver's host, the Compose desktop window.
   */
  COMPOSE(
    yamlKey = "compose",
    platform = TrailblazeDevicePlatform.DESKTOP,
    nativeDriver = TrailblazeDriverType.COMPOSE,
    resolvesViaMaestroPipeline = false,
  ),
  ;

  /** This dialect's leaf on [selector] itself (not its combinators), or null if it carries none. */
  fun leafOf(selector: TrailblazeNodeSelector): DriverNodeMatch? = when (this) {
    ANDROID_ACCESSIBILITY -> selector.androidAccessibility
    ANDROID_VIEW -> selector.androidView
    ANDROID_MAESTRO -> selector.androidMaestro
    IOS_MAESTRO -> selector.iosMaestro
    IOS_AXE -> selector.iosAxe
    WEB -> selector.web
    COMPOSE -> selector.compose
  }

  /**
   * True when this dialect's leaf appears anywhere in [selector]'s tree — on the selector itself or
   * inside any spatial / hierarchy combinator.
   *
   * Recorded selectors routinely put the identifying leaf under a combinator (`containsChild:`
   * being the common one), so "is this selector written in dialect X" is a whole-tree question. A
   * selector with leaves in two dialects answers true to both, which is what makes
   * "source present AND target absent" a safe migration gate.
   */
  fun hasLeaf(selector: TrailblazeNodeSelector): Boolean {
    if (leafOf(selector) != null) return true
    val children = listOfNotNull(
      selector.below,
      selector.above,
      selector.leftOf,
      selector.rightOf,
      selector.childOf,
      selector.containsChild,
    ) + selector.containsDescendants.orEmpty()
    return children.any { hasLeaf(it) }
  }

  override fun toString(): String = yamlKey

  companion object {
    /** The dialect a captured node's properties are written in. */
    fun of(detail: DriverNodeDetail): SelectorDialect = when (detail) {
      is DriverNodeDetail.AndroidAccessibility -> ANDROID_ACCESSIBILITY
      is DriverNodeDetail.AndroidView -> ANDROID_VIEW
      is DriverNodeDetail.AndroidMaestro -> ANDROID_MAESTRO
      is DriverNodeDetail.IosMaestro -> IOS_MAESTRO
      is DriverNodeDetail.IosAxe -> IOS_AXE
      is DriverNodeDetail.Web -> WEB
      is DriverNodeDetail.Compose -> COMPOSE
    }

    /**
     * The dialect of the tree rooted at [root] — the shape every selector written against this
     * capture has to speak.
     *
     * Read off the root, which every producer stamps with the same variant it stamps its
     * descendants with. [TrailblazeNode.driverDetail] is non-null, so a tree always names a
     * dialect.
     */
    fun ofTree(root: TrailblazeNode): SelectorDialect = of(root.driverDetail)

    /** Dialect for a `nodeSelector:` slot key, or null when the key names no dialect. */
    fun fromYamlKey(yamlKey: String): SelectorDialect? = entries.find { it.yamlKey == yamlKey }

    /**
     * The dialect [driver]'s captures come out in, or null for drivers that produce no local tree.
     *
     * Exhaustive on purpose: a new driver must state its dialect here rather than silently
     * inheriting one.
     */
    fun forDriver(driver: TrailblazeDriverType): SelectorDialect? = when (driver) {
      TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY -> ANDROID_ACCESSIBILITY
      TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION -> ANDROID_MAESTRO
      TrailblazeDriverType.ANDROID_TEST -> ANDROID_VIEW
      TrailblazeDriverType.IOS_HOST -> IOS_MAESTRO
      TrailblazeDriverType.IOS_AXE -> IOS_AXE
      TrailblazeDriverType.PLAYWRIGHT_NATIVE, TrailblazeDriverType.PLAYWRIGHT_ELECTRON -> WEB
      TrailblazeDriverType.COMPOSE -> COMPOSE
      // Cloud runners: the tree comes back from the vendor's API in the vendor's own shape, with
      // no Trailblaze dialect to name.
      TrailblazeDriverType.REVYL_ANDROID, TrailblazeDriverType.REVYL_IOS -> null
    }
  }
}
