package xyz.block.trailblaze.host

import ai.koog.agents.core.tools.ToolDescriptor
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.bundle.WorkspaceClientDtsGenerator
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.scripting.LazyYamlScriptedToolRegistration
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toTrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.util.Console

/**
 * The machine-readable arg-type sidecar that co-locates with each generated
 * `tools/trailblaze-client.d.ts`, carrying the same per-tool parameter types that back the `.d.ts`.
 *
 * ## Why this exists
 *
 * [PerTrailmapClientDtsEmitter] renders a TypeScript declaration (`.d.ts`) that
 * [TrailTscValidator] type-checks recorded trail calls against via `tsc`. But a recorded
 * `.trail.yaml` step's YAML→JSON decode guesses a scalar's type from its content (kaml discards
 * the source quote style), so a recorded quoted string like a passcode `'12345678'` or a flag
 * value `'true'` surfaces as a JSON number/boolean. `tsc` then flags `number not assignable to
 * string` on a faithfully-recorded, replay-passing trail. Replay itself already fixes this at
 * dispatch via [xyz.block.trailblaze.toolcalls.coerceArgsToDescriptorTypes]; the validator needs
 * the same coercion, which needs the tool's declared parameter types — but the validator only
 * reads disk, and the `.d.ts` isn't a convenient type oracle to parse back.
 *
 * So the emitter writes this JSON sidecar (a serialized [TrailblazeToolDescriptor] list) next to
 * the `.d.ts`, and the validator loads it to coerce each recorded call's args to its declared
 * types before transpiling — killing the false findings without weakening the gate. Both files are
 * derived from the SAME resolved tool set in the same emitter pass, and the scripted-tool
 * schema→descriptor walk is the shared [LazyYamlScriptedToolRegistration.buildScriptedToolDescriptor]
 * the runtime advertise path uses — so their per-tool parameter types stay in lockstep.
 */
object TrailValidationDescriptorSidecar {

  /**
   * Sidecar filename, written into the same `tools/` dir as [WorkspaceClientDtsGenerator.GENERATED_FILE_NAME].
   * `.json` so `tsc`'s `.ts`/`.js` include globs never pick it up as a compile input.
   */
  const val FILE_NAME: String = "trailblaze-tool-descriptors.json"

  private val json = Json {
    prettyPrint = true
    encodeDefaults = false
    // Forward-compatible: an older reader must skip a field a newer emitter added (e.g. this PR's
    // `inputSchema`) rather than throw and fall back to "no coercion" — which would resurface the
    // number/boolean false positives across a version skew.
    ignoreUnknownKeys = true
  }

  private val listSerializer = ListSerializer(TrailblazeToolDescriptor.serializer())

  /**
   * Write the descriptor sidecar into `<trailmapDir>/tools/`. Idempotent (skip-write-if-unchanged),
   * mirroring the `.d.ts` writer so a re-run with the same tool set doesn't churn mtimes. Empty
   * descriptor lists are still written so a stale non-empty sidecar can't survive a tool set that
   * shrank to nothing.
   */
  fun write(trailmapDir: Path, descriptors: List<TrailblazeToolDescriptor>) {
    val outputPath = trailmapDir
      .resolve(WorkspaceClientDtsGenerator.TRAILMAP_TOOLS_SUBDIR)
      .resolve(FILE_NAME)
    val rendered = json.encodeToString(listSerializer, descriptors)
    Files.createDirectories(outputPath.parent)
    val existing = if (Files.isRegularFile(outputPath)) Files.readString(outputPath) else null
    if (existing != rendered) Files.writeString(outputPath, rendered)
  }

  /**
   * Load the descriptor sidecar from `<trailmapDir>/tools/`, keyed by tool name. Returns an empty
   * map when the sidecar is missing or unparseable — the validator then simply skips coercion for
   * that trailmap (the pre-sidecar behavior), never throws.
   */
  fun read(trailmapDir: Path): Map<String, TrailblazeToolDescriptor> {
    val toolsDir = trailmapDir.resolve(WorkspaceClientDtsGenerator.TRAILMAP_TOOLS_SUBDIR)
    val path = toolsDir.resolve(FILE_NAME)
    if (!Files.isRegularFile(path)) {
      // A missing sidecar is normal for a target with no generated surface (classpath-bundled
      // targets, no-tools trailmaps) and correctly degrades to "no coercion." But when the `.d.ts`
      // IS present the sidecar should have been co-emitted beside it; its absence silently skips
      // coercion, so the number/boolean-not-string false positives can resurface — and for a
      // non-exempt target become fatal. Log that one case so the degraded path is greppable.
      if (Files.isRegularFile(toolsDir.resolve(WorkspaceClientDtsGenerator.GENERATED_FILE_NAME))) {
        Console.log(
          "[TrailValidationDescriptorSidecar] $path absent though a typed surface (.d.ts) exists " +
            "beside it; skipping arg coercion for this trailmap.",
        )
      }
      return emptyMap()
    }
    return try {
      json.decodeFromString(listSerializer, Files.readString(path)).associateBy { it.name }
    } catch (e: Exception) {
      Console.error(
        "[TrailValidationDescriptorSidecar] failed to read $path (skipping arg coercion for this " +
          "trailmap): ${e.message ?: e::class.simpleName}",
      )
      emptyMap()
    }
  }

  /**
   * PURE. Build the descriptor list backing one trailmap's typed surface, mirroring the merge
   * [WorkspaceClientDtsGenerator.collectEntriesFromResolved] performs so the sidecar and the `.d.ts`
   * agree on every tool's parameter types:
   *
   *  - **Kotlin/YAML descriptors first** ([kotlinDescriptors], the koog [ToolDescriptor]s the emitter
   *    resolves) — first-write-wins by name, so a Kotlin tool shadows a scripted tool of the same name.
   *  - **Scripted tools second** ([scriptedTools]) — delegated to
   *    [LazyYamlScriptedToolRegistration.buildScriptedToolDescriptor], the same schema→descriptor
   *    walk the runtime advertise path uses, so the sidecar can't diverge from the advertised shape.
   *    The schema fed in is the analyzer's typed override when present ([typedOverrides]), else the
   *    tool's own YAML-derived `inputSchema` — exactly what the `.d.ts` renders for that tool.
   *
   * Only scalar values are ever re-typed by [xyz.block.trailblaze.toolcalls.coerceArgsToDescriptorTypes],
   * so how non-scalar params are recorded here doesn't matter: an `object`/`array` arg (e.g. a
   * `nodeSelector`) carries its declared type but is inert, and a typeless `$ref`/`anyOf` prop is
   * recorded as `string` by the shared builder — coercion still only touches scalar values, so
   * neither can misfire.
   */
  fun buildValidationDescriptors(
    kotlinDescriptors: List<ToolDescriptor>,
    scriptedTools: List<InlineScriptToolConfig>,
    typedOverrides: Map<String, WorkspaceClientDtsGenerator.TypedToolOverride>,
  ): List<TrailblazeToolDescriptor> {
    val byName = LinkedHashMap<String, TrailblazeToolDescriptor>()
    kotlinDescriptors.forEach { descriptor ->
      byName.putIfAbsent(descriptor.name, descriptor.toTrailblazeToolDescriptor())
    }
    scriptedTools.forEach { tool ->
      if (byName.containsKey(tool.name)) return@forEach
      val schema = typedOverrides[tool.name]?.inputSchema ?: tool.inputSchema
      byName[tool.name] =
        LazyYamlScriptedToolRegistration.buildScriptedToolDescriptor(tool.copy(inputSchema = schema))
    }
    return byName.values.toList()
  }
}
