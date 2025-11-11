package xyz.block.trailblaze.viewmatcher

import maestro.DeviceInfo
import maestro.Maestro
import maestro.ViewHierarchy
import maestro.orchestra.filter.FilterWithDescription
import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.screenstate.toMaestroPlatform
import xyz.block.trailblaze.toolcalls.commands.TrailblazeElementSelectorExt.toMaestroElementSelector
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

  fun ViewHierarchyTreeNode.asTrailblazeElementSelector(): TrailblazeElementSelector = TrailblazeElementSelector(
    textRegex = resolveMaestroText()?.takeIf { it.isNotBlank() },
    idRegex = resourceId?.takeIf { it.isNotBlank() },
    enabled = enabled.takeIf { !it },
    selected = selected.takeIf { it },
    checked = checked.takeIf { it },
    focused = focused.takeIf { it },
  )

  /**
   * Gets all matching elements in the exact order that Orchestra/Maestro would match them
   */
  fun getMatchingElementsFromSelector(
    rootTreeNode: ViewHierarchyTreeNode,
    trailblazeElementSelector: TrailblazeElementSelector,
    trailblazeDevicePlatform: TrailblazeDevicePlatform,
    widthPixels: Int,
    heightPixels: Int,
  ): ElementMatches {
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

    // Use the first constructor with just the maestro parameter - other parameters have default values
    val constructor = orchestraKClass.constructors.first()
    val orchestra = constructor.callBy(mapOf(constructor.parameters.first() to maestro))
    val elementSelector = trailblazeElementSelector.toMaestroElementSelector()
    assert(elementSelector.description() == trailblazeElementSelector.description())

    // Replicate Orchestra's findElement logic for childOf handling
    // Source: https://github.com/mobile-dev-inc/Maestro/blob/42ae01049fc1e3466ad4ba45414b7bb25a19c899/maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt#L1168-L1182
    val searchHierarchy: ViewHierarchy = if (elementSelector.childOf != null) {
      // When childOf is specified, we need to find the parent element first and search within it
      findElementViewHierarchyMethod.call(orchestra, elementSelector.childOf, 0L) as ViewHierarchy
    } else {
      viewHierarchy
    }

    val computedFilterWithDescription = buildFilterMethod.call(orchestra, elementSelector) as FilterWithDescription
    val allElements = searchHierarchy.aggregate()
    val matchingNodes = computedFilterWithDescription.filterFunc(allElements)
    return when (matchingNodes.size) {
      0 -> ElementMatches.NoMatches(trailblazeElementSelector)
      1 -> ElementMatches.SingleMatch(matchingNodes.first(), trailblazeElementSelector)
      else -> ElementMatches.MultipleMatches(matchingNodes, trailblazeElementSelector)
    }
  }
}
