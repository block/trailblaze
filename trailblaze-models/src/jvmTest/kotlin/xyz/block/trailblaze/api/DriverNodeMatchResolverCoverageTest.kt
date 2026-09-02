package xyz.block.trailblaze.api

import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Forcing function for [TrailblazeNodeSelectorResolver]'s per-dialect match ladders.
 *
 * Each `matches<Dialect>` function enumerates its [DriverNodeMatch] variant's fields by hand. A
 * field with no rung fails silently and in the worst direction: the YAML keeps the matcher, the
 * runtime ignores it, and the selector matches MORE loosely than it reads — no error at any layer.
 *
 * Two tests close that hole, in the same reflection style as `:trailblaze-host`'s
 * `TrailblazeNodeSelectorYamlEmitterTest`:
 *
 *  1. [`every driver-match field has a probe`] — the probe table's field names must equal the
 *     variant's primary-constructor parameter names, so a newly added field fails here with a
 *     "add a probe" message instead of being skipped by omission. It also asserts each probe's
 *     match sets exactly one field, so a NoMatch can't come from some other constraint.
 *  2. [`the resolver rejects a node that fails any single driver-match field`] — for every
 *     probe, a one-node tree whose detail does NOT satisfy that field, against a match that
 *     constrains only it. A missing rung makes the constraint vacuous and the node matches.
 */
class DriverNodeMatchResolverCoverageTest {

  /**
   * One field's rung: [detail] deliberately fails [match], and [match] constrains nothing else.
   * `field` is the primary-constructor parameter name on the [DriverNodeMatch] variant.
   */
  private data class Probe(
    val field: String,
    val detail: DriverNodeDetail,
    val match: DriverNodeMatch,
  )

  @Test
  fun `every driver-match field has a probe`() {
    PROBES.forEach { (matchClass, probes) ->
      assertEquals(
        primaryCtorParameterNames(matchClass).toSet(),
        probes.map { it.field }.toSet(),
        "${matchClass.simpleName}'s probe table in DriverNodeMatchResolverCoverageTest is out of " +
          "sync with its primary constructor. Add a probe per missing field (a detail value that " +
          "FAILS the field, plus a match that sets only that field), and give the field a rung in " +
          "TrailblazeNodeSelectorResolver.",
      )
      probes.forEach { probe ->
        val set = primaryCtorParameterNames(matchClass).filter { fieldValue(probe.match, it) != null }
        assertEquals(
          listOf(probe.field),
          set,
          "${matchClass.simpleName}.${probe.field}'s probe must constrain only that field, " +
            "otherwise its NoMatch could come from another constraint",
        )
      }
    }
  }

  @Test
  fun `the resolver rejects a node that fails any single driver-match field`() {
    PROBES.forEach { (matchClass, probes) ->
      probes.forEach { probe ->
        val root = TrailblazeNode(
          nodeId = 1,
          bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
          driverDetail = probe.detail,
        )
        val result = TrailblazeNodeSelectorResolver.resolve(
          root,
          TrailblazeNodeSelector.withMatch(probe.match),
        )
        assertIs<TrailblazeNodeSelectorResolver.ResolveResult.NoMatch>(
          result,
          "TrailblazeNodeSelectorResolver ignores ${matchClass.simpleName}.${probe.field} — a node " +
            "that fails that constraint still matched. Add the missing rung to the dialect's " +
            "matches* function.",
        )
      }
    }
  }

  @Test
  fun `every DriverNodeMatch variant is probed`() {
    // Type-level companion to the per-field check: a brand-new dialect gets a ladder of its own,
    // and without this it would have no probe table at all.
    val variants = DriverNodeMatch::class.sealedSubclasses.toSet()
    val probed = PROBES.map { it.first }.toSet()
    assertTrue(
      (variants - probed).isEmpty(),
      "DriverNodeMatch variant(s) ${(variants - probed).map { it.simpleName }} have no probe table " +
        "in DriverNodeMatchResolverCoverageTest. Add one so their resolver ladder is covered.",
    )
  }

  private companion object {

    private val ANDROID_VIEW_PROBES = listOf(
      Probe(
        "classNameRegex",
        DriverNodeDetail.AndroidView(className = "android.widget.TextView"),
        DriverNodeMatch.AndroidView(classNameRegex = "android.widget.Button"),
      ),
      Probe(
        "resourceIdRegex",
        DriverNodeDetail.AndroidView(resourceId = "com.example:id/alpha"),
        DriverNodeMatch.AndroidView(resourceIdRegex = "com.example:id/beta"),
      ),
      Probe(
        "tagRegex",
        DriverNodeDetail.AndroidView(tag = "tag_alpha"),
        DriverNodeMatch.AndroidView(tagRegex = "tag_beta"),
      ),
      Probe(
        "textRegex",
        DriverNodeDetail.AndroidView(text = "Alpha"),
        DriverNodeMatch.AndroidView(textRegex = "Beta"),
      ),
      Probe(
        "contentDescriptionRegex",
        DriverNodeDetail.AndroidView(contentDescription = "Alpha"),
        DriverNodeMatch.AndroidView(contentDescriptionRegex = "Beta"),
      ),
      Probe(
        "hintTextRegex",
        DriverNodeDetail.AndroidView(hintText = "Alpha"),
        DriverNodeMatch.AndroidView(hintTextRegex = "Beta"),
      ),
      Probe(
        "stateDescriptionRegex",
        DriverNodeDetail.AndroidView(stateDescription = "On"),
        DriverNodeMatch.AndroidView(stateDescriptionRegex = "Off"),
      ),
      Probe(
        "errorTextRegex",
        DriverNodeDetail.AndroidView(errorText = "Email is required"),
        DriverNodeMatch.AndroidView(errorTextRegex = "Email is invalid"),
      ),
      Probe(
        "isEnabled",
        DriverNodeDetail.AndroidView(isEnabled = true),
        DriverNodeMatch.AndroidView(isEnabled = false),
      ),
      Probe(
        "isClickable",
        DriverNodeDetail.AndroidView(isClickable = false),
        DriverNodeMatch.AndroidView(isClickable = true),
      ),
      Probe(
        "isChecked",
        DriverNodeDetail.AndroidView(isChecked = true),
        DriverNodeMatch.AndroidView(isChecked = false),
      ),
      Probe(
        "isSelected",
        DriverNodeDetail.AndroidView(isSelected = false),
        DriverNodeMatch.AndroidView(isSelected = true),
      ),
      Probe(
        "isFocused",
        DriverNodeDetail.AndroidView(isFocused = false),
        DriverNodeMatch.AndroidView(isFocused = true),
      ),
      Probe(
        "isEditable",
        DriverNodeDetail.AndroidView(isEditable = false),
        DriverNodeMatch.AndroidView(isEditable = true),
      ),
      Probe(
        "isPassword",
        DriverNodeDetail.AndroidView(isPassword = false),
        DriverNodeMatch.AndroidView(isPassword = true),
      ),
      Probe(
        "inputType",
        DriverNodeDetail.AndroidView(inputType = 1),
        DriverNodeMatch.AndroidView(inputType = 2),
      ),
    )

    private val COMPOSE_PROBES = listOf(
      Probe(
        "testTag",
        DriverNodeDetail.Compose(testTag = "alpha"),
        DriverNodeMatch.Compose(testTag = "beta"),
      ),
      Probe(
        "role",
        DriverNodeDetail.Compose(role = "Button"),
        DriverNodeMatch.Compose(role = "Checkbox"),
      ),
      Probe(
        "textRegex",
        DriverNodeDetail.Compose(text = "Alpha"),
        DriverNodeMatch.Compose(textRegex = "Beta"),
      ),
      Probe(
        "editableTextRegex",
        DriverNodeDetail.Compose(editableText = "Alpha"),
        DriverNodeMatch.Compose(editableTextRegex = "Beta"),
      ),
      Probe(
        "contentDescriptionRegex",
        DriverNodeDetail.Compose(contentDescription = "Alpha"),
        DriverNodeMatch.Compose(contentDescriptionRegex = "Beta"),
      ),
      Probe(
        "toggleableState",
        DriverNodeDetail.Compose(toggleableState = "On"),
        DriverNodeMatch.Compose(toggleableState = "Off"),
      ),
      Probe(
        "isEnabled",
        DriverNodeDetail.Compose(isEnabled = true),
        DriverNodeMatch.Compose(isEnabled = false),
      ),
      Probe(
        "isFocused",
        DriverNodeDetail.Compose(isFocused = false),
        DriverNodeMatch.Compose(isFocused = true),
      ),
      Probe(
        "isSelected",
        DriverNodeDetail.Compose(isSelected = false),
        DriverNodeMatch.Compose(isSelected = true),
      ),
      Probe(
        "isPassword",
        DriverNodeDetail.Compose(isPassword = false),
        DriverNodeMatch.Compose(isPassword = true),
      ),
      Probe(
        "collectionItemRowIndex",
        DriverNodeDetail.Compose(collectionItemRowIndex = 1),
        DriverNodeMatch.Compose(collectionItemRowIndex = 2),
      ),
      Probe(
        "collectionItemColumnIndex",
        DriverNodeDetail.Compose(collectionItemColumnIndex = 1),
        DriverNodeMatch.Compose(collectionItemColumnIndex = 2),
      ),
      Probe(
        "stateDescriptionRegex",
        DriverNodeDetail.Compose(stateDescription = "Expanded"),
        DriverNodeMatch.Compose(stateDescriptionRegex = "Collapsed"),
      ),
      Probe(
        "isHeading",
        DriverNodeDetail.Compose(isHeading = false),
        DriverNodeMatch.Compose(isHeading = true),
      ),
      Probe(
        "paneTitleRegex",
        DriverNodeDetail.Compose(paneTitle = "Confirm deletion"),
        DriverNodeMatch.Compose(paneTitleRegex = "Confirm purchase"),
      ),
      Probe(
        "isDialog",
        DriverNodeDetail.Compose(isDialog = false),
        DriverNodeMatch.Compose(isDialog = true),
      ),
      Probe(
        "isPopup",
        DriverNodeDetail.Compose(isPopup = false),
        DriverNodeMatch.Compose(isPopup = true),
      ),
      Probe(
        "errorTextRegex",
        DriverNodeDetail.Compose(errorText = "Email is required"),
        DriverNodeMatch.Compose(errorTextRegex = "Email is invalid"),
      ),
      Probe(
        "hasSetTextAction",
        DriverNodeDetail.Compose(hasSetTextAction = false),
        DriverNodeMatch.Compose(hasSetTextAction = true),
      ),
    )

    private val ANDROID_ACCESSIBILITY_PROBES = listOf(
      Probe(
        "classNameRegex",
        DriverNodeDetail.AndroidAccessibility(className = "android.widget.TextView"),
        DriverNodeMatch.AndroidAccessibility(classNameRegex = "android.widget.Button"),
      ),
      Probe(
        "resourceIdRegex",
        DriverNodeDetail.AndroidAccessibility(resourceId = "com.example:id/alpha"),
        DriverNodeMatch.AndroidAccessibility(resourceIdRegex = "com.example:id/beta"),
      ),
      Probe(
        "uniqueId",
        DriverNodeDetail.AndroidAccessibility(uniqueId = "uid-alpha"),
        DriverNodeMatch.AndroidAccessibility(uniqueId = "uid-beta"),
      ),
      Probe(
        "composeTestTagRegex",
        DriverNodeDetail.AndroidAccessibility(composeTestTag = "alpha"),
        DriverNodeMatch.AndroidAccessibility(composeTestTagRegex = "beta"),
      ),
      Probe(
        "textRegex",
        DriverNodeDetail.AndroidAccessibility(text = "Alpha"),
        DriverNodeMatch.AndroidAccessibility(textRegex = "Beta"),
      ),
      Probe(
        "contentDescriptionRegex",
        DriverNodeDetail.AndroidAccessibility(contentDescription = "Alpha"),
        DriverNodeMatch.AndroidAccessibility(contentDescriptionRegex = "Beta"),
      ),
      Probe(
        "hintTextRegex",
        DriverNodeDetail.AndroidAccessibility(hintText = "Alpha"),
        DriverNodeMatch.AndroidAccessibility(hintTextRegex = "Beta"),
      ),
      Probe(
        "labeledByTextRegex",
        DriverNodeDetail.AndroidAccessibility(labeledByText = "Alpha"),
        DriverNodeMatch.AndroidAccessibility(labeledByTextRegex = "Beta"),
      ),
      Probe(
        "stateDescriptionRegex",
        DriverNodeDetail.AndroidAccessibility(stateDescription = "On"),
        DriverNodeMatch.AndroidAccessibility(stateDescriptionRegex = "Off"),
      ),
      Probe(
        "paneTitleRegex",
        DriverNodeDetail.AndroidAccessibility(paneTitle = "Confirm deletion"),
        DriverNodeMatch.AndroidAccessibility(paneTitleRegex = "Confirm purchase"),
      ),
      Probe(
        "roleDescriptionRegex",
        DriverNodeDetail.AndroidAccessibility(roleDescription = "Toggle"),
        DriverNodeMatch.AndroidAccessibility(roleDescriptionRegex = "Tab"),
      ),
      Probe(
        "isEnabled",
        DriverNodeDetail.AndroidAccessibility(isEnabled = true),
        DriverNodeMatch.AndroidAccessibility(isEnabled = false),
      ),
      Probe(
        "isClickable",
        DriverNodeDetail.AndroidAccessibility(isClickable = false),
        DriverNodeMatch.AndroidAccessibility(isClickable = true),
      ),
      Probe(
        "isCheckable",
        DriverNodeDetail.AndroidAccessibility(isCheckable = false),
        DriverNodeMatch.AndroidAccessibility(isCheckable = true),
      ),
      Probe(
        "isChecked",
        DriverNodeDetail.AndroidAccessibility(isChecked = false),
        DriverNodeMatch.AndroidAccessibility(isChecked = true),
      ),
      Probe(
        "isSelected",
        DriverNodeDetail.AndroidAccessibility(isSelected = false),
        DriverNodeMatch.AndroidAccessibility(isSelected = true),
      ),
      Probe(
        "isFocused",
        DriverNodeDetail.AndroidAccessibility(isFocused = false),
        DriverNodeMatch.AndroidAccessibility(isFocused = true),
      ),
      Probe(
        "isEditable",
        DriverNodeDetail.AndroidAccessibility(isEditable = false),
        DriverNodeMatch.AndroidAccessibility(isEditable = true),
      ),
      Probe(
        "isScrollable",
        DriverNodeDetail.AndroidAccessibility(isScrollable = false),
        DriverNodeMatch.AndroidAccessibility(isScrollable = true),
      ),
      Probe(
        "isPassword",
        DriverNodeDetail.AndroidAccessibility(isPassword = false),
        DriverNodeMatch.AndroidAccessibility(isPassword = true),
      ),
      Probe(
        "isHeading",
        DriverNodeDetail.AndroidAccessibility(isHeading = false),
        DriverNodeMatch.AndroidAccessibility(isHeading = true),
      ),
      Probe(
        "isMultiLine",
        DriverNodeDetail.AndroidAccessibility(isMultiLine = false),
        DriverNodeMatch.AndroidAccessibility(isMultiLine = true),
      ),
      Probe(
        "inputType",
        DriverNodeDetail.AndroidAccessibility(inputType = 1),
        DriverNodeMatch.AndroidAccessibility(inputType = 2),
      ),
      Probe(
        "collectionItemRowIndex",
        DriverNodeDetail.AndroidAccessibility(
          collectionItemInfo = DriverNodeDetail.AndroidAccessibility.CollectionItemInfo(
            rowIndex = 1,
            rowSpan = 1,
            columnIndex = 1,
            columnSpan = 1,
            isHeading = false,
          ),
        ),
        DriverNodeMatch.AndroidAccessibility(collectionItemRowIndex = 2),
      ),
      Probe(
        "collectionItemColumnIndex",
        DriverNodeDetail.AndroidAccessibility(
          collectionItemInfo = DriverNodeDetail.AndroidAccessibility.CollectionItemInfo(
            rowIndex = 1,
            rowSpan = 1,
            columnIndex = 1,
            columnSpan = 1,
            isHeading = false,
          ),
        ),
        DriverNodeMatch.AndroidAccessibility(collectionItemColumnIndex = 2),
      ),
    )

    // Maestro-shape values differ beyond case: that dialect is case-insensitive, so "alpha"
    // vs "Alpha" would be a satisfied constraint, not a failed one.
    private val ANDROID_MAESTRO_PROBES = listOf(
      Probe(
        "textRegex",
        DriverNodeDetail.AndroidMaestro(text = "Alpha"),
        DriverNodeMatch.AndroidMaestro(textRegex = "Beta"),
      ),
      Probe(
        "resourceIdRegex",
        DriverNodeDetail.AndroidMaestro(resourceId = "com.example:id/alpha"),
        DriverNodeMatch.AndroidMaestro(resourceIdRegex = "com.example:id/beta"),
      ),
      Probe(
        "accessibilityTextRegex",
        DriverNodeDetail.AndroidMaestro(accessibilityText = "Alpha"),
        DriverNodeMatch.AndroidMaestro(accessibilityTextRegex = "Beta"),
      ),
      Probe(
        "classNameRegex",
        DriverNodeDetail.AndroidMaestro(className = "android.widget.TextView"),
        DriverNodeMatch.AndroidMaestro(classNameRegex = "android.widget.Button"),
      ),
      Probe(
        "hintTextRegex",
        DriverNodeDetail.AndroidMaestro(hintText = "Alpha"),
        DriverNodeMatch.AndroidMaestro(hintTextRegex = "Beta"),
      ),
      Probe(
        "clickable",
        DriverNodeDetail.AndroidMaestro(clickable = false),
        DriverNodeMatch.AndroidMaestro(clickable = true),
      ),
      Probe(
        "enabled",
        DriverNodeDetail.AndroidMaestro(enabled = true),
        DriverNodeMatch.AndroidMaestro(enabled = false),
      ),
      Probe(
        "focused",
        DriverNodeDetail.AndroidMaestro(focused = false),
        DriverNodeMatch.AndroidMaestro(focused = true),
      ),
      Probe(
        "checked",
        DriverNodeDetail.AndroidMaestro(checked = false),
        DriverNodeMatch.AndroidMaestro(checked = true),
      ),
      Probe(
        "selected",
        DriverNodeDetail.AndroidMaestro(selected = false),
        DriverNodeMatch.AndroidMaestro(selected = true),
      ),
    )

    private val WEB_PROBES = listOf(
      Probe(
        "ariaRole",
        DriverNodeDetail.Web(ariaRole = "button"),
        DriverNodeMatch.Web(ariaRole = "link"),
      ),
      Probe(
        "ariaNameRegex",
        DriverNodeDetail.Web(ariaName = "Alpha"),
        DriverNodeMatch.Web(ariaNameRegex = "Beta"),
      ),
      Probe(
        "ariaDescriptorRegex",
        DriverNodeDetail.Web(ariaDescriptor = "button \"Alpha\""),
        DriverNodeMatch.Web(ariaDescriptorRegex = "button \"Beta\""),
      ),
      Probe(
        "headingLevel",
        DriverNodeDetail.Web(headingLevel = 1),
        DriverNodeMatch.Web(headingLevel = 2),
      ),
      Probe(
        "cssSelector",
        DriverNodeDetail.Web(cssSelector = "#alpha"),
        DriverNodeMatch.Web(cssSelector = "#beta"),
      ),
      Probe(
        "dataTestId",
        DriverNodeDetail.Web(dataTestId = "alpha"),
        DriverNodeMatch.Web(dataTestId = "beta"),
      ),
      Probe(
        "nthIndex",
        DriverNodeDetail.Web(nthIndex = 0),
        DriverNodeMatch.Web(nthIndex = 1),
      ),
    )

    private val IOS_MAESTRO_PROBES = listOf(
      Probe(
        "textRegex",
        DriverNodeDetail.IosMaestro(text = "Alpha"),
        DriverNodeMatch.IosMaestro(textRegex = "Beta"),
      ),
      Probe(
        "resourceIdRegex",
        DriverNodeDetail.IosMaestro(resourceId = "alpha_button"),
        DriverNodeMatch.IosMaestro(resourceIdRegex = "beta_button"),
      ),
      Probe(
        "accessibilityTextRegex",
        DriverNodeDetail.IosMaestro(accessibilityText = "Alpha"),
        DriverNodeMatch.IosMaestro(accessibilityTextRegex = "Beta"),
      ),
      Probe(
        "classNameRegex",
        DriverNodeDetail.IosMaestro(className = "UILabel"),
        DriverNodeMatch.IosMaestro(classNameRegex = "UIButton"),
      ),
      Probe(
        "hintTextRegex",
        DriverNodeDetail.IosMaestro(hintText = "Alpha"),
        DriverNodeMatch.IosMaestro(hintTextRegex = "Beta"),
      ),
      Probe(
        "focused",
        DriverNodeDetail.IosMaestro(focused = false),
        DriverNodeMatch.IosMaestro(focused = true),
      ),
      Probe(
        "selected",
        DriverNodeDetail.IosMaestro(selected = false),
        DriverNodeMatch.IosMaestro(selected = true),
      ),
    )

    private val IOS_AXE_PROBES = listOf(
      Probe(
        "roleRegex",
        DriverNodeDetail.IosAxe(role = "AXStaticText"),
        DriverNodeMatch.IosAxe(roleRegex = "AXButton"),
      ),
      Probe(
        "subroleRegex",
        DriverNodeDetail.IosAxe(subrole = "AXSecureTextField"),
        DriverNodeMatch.IosAxe(subroleRegex = "AXSearchField"),
      ),
      Probe(
        "labelRegex",
        DriverNodeDetail.IosAxe(label = "Alpha"),
        DriverNodeMatch.IosAxe(labelRegex = "Beta"),
      ),
      Probe(
        "valueRegex",
        DriverNodeDetail.IosAxe(value = "Alpha"),
        DriverNodeMatch.IosAxe(valueRegex = "Beta"),
      ),
      Probe(
        "uniqueId",
        DriverNodeDetail.IosAxe(uniqueId = "alpha_button"),
        DriverNodeMatch.IosAxe(uniqueId = "beta_button"),
      ),
      Probe(
        "typeRegex",
        DriverNodeDetail.IosAxe(type = "StaticText"),
        DriverNodeMatch.IosAxe(typeRegex = "Button"),
      ),
      Probe(
        "titleRegex",
        DriverNodeDetail.IosAxe(title = "Alpha"),
        DriverNodeMatch.IosAxe(titleRegex = "Beta"),
      ),
      Probe(
        "customAction",
        DriverNodeDetail.IosAxe(customActions = listOf("Alpha")),
        DriverNodeMatch.IosAxe(customAction = "Beta"),
      ),
      Probe(
        "enabled",
        DriverNodeDetail.IosAxe(enabled = true),
        DriverNodeMatch.IosAxe(enabled = false),
      ),
    )

    private val PROBES: List<Pair<KClass<out DriverNodeMatch>, List<Probe>>> = listOf(
      DriverNodeMatch.AndroidView::class to ANDROID_VIEW_PROBES,
      DriverNodeMatch.Compose::class to COMPOSE_PROBES,
      DriverNodeMatch.AndroidAccessibility::class to ANDROID_ACCESSIBILITY_PROBES,
      DriverNodeMatch.AndroidMaestro::class to ANDROID_MAESTRO_PROBES,
      DriverNodeMatch.Web::class to WEB_PROBES,
      DriverNodeMatch.IosMaestro::class to IOS_MAESTRO_PROBES,
      DriverNodeMatch.IosAxe::class to IOS_AXE_PROBES,
    )

    private fun primaryCtorParameterNames(kClass: KClass<*>): List<String> =
      (kClass.primaryConstructor ?: error("$kClass has no primary constructor"))
        .parameters
        .mapNotNull { it.name }

    private fun fieldValue(match: DriverNodeMatch, fieldName: String): Any? {
      val property = match::class.memberProperties.firstOrNull { it.name == fieldName }
        ?: error("${match::class} has no property named $fieldName")
      @Suppress("UNCHECKED_CAST")
      return (property as KProperty1<DriverNodeMatch, Any?>).get(match)
    }
  }
}
