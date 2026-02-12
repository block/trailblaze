package xyz.block.trailblaze.toolcalls.commands

import maestro.orchestra.ElementSelector
import xyz.block.trailblaze.api.TrailblazeElementSelector

object TrailblazeElementSelectorExt {
  /**
   * Extension function to convert TrailblazeElementSelector to Maestro's ElementSelector
   */
  fun TrailblazeElementSelector.toMaestroElementSelector(): ElementSelector = ElementSelector(
    textRegex = this.textRegex,
    idRegex = this.idRegex,
    size = this.size?.let {
      ElementSelector.SizeSelector(
        width = it.width,
        height = it.height,
        tolerance = it.tolerance,
      )
    },
    below = this.below?.toMaestroElementSelector(),
    above = this.above?.toMaestroElementSelector(),
    leftOf = this.leftOf?.toMaestroElementSelector(),
    rightOf = this.rightOf?.toMaestroElementSelector(),
    containsChild = this.containsChild?.toMaestroElementSelector(),
    containsDescendants = this.containsDescendants?.map { it.toMaestroElementSelector() },
    traits = null, // Maestro ElementSelector doesn't have traits
    index = this.index,
    enabled = this.enabled,
    selected = this.selected,
    checked = this.checked,
    focused = this.focused,
    childOf = this.childOf?.toMaestroElementSelector(),
    css = this.css,
  )
}
