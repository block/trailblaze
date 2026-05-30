package xyz.block.trailblaze.yaml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Roundtrip tests for the optional `tags` and `skip` fields on [TrailConfig]. These pin three
 * behaviors the CLI's per-file loop relies on:
 *
 *  1. Trails without `tags:` or `skip:` parse with both fields null — every existing trail in the
 *     repo behaves unchanged.
 *  2. `tags: [a, b, c]` parses into a `List<String>` preserving order.
 *  3. `skip:` accepts the bare-string shape the schema specifies (`skip: "reason"`); blank reasons
 *     and missing fields are both decoded as null/empty so the runtime can collapse them into a
 *     single "not skipped" branch.
 *
 * Why direct YAML rather than fixture files: this is a contract test for the field shape itself,
 * not for the encompassing trail document — keeping it isolated makes failure messages specific
 * when the serializer drifts.
 */
class TrailConfigTagsAndSkipTest {

  private val trailblazeYaml = createTrailblazeYaml()

  @Test
  fun `config without tags or skip parses with both fields null - back-compat default`() {
    val parsed = trailblazeYaml.extractTrailConfig(
      """
      - config:
          title: Sample trail
          platform: android
      - tools:
        - pressBack: {}
      """.trimIndent(),
    )

    assertEquals("Sample trail", parsed?.title)
    assertNull(parsed?.tags, "tags must be null when not declared")
    assertNull(parsed?.skip, "skip must be null when not declared")
  }

  @Test
  fun `config with tags list preserves order and content`() {
    val parsed = trailblazeYaml.extractTrailConfig(
      """
      - config:
          title: Tagged trail
          platform: android
          tags: [smoke, login, flaky]
      - tools:
        - pressBack: {}
      """.trimIndent(),
    )

    assertEquals(listOf("smoke", "login", "flaky"), parsed?.tags)
  }

  @Test
  fun `config with namespaced tag string survives roundtrip unchanged`() {
    val parsed = trailblazeYaml.extractTrailConfig(
      """
      - config:
          title: Namespaced tags
          platform: android
          tags:
            - "flaky:retry-once"
            - "owner:platform"
      - tools:
        - pressBack: {}
      """.trimIndent(),
    )

    assertEquals(listOf("flaky:retry-once", "owner:platform"), parsed?.tags)
  }

  @Test
  fun `config with skip reason exposes the reason string`() {
    val parsed = trailblazeYaml.extractTrailConfig(
      """
      - config:
          title: Skipped trail
          platform: android
          skip: "Compact element list regression — see #2194"
      - tools:
        - pressBack: {}
      """.trimIndent(),
    )

    assertEquals("Compact element list regression — see #2194", parsed?.skip)
  }

  @Test
  fun `config with empty skip string is decoded but caller is expected to treat blank as not-skipped`() {
    // The schema accepts `skip: ""` as a valid YAML value; the convention is that blank strings
    // mean "not skipped" (so `skip: ""` doesn't silently gate a trail when someone clears the
    // reason). The CLI's readSkipReason() collapses blank to null — this test only pins that
    // the parser doesn't drop empty values, so the CLI is the single layer that owns the trim.
    val parsed = trailblazeYaml.extractTrailConfig(
      """
      - config:
          title: Empty skip
          platform: android
          skip: ""
      - tools:
        - pressBack: {}
      """.trimIndent(),
    )

    assertTrue(parsed?.skip?.isEmpty() == true, "blank skip must round-trip as an empty string, not null")
  }

  @Test
  fun `firstSkipReason returns the trimmed reason when set`() {
    val items = trailblazeYaml.decodeTrail(
      """
      - config:
          title: Skipped trail
          platform: android
          skip: "  Compact element list regression — see #2194  "
      - tools:
        - pressBack: {}
      """.trimIndent(),
    )

    assertEquals(
      "Compact element list regression — see #2194",
      trailblazeYaml.firstSkipReason(items),
    )
  }

  @Test
  fun `firstSkipReason returns null when no skip field is set`() {
    val items = trailblazeYaml.decodeTrail(
      """
      - config:
          title: Plain trail
          platform: android
      - tools:
        - pressBack: {}
      """.trimIndent(),
    )

    assertNull(trailblazeYaml.firstSkipReason(items))
  }

  @Test
  fun `firstSkipReason returns null when skip value is blank — collapses with absent`() {
    // `skip: ""` is allowed by the schema so callers can clear a skip without dropping the field;
    // every runner-side path that consults `firstSkipReason` must treat blank and absent the same
    // so an accidental blank doesn't silently disable a trail.
    val items = trailblazeYaml.decodeTrail(
      """
      - config:
          title: Empty skip
          platform: android
          skip: ""
      - tools:
        - pressBack: {}
      """.trimIndent(),
    )

    assertNull(trailblazeYaml.firstSkipReason(items))
  }

  @Test
  fun `firstSkipReason returns null when skip value is whitespace only`() {
    val items = trailblazeYaml.decodeTrail(
      """
      - config:
          title: Whitespace skip
          platform: android
          skip: "   "
      - tools:
        - pressBack: {}
      """.trimIndent(),
    )

    assertNull(trailblazeYaml.firstSkipReason(items))
  }

  @Test
  fun `skip is orthogonal to hasActionableSteps — every runner must consult firstSkipReason before iterating`() {
    // Pins the load-bearing invariant the runner-side short-circuit relies on:
    // `hasActionableSteps` reports `true` for a skip-marked trail that contains
    // prompts/tools (because the items DO exist on disk), so the actionable-steps
    // guard cannot double as the skip guard. Every runner-side path that iterates
    // `trailItems` (AndroidTrailblazeRule, the equivalent loop in any downstream
    // rule, TrailblazeHostYamlRunner's 3 driver entry points, BasePlaywrightNativeTest,
    // BasePlaywrightElectronTest, BaseHostTrailblazeTest, BaseComposeTest) MUST
    // consult `firstSkipReason` BEFORE the loop — relying on the actionable-steps
    // gate is insufficient. If a future runner forgets the check, the YAML will
    // run end-to-end even though `trailblaze trail` would have skipped it.
    val items = trailblazeYaml.decodeTrail(
      """
      - config:
          title: Skipped with actionable steps
          platform: android
          skip: "blocked on regression"
      - tools:
        - pressBack: {}
      """.trimIndent(),
    )

    assertEquals(
      "blocked on regression",
      trailblazeYaml.firstSkipReason(items),
      "skip must be reachable from firstSkipReason",
    )
    assertTrue(
      trailblazeYaml.hasActionableSteps(items),
      "the trail still has an actionable tool — skip is enforced by firstSkipReason " +
        "alone, not by hasActionableSteps; a runner that only checks hasActionableSteps " +
        "would still execute the pressBack",
    )
  }

  @Test
  fun `firstSkipReason returns null when the trail has no config block at all`() {
    val items = trailblazeYaml.decodeTrail(
      """
      - tools:
        - pressBack: {}
      """.trimIndent(),
    )

    assertNull(trailblazeYaml.firstSkipReason(items))
  }

  @Test
  fun `tags and skip coexist with the rest of the config block`() {
    val parsed = trailblazeYaml.extractTrailConfig(
      """
      - config:
          title: Full config
          description: Verifies the new fields don't disturb sibling fields
          platform: android
          driver: ANDROID_ONDEVICE_INSTRUMENTATION
          target: com.example.app
          tags: [smoke, regression]
          skip: "Blocked on infra rollout"
      - tools:
        - pressBack: {}
      """.trimIndent(),
    )

    assertEquals("Full config", parsed?.title)
    assertEquals("android", parsed?.platform)
    assertEquals("ANDROID_ONDEVICE_INSTRUMENTATION", parsed?.driver)
    assertEquals("com.example.app", parsed?.target)
    assertEquals(listOf("smoke", "regression"), parsed?.tags)
    assertEquals("Blocked on infra rollout", parsed?.skip)
  }
}
