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

  /**
   * A scratch dir with no workspace anchor above it, pinned as the caller cwd so the getter's
   * `defaults.target` walk-up finds nothing — the test JVM cwd sits inside this repo (itself a
   * workspace declaring a default target), so the getter must be driven from a clean seed.
   */
  private fun <T> withoutWorkspace(block: () -> T): T {
    val dir = java.nio.file.Files.createTempDirectory("target-getter-test")
    org.junit.Assume.assumeTrue(
      "An ancestor of $dir already contains a trailblaze.yaml — skipping.",
      xyz.block.trailblaze.config.project.TrailblazeWorkspaceConfigResolver.resolveConfigFile(dir) == null,
    )
    return CliCallerContext.withCallerCwd(dir) { block() }
  }

  @Test
  fun `target getter returns an authoritative selection unchanged`() {
    withoutWorkspace {
      assertEquals("myapp", targetKey.get(baseConfig.copy(selectedTargetAppId = "myapp")))
    }
  }

  @Test
  fun `target getter reports not-set when nothing is selected and no workspace default exists`() {
    withoutWorkspace {
      assertEquals("(not set)", targetKey.get(baseConfig.copy(selectedTargetAppId = null)))
    }
  }

  @Test
  fun `target getter treats a persisted neutral default id as not-set`() {
    // The neutral-"default" sentinel: legacy auto-persist must not read as an authoritative
    // selection, so with no workspace default the getter shows "(not set)", not "default".
    withoutWorkspace {
      val neutral = xyz.block.trailblaze.model.TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget.id
      assertEquals("(not set)", targetKey.get(baseConfig.copy(selectedTargetAppId = neutral)))
    }
  }

  /**
   * A scratch workspace whose anchor declares `defaults.target: [defaultsTarget]`, pinned as the
   * caller cwd so the getter's walk-up resolves it. Guarded like [withoutWorkspace] against an
   * unexpected ancestor anchor.
   */
  private fun <T> withWorkspaceDefaultTarget(defaultsTarget: String, block: () -> T): T {
    val dir = java.nio.file.Files.createTempDirectory("target-getter-ws-test")
    org.junit.Assume.assumeTrue(
      "An ancestor of $dir already contains a trailblaze.yaml — skipping.",
      xyz.block.trailblaze.config.project.TrailblazeWorkspaceConfigResolver.resolveConfigFile(dir) == null,
    )
    val configDir = dir.resolve("trails/config").toFile().apply { mkdirs() }
    java.io.File(configDir, "trailblaze.yaml").writeText("defaults:\n  target: $defaultsTarget\n")
    return CliCallerContext.withCallerCwd(dir) { block() }
  }

  @Test
  fun `target getter surfaces the workspace default when nothing authoritative is selected`() {
    // The branch this PR added: no authoritative selection + a workspace defaults.target declared
    // → the getter reports the effective target a run resolves to, not "(not set)".
    withWorkspaceDefaultTarget("alpha") {
      assertEquals(
        "(not set — workspace default: alpha)",
        targetKey.get(baseConfig.copy(selectedTargetAppId = null)),
      )
    }
  }
}
