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
