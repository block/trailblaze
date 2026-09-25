/*
 *
 *  Copyright (c) 2022 mobile.dev inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package xyz.block.trailblaze.android.maestro.orchestra

import maestro.ElementFilter
import maestro.Filters
import maestro.Filters.asFilter
import maestro.Maestro
import maestro.MaestroException
import maestro.ViewHierarchy
import maestro.orchestra.ElementSelector
import maestro.orchestra.filter.FilterWithDescription
import maestro.orchestra.filter.TraitFilters
import maestro.utils.StringUtils.toRegexSafe

/**
 * A two-function remnant of Maestro's `Orchestra`, kept only so selector matching can reuse
 * Maestro's own matching logic instead of reimplementing it.
 *
 * Nothing calls this class directly. `ElementMatcherUsingMaestro` (in `trailblaze-common`) reaches
 * it by reflection, BY STRING: the fully-qualified name below, plus the names and arities of the
 * two private functions. See the README in this package before touching any of those.
 *
 * The command-executing body this was cut down from went away with the on-device UiAutomator
 * driver — it was the only thing that ever ran a Maestro flow on a device.
 *
 * Derived from Maestro v2.6.1:
 * https://github.com/mobile-dev-inc/Maestro/blob/cli-2.6.1/maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt
 */
class Orchestra(
  private val maestro: Maestro,
) {

  private suspend fun findElementViewHierarchy(
    selector: ElementSelector?,
    timeout: Long,
  ): ViewHierarchy {
    if (selector == null) {
      return maestro.viewHierarchy()
    }
    val parentViewHierarchy = findElementViewHierarchy(selector.childOf, timeout)
    val (description, filterFunc) = buildFilter(selector = selector)
    val debugMessage = """
            Element with $description not found. Check the UI hierarchy in debug artifacts to verify if the element exists.

            Possible causes:
            - Element selector may be incorrect - check if there are similar elements with slightly different names/properties.
            - Element may be temporarily unavailable due to loading state.
            - This could be a real regression that needs to be addressed.
    """.trimIndent()
    return maestro.findElementWithTimeout(
      timeout,
      filterFunc,
      parentViewHierarchy,
    )?.hierarchy ?: throw MaestroException.ElementNotFound(
      "Element not found: $description",
      parentViewHierarchy.root,
      debugMessage = debugMessage,
    )
  }

  private fun buildFilter(
    selector: ElementSelector,
  ): FilterWithDescription {
    val basicFilters = mutableListOf<ElementFilter>()
    val relativeFilters = mutableListOf<ElementFilter>()
    val descriptions = mutableListOf<String>()

    selector.textRegex
      ?.let {
        descriptions += "Text matching regex: $it"
        basicFilters += Filters.textMatches(it.toRegexSafe(REGEX_OPTIONS))
      }

    selector.idRegex
      ?.let {
        descriptions += "Id matching regex: $it"
        basicFilters += Filters.idMatches(it.toRegexSafe(REGEX_OPTIONS))
      }
    selector.size
      ?.let {
        descriptions += "Size: $it"
        basicFilters += Filters.sizeMatches(
          width = it.width,
          height = it.height,
          tolerance = it.tolerance,
        ).asFilter()
      }

    selector.below
      ?.let {
        descriptions += "Below: ${it.description()}"
        relativeFilters += Filters.below(buildFilter(it).filterFunc)
      }

    selector.above
      ?.let {
        descriptions += "Above: ${it.description()}"
        relativeFilters += Filters.above(buildFilter(it).filterFunc)
      }

    selector.leftOf
      ?.let {
        descriptions += "Left of: ${it.description()}"
        relativeFilters += Filters.leftOf(buildFilter(it).filterFunc)
      }

    selector.rightOf
      ?.let {
        descriptions += "Right of: ${it.description()}"
        relativeFilters += Filters.rightOf(buildFilter(it).filterFunc)
      }

    selector.containsChild
      ?.let {
        descriptions += "Contains child: ${it.description()}"
        relativeFilters += Filters.containsChild(buildFilter(it).filterFunc)
      }

    selector.containsDescendants
      ?.let { descendantSelectors ->
        val descendantDescriptions = descendantSelectors.joinToString("; ") { it.description() }
        descriptions += "Contains descendants: $descendantDescriptions"
        relativeFilters += Filters.containsDescendants(descendantSelectors.map { buildFilter(it).filterFunc })
      }

    selector.traits
      ?.map {
        TraitFilters.buildFilter(it)
      }
      ?.forEach { (description, filter) ->
        descriptions += description
        basicFilters += filter
      }

    selector.enabled
      ?.let {
        descriptions += if (it) {
          "Enabled"
        } else {
          "Disabled"
        }
        basicFilters += Filters.enabled(it)
      }

    selector.selected
      ?.let {
        descriptions += if (it) {
          "Selected"
        } else {
          "Not selected"
        }
        basicFilters += Filters.selected(it)
      }

    selector.checked
      ?.let {
        descriptions += if (it) {
          "Checked"
        } else {
          "Not checked"
        }
        basicFilters += Filters.checked(it)
      }

    selector.focused
      ?.let {
        descriptions += if (it) {
          "Focused"
        } else {
          "Not focused"
        }
        basicFilters += Filters.focused(it)
      }

    selector.css
      ?.let {
        descriptions += "CSS: $it"
        basicFilters += Filters.css(maestro, it)
      }

    // Apply deepestMatchingElement only to basic filters, then intersect with relative filters
    val basicFilter = if (basicFilters.isNotEmpty()) {
      Filters.deepestMatchingElement(Filters.intersect(basicFilters))
    } else {
      { nodes -> nodes } // Identity filter if no basic filters
    }

    val allFilters = listOf(basicFilter) + relativeFilters
    var resultFilter = Filters.intersect(allFilters)

    resultFilter = selector.index
      ?.toDouble()
      ?.toInt()
      ?.let {
        Filters.compose(
          resultFilter,
          Filters.index(it),
        )
      } ?: Filters.compose(
      resultFilter,
      Filters.clickableFirst(),
    )

    return FilterWithDescription(
      descriptions.joinToString(", "),
      resultFilter,
    )
  }

  companion object {

    // Mirrored by TrailblazeNodeSelectorResolver.MAESTRO_REGEX_OPTIONS (MatchDialect.MAESTRO in
    // trailblaze-models) and PropertyUniqueness (trailblaze-common) — keep the three in sync.
    val REGEX_OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
  }
}
