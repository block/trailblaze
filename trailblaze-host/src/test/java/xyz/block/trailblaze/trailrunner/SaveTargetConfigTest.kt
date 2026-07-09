package xyz.block.trailblaze.trailrunner

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.config.PlatformConfig
import xyz.block.trailblaze.config.TrailblazeConfigYaml
import xyz.block.trailblaze.config.project.TrailblazeProjectConfig
import xyz.block.trailblaze.config.project.TrailblazeTrailmapManifest
import xyz.block.trailblaze.llm.config.WorkspaceConfigDirHolder

/**
 * Coverage for [buildSaveTargetConfigResponse] — patching the `target:` block of an existing
 * `trailmap.yaml`, the write-back half of the "Edit Target" feature (GitHub block/trailblaze
 * issue 200 follow-up). Uses the same `WorkspaceConfigDirHolder.resolver` fixture-swap pattern as
 * [ToolCatalogBuilderTest] to point [ToolSourceFiles.trailmapBaseDir] at a temp workspace.
 */
class SaveTargetConfigTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private suspend fun withWorkspace(block: suspend (configDir: File) -> Unit) {
    val configDir = tmp.newFolder("config")
    val previousResolver = WorkspaceConfigDirHolder.resolver
    WorkspaceConfigDirHolder.resolver = { configDir }
    try {
      block(configDir)
    } finally {
      WorkspaceConfigDirHolder.resolver = previousResolver
    }
  }

  private fun manifestFile(configDir: File, trailmapId: String): File =
    File(configDir, "trailmaps/$trailmapId/trailmap.yaml")

  private fun writeManifest(configDir: File, trailmapId: String, yaml: String) {
    manifestFile(configDir, trailmapId).apply { parentFile.mkdirs(); writeText(yaml) }
  }

  private fun readManifest(configDir: File, trailmapId: String): TrailblazeTrailmapManifest =
    TrailblazeConfigYaml.instance.decodeFromString(
      TrailblazeTrailmapManifest.serializer(),
      manifestFile(configDir, trailmapId).readText(),
    )

  @Test
  fun `adds a target block to a library trailmap that has none`() = runBlocking {
    withWorkspace { configDir ->
      writeManifest(
        configDir,
        "lib",
        """
        id: lib
        toolsets:
          - toolsets/lib_extra.yaml
        """.trimIndent(),
      )
      val response = buildSaveTargetConfigResponse(
        SaveTargetConfigRequest(trailmapId = "lib", displayName = "Library App"),
      )
      assertTrue(response.ok)
      val updated = readManifest(configDir, "lib")
      assertEquals("Library App", updated.target?.displayName)
      // Untouched top-level content survives.
      assertEquals(listOf("toolsets/lib_extra.yaml"), updated.toolsets)
    }
  }

  @Test
  fun `updates an existing target's fields while preserving untouched platform fields`() = runBlocking {
    withWorkspace { configDir ->
      writeManifest(
        configDir,
        "demo",
        """
        id: demo
        dependencies:
          - trailblaze
        toolsets:
          - toolsets/demo_extra.yaml
        waypoints:
          - waypoints/demo-home.waypoint.yaml
        target:
          display_name: Demo App
          platforms:
            android:
              app_ids:
                - com.example.demo
              tool_sets:
                - core_interaction
            ios:
              app_ids:
                - com.example.demo.ios
        """.trimIndent(),
      )
      val response = buildSaveTargetConfigResponse(
        SaveTargetConfigRequest(
          trailmapId = "demo",
          displayName = "Demo App Renamed",
          icon = "assets/icons/demo.png",
          platforms = mapOf(
            // Resend the current app_ids alongside the new icon — the documented contract for an
            // edited platform (the form pre-populates current values).
            "android" to SaveTargetPlatformPatch(appIds = listOf("com.example.demo"), icon = "assets/icons/android_demo.png"),
          ),
        ),
      )
      assertTrue(response.ok)
      val updated = readManifest(configDir, "demo")
      val target = updated.target
      assertEquals("Demo App Renamed", target?.displayName)
      assertEquals("assets/icons/demo.png", target?.icon)
      // Edited platform: new icon applied, existing app_ids resent so they survive, and the
      // NOT-exposed tool_sets field is carried over unchanged from the existing manifest.
      val android = target?.platforms?.get("android")
      assertEquals("assets/icons/android_demo.png", android?.icon)
      assertEquals(listOf("com.example.demo"), android?.appIds)
      assertEquals(listOf("core_interaction"), android?.toolSets)
      // Untouched platform (ios) survives completely as-is — not mentioned in the request.
      val ios = target?.platforms?.get("ios")
      assertEquals(listOf("com.example.demo.ios"), ios?.appIds)
      assertNull(ios?.icon)
      // Untouched top-level manifest content survives the re-serialize.
      assertEquals(listOf("trailblaze"), updated.dependencies)
      assertEquals(listOf("toolsets/demo_extra.yaml"), updated.toolsets)
      assertEquals(listOf("waypoints/demo-home.waypoint.yaml"), updated.waypoints)
    }
  }

  @Test
  fun `a null appIds patch clears the field rather than writing an empty list`() = runBlocking {
    withWorkspace { configDir ->
      writeManifest(
        configDir,
        "demo",
        """
        id: demo
        target:
          display_name: Demo App
          platforms:
            web:
              app_ids:
                - stale.example.com
              base_url: https://example.com
        """.trimIndent(),
      )
      // Mirrors the Edit Target form's contract: a blank appIds field sends null (clear), not []
      // — an empty list is a real value that gets serialized as `app_ids: []`, which would read as
      // "explicitly no app ids" rather than "field not touched by this edit."
      val response = buildSaveTargetConfigResponse(
        SaveTargetConfigRequest(
          trailmapId = "demo",
          displayName = "Demo App",
          platforms = mapOf("web" to SaveTargetPlatformPatch(appIds = null, baseUrl = "https://example.com")),
        ),
      )
      assertTrue(response.ok)
      val web = readManifest(configDir, "demo").target?.platforms?.get("web")
      assertNull(web?.appIds)
      assertEquals("https://example.com", web?.baseUrl)
      // The raw YAML shouldn't contain an `app_ids: []` line for this platform.
      assertFalse(manifestFile(configDir, "demo").readText().contains("app_ids"))
    }
  }

  @Test
  fun `removing a platform drops it entirely while leaving other platforms untouched`() = runBlocking {
    withWorkspace { configDir ->
      writeManifest(
        configDir,
        "demo",
        """
        id: demo
        target:
          display_name: Demo App
          platforms:
            android:
              app_ids:
                - com.example.demo
              tool_sets:
                - core_interaction
            ios:
              app_ids:
                - com.example.demo.ios
        """.trimIndent(),
      )
      val response = buildSaveTargetConfigResponse(
        SaveTargetConfigRequest(
          trailmapId = "demo",
          displayName = "Demo App",
          platforms = mapOf("android" to SaveTargetPlatformPatch(remove = true)),
        ),
      )
      assertTrue(response.ok)
      val target = readManifest(configDir, "demo").target
      assertNull(target?.platforms?.get("android"))
      assertEquals(listOf("com.example.demo.ios"), target?.platforms?.get("ios")?.appIds)
    }
  }

  @Test
  fun `removing the only platform leaves platforms absent rather than an empty map`() = runBlocking {
    withWorkspace { configDir ->
      writeManifest(
        configDir,
        "demo",
        """
        id: demo
        target:
          display_name: Demo App
          platforms:
            android:
              app_ids:
                - com.example.demo
        """.trimIndent(),
      )
      val response = buildSaveTargetConfigResponse(
        SaveTargetConfigRequest(
          trailmapId = "demo",
          displayName = "Demo App",
          platforms = mapOf("android" to SaveTargetPlatformPatch(remove = true)),
        ),
      )
      assertTrue(response.ok)
      assertNull(readManifest(configDir, "demo").target?.platforms)
    }
  }

  @Test
  fun `fails with an error when the trailmap id is unknown`() = runBlocking {
    withWorkspace {
      val response = buildSaveTargetConfigResponse(
        SaveTargetConfigRequest(trailmapId = "does-not-exist", displayName = "Whatever"),
      )
      assertFalse(response.ok)
      assertTrue(response.error.orEmpty().contains("unknown trailmap"))
    }
  }

  @Test
  fun `fails with an error when the trailmap directory exists but has no trailmap yaml yet`() = runBlocking {
    withWorkspace { configDir ->
      // A directory with only a tool file, never authored a manifest — bootstrapping a brand-new
      // trailmap.yaml requires the caller to opt in via createIfMissing.
      File(configDir, "trailmaps/bare/tools").mkdirs()
      val response = buildSaveTargetConfigResponse(
        SaveTargetConfigRequest(trailmapId = "bare", displayName = "Whatever"),
      )
      assertFalse(response.ok)
      assertTrue(response.error.orEmpty().contains("no trailmap.yaml"))
    }
  }

  // createIfMissing — bootstrapping a brand-new trailmap from nothing (the "+ Create Target" flow;
  // GitHub block/trailblaze issue 190).

  @Test
  fun `createIfMissing bootstraps a brand-new trailmap with the minimal documented shape`() = runBlocking {
    withWorkspace { configDir ->
      val response = buildSaveTargetConfigResponse(
        SaveTargetConfigRequest(
          trailmapId = "myapp",
          displayName = "My App",
          platforms = mapOf("android" to SaveTargetPlatformPatch(appIds = listOf("com.example.myapp"))),
          createIfMissing = true,
        ),
      )
      assertTrue(response.ok)
      assertTrue(response.created)
      assertNull(response.warning)
      val manifest = readManifest(configDir, "myapp")
      assertEquals("myapp", manifest.id)
      // The minimal shape docs/your-first-trailmap.md teaches: the framework dependency comes along
      // so the new target has the standard interaction toolsets, not an empty surface.
      assertEquals(listOf("trailblaze"), manifest.dependencies)
      assertEquals("My App", manifest.target?.displayName)
      assertEquals(listOf("com.example.myapp"), manifest.target?.platforms?.get("android")?.appIds)
    }
  }

  @Test
  fun `createIfMissing on an existing trailmap edits it in place without reporting a creation`() = runBlocking {
    withWorkspace { configDir ->
      writeManifest(
        configDir,
        "demo",
        """
        id: demo
        target:
          display_name: Demo App
        """.trimIndent(),
      )
      val response = buildSaveTargetConfigResponse(
        SaveTargetConfigRequest(trailmapId = "demo", displayName = "Demo Renamed", createIfMissing = true),
      )
      assertTrue(response.ok)
      assertFalse(response.created)
      assertEquals("Demo Renamed", readManifest(configDir, "demo").target?.displayName)
    }
  }

  @Test
  fun `createIfMissing rejects an id that is not a single safe path segment`() = runBlocking {
    withWorkspace { configDir ->
      listOf("nested/app", "..", "has.dot", " ").forEach { badId ->
        val response = buildSaveTargetConfigResponse(
          SaveTargetConfigRequest(trailmapId = badId, displayName = "Whatever", createIfMissing = true),
        )
        assertFalse(response.ok, "expected '$badId' to be rejected")
      }
      assertEquals(emptyList(), File(configDir, "trailmaps").listFiles().orEmpty().toList())
    }
  }

  @Test
  fun `createIfMissing appends the new id to a non-empty workspace targets list`() = runBlocking {
    withWorkspace { configDir ->
      writeManifest(configDir, "existing", "id: existing\ntarget:\n  display_name: Existing\n")
      File(configDir, "trailblaze.yaml").writeText("targets:\n  - existing\n")
      val response = buildSaveTargetConfigResponse(
        SaveTargetConfigRequest(trailmapId = "myapp", displayName = "My App", createIfMissing = true),
      )
      assertTrue(response.ok)
      assertNull(response.warning)
      val config = TrailblazeConfigYaml.instance.decodeFromString(
        TrailblazeProjectConfig.serializer(),
        File(configDir, "trailblaze.yaml").readText(),
      )
      assertEquals(listOf("existing", "myapp"), config.targets)
    }
  }

  @Test
  fun `createIfMissing leaves an absent trailblaze yaml absent because auto-discovery covers the new trailmap`() = runBlocking {
    withWorkspace { configDir ->
      val response = buildSaveTargetConfigResponse(
        SaveTargetConfigRequest(trailmapId = "myapp", displayName = "My App", createIfMissing = true),
      )
      assertTrue(response.ok)
      assertNull(response.warning)
      assertFalse(File(configDir, "trailblaze.yaml").exists())
    }
  }

  @Test
  fun `createIfMissing surfaces a warning when the targets list needs updating but cannot be`() = runBlocking {
    withWorkspace { configDir ->
      // Malformed YAML: the registration step can't read (let alone rewrite) the targets: list.
      // The create itself must still succeed — registration is best-effort — with the failure
      // surfaced as a warning rather than an error.
      File(configDir, "trailblaze.yaml").writeText("targets: [")
      val response = buildSaveTargetConfigResponse(
        SaveTargetConfigRequest(trailmapId = "myapp", displayName = "My App", createIfMissing = true),
      )
      assertTrue(response.ok)
      assertTrue(response.created)
      assertTrue(response.warning.orEmpty().contains("trailblaze.yaml"))
      assertEquals("My App", readManifest(configDir, "myapp").target?.displayName)
    }
  }

  @Test
  fun `createIfMissing on an existing unregistered trailmap re-attempts the targets registration`() = runBlocking {
    withWorkspace { configDir ->
      // A previous create wrote the manifest but its registration failed (simulated: the manifest
      // exists, the non-empty targets: list doesn't carry the id). A repeat createIfMissing save
      // must take the edit path (created=false) yet still register the id — the retry contract.
      writeManifest(configDir, "myapp", "id: myapp\ntarget:\n  display_name: My App\n")
      File(configDir, "trailblaze.yaml").writeText("targets:\n  - existing\n")
      val response = buildSaveTargetConfigResponse(
        SaveTargetConfigRequest(trailmapId = "myapp", displayName = "My App", createIfMissing = true),
      )
      assertTrue(response.ok)
      assertFalse(response.created)
      assertNull(response.warning)
      val config = TrailblazeConfigYaml.instance.decodeFromString(
        TrailblazeProjectConfig.serializer(),
        File(configDir, "trailblaze.yaml").readText(),
      )
      assertEquals(listOf("existing", "myapp"), config.targets)
    }
  }

  @Test
  fun `createIfMissing leaves an empty targets list untouched rather than narrowing it to an allow-list`() = runBlocking {
    withWorkspace { configDir ->
      val original = "defaults:\n  llm: openai/gpt-4.1\n"
      File(configDir, "trailblaze.yaml").writeText(original)
      val response = buildSaveTargetConfigResponse(
        SaveTargetConfigRequest(trailmapId = "myapp", displayName = "My App", createIfMissing = true),
      )
      assertTrue(response.ok)
      // Byte-identical: no rewrite happened at all, so hand-authored formatting survives too.
      assertEquals(original, File(configDir, "trailblaze.yaml").readText())
    }
  }

  // computePlatformsWithChangedIconInputs — the icon-extraction trigger's change-detection logic,
  // extracted pure so it's testable with plain maps rather than by asserting on log output (this
  // codebase's testing philosophy explicitly disallows pinning tests to internal log lines).

  @Test
  fun `computePlatformsWithChangedIconInputs ignores an icon-only edit`() {
    val existing = mapOf("android" to PlatformConfig(appIds = listOf("com.example.demo")))
    val merged = mapOf("android" to PlatformConfig(appIds = listOf("com.example.demo"), icon = "assets/icons/new.png"))
    val requested = mapOf("android" to SaveTargetPlatformPatch(appIds = listOf("com.example.demo"), icon = "assets/icons/new.png"))
    assertTrue(computePlatformsWithChangedIconInputs(existing, merged, requested).isEmpty())
  }

  @Test
  fun `computePlatformsWithChangedIconInputs flags a changed appIds list`() {
    val existing = mapOf("android" to PlatformConfig(appIds = listOf("com.example.demo")))
    val merged = mapOf("android" to PlatformConfig(appIds = listOf("com.example.demo", "com.example.demo.internal")))
    val requested = mapOf("android" to SaveTargetPlatformPatch(appIds = listOf("com.example.demo", "com.example.demo.internal")))
    assertEquals(setOf("android"), computePlatformsWithChangedIconInputs(existing, merged, requested))
  }

  @Test
  fun `computePlatformsWithChangedIconInputs flags a changed baseUrl`() {
    val existing = mapOf("web" to PlatformConfig(baseUrl = "https://old.example.com"))
    val merged = mapOf("web" to PlatformConfig(baseUrl = "https://new.example.com"))
    val requested = mapOf("web" to SaveTargetPlatformPatch(baseUrl = "https://new.example.com"))
    assertEquals(setOf("web"), computePlatformsWithChangedIconInputs(existing, merged, requested))
  }

  @Test
  fun `computePlatformsWithChangedIconInputs flags a brand-new platform`() {
    val requested = mapOf("android" to SaveTargetPlatformPatch(appIds = listOf("com.example.demo")))
    val merged = mapOf("android" to PlatformConfig(appIds = listOf("com.example.demo")))
    assertEquals(setOf("android"), computePlatformsWithChangedIconInputs(existingPlatforms = emptyMap(), merged, requested))
  }

  @Test
  fun `computePlatformsWithChangedIconInputs excludes a removed platform`() {
    val existing = mapOf("android" to PlatformConfig(appIds = listOf("com.example.demo")))
    // A removed platform has no entry in mergedPlatforms — the fixture omits it too, matching
    // buildSaveTargetConfigResponse's real map-building behavior for a `remove: true` patch.
    val requested = mapOf("android" to SaveTargetPlatformPatch(remove = true))
    assertTrue(computePlatformsWithChangedIconInputs(existing, mergedPlatforms = emptyMap(), requested).isEmpty())
  }

  @Test
  fun `computePlatformsWithChangedIconInputs ignores a platform the request didn't mention`() {
    val existing = mapOf("ios" to PlatformConfig(appIds = listOf("com.example.demo.ios")))
    val merged = existing
    assertTrue(computePlatformsWithChangedIconInputs(existing, merged, requestedPlatforms = emptyMap()).isEmpty())
  }

  // isValidBundleIdForIconPath — the guard that keeps a crafted app_ids entry from ever composing
  // a path outside assets/icons/ when the iOS extraction trigger builds its output filename.

  @Test
  fun `isValidBundleIdForIconPath accepts a normal reverse-DNS bundle id`() {
    assertTrue(isValidBundleIdForIconPath("com.example.app"))
  }

  @Test
  fun `isValidBundleIdForIconPath rejects a value containing a path separator`() {
    assertFalse(isValidBundleIdForIconPath("../../../etc/cron.d/evil"))
    assertFalse(isValidBundleIdForIconPath("com.example/app"))
  }

  @Test
  fun `isValidBundleIdForIconPath rejects blank`() {
    assertFalse(isValidBundleIdForIconPath(""))
  }

  // computeWorkspaceTargetsAfterCreate — whether (and how) a bootstrap rewrites the workspace
  // `targets:` list, extracted pure for the same reason as the block above.

  @Test
  fun `computeWorkspaceTargetsAfterCreate leaves an empty list alone because it means auto-discover`() {
    assertNull(computeWorkspaceTargetsAfterCreate(emptyList(), "myapp"))
  }

  @Test
  fun `computeWorkspaceTargetsAfterCreate leaves an already-listed id alone`() {
    assertNull(computeWorkspaceTargetsAfterCreate(listOf("myapp", "other"), "myapp"))
  }

  @Test
  fun `computeWorkspaceTargetsAfterCreate appends to a non-empty list`() {
    assertEquals(listOf("existing", "myapp"), computeWorkspaceTargetsAfterCreate(listOf("existing"), "myapp"))
  }
}
