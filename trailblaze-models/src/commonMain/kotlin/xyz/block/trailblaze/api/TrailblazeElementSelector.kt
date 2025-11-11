package xyz.block.trailblaze.api

import kotlinx.serialization.Serializable

/**
 * Element selector for UI automation, supporting property matching, spatial relationships, and hierarchy constraints.
 *
 * ## Matching Strategies
 * - **Properties**: text, id, enabled/selected/checked/focused state
 * - **Spatial**: above, below, leftOf, rightOf another element
 * - **Hierarchy**: childOf parent, containsChild/containsDescendants
 * - **Positioning**: index (sorted by Y, then X)
 *
 * ## Important Notes
 * - **containsChild**: Only finds FIRST matching child (can give false positives). Use containsDescendants for reliability.
 * - **Index**: Applied after sorting top-to-bottom, left-to-right
 * - **Recursive**: All relationship properties accept nested selectors
 *
 * @see TapSelectorV2.findBestTrailblazeElementSelectorForTargetNode
 * @see Maestro's ElementSelector: https://github.com/mobile-dev-inc/Maestro/blob/main/maestro-orchestra-models/src/main/java/maestro/orchestra/ElementSelector.kt
 */
@Serializable
data class TrailblazeElementSelector(
  val textRegex: String? = null,
  val idRegex: String? = null,
  val size: TrailblazeElementSelectorSizeSelector? = null,
  val traits: List<TrailblazeElementSelectorElementTrait>? = null,
  val index: String? = null,
  val enabled: Boolean? = null,
  val selected: Boolean? = null,
  val checked: Boolean? = null,
  val focused: Boolean? = null,
  val css: String? = null,
  val below: TrailblazeElementSelector? = null,
  val above: TrailblazeElementSelector? = null,
  val leftOf: TrailblazeElementSelector? = null,
  val rightOf: TrailblazeElementSelector? = null,
  /** A Unique direct child.  Maestro will only pick the first child that matches, so make sure it's unique. */
  val containsChild: TrailblazeElementSelector? = null,
  val containsDescendants: List<TrailblazeElementSelector>? = null,
  val childOf: TrailblazeElementSelector? = null,
) {
  fun description(): String {
    val descriptions = mutableListOf<String>()

    textRegex?.let {
      descriptions.add("\"$it\"")
    }

    idRegex?.let {
      descriptions.add("id: $it")
    }

    enabled?.let {
      when (enabled) {
        true -> descriptions.add("enabled")
        false -> descriptions.add("disabled")
      }
    }

    below?.let {
      descriptions.add("Below ${it.description()}")
    }

    above?.let {
      descriptions.add("Above ${it.description()}")
    }

    leftOf?.let {
      descriptions.add("Left of ${it.description()}")
    }

    rightOf?.let {
      descriptions.add("Right of ${it.description()}")
    }

    containsChild?.let {
      descriptions.add("Contains child: ${it.description()}")
    }

    containsDescendants?.let { selectors ->
      val descendantDescriptions = selectors.joinToString(", ") { it.description() }
      descriptions.add("Contains descendants: [$descendantDescriptions]")
    }

    size?.let {
      var description = "Size: ${it.width}x${it.height}"
      it.tolerance?.let { tolerance ->
        description += "(tolerance: $tolerance)"
      }

      descriptions.add(description)
    }

    traits?.let {
      descriptions.add(
        "Has traits: ${traits.joinToString(", ") { it.description }}",
      )
    }

    index?.let {
      descriptions.add("Index: ${it.toDoubleOrNull()?.toInt() ?: it}")
    }

    selected?.let {
      when (selected) {
        true -> descriptions.add("selected")
        false -> descriptions.add("not selected")
      }
    }

    focused?.let {
      when (focused) {
        true -> descriptions.add("focused")
        false -> descriptions.add("not focused")
      }
    }

    childOf?.let {
      descriptions.add("Child of: ${it.description()}")
    }

    css?.let {
      descriptions.add("CSS: $it")
    }

    return descriptions.joinToString(", ")
  }
}
