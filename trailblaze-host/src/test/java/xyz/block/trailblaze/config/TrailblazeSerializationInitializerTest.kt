package xyz.block.trailblaze.config

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isNotEmpty
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import org.junit.Test
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeSerializationInitializer
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * Tests for [TrailblazeSerializationInitializer] — the central entry point that discovers tool
 * classes from YAML and configures [TrailblazeJsonInstance] and [TrailblazeYaml.Default].
 *
 * These tests run in the `trailblaze-host` module which depends on all driver modules
 * (Playwright, Compose, Revyl), so all opensource YAML-registered tools are on the classpath.
 */
class TrailblazeSerializationInitializerTest {

  @Test
  fun `initialize discovers YAML tools and configures serialization`() {
    TrailblazeSerializationInitializer.initialize()

    // web_snapshot is only registered via YAML (not built-in), so if this
    // decodes without error the YAML discovery pipeline is working end-to-end.
    val items = TrailblazeYaml.Default.decodeTools(
      """
      - web_snapshot:
          screenName: test
      """.trimIndent()
    )
    assertThat(items).hasSize(1)
  }

  @Test
  fun `initialize is idempotent — second call with same tools is a no-op`() {
    TrailblazeSerializationInitializer.initialize()
    val firstJson = TrailblazeJsonInstance
    val firstYaml = TrailblazeYaml.Default

    TrailblazeSerializationInitializer.initialize()

    // Same tool set → early return → instances unchanged
    assertThat(TrailblazeJsonInstance).isSameInstanceAs(firstJson)
    assertThat(TrailblazeYaml.Default).isSameInstanceAs(firstYaml)
  }

  @Test
  fun `all YAML-registered tool classes resolve to TrailblazeTool`() {
    val discoveredTools = ToolYamlLoader.discoverAndLoadAll()
    assertThat(discoveredTools.entries).isNotEmpty()

    for ((toolName, kClass) in discoveredTools) {
      assertThat(TrailblazeTool::class.java.isAssignableFrom(kClass.java)).isTrue()
    }
  }

  @Test
  fun `YAML tool discovery finds all Playwright tools`() {
    val toolNames = discoverToolNames()

    for (name in EXPECTED_PLAYWRIGHT_TOOLS) {
      assertThat(toolNames).contains(name)
    }
  }

  @Test
  fun `YAML tool discovery finds all Compose tools`() {
    val toolNames = discoverToolNames()

    for (name in EXPECTED_COMPOSE_TOOLS) {
      assertThat(toolNames).contains(name)
    }
  }

  @Test
  fun `YAML tool discovery finds all Revyl tools`() {
    val toolNames = discoverToolNames()

    for (name in EXPECTED_REVYL_TOOLS) {
      assertThat(toolNames).contains(name)
    }
  }

  @Test
  fun `initialized TrailblazeYaml can decode tools from every driver`() {
    TrailblazeSerializationInitializer.initialize()
    val yaml = TrailblazeYaml.Default

    // Playwright
    assertThat(yaml.decodeTools("- web_click:\n    ref: e1")).hasSize(1)
    // Compose
    assertThat(yaml.decodeTools("- compose_click:\n    elementId: c1")).hasSize(1)
    // Revyl
    assertThat(yaml.decodeTools("- revyl_tap:\n    target: Submit")).hasSize(1)
  }

  private fun discoverToolNames(): Set<String> =
    ToolYamlLoader.discoverAndLoadAll().keys.map { it.toolName }.toSet()

  companion object {
    private val EXPECTED_PLAYWRIGHT_TOOLS = listOf(
      "web_click", "web_type", "web_navigate",
      "web_scroll", "web_hover", "web_press_key",
      "web_select_option", "web_wait", "web_snapshot",
      "web_request_details", "web_verify_element_visible",
      "web_verify_text_visible", "web_verify_value",
      "web_verify_list_visible", "playwright_desktop_launchGoose",
    )
    private val EXPECTED_COMPOSE_TOOLS = listOf(
      "compose_click", "compose_type", "compose_scroll", "compose_wait",
      "compose_verify_element_visible", "compose_verify_text_visible",
      "compose_request_details",
    )
    private val EXPECTED_REVYL_TOOLS = listOf(
      "revyl_tap", "revyl_type", "revyl_swipe", "revyl_back",
      "revyl_assert", "revyl_doubleTap", "revyl_navigate", "revyl_pressKey",
    )
  }
}
