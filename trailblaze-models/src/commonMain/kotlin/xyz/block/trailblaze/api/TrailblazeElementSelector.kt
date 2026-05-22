package xyz.block.trailblaze.api

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

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

  /**
   * Converts this legacy selector to a [TrailblazeNodeSelector] for the given [platform]'s
   * Maestro driver. This is the inverse of [TrailblazeNodeSelector.toTrailblazeElementSelector].
   *
   * The conversion is lossy: properties without a driver-native equivalent (size, traits, css)
   * are dropped, and `textRegex` maps to a single text field (the driver's primary text property).
   *
   * Spatial and hierarchy relationships are recursively converted.
   */
  fun toTrailblazeNodeSelector(platform: TrailblazeDevicePlatform): TrailblazeNodeSelector {
    val convertedIndex = index?.toDoubleOrNull()?.toInt()
    val driverMatch: DriverNodeMatch? = when (platform) {
      TrailblazeDevicePlatform.IOS -> {
        if (textRegex != null || idRegex != null || focused != null || selected != null) {
          DriverNodeMatch.IosMaestro(
            textRegex = textRegex,
            resourceIdRegex = idRegex,
            focused = focused,
            selected = selected,
          )
        } else {
          null
        }
      }
      TrailblazeDevicePlatform.ANDROID -> {
        if (textRegex != null || idRegex != null || enabled != null || focused != null ||
          selected != null || checked != null
        ) {
          DriverNodeMatch.AndroidMaestro(
            textRegex = textRegex,
            resourceIdRegex = idRegex,
            enabled = enabled,
            focused = focused,
            selected = selected,
            checked = checked,
          )
        } else {
          null
        }
      }
      TrailblazeDevicePlatform.WEB -> null
      // The Compose desktop driver uses [TrailblazeNodeSelector] natively (selectors are
      // authored against [DriverNodeMatch.Compose]); converting from the legacy
      // Maestro-shaped [TrailblazeElementSelector] is lossy in the same way Web is and
      // not used in the Compose authoring path. Mirror Web's "drop the conversion" stance.
      TrailblazeDevicePlatform.DESKTOP -> null
    }

    return TrailblazeNodeSelector(
      iosMaestro = (driverMatch as? DriverNodeMatch.IosMaestro),
      androidMaestro = (driverMatch as? DriverNodeMatch.AndroidMaestro),
      index = convertedIndex,
      below = below?.toTrailblazeNodeSelector(platform),
      above = above?.toTrailblazeNodeSelector(platform),
      leftOf = leftOf?.toTrailblazeNodeSelector(platform),
      rightOf = rightOf?.toTrailblazeNodeSelector(platform),
      childOf = childOf?.toTrailblazeNodeSelector(platform),
      containsChild = containsChild?.toTrailblazeNodeSelector(platform),
      containsDescendants = containsDescendants?.map { it.toTrailblazeNodeSelector(platform) },
    )
  }

  companion object
}

/**
 * Returns true if this selector carries no Maestro-matchable predicate, anywhere in its
 * structural tree. A "matchable" predicate is one Maestro Orchestra can actually filter on:
 * [textRegex], [idRegex], the state booleans, [index], [css], [traits], [size]. Structural
 * relations ([containsChild], [childOf], [below], [above], [leftOf], [rightOf],
 * [containsDescendants]) only matter if they themselves contain a matchable predicate —
 * a structural wrapper around an empty inner selector is just as blank as no wrapper at all.
 *
 * This is the "would Maestro receive a selector with zero predicates?" check, used as the
 * gate when lowering a [TrailblazeNodeSelector] into a [TrailblazeElementSelector] for the
 * Maestro orchestra path. A blank lowering would silently match an arbitrary element rather
 * than failing, so callers throw on it.
 */
fun TrailblazeElementSelector.isBlank(): Boolean {
  // Any scalar matchable predicate set? Not blank.
  if (textRegex != null || idRegex != null ||
    focused != null || selected != null || enabled != null || checked != null ||
    css != null || index != null || size != null || !traits.isNullOrEmpty()
  ) {
    return false
  }
  // Any structural relation with a non-blank inner selector? Not blank.
  if (below != null && !below.isBlank()) return false
  if (above != null && !above.isBlank()) return false
  if (leftOf != null && !leftOf.isBlank()) return false
  if (rightOf != null && !rightOf.isBlank()) return false
  if (childOf != null && !childOf.isBlank()) return false
  if (containsChild != null && !containsChild.isBlank()) return false
  if (containsDescendants?.any { !it.isBlank() } == true) return false
  return true
}
