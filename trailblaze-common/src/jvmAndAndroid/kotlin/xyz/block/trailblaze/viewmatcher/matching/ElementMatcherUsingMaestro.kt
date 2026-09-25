package xyz.block.trailblaze.viewmatcher.matching

import maestro.DeviceInfo
import maestro.Maestro
import maestro.ViewHierarchy
import maestro.orchestra.filter.FilterWithDescription
import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.toMaestroPlatform
import xyz.block.trailblaze.toolcalls.commands.TrailblazeElementSelectorExt.toMaestroElementSelector
import xyz.block.trailblaze.tracing.TrailblazeTracer
import xyz.block.trailblaze.viewmatcher.models.ElementMatches
import xyz.block.trailblaze.yaml.TrailblazeYaml
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.jvm.isAccessible
import kotlinx.coroutines.runBlocking

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
   * Private method in Orchestra used via reflection to find element view hierarchy
   */
  private val findElementViewHierarchyMethod = orchestraKClass.memberFunctions
    .find { it.name == "findElementViewHierarchy" && it.parameters.size == 3 }
    ?.also { it.isAccessible = true }
    ?: throw IllegalStateException("Could not find findElementViewHierarchy method")

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
    // its defaults are 17s / 7s. Today neither reflected entrypoint polls on them — buildFilter
    // composes pure filters and findElementViewHierarchy is handed an explicit 0L — so this is
    // insurance against a Maestro bump moving a lookup behind the constructor default, which
    // would only ever surface as a migrate-trail scan taking hours. No-ops on the vendored fork.
    paramsByName["lookupTimeoutMs"]?.let { args[it] = 0L }
    paramsByName["optionalLookupTimeoutMs"]?.let { args[it] = 0L }
    val orchestra = constructor.callBy(args)
    val elementSelector = trailblazeElementSelector.toMaestroElementSelector()
    assert(elementSelector.description() == trailblazeElementSelector.description())

    // Replicate Orchestra's findElement logic for childOf handling
    // Source: https://github.com/mobile-dev-inc/Maestro/blob/42ae01049fc1e3466ad4ba45414b7bb25a19c899/maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt#L1168-L1182
    val searchHierarchy: ViewHierarchy = if (elementSelector.childOf != null) {
      // When childOf is specified, we need to find the parent element first and search within it.
      // findElementViewHierarchy became a suspend fun in Maestro 2.6.1 (our vendored on-device
      // Orchestra matches that signature), so invoke it through reflection's suspend-aware
      // callSuspend inside runBlocking. We're resolving against a static snapshot with a 0L
      // timeout, so this never actually blocks on device I/O.
      runBlocking {
        findElementViewHierarchyMethod.callSuspend(orchestra, elementSelector.childOf, 0L) as ViewHierarchy
      }
    } else {
      viewHierarchy
    }
    return try {
      val computedFilterWithDescription =
        buildFilterMethod.call(orchestra, elementSelector) as FilterWithDescription
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
}
