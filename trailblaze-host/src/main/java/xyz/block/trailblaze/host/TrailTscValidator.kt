package xyz.block.trailblaze.host

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import xyz.block.trailblaze.agent.trail.toJsonArgs
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.coerceArgsToDescriptorTypes
import xyz.block.trailblaze.toolcalls.commands.SwitchDeviceTrailblazeTool
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.TrailDocument
import xyz.block.trailblaze.yaml.unified.UnifiedTrail

/**
 * Type-validates trail recordings — per-device `*.trail.yaml` files AND unified-format trails
 * (bare `trail.yaml` or any name; detected by content) — against each trailmap's generated typed
 * tool surface (`tools/trailblaze-client.d.ts`) by transpiling every recorded tool call into a
 * throwaway TypeScript file and compiling it with the bundled `tsc`. A unified trail's per-step
 * `recording:` classifier slots are each validated in full (no closest-wins lowering), so a bad
 * call in any device's slot is caught and attributed to its step + classifier.
 *
 * ## One trail, one target per DEVICE — not per trail
 *
 * A multi-device configuration gives each named device its own `target:`, and the runtime honors that
 * per device — a companion's agent resolves ITS target's tools, not the session target's (see
 * [xyz.block.trailblaze.host.yaml.MultiDeviceConfigurationResolver.resolveMemberTargets]). So a trail
 * is not checked against one surface: [attributeRecordedCalls] replays each configuration leg
 * statically, tracking which member is active across the leg's `switchDevice` handovers, and every
 * call is checked against the surface of the target THAT member runs. The calls partition by target
 * and each partition gets its own gen file under its own trailmap.
 *
 * Validating a mixed-target trail against a single target reds on every tool that exists only on the
 * other member's surface, which is why such a trail previously had to omit its `target:` entirely and
 * get no type-checking at all.
 *
 * ## Why this exists
 *
 * `TrailYamlValidationTest` already proves every trail *parses*. It does NOT prove the recorded
 * tool calls are *type-correct* for their target — that a tool actually exists, that every argument
 * has the right type, and that no required argument is missing. The framework already emits, per
 * trailmap, a `client.tools.<name>(args): Promise<O>` typed surface (see
 * [PerTrailmapClientDtsEmitter]) that the trailmap's `.ts` scripted tools compile against. That same
 * surface is the type oracle here: a recorded call `tapOnElementWithText: { text: "Buy" }` becomes
 * `client.tools.tapOnElementWithText({ "text": "Buy" })`, and a clean `tsc --noEmit` means the
 * recording is type-valid. Tools that don't exist, wrong-typed args, and missing required args all
 * surface as ordinary TypeScript diagnostics, which we remap back to `<trail>.yaml · step N`.
 *
 * ## Arg type coercion before transpile
 *
 * A recorded `.trail.yaml` step's YAML→JSON decode guesses a scalar's type from its content (kaml
 * discards the source quote style), so a recorded quoted passcode `'12345678'` or flag value
 * `'true'` surfaces as a JSON number/boolean — and `tsc` would flag `number not assignable to
 * string` on a faithfully-recorded, replay-passing trail. Before transpiling, each recorded call's
 * scalar args are re-aligned to their declared types via
 * [xyz.block.trailblaze.toolcalls.coerceArgsToDescriptorTypes] — the SAME coercion replay applies
 * at dispatch — using the tool's parameter types loaded from the arg-type sidecar the emitter
 * co-locates with the `.d.ts` (see [TrailValidationDescriptorSidecar]). This clears the false
 * findings without weakening the gate: a genuinely wrong-typed arg (an object where a string is
 * declared, a missing required arg) is untouched by the coercion and still fails `tsc`.
 *
 * ## How the error→YAML mapping works (no YAML position API needed)
 *
 * Codegen emits **exactly one tool-call statement per line** and records, as it writes each line, a
 * `genLine -> {trail, step, tool}` entry in [GenFile.table]. tsc reports
 * `<file>.trail.gen.ts(line,col): error TS####: …`; [remap] parses those plain diagnostics and
 * looks the line up in the table. The mapping is built while emitting (we know exactly what each
 * line came from) — never recovered by parsing the file back.
 *
 * ## Default-strict, with explicit per-target exemptions
 *
 * The phase **fails the build by default**: a finding on a non-exempt target, or a non-exempt
 * target that couldn't be validated at all (no generated typed surface), is fatal. This keeps a
 * new uncovered target from silently slipping in. Two outcomes are NOT fatal:
 *
 * - **No manifest anywhere** — a trail whose `target:` resolves to no reachable trailmap manifest
 *   at all (placeholder / package-id targets used by smoke & eval trails, and trails with no
 *   `target:`) is a first-class permanent skip ([Report.skippedNoManifest]). There is no surface to
 *   validate against and never will be, so this needs no exemption entry — it's classified by
 *   membership in [validate]'s `knownManifestTargets`, not a hand-maintained allow-list.
 * - **Per-target exemption** — a target's `trail_validation.exempt: "<reason>"` in its
 *   `trailmap.yaml` (see [xyz.block.trailblaze.config.project.TrailValidationConfig]) opts a target
 *   that DOES have a manifest out of the gate: its findings and its missing-surface status are
 *   reported but non-fatal. This is the durable, co-located mechanism, honored via [validate]'s
 *   `exemptTargets`, and covers both filesystem and classpath-bundled trailmaps.
 *
 * The emitted `trailblaze-client.d.ts` is the FULL, ungated tool surface — every class-backed tool a
 * trailmap resolves is typed there, and the emitter re-injects selector args and surfaces every
 * recordable tool (class- and YAML-defined), so a faithfully-recorded call type-checks cleanly.
 * Validation is bounded by how *faithfully* that surface models a recorded call; a residual
 * per-target fidelity gap is absorbed by the exemption above until the surface closes it.
 *
 * Runs by default on every `trailblaze check`. Set `TRAILBLAZE_DISABLE_TRAIL_RECORDING_VALIDATION=1`
 * to skip the phase entirely.
 */
object TrailTscValidator {

  /** Env var that opts a `check` run OUT of the trail-recording validation phase entirely. */
  const val DISABLE_ENV_VAR: String = "TRAILBLAZE_DISABLE_TRAIL_RECORDING_VALIDATION"

  /**
   * Subdirectory under the workspace's `<trails>/.trailblaze/` where per-classpath-trailmap
   * validation surfaces are materialized (`<base>/<trailmapId>/tools/{tsconfig.json,
   * trailblaze-client.d.ts}`). Written by the compile phase (see
   * [PerTrailmapClientDtsEmitter.emitClasspathValidationSurfaces] +
   * [PerTrailmapTsconfigEmitter.emitClasspathValidationTsconfigs]) and discovered by the check
   * phase, which appends each surface dir to the trailmap list handed to [validate] — so a trail
   * whose `target:` is a JAR-bundled trailmap (e.g. an app-bundled target) type-checks against a
   * real surface instead of reading as skipped-no-surface. Lives under `.trailblaze/` so it's
   * already gitignored alongside the extracted SDK bundle.
   */
  const val CLASSPATH_VALIDATION_SURFACES_SUBDIR: String = "trail-validation"

  /**
   * Resolve the base directory holding classpath validation surfaces for a workspace, given its
   * `trails/` root. Single source of truth shared by the compile-side writer and the check-side
   * reader so the two never drift on where the surfaces live.
   */
  fun classpathValidationSurfacesBaseDir(trailsRoot: Path): Path =
    trailsRoot
      .resolve(WorkspaceTypeScriptSetup.GENERATED_DIR_NAME)
      .resolve(CLASSPATH_VALIDATION_SURFACES_SUBDIR)

  /**
   * The trailmap id owning a classpath validation-surface file emitted under the layout
   * `<base>/<id>/tools/<file>`, or null if [surfaceFile] doesn't sit at that depth. Single source
   * of truth for that layout so callers (e.g. the compile-side derivation of which ids got a
   * surface) don't re-encode the `parent.parent` path shape and silently drift if it changes.
   */
  fun trailmapIdForSurfaceFile(surfaceFile: Path): String? =
    surfaceFile.parent?.parent?.fileName?.toString()

  /** [Report.skippedNoSurface] / [exemptTargets] key for a trail that declares no `target:`. */
  const val NO_TARGET_KEY: String = "<no target:>"

  /** A single recorded tool call, flattened to the shape codegen needs. */
  data class RecordedCall(
    val toolName: String,
    /** The flat executor args as a JSON object literal (valid TS object-literal syntax). */
    val argsJson: String,
    val stepIndex: Int,
    val stepLabel: String,
    /**
     * The `recording:` classifier slot this call came from (`android`, `ios-iphone`, …) in a
     * unified-format trail. Null for v1 trails, whose recordings aren't classifier-keyed.
     */
    val classifier: String? = null,
    /**
     * The configuration member this call dispatches on, when its leg is a multi-device configuration
     * leg. Null for an ordinary single-device leg. Carried so a finding on a shared leg names the
     * device it was judged as, rather than leaving a reader to re-derive it from the handovers.
     */
    val deviceName: String? = null,
  )

  /** A generated throwaway `.trail.gen.ts` source plus its `genLine -> call` mapping table. */
  data class GenFile(val source: String, val table: Map<Int, RecordedCall>)

  /** One type-validation finding, keyed back to the originating trail YAML. */
  data class Finding(
    val trailRelPath: String,
    val stepIndex: Int,
    val stepLabel: String,
    val toolName: String,
    val tsCode: String,
    val message: String,
    /**
     * The target this call was checked against — the device's own `target:` in a multi-device
     * configuration, else the trail's `config.target:` (null when it declares none). Used to
     * classify exemptions.
     */
    val target: String? = null,
    /** The unified-format classifier slot the offending call sits in; null for v1 trails. */
    val classifier: String? = null,
    /** The configuration member the call dispatches on; null on a single-device leg. */
    val deviceName: String? = null,
  )

  /**
   * Aggregate outcome of validating a workspace's trails.
   *
   * The `fatal*` fields are the subset that fails the build under the default-strict gate — a
   * finding on a non-exempt target, or a non-exempt target that couldn't be validated at all
   * (no generated typed surface). Everything a caller needs to decide the exit code is precomputed
   * here so the CLI stays a thin renderer; [hasFatal] is the single boolean the exit code keys on.
   */
  data class Report(
    val trailsDiscovered: Int,
    val trailsValidated: Int,
    val toolCallsChecked: Int,
    val findings: List<Finding>,
    val skippedNoSurface: Map<String, Int>,
    /**
     * Targets (by `target:` value; [NO_TARGET_KEY] for the no-`target:` case) that resolve to NO
     * reachable trailmap manifest ANYWHERE — placeholder / package-id targets used by smoke & eval
     * trails, and trails that declare no `target:`. These can never be validated (there is no surface
     * to validate against and never will be), so they are a **permanent skip** and are never fatal.
     * Distinct from [skippedNoSurface], which is for a target that DOES have a manifest but whose
     * surface wasn't loaded in this run.
     */
    val skippedNoManifest: Map<String, Int> = emptyMap(),
    val skippedNoRecording: Int,
    /**
     * Recorded calls dropped because the static replay of a multi-device leg could no longer say
     * which device is active — see [attributeRecordedCalls]. Reported so the coverage this costs is
     * visible rather than silently missing; never fatal, since no target could be attributed.
     */
    val skippedUndeterminedDevice: Int = 0,
    val errors: List<String>,
    /**
     * Findings that FAIL the build: on a non-exempt target. Empty when the gate is satisfied.
     * A subset of [findings].
     */
    val fatalFindings: List<Finding> = emptyList(),
    /**
     * Non-exempt targets (by `target:` value; [NO_TARGET_KEY] for the no-`target:` case) that
     * couldn't be validated because no reachable trailmap in the workspace carries a generated
     * typed surface for them — a build failure, because a new uncovered target must not slip in
     * silently. A subset of [skippedNoSurface].
     */
    val fatalMissingSurfaceTargets: Map<String, Int> = emptyMap(),
  ) {
    /** True when the default-strict gate should fail the build. */
    fun hasFatal(): Boolean = fatalFindings.isNotEmpty() || fatalMissingSurfaceTargets.isNotEmpty()
  }

  // Header lines emitted before the first tool-call statement. The first call therefore lands on
  // line (HEADER.size + 1); [generateGenFile] tracks the real line number as it appends, so this
  // count is not load-bearing for the mapping — only for keeping codegen one-call-per-line.
  private val HEADER: List<String> = listOf(
    "// GENERATED by TrailTscValidator — trail type-validation. Throwaway; deleted after the run.",
    "import type { TrailblazeClient } from \"@trailblaze/scripting\";",
    "declare const client: TrailblazeClient;",
    "async function __trail__(): Promise<void> {",
  )

  /**
   * PURE. Build the throwaway TS source for one trail plus the `genLine -> call` table.
   *
   * One `client.tools.<name>(<args>)` statement per line; a trailing comment names the source step
   * for humans reading raw `tsc --pretty` output, but the authoritative mapping is [GenFile.table]
   * (built here, never recovered from the comment — a `//` inside a URL arg would fool that).
   */
  fun generateGenFile(trailRelPath: String, calls: List<RecordedCall>): GenFile {
    val lines = HEADER.toMutableList()
    val table = mutableMapOf<Int, RecordedCall>()
    for (call in calls) {
      val lineNo = lines.size + 1 // 1-based line this statement will occupy
      val label = singleLine(call.stepLabel).take(70)
      val slot = call.classifier?.let { " [${slotLabel(it, call.deviceName)}]" } ?: ""
      lines.add("  ${calleeExpr(call.toolName)}(${call.argsJson}); // step ${call.stepIndex}$slot: $label")
      table[lineNo] = call
    }
    lines.add("}")
    lines.add("void __trail__;")
    return GenFile(source = lines.joinToString("\n") + "\n", table = table)
  }

  /**
   * The `client.tools.<name>` callee for one tool. Plain dot access for a valid JS identifier (the
   * common case, readable); bracket access with a JSON-escaped string key otherwise. Bracket access
   * keeps the generated call aligned with the typed surface for tool names that legitimately carry
   * `-`/`.` (which `TrailblazeToolMap` exposes as quoted keys), and makes interpolation
   * injection-safe for any name — a malformed name can't break out of the quoted key.
   */
  private fun calleeExpr(toolName: String): String =
    if (VALID_TOOL_NAME.matches(toolName)) {
      "client.tools.$toolName"
    } else {
      "client.tools[${jsonStringLiteral(toolName)}]"
    }

  /**
   * How a call's recording leg is named in generated comments and report rows: the classifier slot,
   * plus the configuration member the call dispatches on when the leg is a multi-device one
   * (`register-kitchen → kitchen`). Both halves are YAML-sourced, so both go through [singleLine].
   */
  private fun slotLabel(classifier: String, deviceName: String?): String =
    singleLine(classifier) + (deviceName?.let { " → ${singleLine(it)}" } ?: "")

  /**
   * Collapse CR/LF to spaces so interpolated YAML-sourced text (a step label, a classifier key —
   * both may legally contain line breaks as quoted scalars) can't split a generated
   * one-statement-per-line line or a report row.
   */
  private fun singleLine(s: String): String = s.replace('\n', ' ').replace('\r', ' ')

  /** Minimal JSON/TS string-literal escaping for a map key. */
  private fun jsonStringLiteral(s: String): String = buildString {
    append('"')
    for (c in s) when (c) {
      '\\' -> append("\\\\")
      '"' -> append("\\\"")
      '\n' -> append("\\n")
      '\r' -> append("\\r")
      '\t' -> append("\\t")
      else -> append(c)
    }
    append('"')
  }

  /** Pairs a gen file's source-trail path with its line table for the remap step. */
  data class GenFileMeta(
    val trailRelPath: String,
    val table: Map<Int, RecordedCall>,
    /**
     * The target this gen file was compiled against; flows onto each [Finding]. One trail can
     * produce several gen files — one per target its devices run — so this is the GROUP's target,
     * not necessarily the trail's `config.target:`.
     */
    val target: String? = null,
  )

  // `<path>.trail.gen.ts(line,col): error TS####: message`
  private val DIAGNOSTIC_RE =
    Regex("""(\S*\.trail\.gen\.ts)\((\d+),(\d+)\):\s+error\s+(TS\d+):\s+(.*)""")

  /**
   * PURE. Parse plain (`--pretty false`) tsc output and remap every `.trail.gen.ts` diagnostic to a
   * [Finding] keyed by trail + step, using [metasByGenFileName] (keyed by gen-file basename).
   *
   * Continuation lines (tsc indents the elaboration of a multi-line diagnostic) are folded into the
   * preceding finding's message. Diagnostics on a gen file's header lines (no table entry) are
   * dropped — they'd be framework codegen bugs, not author errors.
   */
  fun remap(tscPlainOutput: String, metasByGenFileName: Map<String, GenFileMeta>): List<Finding> {
    val findings = mutableListOf<Finding>()
    var current: Int? = null // index into `findings` for continuation-line folding
    for (raw in tscPlainOutput.lines()) {
      val match = DIAGNOSTIC_RE.find(raw)
      if (match != null) {
        val genFileName = File(match.groupValues[1]).name
        // The regex group is `\d+`, so this only returns null on an absurdly large line number;
        // guard it anyway so the non-null Int indexes the table cleanly.
        val lineNo = match.groupValues[2].toIntOrNull()
        val code = match.groupValues[4]
        val message = match.groupValues[5]
        val meta = metasByGenFileName[genFileName]
        val call = if (lineNo != null) meta?.table?.get(lineNo) else null
        if (meta != null && call != null) {
          findings.add(
            Finding(
              trailRelPath = meta.trailRelPath,
              stepIndex = call.stepIndex,
              stepLabel = call.stepLabel,
              toolName = call.toolName,
              tsCode = code,
              message = message,
              target = meta.target,
              classifier = call.classifier,
              deviceName = call.deviceName,
            ),
          )
          current = findings.size - 1
        } else {
          current = null
        }
      } else if (current != null && raw.startsWith("  ")) {
        val f = findings[current]
        findings[current] = f.copy(message = "${f.message} ${raw.trim()}")
      }
    }
    return findings
  }

  /**
   * Validate every trail file under [trailsRoot] (see [isTrailFile]) against the typed surfaces of [trailmaps]
   * (the resolved workspace trailmap directories, each expected to carry a generated
   * `tools/trailblaze-client.d.ts` + `tools/tsconfig.json`). Side-effecting: writes and deletes
   * throwaway `*.trail.gen.ts` files under each trailmap's `tools/` dir, and spawns one `tsc` per
   * trailmap that has trails to validate.
   *
   * A trail's calls are partitioned by the target the device that dispatches them runs (see
   * [attributeRecordedCalls]), so a multi-device trail whose members declare different `target:`s
   * stages one gen file per target rather than being checked — wrongly — against a single surface.
   * The skip buckets are counted per trail-and-target for the same reason: such a trail can have one
   * member's target validated and another's read as a permanent skip.
   *
   * Never throws — per-trail and per-trailmap failures are captured into [Report.errors] so a
   * single bad file can't abort the whole pass.
   */
  fun validate(
    trailsRoot: File,
    trailmaps: List<Path>,
    jsRuntime: String,
    tscJs: Path,
    /**
     * Targets exempted from failing the gate, keyed by `target:` value ([NO_TARGET_KEY] for the
     * no-`target:` case), with a human-readable reason as the value. Findings on — and the
     * missing-surface status of — an exempt target are reported but never fatal.
     */
    exemptTargets: Map<String, String> = emptyMap(),
    /**
     * The set of `target:` values that DO have a reachable trailmap manifest somewhere — every
     * workspace trailmap id plus every classpath-bundled manifest id. A trail whose `target:` is NOT
     * in this set resolves to no manifest at all, so it's a permanent skip ([Report.skippedNoManifest])
     * rather than a missing-surface skip. Defaults to empty, in which case any target with no loaded
     * surface is treated as manifest-less.
     */
    knownManifestTargets: Set<String> = emptySet(),
    /**
     * When false (a scoped run), a non-exempt target with no loaded surface is reported as skipped
     * but is NOT fatal — see [classify]. Only an all-workspace pass loads every surface and can
     * treat a missing surface as an uncovered target.
     */
    failOnMissingSurface: Boolean = true,
    timeoutMs: Long = DEFAULT_TSC_TIMEOUT_MS,
  ): Report {
    val yaml = createTrailblazeYaml()
    // Map the workspace trailmaps by directory name. For a filesystem trailmap the dir name is the
    // trailmap id, which is what a trail's `target:` references — so this is the correct key here,
    // distinct from the `manifest.id` keying the compile-time emitters use (they operate on
    // ResolvedTrailmap metadata; we operate on the post-compile filesystem dirs the CLI hands us).
    // Classpath-bundled targets (no dir here) resolve to null and are reported as skipped-no-surface.
    val trailmapDirByName: Map<String, Path> = trailmaps.associateBy { it.fileName.toString() }

    val errors = mutableListOf<String>()
    val skippedNoSurface = mutableMapOf<String, Int>()
    val skippedNoManifest = mutableMapOf<String, Int>()
    var skippedNoRecording = 0
    var skippedUndeterminedDevice = 0
    var discovered = 0

    // A trail staged for validation: its gen-file source + target path + the remap metadata. Gen
    // file CONTENT is held in memory here and only written to disk inside the per-trailmap
    // try/finally below, so an exception during discovery can never orphan a `.trail.gen.ts`.
    data class Staged(val genPath: Path, val source: String, val meta: GenFileMeta, val callCount: Int)
    val stagedByTrailmap = mutableMapOf<Path, MutableList<Staged>>()

    // Per-trailmap arg-type descriptors (from the sidecar the emitter co-locates with the .d.ts),
    // loaded once per trailmap dir and reused across its trails. Used to coerce recorded scalar
    // args back to their declared types before transpiling — see [TrailValidationDescriptorSidecar].
    val descriptorsByTrailmap = mutableMapOf<Path, Map<String, TrailblazeToolDescriptor>>()

    val trailFiles = trailsRoot.walkTopDown().filter { it.isFile && isTrailFile(it.name) }.toList()
    for (trailFile in trailFiles) {
      discovered++
      val rel = trailsRoot.parentFile?.toPath()?.relativize(trailFile.toPath())?.toString() ?: trailFile.name
      try {
        val text = trailFile.readText()
        // One version-aware parse per trail; the format is detected from CONTENT, never the
        // filename (a unified trail may be a bare `trail.yaml` or carry any name).
        val doc = yaml.decodeTrailDocument(text)
        val attribution = attributeRecordedCalls(doc)
        skippedUndeterminedDevice += attribution.undeterminedDeviceCalls
        if (attribution.calls.isEmpty()) {
          skippedNoRecording++
          continue
        }
        // One gen file per target this trail's devices run. Grouping preserves encounter order, so
        // each target's file keeps the trail's own call order.
        for ((target, attributed) in attribution.calls.groupBy { it.target }) {
          val trailmapDir = target?.let { trailmapDirByName[it] }
          if (trailmapDir == null) {
            val key = target ?: NO_TARGET_KEY
            // Split a no-loaded-surface target two ways:
            //  - a target with a reachable manifest (in [knownManifestTargets]) whose surface just
            //    wasn't loaded in this run → skipped-no-surface (fatal on an all-workspace pass, so an
            //    uncovered target can't slip in silently).
            //  - a target with NO manifest anywhere (placeholder / package-id targets, the no-target:
            //    case) → skipped-no-manifest: it can never be validated, so it's a permanent, non-fatal
            //    skip rather than something a hand-maintained exemption list has to carry.
            if (key in knownManifestTargets) {
              skippedNoSurface[key] = (skippedNoSurface[key] ?: 0) + 1
            } else {
              skippedNoManifest[key] = (skippedNoManifest[key] ?: 0) + 1
            }
            continue
          }
          val descriptors = descriptorsByTrailmap.getOrPut(trailmapDir) {
            TrailValidationDescriptorSidecar.read(trailmapDir)
          }
          val calls = attributed.map { it.toRecordedCall(descriptors) }
          val gen = generateGenFile(rel, calls)
          // Unique gen-file name per trail: a sanitized stem (readable) plus a stable hash of the
          // full relative path, so two distinct trails that sanitize to the same stem can't collide
          // (the gen files share one `tools/` dir and are keyed by basename in remap). No target in
          // the name: distinct targets resolve to distinct trailmap dirs, so one trail contributes at
          // most one gen file per dir.
          val stem = rel.replace(Regex("[^A-Za-z0-9]"), "_")
          val unique = Integer.toHexString(rel.hashCode())
          val genPath = trailmapDir.resolve("tools").resolve("${stem}_$unique.trail.gen.ts")
          stagedByTrailmap.getOrPut(trailmapDir) { mutableListOf() }
            .add(Staged(genPath, gen.source, GenFileMeta(rel, gen.table, target), calls.size))
        }
      } catch (e: Exception) {
        errors.add("$rel: ${e::class.simpleName}: ${e.message}")
      }
    }

    val findings = mutableListOf<Finding>()
    // Trails, not staged gen files: a mixed-target trail stages one file per target and must still
    // count once.
    val validatedTrails = mutableSetOf<String>()
    var toolCalls = 0
    for ((trailmapDir, staged) in stagedByTrailmap) {
      val tsconfig = trailmapDir.resolve("tools").resolve("tsconfig.json")
      if (!Files.isRegularFile(tsconfig)) {
        errors.add("${trailmapDir.fileName}: no tools/tsconfig.json (run the compile phase first)")
        continue
      }
      try {
        staged.forEach { Files.writeString(it.genPath, it.source) }
        val output = runTsc(jsRuntime, tscJs, tsconfig, timeoutMs, trailmapDir.fileName.toString())
        val metas = staged.associate { it.genPath.fileName.toString() to it.meta }
        findings += remap(output, metas)
        staged.forEach { validatedTrails.add(it.meta.trailRelPath) }
        toolCalls += staged.sumOf { it.callCount }
      } catch (e: Exception) {
        errors.add("${trailmapDir.fileName}: tsc run failed: ${e::class.simpleName}: ${e.message}")
      } finally {
        staged.forEach { runCatching { Files.deleteIfExists(it.genPath) } }
      }
    }

    val classification =
      classify(findings, skippedNoSurface, exemptTargets, failOnMissingSurface)

    return Report(
      trailsDiscovered = discovered,
      trailsValidated = validatedTrails.size,
      toolCallsChecked = toolCalls,
      findings = findings,
      skippedNoSurface = skippedNoSurface,
      skippedNoManifest = skippedNoManifest,
      skippedNoRecording = skippedNoRecording,
      skippedUndeterminedDevice = skippedUndeterminedDevice,
      errors = errors,
      fatalFindings = classification.fatalFindings,
      fatalMissingSurfaceTargets = classification.fatalMissingSurfaceTargets,
    )
  }

  /** The fatal/non-fatal split of a validation pass — the output of [classify]. */
  data class Classification(
    val fatalFindings: List<Finding>,
    val fatalMissingSurfaceTargets: Map<String, Int>,
  )

  /**
   * PURE. Apply the exemption rules to split findings and no-surface targets into fatal vs
   * non-fatal buckets. A finding is fatal unless its target is exempt; a no-surface target is fatal
   * unless it's exempt — and only when [failOnMissingSurface] is set (see below).
   *
   * A finding's `target` is always a validatable target in practice (findings only come from
   * trailmaps with a generated surface), so `target == null` shouldn't occur — but it's treated
   * defensively as non-exempt so any future no-target validation path fails loud rather than
   * silently passing.
   *
   * @param failOnMissingSurface when false (a scoped run), a target with no loaded surface is NOT
   *   fatal. The validator walks every trail under the workspace, but a scoped run only loaded the
   *   selected trailmap's surface, so the other workspace targets legitimately have no surface here
   *   and must read as out-of-scope skips, not defects. Only an all-workspace pass — which loads
   *   every surface — can conclude a missing surface means an uncovered target.
   */
  fun classify(
    findings: List<Finding>,
    skippedNoSurface: Map<String, Int>,
    exemptTargets: Map<String, String>,
    failOnMissingSurface: Boolean = true,
  ): Classification {
    val fatalFindings = findings.filterNot { f ->
      f.target != null && exemptTargets.containsKey(f.target) // exempt: reported, non-fatal
    }
    // A non-exempt target we couldn't validate at all (no generated surface) is a build failure ON AN
    // ALL-WORKSPACE PASS — it prevents a new uncovered target from slipping in unnoticed. On a scoped
    // pass we can't draw that conclusion (only the selected surface was loaded), so it's non-fatal.
    val fatalMissingSurfaceTargets =
      if (failOnMissingSurface) skippedNoSurface.filterKeys { it !in exemptTargets } else emptyMap()
    return Classification(fatalFindings, fatalMissingSurfaceTargets)
  }

  // Tool names that are valid JS identifiers can be emitted as `client.tools.<name>`; any other
  // name (legitimately containing `-`/`.`, or a malformed recording) goes through bracket access
  // with an escaped string key — see [calleeExpr].
  private val VALID_TOOL_NAME = Regex("""^[A-Za-z_][A-Za-z0-9_]*$""")

  /**
   * True for both trail filename shapes: per-device `<stem>.trail.yaml` and the unified format's
   * bare `trail.yaml` (no leading dot, so a plain `.trail.yaml` suffix check misses it).
   */
  internal fun isTrailFile(fileName: String): Boolean =
    fileName.endsWith(".trail.yaml") || fileName == "trail.yaml"

  /** One recorded leg: the tools a single step declares under a single `recording:` slot. */
  internal data class RecordedLeg(
    /** The trailhead is the deterministic step 0; `trail:` entries are their index + 1. */
    val stepIndex: Int,
    val stepLabel: String,
    val classifier: String,
    val tools: List<TrailblazeToolYamlWrapper>,
  )

  /**
   * Single owner of the recorded-leg walk over a unified trail: the trailhead is the deterministic
   * step 0, list steps are index + 1, and every classifier slot is visited. Everything that reads a
   * trail's recordings here goes through this, so the step-index convention can't drift.
   *
   * Legs rather than a flat tool list because attribution is per leg and ORDERED within it — a
   * `switchDevice` moves the session for the calls that follow it in the same leg, and for nothing
   * else.
   */
  internal fun recordedLegs(trail: UnifiedTrail): List<RecordedLeg> = buildList {
    trail.trailhead?.let { trailhead ->
      trailhead.recordings.forEach { (classifier, tools) ->
        add(RecordedLeg(0, trailhead.step, classifier, tools))
      }
    }
    trail.trail.forEachIndexed { index, step ->
      step.recordings.forEach { (classifier, tools) ->
        add(RecordedLeg(index + 1, step.step, classifier, tools))
      }
    }
  }

  /**
   * One recorded call plus the device that dispatches it and the target that device runs — the unit
   * [validate] partitions by. Holds the raw wrapper because the arg-type coercion needs the
   * descriptors of the target's OWN trailmap, which the caller only resolves after grouping.
   */
  internal data class AttributedCall(
    /** The target this call is checked against; null when neither the device nor the trail names one. */
    val target: String?,
    /** The configuration member dispatching this call; null on an ordinary single-device leg. */
    val deviceName: String?,
    val stepIndex: Int,
    val stepLabel: String,
    val classifier: String,
    val tool: TrailblazeToolYamlWrapper,
  ) {
    /**
     * Transpile-ready form. [descriptorsByName] carries the tool's declared parameter types (from
     * the emitter's arg-type sidecar) so recorded scalar args can be coerced back to their declared
     * types before transpiling. Empty means no coercion.
     */
    internal fun toRecordedCall(
      descriptorsByName: Map<String, TrailblazeToolDescriptor> = emptyMap(),
    ): RecordedCall {
      val rawArgs = tool.toJsonArgs()
      // Re-align each scalar arg to its declared type BEFORE transpiling: a recorded quoted
      // passcode/flag surfaces as a JSON number/boolean (kaml drops quote style), which `tsc`
      // would otherwise flag as `number not assignable to string` on a faithfully-recorded trail.
      // This is the same coercion replay applies at dispatch; here it clears the false findings.
      val descriptor = descriptorsByName[tool.name]
      val args = if (descriptor != null) coerceArgsToDescriptorTypes(rawArgs, descriptor) else rawArgs
      return RecordedCall(
        toolName = tool.name,
        argsJson = args.toString(),
        stepIndex = stepIndex,
        stepLabel = stepLabel,
        classifier = classifier,
        deviceName = deviceName,
      )
    }
  }

  /** Every attributable recorded call of a trail, plus what attribution could not reach. */
  internal data class Attribution(
    val calls: List<AttributedCall>,
    /**
     * Calls dropped because the static replay lost track of the active device AND the configuration
     * is mixed-target, so which surface applies is undecidable. Counted rather than guessed at:
     * attributing them to the last known member would check them against a surface the run may never
     * use, and a wrong FATAL finding on a trail that replays fine is worse than the reported gap. A
     * single-target configuration keeps its coverage here — see [attributeRecordedCalls].
     */
    val undeterminedDeviceCalls: Int = 0,
  )

  /**
   * PURE. Attribute every recorded tool call of a parsed trail to the device that dispatches it and
   * the target that device runs. EVERY `recording:` classifier slot contributes (`android:`,
   * `ios-iphone:`, …) — no closest-wins lowering, so validation checks all slots rather than one
   * device's resolution — and the trailhead's bootstrap tools are treated as step 0.
   *
   * ## Which target a call is checked against
   *
   * - **A leg keyed by a multi-device configuration name** is replayed statically, exactly the way
   *   the runtime dispatches it: the session starts on the FIRST declared member and each recorded
   *   `switchDevice` moves it, ACROSS legs — which device is active is session state, so a handover
   *   in one step is still in force in the next. Each call is attributed to the active member and
   *   checked against that
   *   member's own `target:` override, falling back to the trail's `config.target:`. This is what
   *   [xyz.block.trailblaze.host.yaml.MultiDeviceConfigurationResolver.resolveMemberTargets] does at
   *   run time; without it a mixed-target cast reds on every tool that exists only on the other
   *   member's surface. The `switchDevice` call itself is attributed to the member active BEFORE it,
   *   which is the device that dispatches it.
   * - **A leg keyed by a plain classifier, on a trail declaring exactly one configuration**, is a
   *   FALLBACK leg of that same configuration, not a separate single-device run: the trail always
   *   binds the configuration (`selectConfigurationName`), and the lowering puts the selected
   *   configuration at the head of the resolution chain, letting a classifier key match when the
   *   configuration key is absent (`UnifiedTrailAdapter`). The session is the same one, so such a leg
   *   is replayed with the same member roster and the same active device — a `switchDevice` recorded
   *   in it reroutes the tools after it, exactly as `activeAgent()` does at run time.
   * - **Any other leg** — no configuration declared, or several, where which one binds is a run-time
   *   selection — has no roster to replay against and falls back to the trail's `config.target:`.
   *
   * One step's slots are ALTERNATIVES, not a sequence — the lowering picks a single closest match, so
   * a run executes at most one of them. All of them are still validated (any can be the winner on
   * some device), but each replays from the device the step STARTED on, and only the winning slot's
   * handover carries to the next step: a configuration-keyed slot where one exists, otherwise the
   * fallback slots' result where they agree. Otherwise an unreachable slot's `switchDevice` would
   * decide which surface the next step is checked against.
   *
   * ## Where it stops
   *
   * Past a handover the replay can't resolve — an undeclared or memory-interpolated `switchDevice`
   * name, or a `switchDevice` nested in a conditional branch that may or may not run — the active
   * device is genuinely undecidable, and stays that way for the rest of the configuration's legs.
   * This mirrors [SelectorDialectLint]'s replay, which abandons a leg at the same points and for the
   * same reason.
   *
   * The remaining calls are still checked when every member of the configuration resolves to the
   * SAME target: the target is decided regardless of who is active, so only the device name is lost.
   * Only a mixed-target configuration gives up its coverage there, counted into
   * [Attribution.undeterminedDeviceCalls] rather than attributed to a guess.
   *
   * The replay RESUMES at the next top-level `switchDevice` naming a declared member: that is
   * unconditional at run time — `switchTo(name)` leaves that member active whatever preceded it — so
   * everything after it is decided again even though the switch itself dispatched on an unknown
   * device. Without this, one conditional handover cost the rest of the trail its coverage.
   */
  internal fun attributeRecordedCalls(doc: TrailDocument): Attribution = when (doc) {
    is TrailDocument.Unified -> attributeRecordedCalls(doc.trail)
  }

  private fun attributeRecordedCalls(trail: UnifiedTrail): Attribution {
    val sessionTarget = trail.config.target
    val configurations = trail.config.devices.orEmpty().filterValues { it.isConfiguration }
    // A trail declaring exactly one configuration always binds it, and the lowering puts the
    // selected configuration at the HEAD of the resolution chain (UnifiedTrailAdapter) — so a leg
    // keyed by a plain classifier is a fallback leg of that same configuration, running on the same
    // session. It gets the same member roster and the same active-device state, handovers included.
    val soleConfigurationName = configurations.entries.singleOrNull()?.key

    // Which member each configuration is currently on. The first declared member is the start
    // device, and this is SESSION state: a handover in step N is still in force in step N+1, the way
    // SessionDeviceBindings.activeName and SelectorDialectLint's replay both hold it. Reset per leg
    // it would forget the handover and check step N+1's companion-only tools against the start
    // member's target — the false FATAL this pass exists to remove.
    val activeMemberByConfiguration: MutableMap<String, String?> = configurations
      .filterValues { it.devices.orEmpty().isNotEmpty() }
      .mapValuesTo(mutableMapOf()) { (_, configuration) -> configuration.devices.orEmpty().keys.first() }

    val calls = mutableListOf<AttributedCall>()
    var undetermined = 0
    // One step's slots are ALTERNATIVES, not a sequence: the lowering picks the single closest
    // match, so a run executes at most one of them. They are all validated — any of them can be the
    // winner on some device — but each replays from the device the step STARTED on, and the state
    // committed for the next step is the winning slot's alone. Replaying them as a sequence would
    // let an unreachable slot's `switchDevice` decide which surface the next step is checked against.
    for ((_, step) in recordedLegs(trail).groupBy { it.stepIndex }) {
      val resultsByConfiguration = mutableMapOf<String, MutableList<Pair<Boolean, String?>>>()
      for (leg in step) {
        val configurationName =
          if (leg.classifier in configurations) leg.classifier else soleConfigurationName
        val members = configurationName?.let { configurations.getValue(it).devices.orEmpty() }.orEmpty()
        if (configurationName == null || members.isEmpty()) {
          // No configuration governs this leg: either the trail declares none, or it declares several
          // and which one binds is a run-time selection, so there is no roster to replay against.
          leg.tools.forEach { tool ->
            calls.add(
              AttributedCall(
                target = sessionTarget,
                deviceName = null,
                stepIndex = leg.stepIndex,
                stepLabel = leg.stepLabel,
                classifier = leg.classifier,
                tool = tool,
              ),
            )
          }
          continue
        }
        // Every member resolving to the same target decides the target no matter which one is active,
        // so an unresolvable handover there costs the device NAME but not the type-checking. Most
        // multi-device trails are this shape; dropping their whole leg would be the bulk of the gate.
        val memberTargets = members.values.mapTo(mutableSetOf()) { it.target ?: sessionTarget }

        var activeMember: String? = activeMemberByConfiguration[configurationName]
        for (tool in leg.tools) {
          val member = activeMember
          if (member == null) {
            if (memberTargets.size == 1) {
              calls.add(
                AttributedCall(
                  target = memberTargets.first(),
                  deviceName = null,
                  stepIndex = leg.stepIndex,
                  stepLabel = leg.stepLabel,
                  classifier = leg.classifier,
                  tool = tool,
                ),
              )
            } else {
              undetermined++
            }
            // A top-level `switchDevice` naming a declared member is unconditional at run time —
            // `switchTo(name)` leaves that member active whatever was active before, and it is
            // idempotent when it already was. So the replay RESUMES here: it still cannot say which
            // device dispatched the switch itself, but everything after it is decided again.
            if (tool.name == SwitchDeviceTrailblazeTool.ADVERTISED_TOOL_NAME) {
              activeMember = MultiDeviceHandoverGuard.readTargetName(tool)?.takeIf { it in members.keys }
            }
            continue
          }
          calls.add(
            AttributedCall(
              target = members.getValue(member).target ?: sessionTarget,
              deviceName = member,
              stepIndex = leg.stepIndex,
              stepLabel = leg.stepLabel,
              classifier = leg.classifier,
              tool = tool,
            ),
          )
          activeMember = when {
            tool.name == SwitchDeviceTrailblazeTool.ADVERTISED_TOOL_NAME ->
              // An unreadable or undeclared name resolves to nothing the replay can follow. The name
              // itself is reported by SelectorDialectLint's handover pass, not here.
              MultiDeviceHandoverGuard.readTargetName(tool)?.takeIf { it in members.keys }
            // A handover nested in a conditional dispatches for real, but whether its branch runs is a
            // run-time question, so past one the active device is undecidable. Asked against the
            // bindable names so a predicate switch that can only throw (caught, false verdict, same
            // device) doesn't cost the rest of the leg its coverage.
            MultiDeviceHandoverGuard.canMoveSession(tool, members.keys) -> null
            else -> member
          }
        }
        resultsByConfiguration.getOrPut(configurationName) { mutableListOf() }
          .add((leg.classifier == configurationName) to activeMember)
      }
      // Commit the winning slot's device, carrying an undetermined one forward with it: an
      // unresolvable handover does not become resolvable because the step ended. A configuration-keyed
      // slot always wins where one exists — it heads the resolution chain. Among fallback slots alone
      // (`android:` vs `ios-iphone:`) the winner is the run's device, so they only decide the next
      // step where they agree; where they disagree the device is genuinely run-dependent.
      resultsByConfiguration.forEach { (name, results) ->
        val keyed = results.firstOrNull { (isKeyed, _) -> isKeyed }
        activeMemberByConfiguration[name] = when {
          keyed != null -> keyed.second
          else -> results.map { (_, member) -> member }.distinct().singleOrNull()
        }
      }
    }
    return Attribution(calls, undetermined)
  }

  /**
   * Spawn `<jsRuntime> <tscJs> --noEmit --pretty false --project <tsconfig>` and return its output.
   *
   * The child's output is drained on a reader thread so the [timeoutMs] bound actually applies even
   * when tsc hangs without closing its stream — reading inline before `waitFor` would block past the
   * timeout. Diverges intentionally from [CheckCommand]'s `runTsc`, which `inheritIO()`s straight to
   * the terminal (it needs no capture); here we must capture stdout to remap diagnostics.
   */
  private fun runTsc(jsRuntime: String, tscJs: Path, tsconfig: Path, timeoutMs: Long, label: String): String {
    val proc = ProcessBuilder(
      jsRuntime,
      tscJs.toAbsolutePath().toString(),
      "--noEmit",
      "--pretty",
      "false",
      "--project",
      tsconfig.toAbsolutePath().toString(),
    ).redirectErrorStream(true).start()
    val captured = StringBuilder()
    val reader = Thread {
      runCatching { proc.inputStream.bufferedReader().forEachLine { captured.appendLine(it) } }
    }.apply { isDaemon = true; start() }
    try {
      if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
        proc.destroyForcibly()
        throw IllegalStateException("tsc for '$label' did not finish within ${timeoutMs}ms")
      }
      return captured.toString()
    } finally {
      // Close the stream so a reader still blocked in `forEachLine` after `destroyForcibly()`
      // (some platforms don't close child streams on forcible kill) unblocks and the daemon
      // thread exits promptly rather than lingering for the life of the daemon process.
      runCatching { proc.inputStream.close() }
      reader.join(2_000)
    }
  }

  /**
   * Render [report] as a human-readable, YAML-keyed summary. The header states whether the gate
   * passed or failed; the detailed listing shows the FATAL findings (the ones that fail the build)
   * grouped by trail, then a compact accounting of what was exempted or downgraded.
   */
  fun renderReport(report: Report): String = buildString {
    val verdict = if (report.hasFatal()) "FAILED" else "passed"
    appendLine("── trail recording type-validation ($verdict) ──────────────────")
    appendLine("Trails discovered:        ${report.trailsDiscovered}")
    appendLine("Trails validated:         ${report.trailsValidated}")
    appendLine("Tool calls type-checked:  ${report.toolCallsChecked}")
    if (report.skippedNoRecording > 0) appendLine("Skipped (no recording):   ${report.skippedNoRecording}")
    if (report.skippedUndeterminedDevice > 0) {
      // Named rather than folded into a total: this is coverage the pass gave up, and a silent
      // omission would read as "everything was checked".
      appendLine(
        "Skipped (device undetermined after a handover this gate can't resolve statically): " +
          "${report.skippedUndeterminedDevice} tool call(s)",
      )
    }
    if (report.skippedNoManifest.isNotEmpty()) {
      // Permanent, non-fatal skips: no trailmap manifest exists for these target strings.
      appendLine("Skipped (no manifest):    ${report.skippedNoManifest}")
    }
    if (report.errors.isNotEmpty()) {
      appendLine("Load/run errors: ${report.errors.size}")
      report.errors.take(10).forEach { appendLine("    ! $it") }
    }

    // Non-fatal accounting: targets skipped for no surface, split by whether they're exempt.
    val fatalMissing = report.fatalMissingSurfaceTargets
    val exemptMissing = report.skippedNoSurface.filterKeys { it !in fatalMissing }
    if (exemptMissing.isNotEmpty()) {
      appendLine("Exempt (no surface, non-fatal): $exemptMissing")
    }
    val exemptTargetFindings = report.findings.size - report.fatalFindings.size
    if (exemptTargetFindings > 0) {
      appendLine("Exempt-target findings (non-fatal): $exemptTargetFindings")
    }

    // Fatal section — the only part that fails the build.
    if (report.fatalMissingSurfaceTargets.isNotEmpty()) {
      appendLine("")
      appendLine("FATAL — non-exempt target(s) with no typed surface to validate against: $fatalMissing")
      appendLine("  Fix: add a generated surface for the target, or add `trail_validation.exempt: \"<reason>\"`")
      appendLine("  to its trailmap.yaml (or the central allow-list for classpath-bundled / no-target trails).")
    }
    val byCode = report.fatalFindings.groupingBy { it.tsCode }.eachCount()
    val trailsWith = report.fatalFindings.map { it.trailRelPath }.distinct().size
    appendLine("")
    appendLine("FATAL type findings: ${report.fatalFindings.size} across $trailsWith trail(s)  ${if (byCode.isNotEmpty()) "— by tsc code: $byCode" else ""}")
    var currentTrail: String? = null
    report.fatalFindings.sortedWith(compareBy({ it.trailRelPath }, { it.stepIndex })).forEach { f ->
      if (f.trailRelPath != currentTrail) {
        appendLine("")
        appendLine("  ${f.trailRelPath}")
        currentTrail = f.trailRelPath
      }
      val short = f.message.substringBefore(". ").take(140)
      val slot = f.classifier?.let { " [${slotLabel(it, f.deviceName)}]" } ?: ""
      appendLine("     · step ${f.stepIndex}$slot \"${singleLine(f.stepLabel).take(48)}\" — tool ${f.toolName}: $short  [${f.tsCode}]")
    }
  }

  private const val DEFAULT_TSC_TIMEOUT_MS: Long = 300_000
}
