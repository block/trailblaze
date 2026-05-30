package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig

/**
 * Unit tests for the `target` entry in [CONFIG_KEYS]. The setter delegates validation to
 * [xyz.block.trailblaze.model.TrailblazeHostAppTarget.isValidId] — the test in
 * `trailblaze-models` pins the regex itself; this test pins the wiring so the setter
 * actually invokes that validator and returns the expected shape.
 */
class CliConfigHelperTargetKeyTest {

  private val baseConfig = SavedTrailblazeAppConfig(selectedTrailblazeDriverTypes = emptyMap())

  private val targetKey = CONFIG_KEYS.getValue("target")

  @Test
  fun `target setter accepts lowercase alphanumeric`() {
    val updated = targetKey.set(baseConfig, "myapp")
    assertNotNull(updated)
    assertEquals("myapp", updated.selectedTargetAppId)
  }

  @Test
  fun `target setter accepts hyphens and underscores`() {
    val hyphen = targetKey.set(baseConfig, "demo-app")
    assertNotNull(hyphen)
    assertEquals("demo-app", hyphen.selectedTargetAppId)

    val underscore = targetKey.set(baseConfig, "my_app")
    assertNotNull(underscore)
    assertEquals("my_app", underscore.selectedTargetAppId)
  }

  @Test
  fun `target setter preserves lowerCamelCase casing`() {
    // The 2026-05-27 trailmap-scoped tool naming work widened the regex to accept uppercase
    // letters so lowerCamelCase trailmap ids (`playwrightSample`, `googleCalendar`,
    // `androidSettings`) round-trip correctly. The setter no longer forces `.lowercase()`,
    // and case-insensitive lookup happens at the resolver layer (`findById`).
    val camel = targetKey.set(baseConfig, "playwrightSample")
    assertNotNull(camel)
    assertEquals("playwrightSample", camel.selectedTargetAppId)

    val mixed = targetKey.set(baseConfig, "Myapp")
    assertNotNull(mixed)
    assertEquals("Myapp", mixed.selectedTargetAppId)
  }

  @Test
  fun `target setter returns null for invalid values`() {
    assertNull(targetKey.set(baseConfig, "my app"), "Space is invalid")
    assertNull(targetKey.set(baseConfig, "my.app"), "Dot is invalid")
    assertNull(targetKey.set(baseConfig, ""), "Empty string is invalid")
  }
}
