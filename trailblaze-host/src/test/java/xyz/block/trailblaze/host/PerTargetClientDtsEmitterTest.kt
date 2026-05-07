package xyz.block.trailblaze.host

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.PlatformConfig

/**
 * Tests for [PerTargetClientDtsEmitter] — the orchestration layer that fans
 * `WorkspaceClientDtsGenerator` out across resolved targets at daemon-init / `trailblaze
 * compile` time.
 *
 * Coverage:
 *  - Multiple resolved targets → one `client.<target-id>.d.ts` per target, each scoped to
 *    that target's scripted tools (slicing isolation).
 *  - Empty `resolvedTargets` → no-op (returns empty list, no files written).
 *  - Each target's binding includes the workspace-wide Kotlin tool descriptor superset
 *    (so cross-platform conditional tools see every primitive, regardless of which target
 *    the binding is for).
 */
class PerTargetClientDtsEmitterTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanupTempDirs() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  @Test
  fun `emits one binding per resolved target with sliced scripted tools`() {
    // Two targets, each with its own scripted tool. Bindings should land in distinct
    // `client.<target-id>.d.ts` files, and each file should ONLY contain its target's
    // scripted tool — cross-target tool autocomplete pollution is the slicing-failure mode
    // we're guarding against.
    val workspace = newWorkspaceRoot()

    val alphaTarget = AppTargetYamlConfig(
      id = "alpha",
      displayName = "Alpha",
      platforms = mapOf("android" to PlatformConfig(appIds = listOf("com.betaup.alpha"))),
      tools = listOf(
        InlineScriptToolConfig(
          script = "./trails/config/packs/alpha/tools/alpha_login.ts",
          name = "alpha_login",
          description = "Sign into Alpha.",
          inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject { /* no params */ })
          },
        ),
      ),
    )
    val betaTarget = AppTargetYamlConfig(
      id = "beta",
      displayName = "Beta",
      platforms = mapOf("android" to PlatformConfig(appIds = listOf("com.betaup"))),
      tools = listOf(
        InlineScriptToolConfig(
          script = "./trails/config/packs/beta/tools/beta_login.ts",
          name = "beta_login",
          description = "Sign into Beta.",
          inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
              "properties",
              buildJsonObject {
                put(
                  "merchantId",
                  buildJsonObject { put("type", JsonPrimitive("string")) },
                )
              },
            )
            put("required", buildJsonArray { add(JsonPrimitive("merchantId")) })
          },
        ),
      ),
    )

    val emitted = PerTargetClientDtsEmitter.emit(
      workspaceRoot = workspace.toPath(),
      resolvedTargets = listOf(alphaTarget, betaTarget),
    )

    // Two files, named per-target.
    assertEquals(2, emitted.size, "expected one binding per target, got: $emitted")
    val byName = emitted.associateBy { it.fileName.toString() }
    assertTrue("missing client.alpha.d.ts in $emitted") { "client.alpha.d.ts" in byName }
    assertTrue("missing client.beta.d.ts in $emitted") { "client.beta.d.ts" in byName }

    val alphaRendered = Files.readString(byName.getValue("client.alpha.d.ts"))
    val betaRendered = Files.readString(byName.getValue("client.beta.d.ts"))

    // Per-target slicing: alpha's binding contains only alpha's scripted tool, NOT beta's.
    assertTrue("alpha binding should contain alpha_login: $alphaRendered") {
      alphaRendered.contains("alpha_login:")
    }
    assertFalse("alpha binding should NOT contain beta_login: $alphaRendered") {
      alphaRendered.contains("beta_login:")
    }
    assertTrue("beta binding should contain beta_login: $betaRendered") {
      betaRendered.contains("beta_login:")
    }
    assertFalse("beta binding should NOT contain alpha_login: $betaRendered") {
      betaRendered.contains("alpha_login:")
    }
  }

  @Test
  fun `emit with empty resolvedTargets is a no-op`() {
    // Library-pack-only workspace (no target packs) — no per-target binding to emit.
    // Returns empty list; no files written.
    val workspace = newWorkspaceRoot()

    val emitted = PerTargetClientDtsEmitter.emit(
      workspaceRoot = workspace.toPath(),
      resolvedTargets = emptyList(),
    )

    assertTrue("expected no emitted files for empty resolvedTargets, got: $emitted") {
      emitted.isEmpty()
    }
    // The `.trailblaze/` dir is not even created — no-op all the way down.
    val generatedDir = File(workspace, "config/tools/.trailblaze")
    assertFalse(
      "expected no .trailblaze/ dir for empty resolvedTargets, but found: $generatedDir",
    ) {
      generatedDir.exists()
    }
  }

  @Test
  fun `every per-target binding includes the workspace-wide Kotlin tool superset`() {
    // The per-target slicing ONLY affects the scripted-tool half. The Kotlin tool
    // descriptor set comes from `TrailblazeSerializationInitializer.buildAllTools()` —
    // every framework primitive on the JVM classpath — and that set is identical across
    // every target's binding. This pins that intent: an author editing `alpha` tools sees
    // the same Kotlin-tool autocomplete as one editing `beta` tools.
    val workspace = newWorkspaceRoot()

    val alphaTarget = AppTargetYamlConfig(
      id = "alpha",
      displayName = "Alpha",
      tools = emptyList(),
    )
    val betaTarget = AppTargetYamlConfig(
      id = "beta",
      displayName = "Beta",
      tools = emptyList(),
    )

    val emitted = PerTargetClientDtsEmitter.emit(
      workspaceRoot = workspace.toPath(),
      resolvedTargets = listOf(alphaTarget, betaTarget),
    )
    assertEquals(2, emitted.size)
    val alphaRendered = Files.readString(emitted.first { it.fileName.toString() == "client.alpha.d.ts" })
    val betaRendered = Files.readString(emitted.first { it.fileName.toString() == "client.beta.d.ts" })

    // Both files should be valid TS modules with `interface TrailblazeToolMap`.
    assertTrue("alpha: $alphaRendered") {
      alphaRendered.contains("declare module \"@trailblaze/scripting\"")
    }
    assertTrue("beta: $betaRendered") {
      betaRendered.contains("declare module \"@trailblaze/scripting\"")
    }
    // The classpath-discovered Kotlin tool surface is identical across both files —
    // strip the per-target-only blanks so we're comparing the structural Kotlin shell.
    // Since neither target declares scripted tools, the entire interface body is the
    // Kotlin superset, so the two should be byte-for-byte identical apart from the
    // generated banner (which is also identical — content-only check).
    assertEquals(
      alphaRendered,
      betaRendered,
      "Kotlin tool superset should be identical across per-target bindings when neither " +
        "target contributes scripted tools",
    )
  }

  @Test
  fun `emit rejects target id that would weaponize path traversal in filename template`() {
    // A pack id containing `/`, `\\`, or `..` would let `client.${target.id}.d.ts`
    // resolve outside the bindings dir. Pack-loader id validation should catch most
    // cases upstream, but the emitter has its own backstop check that names the
    // offending id explicitly so the author's error is actionable.
    val workspace = newWorkspaceRoot()
    val maliciousTarget = AppTargetYamlConfig(
      id = "../escape",
      displayName = "Escape",
      tools = emptyList(),
    )

    val ex = assertFailsWith<IllegalArgumentException> {
      PerTargetClientDtsEmitter.emit(
        workspaceRoot = workspace.toPath(),
        resolvedTargets = listOf(maliciousTarget),
      )
    }
    val msg = ex.message ?: ""
    assertTrue("expected message to name the offending id: $msg") { msg.contains("'../escape'") }
    assertTrue("expected message to mention 'filename-safe': $msg") { msg.contains("filename-safe") }
  }

  @Test
  fun `emit rejects target id with forward slash`() {
    val workspace = newWorkspaceRoot()
    val maliciousTarget = AppTargetYamlConfig(
      id = "foo/bar",
      displayName = "Slashed",
      tools = emptyList(),
    )

    assertFailsWith<IllegalArgumentException> {
      PerTargetClientDtsEmitter.emit(
        workspaceRoot = workspace.toPath(),
        resolvedTargets = listOf(maliciousTarget),
      )
    }
  }

  // ---- helpers ----------------------------------------------------------------------------

  private fun newWorkspaceRoot(): File {
    // Mirrors the generator's `<workspaceRoot>/config/tools/.trailblaze/` resolution rule —
    // pass the temp dir as the `trails/` input.
    val dir = createTempDirectory("per-target-client-dts-test").toFile()
    tempDirs += dir
    return dir
  }
}
