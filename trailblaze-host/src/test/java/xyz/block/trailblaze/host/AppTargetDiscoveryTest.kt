package xyz.block.trailblaze.host

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.config.project.TrailblazeWorkspaceConfigResolver
import xyz.block.trailblaze.devices.TrailblazeDriverType

class AppTargetDiscoveryTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `discover loads app targets from workspace trailblaze_yaml`() {
    val workspace = tempFolder.newFolder("workspace")
    File(workspace, "trails/config/trailblaze.yaml").apply {
      parentFile.mkdirs()
      writeText(
      """
      toolsets:
        - id: workspace_android_tools
          tools:
            - tapOnElementWithText
      targets:
        - id: workspaceapp
          display_name: Workspace App
          platforms:
            android:
              app_ids:
                - com.example.workspace
              tool_sets:
                - workspace_android_tools
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
    File(workspace, "trails/config/trailblaze.yaml").apply {
      parentFile.mkdirs()
      writeText(
        """
        packs:
          - packs/workspaceapp/pack.yaml
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
  fun `workspace trailblaze_yaml overrides filesystem config targets by id`() {
    val workspace = tempFolder.newFolder("workspace")
    File(workspace, "trails/config/trailblaze.yaml").apply {
      parentFile.mkdirs()
      writeText(
      """
      targets:
        - id: sharedtarget
          display_name: Project Override
          platforms:
            android:
              app_ids:
                - com.example.project
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
      setOf("com.example.project"),
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
        - id: workspaceapp
          display_name: Workspace App
          platforms:
            android:
              app_ids:
                - com.example.workspace
              tool_sets:
                - working_tools
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
    File(workspace, "trails/config/trailblaze.yaml").apply {
      parentFile.mkdirs()
      writeText(
        """
        packs:
          - packs/broken-pack/pack.yaml
        targets:
          - id: workspaceapp
            display_name: Workspace App
            platforms:
              android:
                app_ids:
                  - com.example.workspace
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
