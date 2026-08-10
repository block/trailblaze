package xyz.block.trailblaze.devices

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins the classifier-lineage primitive — the shared keystone the unified
 * trail adapter (and the waypoint schema) use to turn a device's classifier
 * into a total, most-specific-first fallback chain.
 *
 * Uses generic placeholder family names (`foldable`, `x2`) for the
 * explicit-override cases; no distribution-specific hardware names appear here.
 */
class TrailblazeClassifierLineageTest {

  @AfterTest
  fun resetGlobalOverrides() {
    // Tests that touch the GLOBAL registry must not leak into others.
    TrailblazeClassifierLineage.clearRegisteredOverridesForTest()
  }

  private fun chain(classifier: String, overrides: Map<String, String> = emptyMap()): List<String> =
    TrailblazeClassifierLineage.chainFor(TrailblazeDeviceClassifier(classifier), overrides)
      .map { it.classifier }

  // ── String-derivation (hyphen-drop) ────────────────────────────────────

  @Test
  fun `string-derivation drops the trailing hyphen segment one level at a time`() {
    assertEquals(listOf("android-phone", "android", "all"), chain("android-phone"))
    assertEquals(listOf("ios-ipad", "ios", "all"), chain("ios-ipad"))
    assertEquals(listOf("ios-iphone", "ios", "all"), chain("ios-iphone"))
  }

  @Test
  fun `arbitrary classifier depth resolves up to the family with no schema change`() {
    // The headline property: a deeper classifier resolves through every
    // intermediate up to its family purely by string-derivation — no override,
    // no parser change, no enum entry.
    assertEquals(
      listOf("android-phone-37", "android-phone", "android", "all"),
      chain("android-phone-37"),
    )
    // And one level deeper still, for free.
    assertEquals(
      listOf("android-phone-37-xl", "android-phone-37", "android-phone", "android", "all"),
      chain("android-phone-37-xl"),
    )
  }

  @Test
  fun `a single-segment classifier is its own family root, closed by the universal root`() {
    assertEquals(listOf("android", "all"), chain("android"))
    assertEquals(listOf("ios", "all"), chain("ios"))
    assertEquals(listOf("web", "all"), chain("web"))
  }

  // ── Universal root ───────────────────────────────────────────────────────

  @Test
  fun `the universal root is its own entire chain — no duplicate, no parent`() {
    assertEquals(listOf("all"), chain("all"))
  }

  // ── Totality + ordering ─────────────────────────────────────────────────

  @Test
  fun `chain is total — always non-empty, starts with the input, ends at the universal root`() {
    for (c in listOf("android", "android-phone", "ios-iphone", "android-phone-37", "foldable-x2")) {
      val chainFor = chain(c)
      assertTrue(chainFor.isNotEmpty(), "chain for `$c` must be non-empty")
      assertEquals(c, chainFor.first(), "chain for `$c` must start with the input (most specific first)")
      assertEquals("all", chainFor.last(), "chain for `$c` must end at the universal root")
    }
  }

  @Test
  fun `chain is ordered most-specific-first through the family root to the universal root`() {
    val c = chain("android-phone-37")
    assertEquals("android-phone-37", c.first())
    assertEquals(listOf("android", "all"), c.takeLast(2))
  }

  // ── Explicit parent overrides (injected) ────────────────────────────────

  @Test
  fun `explicit override supplies the parent for an irregular family that does not string-derive`() {
    // `foldable` has no hyphen to drop, so without an override it would be a
    // root. An explicit `foldable -> android` makes it fall back to the
    // android family — and a derived sub-classifier `foldable-x2` reaches
    // android through it for free.
    val overrides = mapOf("foldable" to "android")
    assertEquals(listOf("foldable", "android", "all"), chain("foldable", overrides))
    assertEquals(listOf("foldable-x2", "foldable", "android", "all"), chain("foldable-x2", overrides))
  }

  @Test
  fun `override takes precedence over string-derivation`() {
    // `a-b` would string-derive to `a`, but an explicit override wins.
    val overrides = mapOf("a-b" to "z")
    assertEquals(listOf("a-b", "z", "all"), chain("a-b", overrides))
  }

  @Test
  fun `a malformed override cycle cannot hang resolution`() {
    // a -> b -> a is a cycle; the visited-set breaks it instead of looping.
    val overrides = mapOf("a" to "b", "b" to "a")
    assertEquals(listOf("a", "b", "all"), chain("a", overrides))
    assertEquals(listOf("b", "a", "all"), chain("b", overrides))
  }

  @Test
  fun `an override that routes through the universal root does not duplicate it`() {
    // `foo -> all` is redundant (every chain ends at `all` anyway) but must stay harmless.
    val overrides = mapOf("foo" to "all")
    assertEquals(listOf("foo", "all"), chain("foo", overrides))
  }

  // ── resolutionChain (broad-first device segments) ────────────────────────

  @Test
  fun `resolutionChain joins broad-first device segments into the compound identity then expands`() {
    // Compound identity first (ios-iphone -> ios), then the original segments as
    // a lower-priority fallback (bare `iphone` last). For a normal hierarchical
    // device the bare segment is harmless — no recording is keyed by it.
    assertEquals(
      listOf("ios-iphone", "ios", "iphone", "all"),
      TrailblazeClassifierLineage.resolutionChain(
        listOf(TrailblazeDeviceClassifier("ios"), TrailblazeDeviceClassifier("iphone")),
      ).map { it.classifier },
    )
    assertEquals(
      listOf("android-phone", "android", "phone", "all"),
      TrailblazeClassifierLineage.resolutionChain(
        listOf(TrailblazeDeviceClassifier("android"), TrailblazeDeviceClassifier("phone")),
      ).map { it.classifier },
    )
  }

  @Test
  fun `the universal root is strictly last — every explicit segment fallback outranks it`() {
    // The compound identity's own root (`ios`) must not drag `all` in ahead of the
    // lower-priority bare-segment fallbacks: an `iphone:`-keyed entry still beats `all:`.
    val chain = TrailblazeClassifierLineage.resolutionChain(
      listOf(TrailblazeDeviceClassifier("ios"), TrailblazeDeviceClassifier("iphone")),
    ).map { it.classifier }
    assertEquals("all", chain.last())
    assertEquals(1, chain.count { it == "all" })
    assertTrue(chain.indexOf("iphone") < chain.indexOf("all"))
  }

  @Test
  fun `an override routed through the universal root cannot rank it above later segment fallbacks`() {
    // `foo -> all` puts `all` on the compound identity's chain BEFORE step 2 merges the
    // bare-segment fallbacks. Strictly-last must hold anyway — `all` is moved to the end,
    // not left mid-chain — so a `bar:`-keyed entry still beats `all:`.
    val chain = TrailblazeClassifierLineage.resolutionChain(
      listOf(TrailblazeDeviceClassifier("foo"), TrailblazeDeviceClassifier("bar")),
      overrides = mapOf("foo" to "all"),
    ).map { it.classifier }
    assertEquals(listOf("foo-bar", "foo", "bar", "all"), chain)
  }

  @Test
  fun `resolutionChain preserves a non-suffix segment so it stays probeable`() {
    // A provider that emits a segment which is NOT a suffix of the joined
    // compound — e.g. a cloud-runner device reporting [ios, cloud-runner].
    // Joining alone gives ios-cloud-runner -> ios-cloud -> ios and never probes
    // `cloud-runner`, so a recording keyed `cloud-runner:` would silently fall
    // back to AI. The original segment must remain reachable as a fallback.
    val chain = TrailblazeClassifierLineage.resolutionChain(
      listOf(TrailblazeDeviceClassifier("ios"), TrailblazeDeviceClassifier("cloud-runner")),
    ).map { it.classifier }
    assertEquals(
      listOf("ios-cloud-runner", "ios-cloud", "ios"),
      chain.take(3),
      "compound identity must lead the chain",
    )
    assertTrue("cloud-runner" in chain, "the non-suffix segment must remain probeable; got $chain")
  }

  @Test
  fun `resolutionChain of a single segment is that classifier plus the universal root`() {
    assertEquals(
      listOf("android", "all"),
      TrailblazeClassifierLineage.resolutionChain(listOf(TrailblazeDeviceClassifier("android")))
        .map { it.classifier },
    )
  }

  @Test
  fun `resolutionChain of an empty device-classifier list is empty`() {
    assertTrue(TrailblazeClassifierLineage.resolutionChain(emptyList()).isEmpty())
  }

  @Test
  fun `resolutionChain drops blank segments so they cannot corrupt the compound identity`() {
    // A stray blank segment must not turn [ios, "", iphone] into the malformed `ios--iphone`;
    // it's filtered, leaving the same chain as [ios, iphone].
    assertEquals(
      listOf("ios-iphone", "ios", "iphone", "all"),
      TrailblazeClassifierLineage.resolutionChain(
        listOf(
          TrailblazeDeviceClassifier("ios"),
          TrailblazeDeviceClassifier(""),
          TrailblazeDeviceClassifier("iphone"),
        ),
      ).map { it.classifier },
    )
    // All-blank input has no identity to resolve → empty chain.
    assertTrue(
      TrailblazeClassifierLineage.resolutionChain(listOf(TrailblazeDeviceClassifier(" ")))
        .isEmpty(),
    )
  }

  @Test
  fun `chainFor a blank classifier is an empty chain`() {
    assertTrue(chain("").isEmpty())
    assertTrue(chain("   ").isEmpty())
  }

  @Test
  fun `resolutionChain honors an injected override for an irregular family`() {
    // A foldable hardware device whose provider emits `[foldable, x2]`.
    val overrides = mapOf("foldable" to "android")
    assertEquals(
      // compound foldable-x2 -> foldable -> android (override), then the bare `x2` segment.
      listOf("foldable-x2", "foldable", "android", "x2", "all"),
      TrailblazeClassifierLineage.resolutionChain(
        listOf(TrailblazeDeviceClassifier("foldable"), TrailblazeDeviceClassifier("x2")),
        overrides,
      ).map { it.classifier },
    )
  }

  // ── Global registry ──────────────────────────────────────────────────────

  @Test
  fun `globally-registered override feeds chainFor when no overrides argument is passed`() {
    TrailblazeClassifierLineage.registerParentOverride("foldable", "android")
    // No explicit overrides arg → falls back to the global registry.
    assertEquals(
      listOf("foldable-x2", "foldable", "android", "all"),
      TrailblazeClassifierLineage.chainFor(TrailblazeDeviceClassifier("foldable-x2"))
        .map { it.classifier },
    )
  }

  @Test
  fun `clearing the global registry restores pure string-derivation`() {
    TrailblazeClassifierLineage.registerParentOverride("foldable", "android")
    TrailblazeClassifierLineage.clearRegisteredOverridesForTest()
    // `foldable` is a family root again with no override.
    assertEquals(
      listOf("foldable", "all"),
      TrailblazeClassifierLineage.chainFor(TrailblazeDeviceClassifier("foldable"))
        .map { it.classifier },
    )
  }

  @Test
  fun `registering an invalid override is rejected`() {
    assertFailsWith<IllegalArgumentException> {
      TrailblazeClassifierLineage.registerParentOverride("", "android")
    }
    assertFailsWith<IllegalArgumentException> {
      TrailblazeClassifierLineage.registerParentOverride("foldable", " ")
    }
    assertFailsWith<IllegalArgumentException> {
      TrailblazeClassifierLineage.registerParentOverride("foldable", "foldable")
    }
    // The universal root is an ancestor of everything; giving it a parent would make the
    // lineage cyclic by construction.
    assertFailsWith<IllegalArgumentException> {
      TrailblazeClassifierLineage.registerParentOverride("all", "android")
    }
  }
}
