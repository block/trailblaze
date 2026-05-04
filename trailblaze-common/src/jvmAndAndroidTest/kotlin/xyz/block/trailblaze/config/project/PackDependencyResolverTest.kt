package xyz.block.trailblaze.config.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.PlatformConfig

/**
 * Unit tests for [PackDependencyResolver]. These exercise the dep-graph + closest-wins
 * defaults inheritance in isolation from the rest of the loader pipeline. Synthetic
 * [TrailblazeProjectConfigLoader.ResolvedPack] fixtures stand in for parsed pack
 * manifests so the merge semantics can be tested without touching the filesystem.
 */
class PackDependencyResolverTest {

  @Test
  fun `all dependencies with null defaults returns own target unchanged`() {
    // Exercises the `contributions.isEmpty()` early return in resolveTarget — every
    // pack in the graph has `defaults: null`, so the dep walk records no contributions
    // and the consumer's target is returned untouched. Without this test the early
    // return is a silent dead-codepath; if a future refactor moved it incorrectly,
    // we'd silently start resolving against an empty defaults map (still correct in
    // outcome here, but the branch wouldn't be exercised at all).
    val libA = libraryPack("libA", dependencies = listOf("libB"), defaults = null)
    val libB = libraryPack("libB", defaults = null)
    val ownTarget = target(
      "consumer",
      platforms = mapOf("android" to PlatformConfig(appIds = listOf("com.example"))),
    )

    val result = PackDependencyResolver.resolveTarget(
      ownTarget = ownTarget,
      ownDependencies = listOf("libA"),
      packsById = mapOf("libA" to libA, "libB" to libB),
      rootPackId = "consumer",
    )

    assertEquals(ownTarget, result)
  }

  @Test
  fun `consumer with no dependencies returns own target unchanged`() {
    val ownTarget = target(
      "consumer",
      platforms = mapOf("android" to PlatformConfig(appIds = listOf("com.example"))),
    )
    val result = PackDependencyResolver.resolveTarget(
      ownTarget = ownTarget,
      ownDependencies = emptyList(),
      packsById = emptyMap(),
      rootPackId = "consumer",
    )
    assertEquals(ownTarget, result)
  }

  @Test
  fun `omitting tool_sets inherits from dependency defaults`() {
    val frameworkPack = libraryPack(
      "trailblaze",
      defaults = mapOf(
        "android" to PlatformConfig(toolSets = listOf("core_interaction", "memory")),
        "ios" to PlatformConfig(toolSets = listOf("core_interaction", "memory")),
      ),
    )
    val ownTarget = target(
      "consumer",
      platforms = mapOf(
        "android" to PlatformConfig(appIds = listOf("com.example")),
        "ios" to PlatformConfig(),
      ),
    )

    val result = PackDependencyResolver.resolveTarget(
      ownTarget = ownTarget,
      ownDependencies = listOf("trailblaze"),
      packsById = mapOf("trailblaze" to frameworkPack),
      rootPackId = "consumer",
    )

    val android = assertNotNull(result.platforms?.get("android"))
    assertEquals(listOf("com.example"), android.appIds)
    assertEquals(listOf("core_interaction", "memory"), android.toolSets)

    val ios = assertNotNull(result.platforms?.get("ios"))
    assertEquals(listOf("core_interaction", "memory"), ios.toolSets)
  }

  @Test
  fun `consumer that sets tool_sets explicitly overrides inherited list with no merge`() {
    val frameworkPack = libraryPack(
      "trailblaze",
      defaults = mapOf(
        "android" to PlatformConfig(toolSets = listOf("core_interaction", "memory")),
      ),
    )
    val ownTarget = target(
      "consumer",
      platforms = mapOf(
        "android" to PlatformConfig(toolSets = listOf("only_this")),
      ),
    )

    val result = PackDependencyResolver.resolveTarget(
      ownTarget = ownTarget,
      ownDependencies = listOf("trailblaze"),
      packsById = mapOf("trailblaze" to frameworkPack),
      rootPackId = "consumer",
    )

    // Closest-wins is per-field with NO list concatenation. The consumer's tool_sets
    // wholesale replaces the inherited list — preserves visibility for authors who
    // want to see exactly which toolsets a platform uses.
    assertEquals(listOf("only_this"), result.platforms?.get("android")?.toolSets)
  }

  @Test
  fun `defaults only fill in platforms the consumer explicitly declares`() {
    val frameworkPack = libraryPack(
      "trailblaze",
      defaults = mapOf(
        "android" to PlatformConfig(toolSets = listOf("core_interaction")),
        "ios" to PlatformConfig(toolSets = listOf("core_interaction")),
        "web" to PlatformConfig(toolSets = listOf("web_core")),
      ),
    )
    val ownTarget = target(
      "consumer",
      platforms = mapOf("android" to PlatformConfig()),
    )

    val result = PackDependencyResolver.resolveTarget(
      ownTarget = ownTarget,
      ownDependencies = listOf("trailblaze"),
      packsById = mapOf("trailblaze" to frameworkPack),
      rootPackId = "consumer",
    )

    // Consumer never declared ios or web — they don't sneak in via defaults inheritance.
    assertEquals(setOf("android"), result.platforms?.keys)
  }

  @Test
  fun `transitive defaults inherit through the dep graph`() {
    // consumer -> midPack -> framework
    val frameworkPack = libraryPack(
      "trailblaze",
      defaults = mapOf("android" to PlatformConfig(toolSets = listOf("core_interaction"))),
    )
    val midPack = libraryPack(
      "mid",
      dependencies = listOf("trailblaze"),
      defaults = null,
    )
    val ownTarget = target(
      "consumer",
      platforms = mapOf("android" to PlatformConfig(appIds = listOf("com.example"))),
    )

    val result = PackDependencyResolver.resolveTarget(
      ownTarget = ownTarget,
      ownDependencies = listOf("mid"),
      packsById = mapOf("trailblaze" to frameworkPack, "mid" to midPack),
      rootPackId = "consumer",
    )

    assertEquals(
      listOf("core_interaction"),
      result.platforms?.get("android")?.toolSets,
    )
  }

  @Test
  fun `closest dep overrides transitive default for the same platform field`() {
    // consumer -> middlePack -> framework
    // both middlePack and framework set android.tool_sets — middlePack is closer, wins.
    val frameworkPack = libraryPack(
      "trailblaze",
      defaults = mapOf("android" to PlatformConfig(toolSets = listOf("framework_set"))),
    )
    val middlePack = libraryPack(
      "middle",
      dependencies = listOf("trailblaze"),
      defaults = mapOf("android" to PlatformConfig(toolSets = listOf("middle_set"))),
    )
    val ownTarget = target(
      "consumer",
      platforms = mapOf("android" to PlatformConfig()),
    )

    val result = PackDependencyResolver.resolveTarget(
      ownTarget = ownTarget,
      ownDependencies = listOf("middle"),
      packsById = mapOf("trailblaze" to frameworkPack, "middle" to middlePack),
      rootPackId = "consumer",
    )

    assertEquals(listOf("middle_set"), result.platforms?.get("android")?.toolSets)
  }

  @Test
  fun `closer-depth value wins even when reached via a later sibling branch`() {
    // Regression test for the "closest-wins must hold across branches" semantics.
    //
    // Graph:  consumer → a → x   (x's defaults at depth 2 via the 'a' branch)
    //         consumer → b → y → z   (z's defaults at depth 3 via the 'b' branch)
    //
    // Both 'x' and 'z' set android.tool_sets. With a naive DFS-overlay scheme, walking
    // 'a' first then 'b' would let z (depth 3, declared later in DFS order) overwrite
    // x (depth 2). That violates closest-wins. The correct behavior: x wins because its
    // depth is shallower, regardless of declaration order between subtrees.
    val x = libraryPack(
      "x",
      defaults = mapOf("android" to PlatformConfig(toolSets = listOf("x_at_depth_2"))),
    )
    val z = libraryPack(
      "z",
      defaults = mapOf("android" to PlatformConfig(toolSets = listOf("z_at_depth_3"))),
    )
    val a = libraryPack("a", dependencies = listOf("x"))
    val y = libraryPack("y", dependencies = listOf("z"))
    val b = libraryPack("b", dependencies = listOf("y"))
    val ownTarget = target("consumer", platforms = mapOf("android" to PlatformConfig()))

    val result = PackDependencyResolver.resolveTarget(
      ownTarget = ownTarget,
      ownDependencies = listOf("a", "b"),
      packsById = mapOf("a" to a, "b" to b, "x" to x, "y" to y, "z" to z),
      rootPackId = "consumer",
    )

    assertEquals(
      listOf("x_at_depth_2"),
      result.platforms?.get("android")?.toolSets,
      "x at depth 2 must win over z at depth 3 even though z's branch was declared later",
    )
  }

  @Test
  fun `diamond dependencies converge consistently and contribute the deepest pack once`() {
    // Graph:  consumer → a, b
    //         a → d
    //         b → d
    // 'd' is reached via two paths but at the same depth (2); its defaults must apply
    // regardless of which branch encountered it first. No false cycle should fire even
    // though 'd' is "visited twice" — each branch unwinds 'd' from the visiting set on
    // its own return.
    val d = libraryPack(
      "d",
      defaults = mapOf("android" to PlatformConfig(toolSets = listOf("d_default"))),
    )
    val a = libraryPack("a", dependencies = listOf("d"))
    val b = libraryPack("b", dependencies = listOf("d"))
    val ownTarget = target("consumer", platforms = mapOf("android" to PlatformConfig()))

    val result = PackDependencyResolver.resolveTarget(
      ownTarget = ownTarget,
      ownDependencies = listOf("a", "b"),
      packsById = mapOf("a" to a, "b" to b, "d" to d),
      rootPackId = "consumer",
    )

    assertEquals(listOf("d_default"), result.platforms?.get("android")?.toolSets)
  }

  @Test
  fun `three-link cycle A to B to C to A throws with chain in message`() {
    val a = libraryPack("a", dependencies = listOf("b"))
    val b = libraryPack("b", dependencies = listOf("c"))
    val c = libraryPack("c", dependencies = listOf("a"))
    val ownTarget = target("a", platforms = mapOf("android" to PlatformConfig()))

    val ex = assertFailsWith<TrailblazeProjectConfigException> {
      PackDependencyResolver.resolveTarget(
        ownTarget = ownTarget,
        ownDependencies = listOf("b"),
        packsById = mapOf("a" to a, "b" to b, "c" to c),
        rootPackId = "a",
      )
    }
    assertTrue(ex.message!!.contains("Cycle"), "Expected cycle error, got: ${ex.message}")
    // Chain reporting helps operators trace which packs are involved.
    assertTrue(
      ex.message!!.contains("a") && ex.message!!.contains("b") && ex.message!!.contains("c"),
      "Expected cycle message to include all three pack ids, got: ${ex.message}",
    )
  }

  @Test
  fun `missing dependency error includes the consumer chain that referenced it`() {
    val mid = libraryPack("mid", dependencies = listOf("nonexistent"))
    val ownTarget = target("consumer", platforms = mapOf("android" to PlatformConfig()))

    val ex = assertFailsWith<TrailblazeProjectConfigException> {
      PackDependencyResolver.resolveTarget(
        ownTarget = ownTarget,
        ownDependencies = listOf("mid"),
        packsById = mapOf("mid" to mid),
        rootPackId = "consumer",
      )
    }
    // The consumer pack ('consumer') and the intermediate pack ('mid') must be in the
    // chain so an operator reading the log can find which pack referenced the missing
    // dep — without this context, "Pack 'nonexistent' not found" is unactionable in a
    // workspace with many packs.
    assertTrue(
      ex.message!!.contains("nonexistent") &&
        ex.message!!.contains("mid") &&
        ex.message!!.contains("consumer"),
      "Expected missing-dep error to include the chain consumer → mid → nonexistent, got: ${ex.message}",
    )
  }

  @Test
  fun `later-declared sibling dep overrides earlier sibling at same depth`() {
    val firstPack = libraryPack(
      "first",
      defaults = mapOf("android" to PlatformConfig(toolSets = listOf("first_set"))),
    )
    val secondPack = libraryPack(
      "second",
      defaults = mapOf("android" to PlatformConfig(toolSets = listOf("second_set"))),
    )
    val ownTarget = target("consumer", platforms = mapOf("android" to PlatformConfig()))

    val result = PackDependencyResolver.resolveTarget(
      ownTarget = ownTarget,
      ownDependencies = listOf("first", "second"),
      packsById = mapOf("first" to firstPack, "second" to secondPack),
      rootPackId = "consumer",
    )

    // "second" was declared later → wins on the shared field. Mirrors how language
    // module systems resolve sibling-overlay conflicts.
    assertEquals(listOf("second_set"), result.platforms?.get("android")?.toolSets)
  }

  @Test
  fun `cycle in dependency graph throws TrailblazeProjectConfigException`() {
    val a = libraryPack("a", dependencies = listOf("b"))
    val b = libraryPack("b", dependencies = listOf("a"))
    val ownTarget = target("a", platforms = mapOf("android" to PlatformConfig()))

    val ex = assertFailsWith<TrailblazeProjectConfigException> {
      PackDependencyResolver.resolveTarget(
        ownTarget = ownTarget,
        ownDependencies = listOf("b"),
        packsById = mapOf("a" to a, "b" to b),
        rootPackId = "a",
      )
    }
    assertTrue(
      ex.message!!.contains("Cycle"),
      "Expected cycle error, got: ${ex.message}",
    )
  }

  @Test
  fun `self-cycle throws when consumer lists itself as dependency`() {
    val a = libraryPack("a", dependencies = listOf("a"))
    val ownTarget = target("a", platforms = mapOf("android" to PlatformConfig()))

    val ex = assertFailsWith<TrailblazeProjectConfigException> {
      PackDependencyResolver.resolveTarget(
        ownTarget = ownTarget,
        ownDependencies = listOf("a"),
        packsById = mapOf("a" to a),
        rootPackId = "a",
      )
    }
    assertTrue(ex.message!!.contains("Cycle"))
  }

  @Test
  fun `missing dependency throws a clear error`() {
    val ownTarget = target("consumer", platforms = mapOf("android" to PlatformConfig()))

    val ex = assertFailsWith<TrailblazeProjectConfigException> {
      PackDependencyResolver.resolveTarget(
        ownTarget = ownTarget,
        ownDependencies = listOf("nonexistent"),
        packsById = emptyMap(),
        rootPackId = "consumer",
      )
    }
    assertTrue(
      ex.message!!.contains("nonexistent") && ex.message!!.contains("not found"),
      "Expected missing-dep error referencing 'nonexistent', got: ${ex.message}",
    )
  }

  @Test
  fun `field-level closest-wins preserves consumer values across all PlatformConfig fields`() {
    // Framework sets EVERY field; consumer overrides ONLY a subset. Verify that:
    // - Consumer-set fields win
    // - Consumer-null fields inherit from framework
    val frameworkPack = libraryPack(
      "trailblaze",
      defaults = mapOf(
        "web" to PlatformConfig(
          appIds = listOf("framework.app"),
          toolSets = listOf("framework_set"),
          tools = listOf("framework_tool"),
          excludedTools = listOf("framework_excluded"),
          drivers = listOf("playwright-native", "playwright-electron"),
          baseUrl = "https://framework.example",
          minBuildVersion = "1",
        ),
      ),
    )
    val ownTarget = target(
      "consumer",
      platforms = mapOf(
        "web" to PlatformConfig(
          drivers = listOf("playwright-native"), // override
          // every other field omitted → inherits from framework
        ),
      ),
    )

    val result = PackDependencyResolver.resolveTarget(
      ownTarget = ownTarget,
      ownDependencies = listOf("trailblaze"),
      packsById = mapOf("trailblaze" to frameworkPack),
      rootPackId = "consumer",
    )

    val web = assertNotNull(result.platforms?.get("web"))
    assertEquals(listOf("playwright-native"), web.drivers, "consumer override wins")
    assertEquals(listOf("framework.app"), web.appIds, "inherited")
    assertEquals(listOf("framework_set"), web.toolSets, "inherited")
    assertEquals(listOf("framework_tool"), web.tools, "inherited")
    assertEquals(listOf("framework_excluded"), web.excludedTools, "inherited")
    assertEquals("https://framework.example", web.baseUrl, "inherited")
    assertEquals("1", web.minBuildVersion, "inherited")
  }

  // ============================================================================
  // Parity contract — mirrored verbatim by `PackTargetGeneratorTest` in build-logic.
  // ============================================================================

  /**
   * Single-test snapshot of the closest-wins inheritance contract this resolver
   * shares with build-logic's `PackTargetGenerator.mergeInheritedDefaults`. Any
   * change to the expected outputs MUST be mirrored in
   * `build-logic/src/test/kotlin/PackTargetGeneratorTest.kt`'s sibling test of the
   * same name. The two implementations are independently maintained but
   * behaviorally identical; both tests pin the contract from their respective
   * side. If you find yourself updating one to make it pass, update the other
   * with the matching change before merging.
   */
  @Test
  fun `parity contract — closest-wins inheritance, no list concat, multi-field, multi-platform`() {
    val framework = libraryPack(
      "framework",
      defaults = mapOf(
        "android" to PlatformConfig(
          toolSets = listOf("framework_android_set"),
          drivers = listOf("android-ondevice-instrumentation"),
          baseUrl = "https://framework.example/android",
          minBuildVersion = "10",
        ),
        "web" to PlatformConfig(
          toolSets = listOf("framework_web_set"),
          drivers = listOf("playwright-native", "playwright-electron"),
        ),
      ),
    )
    val ownTarget = target(
      "consumer",
      platforms = mapOf(
        // android: every field except baseUrl is set; baseUrl should inherit, others win.
        "android" to PlatformConfig(
          appIds = listOf("com.example.consumer"),
          toolSets = listOf("consumer_only"),
          drivers = listOf("android-ondevice-accessibility"),
          minBuildVersion = "20",
        ),
        // web: empty map — every field inherits.
        "web" to PlatformConfig(),
      ),
    )

    val result = PackDependencyResolver.resolveTarget(
      ownTarget = ownTarget,
      ownDependencies = listOf("framework"),
      packsById = mapOf("framework" to framework),
      rootPackId = "consumer",
    )

    val android = assertNotNull(result.platforms?.get("android"))
    assertEquals(listOf("com.example.consumer"), android.appIds)
    assertEquals(listOf("consumer_only"), android.toolSets, "consumer override wins")
    assertEquals(listOf("android-ondevice-accessibility"), android.drivers, "consumer override wins")
    assertEquals("https://framework.example/android", android.baseUrl, "inherited")
    assertEquals("20", android.minBuildVersion, "consumer override wins")

    val web = assertNotNull(result.platforms?.get("web"))
    assertEquals(listOf("framework_web_set"), web.toolSets, "inherited")
    assertEquals(listOf("playwright-native", "playwright-electron"), web.drivers, "inherited")
  }

  // ============================================================================
  // Test helpers — synthesize minimal ResolvedPack fixtures without touching the
  // filesystem. Library packs (no `target:`) are used as dep nodes; "app" packs
  // (with `target:`) are the consumers under test.
  // ============================================================================

  private fun libraryPack(
    id: String,
    dependencies: List<String> = emptyList(),
    defaults: Map<String, PlatformConfig>? = null,
  ): TrailblazeProjectConfigLoader.ResolvedPack = TrailblazeProjectConfigLoader.ResolvedPack(
    manifest = TrailblazePackManifest(
      id = id,
      target = null,
      dependencies = dependencies,
      defaults = defaults,
    ),
    source = PackSource.Filesystem(packDir = java.io.File(".")),
    target = null,
    toolsets = emptyList(),
    tools = emptyList(),
    waypoints = emptyList(),
    workspaceRef = null,
  )

  private fun target(
    id: String,
    platforms: Map<String, PlatformConfig>? = null,
  ) = AppTargetYamlConfig(
    id = id,
    displayName = id,
    platforms = platforms,
  )
}
