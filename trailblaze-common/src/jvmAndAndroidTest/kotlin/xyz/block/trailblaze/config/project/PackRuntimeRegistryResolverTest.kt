package xyz.block.trailblaze.config.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.PlatformConfig

/**
 * Unit tests for [PackRuntimeRegistryResolver]. Pins the **transitive union** semantics
 * that distinguish this resolver from [PackDependencyResolver]'s closest-wins overlay.
 *
 * Synthetic [ResolvedPack] fixtures stand in for parsed
 * manifests so the merge can be tested without touching the filesystem — same harness
 * pattern as `PackDependencyResolverTest`.
 */
class PackRuntimeRegistryResolverTest {

  @Test
  fun `single library pack contributes its own platforms tool_sets`() {
    val pack = libraryPack(
      "lib",
      platforms = mapOf("web" to PlatformConfig(toolSets = listOf("web_core", "web_verification"))),
    )

    val result = PackRuntimeRegistryResolver.resolveRuntimeToolSets(
      rootPackId = "lib",
      packsById = mapOf("lib" to pack),
    )

    assertEquals(setOf("web_core", "web_verification"), result["web"])
    assertEquals(setOf("web"), result.keys)
  }

  @Test
  fun `target pack contributes via target dot platforms`() {
    val pack = targetPack(
      "app",
      targetPlatforms = mapOf(
        "android" to PlatformConfig(toolSets = listOf("core_interaction")),
        "ios" to PlatformConfig(toolSets = listOf("core_interaction")),
      ),
    )

    val result = PackRuntimeRegistryResolver.resolveRuntimeToolSets(
      rootPackId = "app",
      packsById = mapOf("app" to pack),
    )

    assertEquals(setOf("core_interaction"), result["android"])
    assertEquals(setOf("core_interaction"), result["ios"])
  }

  @Test
  fun `transitive union pulls tool_sets from every pack in closure`() {
    // Graph: app -> midLib -> deepLib
    val deepLib = libraryPack(
      "deepLib",
      platforms = mapOf("web" to PlatformConfig(toolSets = listOf("web_deep"))),
    )
    val midLib = libraryPack(
      "midLib",
      dependencies = listOf("deepLib"),
      platforms = mapOf("web" to PlatformConfig(toolSets = listOf("web_mid"))),
    )
    val app = targetPack(
      "app",
      dependencies = listOf("midLib"),
      targetPlatforms = mapOf("web" to PlatformConfig(toolSets = listOf("web_app"))),
    )

    val result = PackRuntimeRegistryResolver.resolveRuntimeToolSets(
      rootPackId = "app",
      packsById = mapOf("app" to app, "midLib" to midLib, "deepLib" to deepLib),
    )

    // All three packs contribute — transitive union, no closest-wins shadowing.
    assertEquals(setOf("web_app", "web_mid", "web_deep"), result["web"])
  }

  @Test
  fun `union dedupes when multiple packs declare the same tool_set`() {
    val libA = libraryPack(
      "libA",
      platforms = mapOf("web" to PlatformConfig(toolSets = listOf("web_core", "memory"))),
    )
    val libB = libraryPack(
      "libB",
      platforms = mapOf("web" to PlatformConfig(toolSets = listOf("memory", "verification"))),
    )
    val app = targetPack(
      "app",
      dependencies = listOf("libA", "libB"),
      targetPlatforms = mapOf("web" to PlatformConfig()),
    )

    val result = PackRuntimeRegistryResolver.resolveRuntimeToolSets(
      rootPackId = "app",
      packsById = mapOf("app" to app, "libA" to libA, "libB" to libB),
    )

    // `memory` is declared by both libA and libB but lives in the set once.
    assertEquals(setOf("web_core", "memory", "verification"), result["web"])
  }

  @Test
  fun `each platform key is unioned independently`() {
    val webLib = libraryPack(
      "webLib",
      platforms = mapOf("web" to PlatformConfig(toolSets = listOf("web_core"))),
    )
    val androidLib = libraryPack(
      "androidLib",
      platforms = mapOf("android" to PlatformConfig(toolSets = listOf("core_interaction"))),
    )
    val app = targetPack(
      "app",
      dependencies = listOf("webLib", "androidLib"),
      targetPlatforms = mapOf(
        "web" to PlatformConfig(),
        "android" to PlatformConfig(),
      ),
    )

    val result = PackRuntimeRegistryResolver.resolveRuntimeToolSets(
      rootPackId = "app",
      packsById = mapOf("app" to app, "webLib" to webLib, "androidLib" to androidLib),
    )

    assertEquals(setOf("web_core"), result["web"])
    assertEquals(setOf("core_interaction"), result["android"])
  }

  @Test
  fun `diamond dep contributes once via set union`() {
    // app -> a -> shared
    // app -> b -> shared
    val shared = libraryPack(
      "shared",
      platforms = mapOf("web" to PlatformConfig(toolSets = listOf("shared_set"))),
    )
    val a = libraryPack("a", dependencies = listOf("shared"))
    val b = libraryPack("b", dependencies = listOf("shared"))
    val app = targetPack(
      "app",
      dependencies = listOf("a", "b"),
      targetPlatforms = mapOf("web" to PlatformConfig()),
    )

    val result = PackRuntimeRegistryResolver.resolveRuntimeToolSets(
      rootPackId = "app",
      packsById = mapOf("app" to app, "a" to a, "b" to b, "shared" to shared),
    )

    assertEquals(setOf("shared_set"), result["web"])
  }

  @Test
  fun `pack with null platforms contributes nothing but does not break the walk`() {
    val emptyLib = libraryPack("emptyLib", platforms = null)
    val realLib = libraryPack(
      "realLib",
      dependencies = listOf("emptyLib"),
      platforms = mapOf("web" to PlatformConfig(toolSets = listOf("web_core"))),
    )

    val result = PackRuntimeRegistryResolver.resolveRuntimeToolSets(
      rootPackId = "realLib",
      packsById = mapOf("emptyLib" to emptyLib, "realLib" to realLib),
    )

    assertEquals(setOf("web_core"), result["web"])
  }

  @Test
  fun `cycle in dependencies throws with chain in message`() {
    val a = libraryPack("a", dependencies = listOf("b"))
    val b = libraryPack("b", dependencies = listOf("c"))
    val c = libraryPack("c", dependencies = listOf("a"))

    val ex = assertFailsWith<TrailblazeProjectConfigException> {
      PackRuntimeRegistryResolver.resolveRuntimeToolSets(
        rootPackId = "a",
        packsById = mapOf("a" to a, "b" to b, "c" to c),
      )
    }
    val message = ex.message!!
    assertTrue("Cycle" in message, "Expected cycle error, got: $message")
    assertTrue(
      "a" in message && "b" in message && "c" in message,
      "Expected all three pack ids in chain, got: $message",
    )
  }

  @Test
  fun `self-cycle throws when pack lists itself as dependency`() {
    val selfRef = libraryPack("selfRef", dependencies = listOf("selfRef"))

    val ex = assertFailsWith<TrailblazeProjectConfigException> {
      PackRuntimeRegistryResolver.resolveRuntimeToolSets(
        rootPackId = "selfRef",
        packsById = mapOf("selfRef" to selfRef),
      )
    }
    assertTrue("Cycle" in ex.message!!)
  }

  @Test
  fun `missing dependency throws with chain in message`() {
    val app = targetPack("app", dependencies = listOf("nonexistent"))

    val ex = assertFailsWith<TrailblazeProjectConfigException> {
      PackRuntimeRegistryResolver.resolveRuntimeToolSets(
        rootPackId = "app",
        packsById = mapOf("app" to app),
      )
    }
    val message = ex.message!!
    assertTrue(
      "nonexistent" in message && "not found" in message,
      "Expected missing-dep error referencing 'nonexistent', got: $message",
    )
    assertTrue("app" in message, "Expected consumer chain to include 'app', got: $message")
  }

  @Test
  fun `unknown root pack throws not found`() {
    val ex = assertFailsWith<TrailblazeProjectConfigException> {
      PackRuntimeRegistryResolver.resolveRuntimeToolSets(
        rootPackId = "ghost",
        packsById = emptyMap(),
      )
    }
    assertTrue("ghost" in ex.message!! && "not found" in ex.message!!)
  }

  @Test
  fun `result is independent of declaration order of sibling deps`() {
    // Different from PackDependencyResolver — there the *later* sibling wins.
    // Here every sibling's contribution is unioned, so order is irrelevant.
    val first = libraryPack(
      "first",
      platforms = mapOf("web" to PlatformConfig(toolSets = listOf("first_set"))),
    )
    val second = libraryPack(
      "second",
      platforms = mapOf("web" to PlatformConfig(toolSets = listOf("second_set"))),
    )
    val app1 = targetPack(
      "app",
      dependencies = listOf("first", "second"),
      targetPlatforms = mapOf("web" to PlatformConfig()),
    )
    val app2 = targetPack(
      "app",
      dependencies = listOf("second", "first"),
      targetPlatforms = mapOf("web" to PlatformConfig()),
    )

    val resultA = PackRuntimeRegistryResolver.resolveRuntimeToolSets(
      rootPackId = "app",
      packsById = mapOf("app" to app1, "first" to first, "second" to second),
    )
    val resultB = PackRuntimeRegistryResolver.resolveRuntimeToolSets(
      rootPackId = "app",
      packsById = mapOf("app" to app2, "first" to first, "second" to second),
    )

    assertEquals(setOf("first_set", "second_set"), resultA["web"])
    assertEquals(resultA, resultB)
  }

  // ==========================================================================
  // Test helpers — mirror the synthesis pattern in PackDependencyResolverTest so
  // both resolvers' tests read consistently against the same ResolvedPack shape.
  // ==========================================================================

  private fun libraryPack(
    id: String,
    dependencies: List<String> = emptyList(),
    platforms: Map<String, PlatformConfig>? = null,
  ): ResolvedPack = ResolvedPack(
    manifest = TrailblazePackManifest(
      id = id,
      target = null,
      dependencies = dependencies,
      platforms = platforms,
    ),
    source = PackSource.Filesystem(packDir = java.io.File(".")),
    target = null,
    toolsets = emptyList(),
    tools = emptyList(),
    waypoints = emptyList(),
  )

  private fun targetPack(
    id: String,
    dependencies: List<String> = emptyList(),
    targetPlatforms: Map<String, PlatformConfig>? = null,
  ): ResolvedPack = ResolvedPack(
    manifest = TrailblazePackManifest(
      id = id,
      target = PackTargetConfig(
        displayName = id,
        platforms = targetPlatforms,
      ),
      dependencies = dependencies,
    ),
    source = PackSource.Filesystem(packDir = java.io.File(".")),
    target = AppTargetYamlConfig(
      id = id,
      displayName = id,
      platforms = targetPlatforms,
    ),
    toolsets = emptyList(),
    tools = emptyList(),
    waypoints = emptyList(),
  )
}
