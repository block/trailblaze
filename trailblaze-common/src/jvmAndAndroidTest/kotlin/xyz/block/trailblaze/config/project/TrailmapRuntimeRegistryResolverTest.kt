package xyz.block.trailblaze.config.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.PlatformConfig

/**
 * Unit tests for [TrailmapRuntimeRegistryResolver]. Pins the **transitive union** semantics
 * that distinguish this resolver from [TrailmapDependencyResolver]'s closest-wins overlay.
 *
 * Synthetic [ResolvedTrailmap] fixtures stand in for parsed
 * manifests so the merge can be tested without touching the filesystem — same harness
 * pattern as `TrailmapDependencyResolverTest`.
 */
class TrailmapRuntimeRegistryResolverTest {

  @Test
  fun `single library trailmap contributes its own platforms tool_sets`() {
    val trailmap = libraryTrailmap(
      "lib",
      platforms = mapOf("web" to PlatformConfig(toolSets = listOf("web_core", "web_verification"))),
    )

    val result = TrailmapRuntimeRegistryResolver.resolveRuntimeToolSets(
      rootTrailmapId = "lib",
      trailmapsById = mapOf("lib" to trailmap),
    )

    assertEquals(setOf("web_core", "web_verification"), result["web"])
    assertEquals(setOf("web"), result.keys)
  }

  @Test
  fun `target trailmap contributes via target dot platforms`() {
    val trailmap = targetTrailmap(
      "app",
      targetPlatforms = mapOf(
        "android" to PlatformConfig(toolSets = listOf("core_interaction")),
        "ios" to PlatformConfig(toolSets = listOf("core_interaction")),
      ),
    )

    val result = TrailmapRuntimeRegistryResolver.resolveRuntimeToolSets(
      rootTrailmapId = "app",
      trailmapsById = mapOf("app" to trailmap),
    )

    assertEquals(setOf("core_interaction"), result["android"])
    assertEquals(setOf("core_interaction"), result["ios"])
  }

  @Test
  fun `transitive union pulls tool_sets from every trailmap in closure`() {
    // Graph: app -> midLib -> deepLib
    val deepLib = libraryTrailmap(
      "deepLib",
      platforms = mapOf("web" to PlatformConfig(toolSets = listOf("web_deep"))),
    )
    val midLib = libraryTrailmap(
      "midLib",
      dependencies = listOf("deepLib"),
      platforms = mapOf("web" to PlatformConfig(toolSets = listOf("web_mid"))),
    )
    val app = targetTrailmap(
      "app",
      dependencies = listOf("midLib"),
      targetPlatforms = mapOf("web" to PlatformConfig(toolSets = listOf("web_app"))),
    )

    val result = TrailmapRuntimeRegistryResolver.resolveRuntimeToolSets(
      rootTrailmapId = "app",
      trailmapsById = mapOf("app" to app, "midLib" to midLib, "deepLib" to deepLib),
    )

    // All three trailmaps contribute — transitive union, no closest-wins shadowing.
    assertEquals(setOf("web_app", "web_mid", "web_deep"), result["web"])
  }

  @Test
  fun `union dedupes when multiple trailmaps declare the same tool_set`() {
    val libA = libraryTrailmap(
      "libA",
      platforms = mapOf("web" to PlatformConfig(toolSets = listOf("web_core", "memory"))),
    )
    val libB = libraryTrailmap(
      "libB",
      platforms = mapOf("web" to PlatformConfig(toolSets = listOf("memory", "verification"))),
    )
    val app = targetTrailmap(
      "app",
      dependencies = listOf("libA", "libB"),
      targetPlatforms = mapOf("web" to PlatformConfig()),
    )

    val result = TrailmapRuntimeRegistryResolver.resolveRuntimeToolSets(
      rootTrailmapId = "app",
      trailmapsById = mapOf("app" to app, "libA" to libA, "libB" to libB),
    )

    // `memory` is declared by both libA and libB but lives in the set once.
    assertEquals(setOf("web_core", "memory", "verification"), result["web"])
  }

  @Test
  fun `each platform key is unioned independently`() {
    val webLib = libraryTrailmap(
      "webLib",
      platforms = mapOf("web" to PlatformConfig(toolSets = listOf("web_core"))),
    )
    val androidLib = libraryTrailmap(
      "androidLib",
      platforms = mapOf("android" to PlatformConfig(toolSets = listOf("core_interaction"))),
    )
    val app = targetTrailmap(
      "app",
      dependencies = listOf("webLib", "androidLib"),
      targetPlatforms = mapOf(
        "web" to PlatformConfig(),
        "android" to PlatformConfig(),
      ),
    )

    val result = TrailmapRuntimeRegistryResolver.resolveRuntimeToolSets(
      rootTrailmapId = "app",
      trailmapsById = mapOf("app" to app, "webLib" to webLib, "androidLib" to androidLib),
    )

    assertEquals(setOf("web_core"), result["web"])
    assertEquals(setOf("core_interaction"), result["android"])
  }

  @Test
  fun `diamond dep contributes once via set union`() {
    // app -> a -> shared
    // app -> b -> shared
    val shared = libraryTrailmap(
      "shared",
      platforms = mapOf("web" to PlatformConfig(toolSets = listOf("shared_set"))),
    )
    val a = libraryTrailmap("a", dependencies = listOf("shared"))
    val b = libraryTrailmap("b", dependencies = listOf("shared"))
    val app = targetTrailmap(
      "app",
      dependencies = listOf("a", "b"),
      targetPlatforms = mapOf("web" to PlatformConfig()),
    )

    val result = TrailmapRuntimeRegistryResolver.resolveRuntimeToolSets(
      rootTrailmapId = "app",
      trailmapsById = mapOf("app" to app, "a" to a, "b" to b, "shared" to shared),
    )

    assertEquals(setOf("shared_set"), result["web"])
  }

  @Test
  fun `trailmap with null platforms contributes nothing but does not break the walk`() {
    val emptyLib = libraryTrailmap("emptyLib", platforms = null)
    val realLib = libraryTrailmap(
      "realLib",
      dependencies = listOf("emptyLib"),
      platforms = mapOf("web" to PlatformConfig(toolSets = listOf("web_core"))),
    )

    val result = TrailmapRuntimeRegistryResolver.resolveRuntimeToolSets(
      rootTrailmapId = "realLib",
      trailmapsById = mapOf("emptyLib" to emptyLib, "realLib" to realLib),
    )

    assertEquals(setOf("web_core"), result["web"])
  }

  @Test
  fun `cycle in dependencies throws with chain in message`() {
    val a = libraryTrailmap("a", dependencies = listOf("b"))
    val b = libraryTrailmap("b", dependencies = listOf("c"))
    val c = libraryTrailmap("c", dependencies = listOf("a"))

    val ex = assertFailsWith<TrailblazeProjectConfigException> {
      TrailmapRuntimeRegistryResolver.resolveRuntimeToolSets(
        rootTrailmapId = "a",
        trailmapsById = mapOf("a" to a, "b" to b, "c" to c),
      )
    }
    val message = ex.message!!
    assertTrue("Cycle" in message, "Expected cycle error, got: $message")
    assertTrue(
      "a" in message && "b" in message && "c" in message,
      "Expected all three trailmap ids in chain, got: $message",
    )
  }

  @Test
  fun `self-cycle throws when trailmap lists itself as dependency`() {
    val selfRef = libraryTrailmap("selfRef", dependencies = listOf("selfRef"))

    val ex = assertFailsWith<TrailblazeProjectConfigException> {
      TrailmapRuntimeRegistryResolver.resolveRuntimeToolSets(
        rootTrailmapId = "selfRef",
        trailmapsById = mapOf("selfRef" to selfRef),
      )
    }
    assertTrue("Cycle" in ex.message!!)
  }

  @Test
  fun `missing dependency throws with chain in message`() {
    val app = targetTrailmap("app", dependencies = listOf("nonexistent"))

    val ex = assertFailsWith<TrailblazeProjectConfigException> {
      TrailmapRuntimeRegistryResolver.resolveRuntimeToolSets(
        rootTrailmapId = "app",
        trailmapsById = mapOf("app" to app),
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
  fun `unknown root trailmap throws not found`() {
    val ex = assertFailsWith<TrailblazeProjectConfigException> {
      TrailmapRuntimeRegistryResolver.resolveRuntimeToolSets(
        rootTrailmapId = "ghost",
        trailmapsById = emptyMap(),
      )
    }
    assertTrue("ghost" in ex.message!! && "not found" in ex.message!!)
  }

  @Test
  fun `result is independent of declaration order of sibling deps`() {
    // Different from TrailmapDependencyResolver — there the *later* sibling wins.
    // Here every sibling's contribution is unioned, so order is irrelevant.
    val first = libraryTrailmap(
      "first",
      platforms = mapOf("web" to PlatformConfig(toolSets = listOf("first_set"))),
    )
    val second = libraryTrailmap(
      "second",
      platforms = mapOf("web" to PlatformConfig(toolSets = listOf("second_set"))),
    )
    val app1 = targetTrailmap(
      "app",
      dependencies = listOf("first", "second"),
      targetPlatforms = mapOf("web" to PlatformConfig()),
    )
    val app2 = targetTrailmap(
      "app",
      dependencies = listOf("second", "first"),
      targetPlatforms = mapOf("web" to PlatformConfig()),
    )

    val resultA = TrailmapRuntimeRegistryResolver.resolveRuntimeToolSets(
      rootTrailmapId = "app",
      trailmapsById = mapOf("app" to app1, "first" to first, "second" to second),
    )
    val resultB = TrailmapRuntimeRegistryResolver.resolveRuntimeToolSets(
      rootTrailmapId = "app",
      trailmapsById = mapOf("app" to app2, "first" to first, "second" to second),
    )

    assertEquals(setOf("first_set", "second_set"), resultA["web"])
    assertEquals(resultA, resultB)
  }

  @Test
  fun `consumer depending on mobile library trailmap inherits mobile_primitives in runtime registry`() {
    // Pins the *runtime-registry contract* for the bundled `mobile` library trailmap shape
    // (see trails/config/trailmaps/mobile/trailmap.yaml). The schema-pinning test in
    // TrailblazeTrailmapManifestLoaderTest verifies the YAML decodes into the expected
    // PlatformConfig shape; this test verifies the *resolver* actually surfaces the toolset
    // to a consumer that depends on `mobile`. Without this end-to-end pinning, a future
    // resolver refactor (e.g. dropping transitive `platforms:` traversal in favor of
    // closest-wins-only) would silently break the ownership contract the YAML promises.
    val mobile = libraryTrailmap(
      "mobile",
      platforms = mapOf(
        "android" to PlatformConfig(toolSets = listOf("mobile_primitives")),
        "ios" to PlatformConfig(toolSets = listOf("mobile_primitives")),
      ),
    )
    // Synthetic consumer that adds mobile as a dep. The consumer's own platform blocks are
    // empty (a real consumer would declare its own tool_sets too, but emptiness here isolates
    // the test on the transitive contribution from `mobile`).
    val consumer = targetTrailmap(
      "demoApp",
      dependencies = listOf("mobile"),
      targetPlatforms = mapOf(
        "android" to PlatformConfig(),
        "ios" to PlatformConfig(),
      ),
    )

    val result = TrailmapRuntimeRegistryResolver.resolveRuntimeToolSets(
      rootTrailmapId = "demoApp",
      trailmapsById = mapOf("demoApp" to consumer, "mobile" to mobile),
    )

    assertEquals(setOf("mobile_primitives"), result["android"])
    assertEquals(setOf("mobile_primitives"), result["ios"])
    // Web/compose deliberately omitted from `mobile`'s `platforms:` — verify the resolver
    // doesn't synthesize phantom keys.
    assertTrue("web" !in result)
    assertTrue("compose" !in result)
  }

  @Test
  fun `consumer depending on android library trailmap inherits android_primitives in runtime registry`() {
    // Pins the *runtime-registry contract* for the bundled `android` library trailmap shape
    // (see trails/config/trailmaps/android/trailmap.yaml, PR companion to the mobile case
    // above). The schema-pinning test in TrailblazeTrailmapManifestLoaderTest verifies the YAML
    // decodes into the expected PlatformConfig shape; this test verifies the *resolver*
    // actually surfaces the toolset to a consumer that depends on `android`. Without this
    // end-to-end pinning, a future resolver refactor (e.g. dropping transitive `platforms:`
    // traversal in favor of closest-wins-only) would silently break the ownership contract
    // the YAML promises.
    //
    // Android-only by construction: unlike `mobile`, the `android` trailmap declares no
    // iOS / web / compose platforms entries — these tools have no implementations there.
    // The bottom of the test pins that the resolver doesn't synthesize phantom keys for
    // platforms the trailmap didn't declare.
    val android = libraryTrailmap(
      "android",
      platforms = mapOf(
        "android" to PlatformConfig(toolSets = listOf("android_primitives")),
      ),
    )
    // Synthetic consumer that adds android as a dep. The consumer's own platform blocks are
    // empty (a real consumer would declare its own tool_sets too, but emptiness here isolates
    // the test on the transitive contribution from `android`).
    val consumer = targetTrailmap(
      "demoAndroidApp",
      dependencies = listOf("android"),
      targetPlatforms = mapOf(
        "android" to PlatformConfig(),
        "ios" to PlatformConfig(),
        "web" to PlatformConfig(),
        "compose" to PlatformConfig(),
      ),
    )

    val result = TrailmapRuntimeRegistryResolver.resolveRuntimeToolSets(
      rootTrailmapId = "demoAndroidApp",
      trailmapsById = mapOf("demoAndroidApp" to consumer, "android" to android),
    )

    assertEquals(setOf("android_primitives"), result["android"])
    // iOS / web / compose deliberately omitted from `android`'s `platforms:` — verify the
    // resolver contributes nothing under those keys from the library trailmap, even though
    // the consumer itself declared empty platform blocks for them. Use the strict
    // key-absent form (matching the mobile counterpart test above) so a future resolver bug
    // that materializes empty sets is caught, not silently accepted.
    assertTrue("ios" !in result)
    assertTrue("web" !in result)
    assertTrue("compose" !in result)
  }

  // ==========================================================================
  // Test helpers — mirror the synthesis pattern in TrailmapDependencyResolverTest so
  // both resolvers' tests read consistently against the same ResolvedTrailmap shape.
  // ==========================================================================

  private fun libraryTrailmap(
    id: String,
    dependencies: List<String> = emptyList(),
    platforms: Map<String, PlatformConfig>? = null,
  ): ResolvedTrailmap = ResolvedTrailmap(
    manifest = TrailblazeTrailmapManifest(
      id = id,
      target = null,
      dependencies = dependencies,
      platforms = platforms,
    ),
    source = TrailmapSource.Filesystem(trailmapDir = java.io.File(".")),
    target = null,
    toolsets = emptyList(),
    tools = emptyList(),
    waypoints = emptyList(),
  )

  private fun targetTrailmap(
    id: String,
    dependencies: List<String> = emptyList(),
    targetPlatforms: Map<String, PlatformConfig>? = null,
  ): ResolvedTrailmap = ResolvedTrailmap(
    manifest = TrailblazeTrailmapManifest(
      id = id,
      target = TrailmapTargetConfig(
        displayName = id,
        platforms = targetPlatforms,
      ),
      dependencies = dependencies,
    ),
    source = TrailmapSource.Filesystem(trailmapDir = java.io.File(".")),
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
