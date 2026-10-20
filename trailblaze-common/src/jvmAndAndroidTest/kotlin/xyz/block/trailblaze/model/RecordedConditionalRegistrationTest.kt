package xyz.block.trailblaze.model

import kotlin.test.Test
import kotlin.test.assertTrue
import xyz.block.trailblaze.config.AppTargetYamlLoader
import xyz.block.trailblaze.config.ToolNameResolver
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.toolcalls.ToolName

/**
 * Every session built through [toSessionToolRepo] (the on-device rules, the host runner and the
 * daemon) must register `runIf`, the recorded-trail conditional, with or without a target. The
 * scripted-tool launchers register exactly [xyz.block.trailblaze.toolcalls.TrailblazeToolRepo.allCatalogScriptedToolNames],
 * so a recorded `runIf` step, or a `block_runIf` forwarding to it, fails as an unknown tool on any
 * session whose repo leaves it out.
 */
class RecordedConditionalRegistrationTest {

  private val drivers = listOf(
    TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    TrailblazeDriverType.ANDROID_TEST,
    TrailblazeDriverType.IOS_HOST,
    TrailblazeDriverType.IOS_AXE,
    TrailblazeDriverType.PLAYWRIGHT_NATIVE,
    TrailblazeDriverType.PLAYWRIGHT_ELECTRON,
  )

  @Test
  fun `a session with no target registers runIf on every driver`() {
    for (driver in drivers) {
      val registered = null.toSessionToolRepo(driver).allCatalogScriptedToolNames
      assertTrue(ToolName("runIf") in registered, "driver=${driver.yamlKey} registered=$registered")
    }
  }

  @Test
  fun `a target that scopes its toolsets still registers runIf on every driver`() {
    // A target's `tool_sets:` narrows the catalog; `runIf` must survive that because it rides an
    // always-enabled toolset rather than one a target has to name.
    val target = AppTargetYamlLoader.loadFromYaml(
      """
      id: scoped
      display_name: Scoped
      platforms:
        android:
          app_ids:
            - com.example.scoped
          tool_sets:
            - verification
        ios:
          app_ids:
            - com.example.scoped
          tool_sets:
            - verification
        web:
          app_ids:
            - scoped-web
          tool_sets:
            - web_verification
      """.trimIndent(),
      toolNameResolver = ToolNameResolver.fromBuiltInAndCustomTools(),
    )
    for (driver in drivers) {
      val registered = target.toSessionToolRepo(driver).allCatalogScriptedToolNames
      assertTrue(ToolName("runIf") in registered, "driver=${driver.yamlKey} registered=$registered")
    }
  }
}
