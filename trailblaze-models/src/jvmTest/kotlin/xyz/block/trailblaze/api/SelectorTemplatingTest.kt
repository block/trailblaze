package xyz.block.trailblaze.api

import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SelectorTemplatingTest {

  /**
   * Selector with `{{target.appId}}` in `resourceIdRegex` expands to the runtime-resolved
   * app id and the compiled regex matches the literal `<appId>:id/foo` — and only that —
   * not a different package with the same id suffix.
   */
  @Test
  fun `appId substituted when target appId is non-null`() {
    val selector = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(
        resourceIdRegex = "^{{target.appId}}:id/foo$",
      ),
    )
    val target = TargetTemplateContext(appId = "com.example.test", appIds = listOf("com.example.test"))
    val expanded = SelectorTemplating.expand(selector, target)

    val hit = resourceNode(resourceId = "com.example.test:id/foo")
    val miss = resourceNode(resourceId = "com.other:id/foo")

    val hitResult = TrailblazeNodeSelectorResolver.resolve(hit, expanded)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(hitResult)

    val missResult = TrailblazeNodeSelectorResolver.resolve(miss, expanded)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.NoMatch>(missResult)
  }

  /**
   * No runtime-resolved app id (`appId = null`) → fall back to alternation over the
   * declared `appIds`. Captured trees from any declared variant must still match.
   */
  @Test
  fun `falls back to appIds alternation when appId is null`() {
    val selector = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(
        resourceIdRegex = "^{{target.appId}}:id/foo$",
      ),
    )
    val target = TargetTemplateContext(
      appId = null,
      appIds = listOf("com.example.dev", "com.example"),
    )
    val expanded = SelectorTemplating.expand(selector, target)

    val dev = resourceNode(resourceId = "com.example.dev:id/foo")
    val prod = resourceNode(resourceId = "com.example:id/foo")
    val other = resourceNode(resourceId = "com.other:id/foo")

    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(
      TrailblazeNodeSelectorResolver.resolve(dev, expanded),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(
      TrailblazeNodeSelectorResolver.resolve(prod, expanded),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.NoMatch>(
      TrailblazeNodeSelectorResolver.resolve(other, expanded),
    )
  }

  /**
   * Substitution does not recurse — replacement strings get [Regex.escape]d so a dot in
   * the app id is matched as a literal dot, not "any character." This guards against the
   * `com.example.test` pattern silently matching `comXexampleXtest:id/foo`.
   */
  @Test
  fun `appId is regex-escaped on substitution`() {
    val expanded = SelectorTemplating.expand(
      pattern = "^{{target.appId}}:id/foo$",
      target = TargetTemplateContext(appId = "com.example.test"),
    )
    val regex = Regex(expanded)
    assertTrue(regex.matches("com.example.test:id/foo"))
    // A literal-dot regex would not match a different character in the dot's slot.
    assertEquals(false, regex.matches("comXexampleXtest:id/foo"))
  }

  /**
   * If neither `appId` nor `appIds` is supplied, the literal placeholder remains so the
   * downstream regex compile produces an obvious zero-match rather than silently matching
   * the wrong thing.
   */
  @Test
  fun `leaves placeholder un-substituted when no context provided`() {
    val expanded = SelectorTemplating.expand(
      pattern = "^{{target.appId}}:id/foo$",
      target = null,
    )
    assertEquals("^{{target.appId}}:id/foo$", expanded)
  }

  /**
   * The selector-overload of [SelectorTemplating.expand] walks every `*Regex` on every
   * `DriverNodeMatch` variant plus the nested spatial/hierarchy sub-selectors. This test
   * pins the nested case so a refactor that forgets to recurse (e.g. drops `containsChild`
   * from the copy walk) fails loudly instead of silently producing un-expanded selectors
   * deep in the tree.
   */
  @Test
  fun `selector overload expands placeholder inside nested containsChild`() {
    val selector = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(
        resourceIdRegex = "^{{target.appId}}:id/outer$",
      ),
      containsChild = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(
          resourceIdRegex = "^{{target.appId}}:id/inner$",
        ),
      ),
    )
    val target = TargetTemplateContext(appId = "com.example.test")
    val expanded = SelectorTemplating.expand(selector, target)
    assertEquals(
      "^\\Qcom.example.test\\E:id/outer$",
      expanded.androidAccessibility?.resourceIdRegex,
    )
    assertEquals(
      "^\\Qcom.example.test\\E:id/inner$",
      expanded.containsChild?.androidAccessibility?.resourceIdRegex,
    )
  }

  /**
   * Selectors with no placeholder anywhere round-trip unchanged. Catches a future bug where
   * the selector-walk deep-copies even when nothing needs substituting.
   */
  @Test
  fun `selector overload returns input unchanged when no placeholder anywhere`() {
    val selector = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(
        resourceIdRegex = "^com.literal.app:id/foo$",
        classNameRegex = "android.widget.TextView",
      ),
    )
    val target = TargetTemplateContext(appId = "com.example.test")
    val expanded = SelectorTemplating.expand(selector, target)
    assertEquals(selector, expanded)
  }

  /**
   * Drift guard for the hand-listed `.expanded()` helpers in [SelectorTemplating]. Walks every
   * `DriverNodeMatch` subclass via reflection, picks one `*Regex`-named property, drives a
   * substitution through `SelectorTemplating.expand(selector, target)`, and asserts the
   * placeholder was substituted. If someone adds a new `DriverNodeMatch` variant or a new
   * `*Regex` field to an existing one without updating the templating helper, this test
   * fails — making the silent miss loud at PR time.
   */
  @Test
  fun `every DriverNodeMatch variant participates in template expansion`() {
    val target = TargetTemplateContext(appId = "com.example.test")
    val cases: List<Pair<String, TrailblazeNodeSelector>> = listOf(
      "androidAccessibility" to TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(
          resourceIdRegex = "^{{target.appId}}:id/x$",
        ),
      ),
      "androidMaestro" to TrailblazeNodeSelector(
        androidMaestro = DriverNodeMatch.AndroidMaestro(
          resourceIdRegex = "^{{target.appId}}:id/x$",
        ),
      ),
      "web" to TrailblazeNodeSelector(
        web = DriverNodeMatch.Web(ariaNameRegex = "^{{target.appId}}$"),
      ),
      "compose" to TrailblazeNodeSelector(
        compose = DriverNodeMatch.Compose(textRegex = "^{{target.appId}}$"),
      ),
      "iosMaestro" to TrailblazeNodeSelector(
        iosMaestro = DriverNodeMatch.IosMaestro(
          resourceIdRegex = "^{{target.appId}}:id/x$",
        ),
      ),
      "iosAxe" to TrailblazeNodeSelector(
        iosAxe = DriverNodeMatch.IosAxe(labelRegex = "^{{target.appId}}$"),
      ),
    )
    for ((label, selector) in cases) {
      val expanded = SelectorTemplating.expand(selector, target)
      val asString = expanded.toString()
      assertTrue(
        !asString.contains("{{target.appId}}"),
        "expansion missed for variant $label: $asString",
      )
      assertTrue(
        asString.contains("com.example.test"),
        "substitution did not appear for variant $label: $asString",
      )
    }
  }

  /**
   * Per-field drift guard. The variant-level test above catches a missed `DriverNodeMatch`
   * subclass; this one walks every property on each subclass whose name ends in `Regex` and
   * whose type is `String?`, instantiates the variant with that single field set to the
   * placeholder, and asserts substitution happens. So adding a new `tooltipRegex` (or any
   * future `*Regex`) to an existing variant without wiring it into `SelectorTemplating`'s
   * `.expanded()` helper fails the test loudly instead of silently dropping the new field.
   *
   * Uses Kotlin reflection so the test stays correct as new fields land — no manual list
   * to keep in sync. (Some `*Regex`-named properties may be lateinit / non-nullable;
   * the filter on `String?` skips them rather than crashing the test setup.)
   */
  @Test
  fun `every Regex-suffixed field on every DriverNodeMatch variant is template-expanded`() {
    val target = TargetTemplateContext(appId = "com.example.test")
    val variantClasses = listOf(
      DriverNodeMatch.AndroidAccessibility::class,
      DriverNodeMatch.AndroidMaestro::class,
      DriverNodeMatch.Web::class,
      DriverNodeMatch.Compose::class,
      DriverNodeMatch.IosMaestro::class,
      DriverNodeMatch.IosAxe::class,
    )
    for (variantClass in variantClasses) {
      val regexProps = variantClass.declaredMemberProperties
        .filter { it.name.endsWith("Regex") && it.returnType.classifier == String::class }
      assertTrue(
        regexProps.isNotEmpty(),
        "${variantClass.simpleName} declares no *Regex properties — update this test if the " +
          "shape changed and the variant truly has no string-regex fields.",
      )
      val ctor = variantClass.primaryConstructor
        ?: error("${variantClass.simpleName} has no primary constructor")
      for (prop in regexProps) {
        // Call the data-class primary constructor with only the one targeted *Regex
        // parameter set to the placeholder; every other parameter uses its default (all
        // current DriverNodeMatch variants declare nullable defaults). `callBy` understands
        // default parameters — passing a partial Map skips the rest.
        val targetedParam = ctor.parameters.firstOrNull { it.name == prop.name }
          ?: error("constructor of ${variantClass.simpleName} has no parameter for ${prop.name}")
        val variant = ctor.callBy(mapOf(targetedParam to "^{{target.appId}}$")) as DriverNodeMatch

        val selector = TrailblazeNodeSelector.withMatch(variant)
        val expanded = SelectorTemplating.expand(selector, target)
        val expandedMatch = expanded.driverMatch
          ?: error("expansion lost the driver match for ${variantClass.simpleName}.${prop.name}")
        val expandedValue = variantClass.declaredMemberProperties
          .first { it.name == prop.name }
          .getter
          .call(expandedMatch) as String?
        assertTrue(
          expandedValue != null && !expandedValue.contains("{{target.appId}}"),
          "expansion missed ${variantClass.simpleName}.${prop.name}: $expandedValue",
        )
        assertTrue(
          expandedValue!!.contains("com.example.test"),
          "substitution did not appear in ${variantClass.simpleName}.${prop.name}: $expandedValue",
        )
      }
    }
  }

  private fun resourceNode(resourceId: String): TrailblazeNode = TrailblazeNode(
    nodeId = 1L,
    children = emptyList(),
    bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
    driverDetail = DriverNodeDetail.AndroidAccessibility(resourceId = resourceId),
  )
}
