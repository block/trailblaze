package xyz.block.trailblaze.host

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.config.project.TrailblazeWorkspaceConfigResolver
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeSerializationInitializer
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog

class AppTargetDiscoveryTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  /**
   * Both `TrailblazeSerializationInitializer` (workspace YAML-defined tool overlay) and
   * `TrailblazeToolSetCatalog` (workspace toolset overlay) hold process-global mutable state
   * that `AppTargetDiscovery.discover()` populates from the workspace's `trails/config/`
   * directory tree. Reset both after every test (success OR failure) so the mutation
   * doesn't leak into other tests in the same JVM via the shared singletons.
   */
  @After
  fun resetWorkspaceOverlays() {
    TrailblazeSerializationInitializer.registerWorkspaceYamlTools(emptyMap())
    TrailblazeToolSetCatalog.registerWorkspaceToolSets(emptyList())
  }

  @Test
  fun `discover loads app targets from workspace trailblaze_yaml`() {
    val workspace = tempFolder.newFolder("workspace")
    val packDir = File(workspace, "trails/config/packs/workspaceapp").apply { mkdirs() }
    File(packDir, "pack.yaml").writeText(
      """
      id: workspaceapp
      target:
        display_name: Workspace App
        platforms:
          android:
            app_ids:
              - com.example.workspace
            tool_sets:
              - workspace_android_tools
      """.trimIndent(),
    )
    File(workspace, "trails/config/trailblaze.yaml").apply {
      parentFile.mkdirs()
      writeText(
      """
      toolsets:
        - id: workspace_android_tools
          tools:
            - tapOnElementWithText
      targets:
        - workspaceapp
      """.trimIndent(),
      )
    }

    val discovered = AppTargetDiscovery.discover(
      workspaceConfigProvider = {
        TrailblazeWorkspaceConfigResolver.resolve(workspace.toPath(), envReader = { null })
      },
    )

    val target = discovered.firstOrNull { it.id == "workspaceapp" }
    assertNotNull(target)
    assertEquals("Workspace App", target.displayName)
    assertTrue(target.getCustomToolsForDriver(TrailblazeDriverType.DEFAULT_ANDROID).isNotEmpty())
  }

  @Test
  fun `discover loads app targets from workspace pack manifest including inline script tools`() {
    val workspace = tempFolder.newFolder("workspace")
    val packDir = File(workspace, "trails/config/packs/workspaceapp").apply { mkdirs() }
    File(packDir, "tools").mkdirs()
    File(packDir, "tools/open_workspace.yaml").writeText(
      """
      script: ./tools/open_workspace.js
      name: openWorkspace
      description: Open the workspace app
      """.trimIndent(),
    )
    File(packDir, "pack.yaml").writeText(
      """
      id: workspaceapp
      target:
        display_name: Workspace Pack App
        platforms:
          android:
            app_ids:
              - com.example.workspace
        tools:
          - tools/open_workspace.yaml
      """.trimIndent(),
    )
    // `target.tools:` (per-target scripted-tool descriptors) is still an explicit list —
    // those go through the per-target tool path, distinct from the auto-discovered
    // operational tools under `<pack>/tools/<name>.{tool,shortcut,trailhead}.yaml`.
    File(workspace, "trails/config/trailblaze.yaml").apply {
      parentFile.mkdirs()
      writeText(
        """
        targets:
          - workspaceapp
        """.trimIndent(),
      )
    }

    val discovered = AppTargetDiscovery.discover(
      workspaceConfigProvider = {
        TrailblazeWorkspaceConfigResolver.resolve(workspace.toPath(), envReader = { null })
      },
    )

    val target = discovered.firstOrNull { it.id == "workspaceapp" }
    assertNotNull(target)
    assertEquals("Workspace Pack App", target.displayName)
    assertEquals("openWorkspace", target.getInlineScriptTools().single().name)
  }

  @Test
  fun `workspace pack-based target overrides filesystem flat target with same id`() {
    val workspace = tempFolder.newFolder("workspace")
    val packDir = File(workspace, "trails/config/packs/sharedtarget").apply { mkdirs() }
    File(packDir, "pack.yaml").writeText(
      """
      id: sharedtarget
      target:
        display_name: Project Override
        platforms:
          android:
            app_ids:
              - com.example.project
      """.trimIndent(),
    )
    File(workspace, "trails/config/trailblaze.yaml").apply {
      parentFile.mkdirs()
      writeText(
      """
      targets:
        - sharedtarget
      """.trimIndent(),
      )
    }
    val targetsDir = File(workspace, "trails/config/targets").apply { mkdirs() }
    File(targetsDir, "sharedtarget.yaml").writeText(
      """
      id: sharedtarget
      display_name: Filesystem Target
      platforms:
        android:
          app_ids:
            - com.example.filesystem
      """.trimIndent(),
    )

    val discovered = AppTargetDiscovery.discover(
      workspaceConfigProvider = {
        TrailblazeWorkspaceConfigResolver.resolve(workspace.toPath(), envReader = { null })
      },
    )

    val target = discovered.firstOrNull { it.id == "sharedtarget" }
    assertNotNull(target)
    assertEquals("Project Override", target.displayName)
    assertEquals(
      listOf("com.example.project"),
      target.getPossibleAppIdsForPlatform(xyz.block.trailblaze.devices.TrailblazeDevicePlatform.ANDROID),
    )
  }

  @Test
  fun `malformed workspace trailblaze_yaml does not suppress sibling target discovery`() {
    val workspace = tempFolder.newFolder("workspace")
    File(workspace, "trails/config/trailblaze.yaml").apply {
      parentFile.mkdirs()
      writeText(
      """
      targets:
        - id: broken
          display_name
      """.trimIndent(),
      )
    }
    val targetsDir = File(workspace, "trails/config/targets").apply { mkdirs() }
    File(targetsDir, "filesystem-target.yaml").writeText(
      """
      id: filesystemtarget
      display_name: Filesystem Target
      platforms:
        android:
          app_ids:
            - com.example.filesystem
      """.trimIndent(),
    )

    val discovered = AppTargetDiscovery.discover(
      workspaceConfigProvider = {
        TrailblazeWorkspaceConfigResolver.resolve(workspace.toPath(), envReader = { null })
      },
    )

    val target = discovered.firstOrNull { it.id == "filesystemtarget" }
    assertNotNull(target)
    assertEquals("Filesystem Target", target.displayName)
  }

  @Test
  fun `invalid workspace toolset does not suppress valid workspace toolsets`() {
    val workspace = tempFolder.newFolder("workspace")
    val packDir = File(workspace, "trails/config/packs/workspaceapp").apply { mkdirs() }
    File(packDir, "pack.yaml").writeText(
      """
      id: workspaceapp
      target:
        display_name: Workspace App
        platforms:
          android:
            app_ids:
              - com.example.workspace
            tool_sets:
              - working_tools
      """.trimIndent(),
    )
    File(workspace, "trails/config/trailblaze.yaml").apply {
      parentFile.mkdirs()
      writeText(
      """
      toolsets:
        - id: working_tools
          tools:
            - tapOnElementWithText
        - id: broken_tools
          drivers:
            - not-a-real-driver
          tools:
            - tapOnElementWithText
      targets:
        - workspaceapp
      """.trimIndent(),
      )
    }

    val discovered = AppTargetDiscovery.discover(
      workspaceConfigProvider = {
        TrailblazeWorkspaceConfigResolver.resolve(workspace.toPath(), envReader = { null })
      },
    )

    val target = discovered.firstOrNull { it.id == "workspaceapp" }
    assertNotNull(target)
    assertEquals("Workspace App", target.displayName)
    assertTrue(target.getCustomToolsForDriver(TrailblazeDriverType.DEFAULT_ANDROID).isNotEmpty())
  }

  @Test
  fun `broken workspace pack does not suppress sibling target discovery`() {
    val workspace = tempFolder.newFolder("workspace")
    // Good pack — sibling that should still resolve.
    val goodPackDir = File(workspace, "trails/config/packs/workspaceapp").apply { mkdirs() }
    File(goodPackDir, "pack.yaml").writeText(
      """
      id: workspaceapp
      target:
        display_name: Workspace App
        platforms:
          android:
            app_ids:
              - com.example.workspace
      """.trimIndent(),
    )
    // Broken pack at a sibling directory — manifest fails to parse. Not listed in the
    // workspace `targets:` so it would only enter scope via auto-discovery; the
    // discovery walk skips it with a logged warning rather than aborting.
    val brokenPackDir = File(workspace, "trails/config/packs/broken-pack").apply { mkdirs() }
    File(brokenPackDir, "pack.yaml").writeText("id: broken\ntarget:\n  display_name") // truncated YAML
    File(workspace, "trails/config/trailblaze.yaml").apply {
      parentFile.mkdirs()
      writeText(
        """
        targets:
          - workspaceapp
        """.trimIndent(),
      )
    }

    val discovered = AppTargetDiscovery.discover(
      workspaceConfigProvider = {
        TrailblazeWorkspaceConfigResolver.resolve(workspace.toPath(), envReader = { null })
      },
    )

    val target = discovered.firstOrNull { it.id == "workspaceapp" }
    assertNotNull(target)
    assertEquals("Workspace App", target.displayName)
  }

  @Test
  fun `workspace tool_yaml pure-yaml composed tool registers and resolves through toolsets`() {
    // Regression: before this wiring landed, a workspace-authored `*.tool.yaml`
    // (Mode.TOOLS — pure YAML composition, no Kotlin class, no `.ts` script body) couldn't
    // be referenced by name from a workspace toolset. The toolset loader emitted
    // `Unknown tool name 'X' — skipping`, the tool dropped out of every toolset that listed
    // it, and the daemon's tool catalog never carried it. Reproducer was a one-shot user
    // pack with `<workspace>/trails/config/tools/wikipedia_back_safe.tool.yaml` listed in
    // `<workspace>/trails/config/toolsets/extras.yaml`. Same gap surfaced for
    // `<pack>/tools/<id>.tool.yaml` inside a workspace pack.
    val workspace = tempFolder.newFolder("workspace")
    File(workspace, "trails/config/tools").apply { mkdirs() }
    File(workspace, "trails/config/tools/workspace_back.tool.yaml").writeText(
      """
      id: workspace_back
      description: |
        Pure-YAML workspace tool that composes the bundled `pressBack` primitive via the
        maestro adapter — same shape as the framework's `pressBack.tool.yaml`, but
        authored by an end user in their workspace rather than shipped on the classpath.
      parameters: []
      tools:
        - maestro:
            commands:
              - back: {}
      """.trimIndent(),
    )
    File(workspace, "trails/config/packs/workspaceapp").apply { mkdirs() }
    File(workspace, "trails/config/packs/workspaceapp/pack.yaml").writeText(
      """
      id: workspaceapp
      target:
        display_name: Workspace App
        platforms:
          android:
            app_ids:
              - com.example.workspace
            tool_sets:
              - workspace_extras
      """.trimIndent(),
    )
    File(workspace, "trails/config/trailblaze.yaml").apply {
      parentFile.mkdirs()
      writeText(
        """
        toolsets:
          - id: workspace_extras
            tools:
              - workspace_back
        targets:
          - workspaceapp
        """.trimIndent(),
      )
    }

    val discovered = AppTargetDiscovery.discover(
      workspaceConfigProvider = {
        TrailblazeWorkspaceConfigResolver.resolve(workspace.toPath(), envReader = { null })
      },
    )

    // The target resolves and the workspace-defined YAML tool name is reachable through the
    // serialization initializer's overlay — both registrations the resolver needs are in place.
    val target = discovered.firstOrNull { it.id == "workspaceapp" }
    assertNotNull(target, "workspaceapp target must be discovered")
    assertEquals("Workspace App", target.displayName)
    assertTrue(
      ToolName("workspace_back") in TrailblazeSerializationInitializer.buildYamlDefinedTools().keys,
      "workspace_back must be registered in the serialization initializer overlay so " +
        "`TrailblazeToolRepo.toolCallToTrailblazeTool` can find its config at runtime",
    )
    // Overlay reset handled by `@After resetWorkspaceYamlOverlay()` so a failed assertion
    // above still cleans up — earlier per-test inline cleanup runs only on the success path.
  }

  @Test
  fun `workspace pack-local tool_yaml registers from pack tools subdir`() {
    // Same as the `trails/config/tools/` registration above, but with the YAML tool inside
    // a pack subdir (`<pack>/tools/<id>.tool.yaml`) — the documented convention for
    // pack-owned operational tools. The resource source's filesystem walk picks both
    // locations up; the registration plumbing must work for both.
    val workspace = tempFolder.newFolder("workspace")
    val packDir = File(workspace, "trails/config/packs/workspaceapp").apply { mkdirs() }
    File(packDir, "tools").mkdirs()
    File(packDir, "tools/workspace_pack_back.tool.yaml").writeText(
      """
      id: workspace_pack_back
      description: Pack-local pure-YAML tool composed from the maestro back primitive.
      parameters: []
      tools:
        - maestro:
            commands:
              - back: {}
      """.trimIndent(),
    )
    File(packDir, "pack.yaml").writeText(
      """
      id: workspaceapp
      target:
        display_name: Workspace App
        platforms:
          android:
            app_ids:
              - com.example.workspace
      """.trimIndent(),
    )
    File(workspace, "trails/config/trailblaze.yaml").apply {
      parentFile.mkdirs()
      writeText(
        """
        targets:
          - workspaceapp
        """.trimIndent(),
      )
    }

    AppTargetDiscovery.discover(
      workspaceConfigProvider = {
        TrailblazeWorkspaceConfigResolver.resolve(workspace.toPath(), envReader = { null })
      },
    )

    assertTrue(
      ToolName("workspace_pack_back") in
        TrailblazeSerializationInitializer.buildYamlDefinedTools().keys,
      "Pack-local `<pack>/tools/<id>.tool.yaml` must register the same way " +
        "workspace-flat `<workspace>/trails/config/tools/<id>.tool.yaml` does",
    )
    // Overlay reset handled by `@After resetWorkspaceYamlOverlay()`.
  }

  @Test
  fun `workspace tool with classpath-colliding id overrides the bundled one`() {
    // Pin the override contract documented on
    // `TrailblazeSerializationInitializer.registerWorkspaceYamlTools`. A workspace
    // `*.tool.yaml` with an id that collides with a classpath-shipped tool — `pressBack`
    // is bundled in `trailblaze-common`'s `trailblaze-config/tools/pressBack.tool.yaml` —
    // must reach the overlay so the union's last-write-wins semantics apply the workspace
    // version. Pre-fix the registration filter dropped workspace tools whose id already
    // appeared on the classpath, silently discarding the override and leaving the
    // bundled config in effect.
    val workspace = tempFolder.newFolder("workspace")
    val toolsDir = File(workspace, "trails/config/tools").apply { mkdirs() }
    File(toolsDir, "pressBack.tool.yaml").writeText(
      """
      id: pressBack
      description: |
        Workspace-authored override of the bundled `pressBack` tool. The body is
        intentionally distinct from the framework's so the test can verify which
        version landed in the registry.
      parameters: []
      tools:
        - maestro:
            commands:
              - back: {}
              - back: {}
      """.trimIndent(),
    )
    File(workspace, "trails/config/trailblaze.yaml").apply {
      parentFile.mkdirs()
      writeText("")
    }

    AppTargetDiscovery.discover(
      workspaceConfigProvider = {
        TrailblazeWorkspaceConfigResolver.resolve(workspace.toPath(), envReader = { null })
      },
    )

    val resolved = TrailblazeSerializationInitializer.buildYamlDefinedTools()[ToolName("pressBack")]
    assertNotNull(resolved, "pressBack must still be reachable after the workspace override registers")
    // The workspace version's description starts with "Workspace-authored override" — the
    // bundled framework one starts with "Navigates back one screen". Asserting on the
    // description is the cleanest signal of which body won the union.
    assertTrue(
      resolved.description.orEmpty().startsWith("Workspace-authored override"),
      "Workspace `pressBack` must override the classpath-bundled one, but the resolved " +
        "config's description was: '${resolved.description}'",
    )
  }

  @Test
  fun `target toolset resolution surfaces workspace yaml tool names for dispatch repo`() {
    // Pin the load-bearing piece of the dispatch-side fix in
    // `TrailblazeMcpServer.ensureSessionScriptToolRuntime`. The session repo is constructed
    // with
    //   customYamlToolNames = collectCustomYamlToolNames(driverType) + resolvedFromPack.yamlToolNames
    // where `resolvedFromPack = TrailblazeToolSetCatalog.resolveForDriver(driverType,
    // target.getDeclaredToolSetIdsForDriver(driverType))`. This test verifies the part that
    // makes the dispatch fix work: after `AppTargetDiscovery.discover()` registers a
    // workspace pure-YAML composed tool referenced by a workspace toolset, the catalog's
    // `resolveForDriver(...)` call (the exact one the MCP server makes at session-init
    // time) returns a `ResolvedToolSet` whose `yamlToolNames` contains the workspace tool.
    //
    // The earlier ignored version of this test failed with `got: [eraseText]` because the
    // catalog's `defaultEntries()` was classpath-only-cached. Now `AppTargetDiscovery`
    // registers the workspace-aware merged toolset map into the catalog overlay via
    // `TrailblazeToolSetCatalog.registerWorkspaceToolSets`, and the test passes.
    val workspace = tempFolder.newFolder("workspace")
    val toolsDir = File(workspace, "trails/config/tools").apply { mkdirs() }
    File(toolsDir, "dispatch_back.tool.yaml").writeText(
      """
      id: dispatch_back
      description: Workspace YAML-defined tool referenced from a workspace toolset.
      parameters: []
      tools:
        - maestro:
            commands:
              - back: {}
      """.trimIndent(),
    )
    val packDir = File(workspace, "trails/config/packs/workspaceapp").apply { mkdirs() }
    File(packDir, "pack.yaml").writeText(
      """
      id: workspaceapp
      target:
        display_name: Workspace App
        platforms:
          android:
            app_ids:
              - com.example.workspace
            tool_sets:
              - dispatch_extras
      """.trimIndent(),
    )
    File(workspace, "trails/config/trailblaze.yaml").apply {
      parentFile.mkdirs()
      writeText(
        """
        toolsets:
          - id: dispatch_extras
            tools:
              - dispatch_back
        targets:
          - workspaceapp
        """.trimIndent(),
      )
    }

    val discovered = AppTargetDiscovery.discover(
      workspaceConfigProvider = {
        TrailblazeWorkspaceConfigResolver.resolve(workspace.toPath(), envReader = { null })
      },
    )
    val target = assertNotNull(discovered.firstOrNull { it.id == "workspaceapp" })

    // The exact call shape `TrailblazeMcpServer.ensureSessionScriptToolRuntime` makes:
    //   val declaredToolSetIds = target.getDeclaredToolSetIdsForDriver(driverType)
    //   val resolvedFromPack = TrailblazeToolSetCatalog.resolveForDriver(driverType, declaredToolSetIds)
    //   customYamlToolNames = collectCustomYamlToolNames(driverType) + resolvedFromPack.yamlToolNames
    val driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION
    val declaredToolSetIds = target.getDeclaredToolSetIdsForDriver(driverType)
    assertTrue(
      "dispatch_extras" in declaredToolSetIds,
      "target's declared toolsets must include the workspace toolset that names the tool — " +
        "got: $declaredToolSetIds",
    )
    val resolvedFromPack = TrailblazeToolSetCatalog.resolveForDriver(driverType, declaredToolSetIds)
    assertTrue(
      ToolName("dispatch_back") in resolvedFromPack.yamlToolNames,
      "TrailblazeToolSetCatalog.resolveForDriver must surface the workspace YAML-defined tool " +
        "name so the dispatch repo's `customYamlToolNames` union includes it — got: " +
        "${resolvedFromPack.yamlToolNames.map { it.toolName }.sorted()}",
    )
  }

  @Test
  fun `classpath-bundled yaml tools are not accidentally registered as workspace overlay`() {
    // Pin the fix for the operational regression flagged in the second lead-dev review:
    // `AppTargetDiscovery.registerWorkspaceYamlTools` walks the composite resource source
    // (classpath + workspace filesystem). Pre-fix, classpath-bundled tools (`pressBack`,
    // `eraseText`) got included in `allDiscovered`, force-flipped to `requires_host =
    // true`, and registered into the workspace overlay — silently changing their
    // dispatch path through the new host-expansion gate in `TrailblazeMcpBridgeImpl.
    // executeTrailblazeTool` (which uses a synthesized empty `AgentMemory()` context).
    //
    // The fix filters `allDiscovered` against the classpath snapshot via
    // `classpathConfigs[k] == v` equality. A workspace with NO `*.tool.yaml` files
    // should produce an EMPTY overlay — verifying that here is the cheapest regression
    // pin: if the filter regresses, classpath tools leak into the overlay and this test
    // fails with a non-empty overlay.
    val workspace = tempFolder.newFolder("workspace")
    File(workspace, "trails/config/trailblaze.yaml").apply {
      parentFile.mkdirs()
      writeText("")
    }
    // No workspace `*.tool.yaml` files. Discovery should NOT register anything in the
    // overlay — bundled `pressBack` and `eraseText` (which the composite source walks)
    // are equal to their classpath cache entries and get filtered out.

    AppTargetDiscovery.discover(
      workspaceConfigProvider = {
        TrailblazeWorkspaceConfigResolver.resolve(workspace.toPath(), envReader = { null })
      },
    )

    // Overlay is empty: `buildYamlDefinedTools()` returns the classpath cache verbatim.
    val classpathOnly = TrailblazeSerializationInitializer.getClasspathYamlDefinedTools()
    val merged = TrailblazeSerializationInitializer.buildYamlDefinedTools()
    assertEquals(
      classpathOnly,
      merged,
      "With no workspace tools authored, the overlay must be empty so classpath tools " +
        "keep their original dispatch behavior — got merged=$merged vs classpath=$classpathOnly",
    )
  }

  @Test
  fun `second discover pass preserves the previously-registered workspace overlay`() {
    // Pin re-discovery soundness — `discover()` is `by lazy` today (runs once per JVM),
    // but the overlay registration must not depend on that. Pre-fix, on a second call,
    // the "subtract classpath" filter computed off `buildYamlDefinedTools()` (which
    // returns classpath + overlay) would drop every previously-registered workspace tool,
    // then `registerWorkspaceYamlTools(emptySet)` would wipe the overlay entirely. The
    // workspace tool would seem to "disappear" on the next session-init call.
    val workspace = tempFolder.newFolder("workspace")
    val toolsDir = File(workspace, "trails/config/tools").apply { mkdirs() }
    File(toolsDir, "persistent_back.tool.yaml").writeText(
      """
      id: persistent_back
      description: Workspace tool registered twice across two discover passes.
      parameters: []
      tools:
        - maestro:
            commands:
              - back: {}
      """.trimIndent(),
    )
    File(workspace, "trails/config/trailblaze.yaml").apply {
      parentFile.mkdirs()
      writeText("")
    }
    val workspaceConfigProvider = {
      TrailblazeWorkspaceConfigResolver.resolve(workspace.toPath(), envReader = { null })
    }

    AppTargetDiscovery.discover(workspaceConfigProvider = workspaceConfigProvider)
    AppTargetDiscovery.discover(workspaceConfigProvider = workspaceConfigProvider)

    assertTrue(
      ToolName("persistent_back") in TrailblazeSerializationInitializer.buildYamlDefinedTools().keys,
      "A workspace YAML tool must still be registered after a second `discover()` pass — " +
        "the registration path must not stomp on its own prior overlay entries.",
    )
  }

  @Test
  fun `discover picks up compiled targets from workspace dist directory`() {
    val workspace = tempFolder.newFolder("workspace")
    File(workspace, "trails/config/trailblaze.yaml").apply {
      parentFile.mkdirs()
      writeText("")
    }
    val distTargets = File(workspace, "trails/config/dist/targets").apply { mkdirs() }
    File(distTargets, "distapp.yaml").writeText(
      """
      id: distapp
      display_name: Dist App
      platforms:
        android:
          app_ids:
            - com.example.dist
      """.trimIndent(),
    )

    val discovered = AppTargetDiscovery.discover(
      workspaceConfigProvider = {
        TrailblazeWorkspaceConfigResolver.resolve(workspace.toPath(), envReader = { null })
      },
    )

    val target = discovered.firstOrNull { it.id == "distapp" }
    assertNotNull(target)
    assertEquals("Dist App", target.displayName)
  }

  @Test
  fun `dist target wins over hand-authored workspace target with same id`() {
    val workspace = tempFolder.newFolder("workspace")
    File(workspace, "trails/config/trailblaze.yaml").apply {
      parentFile.mkdirs()
      writeText("")
    }
    val handAuthoredTargets = File(workspace, "trails/config/targets").apply { mkdirs() }
    File(handAuthoredTargets, "shared.yaml").writeText(
      """
      id: shared
      display_name: Hand-Authored
      platforms:
        android:
          app_ids:
            - com.example.handauthored
      """.trimIndent(),
    )
    val distTargets = File(workspace, "trails/config/dist/targets").apply { mkdirs() }
    File(distTargets, "shared.yaml").writeText(
      """
      id: shared
      display_name: Compiled From Pack
      platforms:
        android:
          app_ids:
            - com.example.compiled
      """.trimIndent(),
    )

    val discovered = AppTargetDiscovery.discover(
      workspaceConfigProvider = {
        TrailblazeWorkspaceConfigResolver.resolve(workspace.toPath(), envReader = { null })
      },
    )

    val target = discovered.firstOrNull { it.id == "shared" }
    assertNotNull(target)
    assertEquals(
      "Compiled From Pack",
      target.displayName,
      "dist/targets/ must override workspace targets/ on id collision so freshly-compiled " +
        "packs win over stale hand-authored copies left over from a pre-pack-migration setup",
    )
  }
}
