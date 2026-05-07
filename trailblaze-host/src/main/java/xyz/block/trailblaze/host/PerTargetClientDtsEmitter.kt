package xyz.block.trailblaze.host

import java.nio.file.Path
import xyz.block.trailblaze.bundle.WorkspaceClientDtsGenerator
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.logs.client.TrailblazeSerializationInitializer
import xyz.block.trailblaze.toolcalls.toKoogToolDescriptor

/**
 * Author-friendly typed bindings: emit one `client.<target-id>.d.ts` per resolved target
 * into the workspace's generated bindings dir. Idempotent — re-run with the same registered
 * toolset writes the same bytes (the generator's content-hash check skips unchanged files).
 *
 * **Why per-target.** A workspace authoring tools for the `alpha` target doesn't want
 * autocomplete polluted with `beta`'s tools, but it absolutely DOES want every
 * platform-/driver-variant of `alpha`'s tools side-by-side at the IDE — cross-platform
 * conditional tools are a
 * real authoring pattern (`if (ctx.target.platform === "android") ...`). The slicing
 * boundary is the **target**, not the platform/driver. Within a target's binding, every
 * tool from every platform/driver shows up.
 *
 * **Tool descriptor source.** The Kotlin tool half is the JVM-classpath-discovered
 * superset from [TrailblazeSerializationInitializer.buildAllTools] — every tool
 * registered via any module's `trailblaze-config/tools/<name>.tool.yaml` resources. Same set
 * across all targets; per-target slicing only affects the scripted-tool half (which is
 * the per-target `target.tools:` list, transitively resolved by `TrailblazeCompiler`).
 *
 * **Why the workspace-wide Kotlin set instead of a per-target filter.** Filtering Kotlin
 * tools by `target.platforms.<platform>.tool_sets:` membership would shrink each per-target
 * binding, but at author-time we want autocomplete on every framework primitive a tool
 * could compose with — `adbShell`, `tapOnElementBySelector`, `inputText`, etc. — even ones
 * the LLM-side gate wouldn't expose to a session running on a specific platform. Same
 * reasoning the previous lead-dev review settled: "tools commonly contain platform/target
 * compatibility in the name; we want all of those accessible because a tool can be used
 * for all of those and can have conditionals."
 *
 * **Why this lives in `:trailblaze-host` (not `:trailblaze-pack-bundler`).** Both wire-in
 * sites that need it ([WorkspaceCompileBootstrap], `CompileCommand`) are in this module.
 * The bundler module owns the rendering primitive; this module orchestrates it against
 * resolved-config + classpath-tool-registry inputs that aren't visible from the bundler's
 * dependency layer.
 *
 * Failure handling is the caller's responsibility — this helper just emits or throws.
 * Daemon-init wraps the call and degrades to a warning so a generation failure can't take
 * the daemon down with it; the CLI's `trailblaze compile` lets the exception propagate so
 * authors see the failure immediately.
 */
object PerTargetClientDtsEmitter {

  /**
   * Emit per-target bindings for every resolved target. Returns the absolute paths of the
   * files written. An empty `resolvedTargets` list is a no-op (returns empty list) — a
   * library-pack-only workspace has no target packs to slice for.
   */
  fun emit(
    workspaceRoot: Path,
    resolvedTargets: List<AppTargetYamlConfig>,
  ): List<Path> {
    if (resolvedTargets.isEmpty()) return emptyList()
    // Validate every target id eagerly before any I/O — gives an actionable error before
    // half the bindings are written. The generator does its own defense-in-depth check at
    // write time, but this layer's error names the offending pack id explicitly so authors
    // see "fix the pack id 'foo/bar'" rather than "filename validation failed."
    resolvedTargets.forEach { requireSafeTargetId(it.id) }
    val generator = WorkspaceClientDtsGenerator(workspaceRoot)
    val kotlinDescriptors = TrailblazeSerializationInitializer
      .buildAllTools()
      .values
      .mapNotNull { it.toKoogToolDescriptor() }
    return resolvedTargets.map { target ->
      generator.generateFromResolved(
        toolDescriptors = kotlinDescriptors,
        scriptedTools = target.tools.orEmpty(),
        outputFileName = "client.${target.id}.d.ts",
      )
    }
  }

  /**
   * Defense in depth against pack ids that would weaponize path traversal when interpolated
   * into the generator's `outputFileName`. The accepted shape mirrors
   * [WorkspaceClientDtsGenerator.SAFE_FILENAME_PATTERN] minus the dot (we explicitly forbid
   * dots in target ids here so the `client.<id>.d.ts` template doesn't end up with
   * unexpected dotted filenames). The pack loader has its own id-shape constraints; this
   * is a backstop in case those constraints loosen or a fixture bypasses them.
   */
  private fun requireSafeTargetId(id: String) {
    require(id.isNotEmpty() && SAFE_TARGET_ID_PATTERN.matches(id)) {
      "Cannot emit typed bindings for target id '$id': not a safe id for the " +
        "`client.<id>.d.ts` filename template (expected $SAFE_TARGET_ID_PATTERN). " +
        "Pack ids must be filename-safe — letters, digits, underscores, and hyphens only."
    }
  }

  private val SAFE_TARGET_ID_PATTERN: Regex = Regex("^[A-Za-z0-9_][A-Za-z0-9_-]*$")
}
