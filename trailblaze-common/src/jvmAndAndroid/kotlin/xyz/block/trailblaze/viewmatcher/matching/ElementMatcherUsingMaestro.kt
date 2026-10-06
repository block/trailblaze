package xyz.block.trailblaze.viewmatcher.matching

import maestro.DeviceInfo
import maestro.Maestro
import maestro.MaestroException
import maestro.ViewHierarchy
import maestro.orchestra.ElementSelector
import maestro.orchestra.filter.FilterWithDescription
import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.toMaestroPlatform
import xyz.block.trailblaze.toolcalls.commands.TrailblazeElementSelectorExt.toMaestroElementSelector
import xyz.block.trailblaze.tracing.TrailblazeTracer
import xyz.block.trailblaze.viewmatcher.models.ElementMatches
import xyz.block.trailblaze.yaml.TrailblazeYaml
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.jvm.isAccessible

/**
 * This class allows us to call Maestro's internal implementations for element matching to guarantee uniqueness
 * when calculating selectors by nodeId
 */
object ElementMatcherUsingMaestro {
  /** Trailblaze's custom on-device fork on Orchestra */
  private val androidOnDeviceCustomMaestroOrchestraClass: Class<*>? = try {
    Class.forName("xyz.block.trailblaze.android.maestro.orchestra.Orchestra")
  } catch (e: ClassNotFoundException) {
    null
  }

  /** Kotlin class for Orchestra */
  private val orchestraKClass = androidOnDeviceCustomMaestroOrchestraClass?.kotlin ?: maestro.orchestra.Orchestra::class

  /**
   * Which Orchestra the reflective lookup above actually landed on. Public only so
   * `OrchestraReflectiveContractTest` can assert it: the fallback to Maestro's own `Orchestra` is
   * silent, and every other assertion in that test passes on either class — without this, a
   * renamed or repackaged fork would leave the test green and the on-device matcher broken.
   */
  val resolvedOrchestraClassName: String = orchestraKClass.qualifiedName.orEmpty()

  /**
   * Private method in Orchestra used via reflection
   */
  private val buildFilterMethod = orchestraKClass.memberFunctions
    .find { it.name == "buildFilter" && it.parameters.size == 2 }
    ?.also { it.isAccessible = true }
    ?: throw IllegalStateException("Could not find buildFilter method")

  /**
   * Gets all matching elements in the exact order that Orchestra/Maestro would match them
   */
  fun getMatchingElementsFromSelector(
    rootTreeNode: ViewHierarchyTreeNode,
    trailblazeElementSelector: TrailblazeElementSelector,
    trailblazeDevicePlatform: TrailblazeDevicePlatform,
    widthPixels: Int,
    heightPixels: Int,
  ): ElementMatches = TrailblazeTracer.trace(
    "getMatchingElementsFromSelector",
    this::class.simpleName!!,
    mapOf(
      "trailblazeElementSelector" to TrailblazeYaml.defaultYamlInstance.encodeToString(
        TrailblazeElementSelector.serializer(),
        trailblazeElementSelector,
      ),
    ),
  ) {
    val maestroRootTreeNode = rootTreeNode.asTreeNode()
    val viewHierarchy = ViewHierarchy(maestroRootTreeNode)
    val maestro = Maestro(
      driver = ViewHierarchyOnlyDriver(
        rootTreeNode = maestroRootTreeNode,
        deviceInfo = DeviceInfo(
          platform = trailblazeDevicePlatform.toMaestroPlatform(),
          widthPixels = widthPixels,
          heightPixels = heightPixels,
          widthGrid = widthPixels,
          heightGrid = heightPixels,
        ),
      ),
    )

    // Every lookup below passes an explicit 0L timeout. We're matching against a static snapshot —
    // there's nothing to poll for; if the element isn't in the captured tree it never will be.
    // Letting a lookup poll instead would cost ~17s per non-matching log, turning a
    // few-hundred-log `migrate-trail` cursor scan into multiple hours.
    val constructor = orchestraKClass.constructors.first()
    val paramsByName = constructor.parameters.associateBy { it.name }
    val args = mutableMapOf<kotlin.reflect.KParameter, Any?>()
    paramsByName["maestro"]?.let { args[it] = maestro }
    // Only the host fallback (upstream maestro.orchestra.Orchestra) has these constructor params;
    // its defaults are 17s / 7s. Today buildFilter doesn't poll on them — it composes pure
    // filters — so this is insurance against a Maestro bump moving a lookup behind the
    // constructor default, which would only ever surface as a migrate-trail scan taking hours.
    // No-ops on the vendored fork.
    paramsByName["lookupTimeoutMs"]?.let { args[it] = 0L }
    paramsByName["optionalLookupTimeoutMs"]?.let { args[it] = 0L }
    val orchestra = constructor.callBy(args)
    val elementSelector = trailblazeElementSelector.toMaestroElementSelector()
    assert(elementSelector.description() == trailblazeElementSelector.description())

    // childOf scopes the search to the first element matching the parent selector (recursively,
    // for a parent that has its own childOf) — the same resolution Maestro's Orchestra.findElement
    // does. It's done here with buildFilter alone, so the only private Orchestra function this
    // matcher depends on is buildFilter.
    val searchHierarchy: ViewHierarchy = elementSelector.childOf?.let { parentSelector ->
      resolveParentHierarchy(orchestra, parentSelector, viewHierarchy)
        ?: throw MaestroException.ElementNotFound(
          "Parent element not found: ${parentSelector.description()}",
          viewHierarchy.root,
          debugMessage = "No element matched the childOf parent, so its children were never searched.",
        )
    } ?: viewHierarchy
    return try {
      val computedFilterWithDescription = buildFilter(orchestra, elementSelector)
      val allElements = searchHierarchy.aggregate()
      val matchingNodes = computedFilterWithDescription.filterFunc(allElements)
      when (matchingNodes.size) {
        0 -> ElementMatches.NoMatches(trailblazeElementSelector)
        1 -> ElementMatches.SingleMatch(matchingNodes.first(), trailblazeElementSelector)
        else -> ElementMatches.MultipleMatches(matchingNodes, trailblazeElementSelector)
      }
    } catch (e: Exception) {
      throw RuntimeException("Exception thrown while using selector $trailblazeElementSelector", e)
    }
  }

  private fun buildFilter(orchestra: Any, selector: ElementSelector): FilterWithDescription =
    buildFilterMethod.call(orchestra, selector) as FilterWithDescription

  /** The subtree of the first element matching [selector], or null when nothing matches. */
  private fun resolveParentHierarchy(
    orchestra: Any,
    selector: ElementSelector?,
    hierarchy: ViewHierarchy,
  ): ViewHierarchy? {
    if (selector == null) return hierarchy
    val grandparentHierarchy = resolveParentHierarchy(orchestra, selector.childOf, hierarchy) ?: return null
    return buildFilter(orchestra, selector).filterFunc(grandparentHierarchy.aggregate()).firstOrNull()
      ?.let { ViewHierarchy(it) }
  }
}
