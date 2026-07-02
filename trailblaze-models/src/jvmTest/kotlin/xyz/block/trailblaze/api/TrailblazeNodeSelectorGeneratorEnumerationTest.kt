package xyz.block.trailblaze.api

import xyz.block.trailblaze.util.escapeForSelector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [TrailblazeNodeSelectorGenerator.enumerateSelectorCandidates] and its supporting
 * helpers ([waypointStableTextRegex], [selectorStabilityRank]).
 *
 * The command this backs (`waypoint suggest-selector`) is a menu, not a picker: it lists every
 * selector the cascade can compute, labeled and ranked, and leaves the choice to the human.
 * Nothing computable is hidden — `index` and `containsChild`/`containsDescendants` appear like
 * everything else when the cascade produces them, just ranked last; when a label repeats on
 * screen, both the plain (ambiguous) selector and an index- or hierarchy-qualified (unique)
 * variant show up side by side, and that pairing is itself the signal that disambiguation was
 * needed. The invariants under test are: nothing is filtered, a run-variable-wildcarded text
 * option is offered alongside the literal, and the whole list sorts stable-first.
 */
class TrailblazeNodeSelectorGeneratorEnumerationTest : TrailblazeNodeSelectorGeneratorTestBase() {

  // ---------------------------------------------------------------------------
  // waypointStableTextRegex — run-variable wildcarding
  // ---------------------------------------------------------------------------

  @Test
  fun `stable text - plain label kept literal`() {
    assertEquals("Add money", waypointStableTextRegex("Add money"))
  }

  @Test
  fun `stable text - currency amount wildcarded to stable head`() {
    assertEquals("Balance:.*", waypointStableTextRegex("Balance: \$0.00"))
  }

  @Test
  fun `stable text - trailing count wildcarded`() {
    assertEquals("Cart.*", waypointStableTextRegex("Cart 3"))
  }

  @Test
  fun `stable text - metacharacter head is Q-quoted before wildcard`() {
    assertEquals("\\QTotal (USD):\\E.*", waypointStableTextRegex("Total (USD): \$5"))
  }

  @Test
  fun `stable text - pure amount has no stable head`() {
    assertNull(waypointStableTextRegex("\$0.00"))
  }

  @Test
  fun `stable text - count-leading label has no stable head`() {
    assertNull(waypointStableTextRegex("5 items"))
  }

  @Test
  fun `stable text - blank is dropped`() {
    assertNull(waypointStableTextRegex("   "))
  }

  @Test
  fun `stable text - sign-only head before a negative amount has no stable head`() {
    // "-" alone isn't identifying anything; wildcarding it would match any negative amount.
    assertNull(waypointStableTextRegex("-\$12.34"))
  }

  @Test
  fun `stable text - punctuation-only head before a phone number has no stable head`() {
    // "(" alone isn't identifying anything; wildcarding it would match any parenthesized digits.
    assertNull(waypointStableTextRegex("(555) 478-7672"))
  }

  // ---------------------------------------------------------------------------
  // selectorStabilityRank
  // ---------------------------------------------------------------------------

  @Test
  fun `rank - identity before text before structural before childOf before containsChild before spatial`() {
    val identity = TrailblazeNodeSelector(iosMaestro = DriverNodeMatch.IosMaestro(resourceIdRegex = "x"))
    val text = TrailblazeNodeSelector(iosMaestro = DriverNodeMatch.IosMaestro(accessibilityTextRegex = "x"))
    val structural = TrailblazeNodeSelector(iosMaestro = DriverNodeMatch.IosMaestro(classNameRegex = "x"))
    val scoped = text.copy(childOf = identity)
    val descendant = text.copy(containsChild = identity)
    val spatial = text.copy(below = identity)

    assertTrue(selectorStabilityRank(identity) < selectorStabilityRank(text))
    assertTrue(selectorStabilityRank(text) < selectorStabilityRank(structural))
    assertTrue(selectorStabilityRank(structural) < selectorStabilityRank(scoped))
    assertTrue(selectorStabilityRank(scoped) < selectorStabilityRank(descendant))
    assertTrue(selectorStabilityRank(descendant) < selectorStabilityRank(spatial))
  }

  @Test
  fun `rank - an index-qualified selector always ranks after every non-indexed selector`() {
    val identity = TrailblazeNodeSelector(iosMaestro = DriverNodeMatch.IosMaestro(resourceIdRegex = "x"))
    val spatial = TrailblazeNodeSelector(
      iosMaestro = DriverNodeMatch.IosMaestro(accessibilityTextRegex = "x"),
      below = identity,
    )
    // Even an identity-tier match ranks worse than the weakest non-indexed selector once an
    // index is attached — index is the signal that nothing else about the node disambiguated it.
    val indexedIdentity = identity.copy(index = 0)

    assertTrue(selectorStabilityRank(spatial) < selectorStabilityRank(indexedIdentity))
    // Relative order among indexed selectors still respects their own underlying tier.
    val indexedSpatial = spatial.copy(index = 0)
    assertTrue(selectorStabilityRank(indexedIdentity) < selectorStabilityRank(indexedSpatial))
  }

  // ---------------------------------------------------------------------------
  // Enumeration invariants — nothing computable is hidden
  // ---------------------------------------------------------------------------

  @Test
  fun `enumerate - non-unique text shows the plain selector AND an index-qualified one that disambiguates it`() {
    // Two identical labels: the plain text selector matches both (presence, not unique), and an
    // index-qualified variant that resolves to exactly one is ALSO offered — its presence in the
    // menu is what tells the author the plain one wasn't unique, rather than the tool hiding it.
    nextId = 1L
    val target = nodeOf(detail = DriverNodeDetail.IosMaestro(accessibilityText = "Add money"))
    val twin = nodeOf(detail = DriverNodeDetail.IosMaestro(accessibilityText = "Add money"))
    val root = nodeOf(detail = DriverNodeDetail.IosMaestro(), children = listOf(target, twin))

    val candidates = TrailblazeNodeSelectorGenerator.enumerateSelectorCandidates(root, target)
    assertTrue(candidates.isNotEmpty())

    // The plain text selector is present and honestly resolves to BOTH nodes (presence, not
    // unique) — it must NOT be dropped just because it isn't unique.
    val plain = candidates.first { it.selector.index == null && it.selector.iosMaestro?.accessibilityTextRegex == "Add money" }
    val plainResult = TrailblazeNodeSelectorResolver.resolve(root, plain.selector)
    assertTrue(plainResult is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches)

    // An index-qualified variant is ALSO present and resolves uniquely to the target.
    val indexed = candidates.first { it.selector.index != null }
    val indexedResult = TrailblazeNodeSelectorResolver.resolve(root, indexed.selector)
    assertTrue(indexedResult is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch)
    assertEquals(target.nodeId, indexedResult.node.nodeId)

    // The index-qualified variant ranks after the plain one.
    assertTrue(
      candidates.indexOf(plain) < candidates.indexOf(indexed),
      "Expected the plain (ambiguous) selector to rank ahead of the index-qualified one",
    )
  }

  @Test
  fun `enumerate - containsChild appears when it's the only way to disambiguate a wrapper`() {
    // Two structurally-identical wrapper containers (same className, nothing else identifying),
    // each with exactly one uniquely-labeled child. Only `containsChild` on the labeled
    // descendant tells them apart — a real "tap the wrapper of a labeled child" shape.
    nextId = 1L
    val childOfWrapper1 = nodeOf(
      detail = DriverNodeDetail.AndroidAccessibility(text = "For your business"),
      bounds = TrailblazeNode.Bounds(0, 0, 100, 20),
    )
    val wrapper1 = nodeOf(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.ViewGroup"),
      bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
      children = listOf(childOfWrapper1),
    )
    val childOfWrapper2 = nodeOf(
      detail = DriverNodeDetail.AndroidAccessibility(text = "All add-ons"),
      bounds = TrailblazeNode.Bounds(0, 100, 100, 120),
    )
    val wrapper2 = nodeOf(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.ViewGroup"),
      bounds = TrailblazeNode.Bounds(0, 100, 100, 150),
      children = listOf(childOfWrapper2),
    )
    val root = nodeOf(
      detail = DriverNodeDetail.AndroidAccessibility(),
      bounds = TrailblazeNode.Bounds(0, 0, 100, 150),
      children = listOf(wrapper1, wrapper2),
    )

    val candidates = TrailblazeNodeSelectorGenerator.enumerateSelectorCandidates(root, wrapper1)

    // The plain className selector is ambiguous — both wrappers share it.
    val plainClass = candidates.first {
      it.selector.androidAccessibility?.classNameRegex != null && it.selector.containsChild == null
    }
    assertTrue(
      TrailblazeNodeSelectorResolver.resolve(root, plainClass.selector) is
        TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches,
    )

    // The containsChild-qualified variant resolves uniquely to wrapper1.
    val withChild = candidates.first { it.selector.containsChild != null }
    val result = TrailblazeNodeSelectorResolver.resolve(root, withChild.selector)
    assertTrue(result is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch)
    assertEquals(wrapper1.nodeId, result.node.nodeId)
  }

  @Test
  fun `enumerate - attribute-less node still gets a bare global-index candidate`() {
    // No text/id/class of its own, no children, no siblings, no identifiable parent — every
    // content, hierarchy, and spatial strategy bottoms out with nothing to work from. The one
    // thing that ALWAYS resolves is a bare positional index across the whole tree — nothing is
    // hidden, so it shows up too, honestly labeled and, since it's the only option, marked best.
    nextId = 1L
    val target = nodeOf(detail = DriverNodeDetail.IosMaestro())
    val root = nodeOf(detail = DriverNodeDetail.IosMaestro(), children = listOf(target))

    val candidates = TrailblazeNodeSelectorGenerator.enumerateSelectorCandidates(root, target)
    assertEquals(1, candidates.size, "Expected exactly the bare-index fallback; got $candidates")
    val only = candidates.single()
    assertNotNull(only.selector.index)
    assertNull(only.selector.driverMatch, "No content properties exist, so the match itself is empty")
    assertTrue(only.isBest)
  }

  // ---------------------------------------------------------------------------
  // Run-variable text: literal AND wildcarded are both offered
  // ---------------------------------------------------------------------------

  @Test
  fun `enumerate - offers both the literal and the run-variable-wildcarded text`() {
    nextId = 1L
    val target = nodeOf(detail = DriverNodeDetail.IosMaestro(accessibilityText = "Balance: \$0.00"))
    val other = nodeOf(detail = DriverNodeDetail.IosMaestro(accessibilityText = "Activity"))
    val root = nodeOf(detail = DriverNodeDetail.IosMaestro(), children = listOf(target, other))

    val texts = TrailblazeNodeSelectorGenerator.enumerateSelectorCandidates(root, target)
      .mapNotNull { it.selector.iosMaestro?.accessibilityTextRegex }

    assertTrue(
      texts.contains(escapeForSelector("Balance: \$0.00")),
      "Expected the literal text option; got $texts",
    )
    assertTrue(
      texts.contains("Balance:.*"),
      "Expected the run-variable-wildcarded text option; got $texts",
    )
  }

  @Test
  fun `runVariableWildcardedTextCandidate - falls back to contentDescription when text has no stable head`() {
    // text is present but unusable ("$0.00" has no stable head); contentDescription is a good
    // stable label. The candidate must fall through to it rather than give up entirely.
    val detail = DriverNodeDetail.AndroidAccessibility(text = "\$0.00", contentDescription = "Balance")

    val candidate = runVariableWildcardedTextCandidate(detail)
    assertNotNull(candidate, "Expected a fallback candidate from contentDescription")
    val (name, match) = candidate
    // "Balance" needs no wildcarding — it's already stable, so no "— run-variable wildcarded"
    // suffix; the value is what matters here (it reached contentDescription at all).
    assertEquals("Content description", name)
    assertEquals("Balance", (match as DriverNodeMatch.AndroidAccessibility).contentDescriptionRegex)
  }

  @Test
  fun `runVariableWildcardedTextCandidate - falls back past an unwildcardable secondary field too`() {
    // text ("$0.00") has no stable head, and contentDescription ("-$5.00") has a punctuation-only
    // head — both unwildcardable — so the chain must continue on to hintText.
    val detail = DriverNodeDetail.AndroidAccessibility(
      text = "\$0.00",
      contentDescription = "-\$5.00",
      hintText = "Enter amount",
    )

    val candidate = runVariableWildcardedTextCandidate(detail)
    assertNotNull(candidate)
    val (name, match) = candidate
    // "Enter amount" is already stable — no wildcarding needed, so no suffix.
    assertEquals("Hint text", name)
    assertEquals("Enter amount", (match as DriverNodeMatch.AndroidAccessibility).hintTextRegex)
  }

  @Test
  fun `runVariableWildcardedTextCandidate - null when no field has a stable head`() {
    val detail = DriverNodeDetail.AndroidAccessibility(text = "\$0.00", contentDescription = "-\$5.00")
    assertNull(runVariableWildcardedTextCandidate(detail))
  }

  @Test
  fun `runVariableWildcardedTextCandidate - fallback field keeps the wildcarded suffix when it needs it`() {
    // text unusable; contentDescription itself carries run-variable content with a stable head —
    // the fallback still gets the "wildcarded" suffix when wildcarding actually happened.
    val detail = DriverNodeDetail.AndroidAccessibility(text = "\$0.00", contentDescription = "Balance: \$0.00")

    val candidate = runVariableWildcardedTextCandidate(detail)
    assertNotNull(candidate)
    val (name, match) = candidate
    assertEquals("Content description — run-variable wildcarded", name)
    assertEquals("Balance:.*", (match as DriverNodeMatch.AndroidAccessibility).contentDescriptionRegex)
  }

  @Test
  fun `enumerate - unwildcardable text falls back to contentDescription in the full menu`() {
    // End-to-end version of the fallback fix: a node whose text is a bare amount but whose
    // contentDescription is a stable label should surface a presence-worthy candidate.
    nextId = 1L
    val target = nodeOf(
      detail = DriverNodeDetail.AndroidAccessibility(text = "\$0.00", contentDescription = "Balance"),
    )
    val other = nodeOf(detail = DriverNodeDetail.AndroidAccessibility(text = "Activity"))
    val root = nodeOf(detail = DriverNodeDetail.AndroidAccessibility(), children = listOf(target, other))

    val candidates = TrailblazeNodeSelectorGenerator.enumerateSelectorCandidates(root, target)
    assertTrue(
      candidates.any { it.selector.androidAccessibility?.contentDescriptionRegex == "Balance" },
      "Expected a contentDescription candidate; got ${candidates.map { it.strategy }}",
    )
  }

  // ---------------------------------------------------------------------------
  // Ranking — most-stable first, across iOS and Android
  // ---------------------------------------------------------------------------

  @Test
  fun `enumerate iOS - ranks identity first and is sorted most-stable first`() {
    nextId = 1L
    val target = nodeOf(
      detail = DriverNodeDetail.IosMaestro(
        resourceId = "keypad_tab_button",
        accessibilityText = "Payment keypad",
        className = "UIButton",
      ),
    )
    val other = nodeOf(detail = DriverNodeDetail.IosMaestro(accessibilityText = "Something else"))
    val root = nodeOf(detail = DriverNodeDetail.IosMaestro(), children = listOf(target, other))

    val candidates = TrailblazeNodeSelectorGenerator.enumerateSelectorCandidates(root, target)
    assertTrue(candidates.size >= 3, "Expected identity + text + structural at least; got ${candidates.size}")

    // First entry is the app-assigned identity.
    assertEquals("keypad_tab_button", candidates.first().selector.iosMaestro?.resourceIdRegex)
    assertTrue(candidates.first().isBest)

    // The whole list is ordered by stability rank.
    val ranks = candidates.map { selectorStabilityRank(it.selector) }
    assertEquals(ranks.sorted(), ranks, "Candidates must be sorted most-stable first")
  }

  @Test
  fun `enumerate Android - identity, text, and structural all offered, ranked`() {
    nextId = 1L
    val target = nodeOf(
      detail = DriverNodeDetail.AndroidAccessibility(
        resourceId = "com.example:id/balance_card",
        text = "Account balance",
        className = "android.widget.TextView",
      ),
    )
    val other = nodeOf(detail = DriverNodeDetail.AndroidAccessibility(text = "Activity"))
    val root = nodeOf(detail = DriverNodeDetail.AndroidAccessibility(), children = listOf(target, other))

    val candidates = TrailblazeNodeSelectorGenerator.enumerateSelectorCandidates(root, target)
    val matches = candidates.mapNotNull { it.selector.androidAccessibility }

    assertTrue(matches.any { it.resourceIdRegex != null }, "identity option expected")
    assertTrue(matches.any { it.textRegex == "Account balance" }, "text option expected")
    assertTrue(
      matches.any { it.classNameRegex != null && it.textRegex == null && it.resourceIdRegex == null },
      "structural type-only option expected",
    )

    // First is identity; whole list ranked stable-first.
    assertNotNull(candidates.first().selector.androidAccessibility?.resourceIdRegex)
    val ranks = candidates.map { selectorStabilityRank(it.selector) }
    assertEquals(ranks.sorted(), ranks)
  }
}
