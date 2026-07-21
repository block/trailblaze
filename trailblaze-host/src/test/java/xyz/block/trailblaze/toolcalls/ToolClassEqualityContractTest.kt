package xyz.block.trailblaze.toolcalls

import kotlin.test.Test
import kotlin.test.assertTrue
import xyz.block.trailblaze.logs.client.TrailblazeSerializationInitializer

/**
 * Every `@TrailblazeToolClass` must decode to a value that compares equal to the original: a plain
 * `class` bottoms out to reference-identity equality, so two decodes of the same recorded tool
 * compare unequal even when nothing changed — silently breaking any consumer that dedupes or
 * diffs decoded tools by value.
 *
 * Every tool discoverable on this module's classpath (trailblaze-common + compose + revyl +
 * playwright, the union `buildAllTools()` returns here) must be a `data class` (value equality) or
 * a Kotlin `object` (singleton identity) — both make repeated decodes compare equal.
 */
class ToolClassEqualityContractTest {

  @Test
  fun `every registered tool class is a data class or an object`() {
    val toolClasses = TrailblazeSerializationInitializer.buildAllTools().values.toSet()

    val offenders = toolClasses.filterNot { it.isData || it.objectInstance != null }

    assertTrue(
      offenders.isEmpty(),
      "These tool classes are plain `class` (reference-identity equals), so two decodes of the " +
        "same recorded tool compare unequal by value. Convert to `data class` (if it has " +
        "constructor params) or `object` (if it has none): ${offenders.map { it.qualifiedName }}",
    )
  }
}
