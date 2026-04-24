package xyz.block.trailblaze.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrailblazeHostAppTargetTest {

  // --- ID validation ---
  // Single source of truth for the target-ID regex. Three callers depend on this
  // (the class contract, `ConfigCommand.applyTarget`, and `CliConfigHelper`'s target
  // config key setter). Pin the accepted and rejected cases here so any future tweak
  // changes the rule in one visible place.

  @Test
  fun `isValidId accepts lowercase alphanumeric`() {
    assertTrue(TrailblazeHostAppTarget.isValidId("myapp"))
    assertTrue(TrailblazeHostAppTarget.isValidId("demoapp"))
    assertTrue(TrailblazeHostAppTarget.isValidId("noteapp"))
    assertTrue(TrailblazeHostAppTarget.isValidId("app123"))
  }

  @Test
  fun `isValidId accepts hyphens and underscores`() {
    assertTrue(TrailblazeHostAppTarget.isValidId("demo-app"))
    assertTrue(TrailblazeHostAppTarget.isValidId("note-web"))
    assertTrue(TrailblazeHostAppTarget.isValidId("my_app"))
    assertTrue(TrailblazeHostAppTarget.isValidId("a-b_c-d"))
  }

  @Test
  fun `isValidId rejects uppercase`() {
    assertFalse(TrailblazeHostAppTarget.isValidId("Myapp"))
    assertFalse(TrailblazeHostAppTarget.isValidId("DemoApp"))
  }

  @Test
  fun `isValidId rejects spaces and special characters`() {
    assertFalse(TrailblazeHostAppTarget.isValidId("my app"))
    assertFalse(TrailblazeHostAppTarget.isValidId("my.app"))
    assertFalse(TrailblazeHostAppTarget.isValidId("my/app"))
    assertFalse(TrailblazeHostAppTarget.isValidId("my@app"))
  }

  @Test
  fun `isValidId rejects empty string`() {
    assertFalse(TrailblazeHostAppTarget.isValidId(""))
  }
}
