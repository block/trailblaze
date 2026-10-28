package xyz.block.trailblaze.host

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.config.YamlBackedHostAppTarget
import xyz.block.trailblaze.config.project.TrailblazeWorkspaceConfigResolver
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog

class AppTargetDiscoveryTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  /**
   * `TrailblazeToolSetCatalog` (workspace toolset overlay) holds process-global mutable state
   * that `AppTargetDiscovery.discover()` populates from the workspace's `trails/config/`
   * directory tree. Reset it after every test (success OR failure) so the mutation doesn't
   * leak into other tests in the same JVM via the shared singleton.
   */
  @After
  fun resetWorkspaceOverlays() {
    TrailblazeToolSetCatalog.registerWorkspaceToolSets(emptyList())
  }

  @Test
  fun `discover loads app targets from workspace trailblaze_yaml`() {
    val workspace = tempFolder.newFolder("workspace")
    val trailmapDir = File(workspace, "trails/config/trailmaps/workspaceapp").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
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
            - tapOnElementBySelector
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
  fun `discover loads app targets from workspace trailmap manifest including inline script tools`() {
    val workspace = tempFolder.newFolder("workspace")
    val trailmapDir = File(workspace, "trails/config/trailmaps/workspaceapp").apply { mkdirs() }
    File(trailmapDir, "tools").mkdirs()
    File(trailmapDir, "tools/open_workspace.yaml").writeText(
      """
      script: ./tools/open_workspace.js
      name: openWorkspace
      description: Open the workspace app
      """.trimIndent(),
    )
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: workspaceapp
      target:
        display_name: Workspace Trailmap App
        platforms:
          android:
            app_ids:
              - com.example.workspace
        tools:
          - openWorkspace
      """.trimIndent(),
    )
    // `target.tools:` is now a list of tool *names* — each name must match the `name:`
    // field declared inside a `<trailmap>/tools/*.yaml` descriptor (auto-discovered into a
    // trailmap-local registry). Operational tools (`*.tool.yaml`,
    // `*.trailhead.yaml`) in the same directory load through their own paths.
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
    assertEquals("Workspace Trailmap App", target.displayName)
    assertEquals("openWorkspace", target.getInlineScriptTools().single().name)
  }

  @Test
  fun `workspace trailmap-based target overrides filesystem flat target with same id`() {
    val workspace = tempFolder.newFolder("workspace")
    val trailmapDir = File(workspace, "trails/config/trailmaps/sharedtarget").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
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
  fun `env config dir wins over a cwd workspace that owns a colliding trailmap id`() {
    // Regression: mirrors the OSS CI hang reproduction. CWD is a workspace that itself
    // owns a `wikipedia` trailmap (here: android-only). TRAILBLAZE_CONFIG_DIR points at
    // a DIFFERENT workspace (here: an `examples/wikipedia/`-shaped tree) whose `wikipedia`
    // trailmap is web-only. The env-pointed workspace must win, so the discovered target is
    // the env one. Without `TRAILBLAZE_CONFIG_DIR` suppressing cwd walk-up, the daemon would
    // load the cwd workspace's trailmap instead.
    val cwdWorkspace = tempFolder.newFolder("cwd-workspace")
    val cwdTrailmapDir = File(cwdWorkspace, "trails/config/trailmaps/wikipedia").apply { mkdirs() }
    File(cwdTrailmapDir, "trailmap.yaml").writeText(
      """
      id: wikipedia
      target:
        display_name: CWD Android Wikipedia
        platforms:
          android:
            app_ids:
              - org.wikipedia
      """.trimIndent(),
    )
    File(cwdWorkspace, "trails/config/trailblaze.yaml").apply {
      parentFile.mkdirs()
      writeText(
        """
        targets:
          - wikipedia
        """.trimIndent(),
      )
    }

    val envWorkspace = tempFolder.newFolder("env-workspace")
    val envTrailmapDir = File(envWorkspace, "trails/config/trailmaps/wikipedia").apply { mkdirs() }
    File(envTrailmapDir, "trailmap.yaml").writeText(
      """
      id: wikipedia
      target:
        display_name: Env Web Wikipedia
        platforms:
          web: {}
      """.trimIndent(),
    )
    val envConfigDir = File(envWorkspace, "trails/config").apply { mkdirs() }
    File(envConfigDir, "trailblaze.yaml").writeText(
      """
      targets:
        - wikipedia
      """.trimIndent(),
    )

    val discovered = AppTargetDiscovery.discover(
      workspaceConfigProvider = {
        TrailblazeWorkspaceConfigResolver.resolve(
          fromPath = cwdWorkspace.toPath(),
          envReader = { envConfigDir.absolutePath },
        )
      },
    )

    // Target reflects env-workspace, not the cwd one with the colliding id.
    val target = discovered.firstOrNull { it.id == "wikipedia" }
    assertNotNull(target)
    assertEquals("Env Web Wikipedia", target.displayName)
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
  fun `failFast rejects a malformed workspace trailblaze_yaml instead of returning a partial set`() {
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

    // Leniency is right for the startup seed (see the sibling test: the workspace's readable
    // targets still show up). It is wrong for a caller that REPLACES a live set, because the
    // partial result is indistinguishable from a clean discovery and would be installed over the
    // previous workspace's real targets — and would then match itself on the drift check.
    assertFailsWith<Exception> {
      AppTargetDiscovery.discover(
        workspaceConfigProvider = {
          TrailblazeWorkspaceConfigResolver.resolve(workspace.toPath(), envReader = { null })
        },
        failFast = true,
      )
    }
  }

  @Test
  fun `invalid workspace toolset does not suppress valid workspace toolsets`() {
    val workspace = tempFolder.newFolder("workspace")
    val trailmapDir = File(workspace, "trails/config/trailmaps/workspaceapp").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
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
            - tapOnElementBySelector
        - id: broken_tools
          drivers:
            - not-a-real-driver
          tools:
            - tapOnElementBySelector
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
  fun `broken workspace trailmap does not suppress sibling target discovery`() {
    val workspace = tempFolder.newFolder("workspace")
    // Good trailmap — sibling that should still resolve.
    val goodTrailmapDir = File(workspace, "trails/config/trailmaps/workspaceapp").apply { mkdirs() }
    File(goodTrailmapDir, "trailmap.yaml").writeText(
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
    // Broken trailmap at a sibling directory — manifest fails to parse. Not listed in the
    // workspace `targets:` so it would only enter scope via auto-discovery; the
    // discovery walk skips it with a logged warning rather than aborting.
    val brokenTrailmapDir = File(workspace, "trails/config/trailmaps/broken-trailmap").apply { mkdirs() }
    File(brokenTrailmapDir, "trailmap.yaml").writeText("id: broken\ntarget:\n  display_name") // truncated YAML
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
      display_name: Compiled From Trailmap
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
      "Compiled From Trailmap",
      target.displayName,
      "dist/targets/ must override workspace targets/ on id collision so freshly-compiled " +
        "trailmaps win over stale hand-authored copies left over from a pre-trailmap-migration setup",
    )
  }

  @Test
  fun `a discovery failure resolves to the fallback set by default`() {
    val discovered = AppTargetDiscovery.discover(
      workspaceConfigProvider = { error("workspace resolution blew up") },
    )

    // The daemon-startup seed can't be denied a set, so the error is absorbed. This is also what
    // makes `failFast` necessary: the result is indistinguishable from a workspace that simply
    // declares no targets of its own.
    assertEquals(
      setOf(TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget.id),
      discovered.map { it.id }.toSet(),
    )
  }

  @Test
  fun `failFast propagates a discovery failure instead of masking it as the fallback set`() {
    val thrown = assertFailsWith<IllegalStateException> {
      AppTargetDiscovery.discover(
        workspaceConfigProvider = { error("workspace resolution blew up") },
        failFast = true,
      )
    }

    // Callers that REPLACE a live set (TrailblazeDeviceManager.reloadAppTargets) rely on this:
    // without it a broken workspace silently overwrites real targets with the one-target fallback.
    assertEquals("workspace resolution blew up", thrown.message)
  }

  @Test
  fun `failFast still returns the fallback for a workspace that declares no targets`() {
    val workspace = tempFolder.newFolder("empty-workspace")
    File(workspace, "trails/config").mkdirs()

    val discovered = AppTargetDiscovery.discover(
      workspaceConfigProvider = {
        TrailblazeWorkspaceConfigResolver.resolve(workspace.toPath(), envReader = { null })
      },
      failFast = true,
    )

    // failFast changes the ERROR path only. An empty result is a legitimate answer, so a switch
    // into a bare workspace must still leave the picker with something to render.
    assertTrue(discovered.isNotEmpty())
  }

  /**
   * A workspace target with `sharedtarget`'s id, both as a trailmap and as a prebuilt target file —
   * the same shape the CLI's own bundled targets take. [systemPromptExists] false makes the trailmap
   * fail to load, so the target file fills in for it. The trailmap depends on a library trailmap,
   * `sharedlib`, from the same workspace.
   */
  private fun workspaceWithTrailmapAndPrebuiltTarget(systemPromptExists: Boolean): Pair<File, File> {
    val workspace = tempFolder.newFolder("workspace")
    File(workspace, "trails/config/trailmaps/sharedlib").apply { mkdirs() }
      .resolve("trailmap.yaml").writeText("id: sharedlib\n")
    val trailmapDir = File(workspace, "trails/config/trailmaps/sharedtarget").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: sharedtarget
      dependencies: [sharedlib]
      target:
        display_name: From Trailmap
        system_prompt_file: prompt.md
      """.trimIndent(),
    )
    if (systemPromptExists) File(trailmapDir, "prompt.md").writeText("Be careful.")
    File(workspace, "trails/config/trailblaze.yaml").writeText("targets:\n  - sharedtarget\n")
    File(workspace, "trails/config/targets").mkdirs()
    File(workspace, "trails/config/targets/sharedtarget.yaml").writeText(
      "id: sharedtarget\ndisplay_name: Prebuilt\n",
    )
    return workspace to trailmapDir
  }

  private fun discoverIn(workspace: File) = AppTargetDiscovery.discover(
    workspaceConfigProvider = {
      TrailblazeWorkspaceConfigResolver.resolve(workspace.toPath(), envReader = { null })
    },
  )

  @Test
  fun `a target loaded from a workspace trailmap records the directory of it and each dependency`() {
    val (workspace, trailmapDir) = workspaceWithTrailmapAndPrebuiltTarget(systemPromptExists = true)

    val target = discoverIn(workspace).single { it.id == "sharedtarget" } as YamlBackedHostAppTarget

    assertEquals("From Trailmap", target.displayName)
    assertEquals(
      mapOf(
        "sharedtarget" to trailmapDir.canonicalFile,
        "sharedlib" to File(workspace, "trails/config/trailmaps/sharedlib").canonicalFile,
      ),
      target.trailmapDirs.mapValues { (_, dir) -> dir?.canonicalFile },
    )
  }

  /**
   * The stand-in is what made a broken workspace trailmap silent: it runs, under the same id, from
   * files the author is not editing. Recording no directory for it is how a command tells.
   */
  @Test
  fun `a target that fills in for a workspace trailmap that failed to load records no directories`() {
    val (workspace, _) = workspaceWithTrailmapAndPrebuiltTarget(systemPromptExists = false)

    val target = discoverIn(workspace).single { it.id == "sharedtarget" } as YamlBackedHostAppTarget

    assertEquals("Prebuilt", target.displayName)
    assertEquals(emptyMap(), target.trailmapDirs)
  }
}
