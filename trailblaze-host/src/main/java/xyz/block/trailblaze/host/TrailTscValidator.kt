package xyz.block.trailblaze.host

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import xyz.block.trailblaze.agent.trail.toJsonArgs
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.TrailYamlItem
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
 * new uncovered target from silently slipping in. Two escape hatches keep it honest while the
 * corpus is brought to zero:
 *
 * - **Per-target exemption** — a target's `trail_validation.exempt: "<reason>"` in its
 *   `trailmap.yaml` (see [xyz.block.trailblaze.config.project.TrailValidationConfig]) opts that
 *   target out: its findings and its missing-surface status are reported but non-fatal. This is the
 *   durable, co-located mechanism, honored via [validate]'s `exemptTargets`. Targets whose manifest
 *   the validator can't reach yet (classpath-bundled targets, and the no-`target:` trails) are
 *   exempted through the same map from a central, explicitly-transitional allow-list in `CheckCommand`.
 * - **Framework-surface allow-list** — [DEFAULT_ALLOWED_UNMODELED_TOOLS] downgrades findings for
 *   recordable tools the generated surface doesn't model yet (a framework gap, not a per-target
 *   defect).
 *
 * The emitted `trailblaze-client.d.ts` is the FULL, ungated tool surface — every class-backed tool a
 * trailmap resolves is typed there. Validation is bounded by how *faithfully* that surface models a
 * recorded call; the two allow-lists above absorb the residual fidelity gaps (filtered selector
 * args on selector-backed tools; recordable tools no toolset surfaces) until the emitter follow-ups
 * close them, at which point the corresponding exemptions shrink away.
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

  /**
   * Recordable tools the generated typed surface doesn't model yet, so a recorded call to one
   * reads as a spurious "does not exist" finding rather than a real per-target defect. Findings
   * for these tool names are downgraded to non-fatal by default (still reported).
   *
   * This is the framework-surface allow-list the phase carries centrally (by tool name, not by
   * target). It shrinks as the emitter learns to surface these tools:
   *  - `eraseText` / `pressBack` — YAML-defined recordable tools; `built-in-tools.ts` can only
   *    curate `@TrailblazeToolClass`-backed tools (enforced by `BuiltInToolsBindingDriftTest`),
   *    so a YAML-defined tool can't be added there until the emitter surfaces YAML-defined tools.
   */
  val DEFAULT_ALLOWED_UNMODELED_TOOLS: Set<String> = setOf("eraseText", "pressBack")

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
    /** The trail's `target:` value (null for a no-`target:` trail). Used to classify exemptions. */
    val target: String? = null,
    /** The unified-format classifier slot the offending call sits in; null for v1 trails. */
    val classifier: String? = null,
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
    val skippedNoRecording: Int,
    val errors: List<String>,
    /**
     * Findings that FAIL the build: on a non-exempt target and not attributable to a tool on the
     * framework-surface allow-list. Empty when the gate is satisfied. A subset of [findings].
     */
    val fatalFindings: List<Finding> = emptyList(),
    /**
     * Non-exempt targets (by `target:` value; [NO_TARGET_KEY] for the no-`target:` case) that
     * couldn't be validated because no reachable trailmap in the workspace carries a generated
     * typed surface for them — a build failure, because a new uncovered target must not slip in
     * silently. A subset of [skippedNoSurface].
     */
    val fatalMissingSurfaceTargets: Map<String, Int> = emptyMap(),
    /**
     * Findings suppressed because their tool is on the framework-surface allow-list (a recordable
     * tool the generated surface doesn't model yet). Reported for visibility; never fatal.
     */
    val allowlistedToolFindings: List<Finding> = emptyList(),
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
      val slot = call.classifier?.let { " [${singleLine(it)}]" } ?: ""
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
    /** The source trail's `target:` value (null for a no-`target:` trail); flows onto each [Finding]. */
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
     * Tool names whose findings are downgraded to non-fatal — recordable tools the generated
     * typed surface doesn't model yet (framework-surface gaps, not per-target defects).
     */
    allowedUnmodeledTools: Set<String> = emptySet(),
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
    var skippedNoRecording = 0
    var discovered = 0

    // A trail staged for validation: its gen-file source + target path + the remap metadata. Gen
    // file CONTENT is held in memory here and only written to disk inside the per-trailmap
    // try/finally below, so an exception during discovery can never orphan a `.trail.gen.ts`.
    data class Staged(val genPath: Path, val source: String, val meta: GenFileMeta, val callCount: Int)
    val stagedByTrailmap = mutableMapOf<Path, MutableList<Staged>>()

    val trailFiles = trailsRoot.walkTopDown().filter { it.isFile && isTrailFile(it.name) }.toList()
    for (trailFile in trailFiles) {
      discovered++
      val rel = trailsRoot.parentFile?.toPath()?.relativize(trailFile.toPath())?.toString() ?: trailFile.name
      try {
        val text = trailFile.readText()
        // One version-aware parse per trail; the format is detected from CONTENT, never the
        // filename (a unified trail may be a bare `trail.yaml` or carry any name).
        val doc = yaml.decodeTrailDocument(text)
        val target = when (doc) {
          is TrailDocument.V1 -> yaml.extractTrailConfig(doc.items)?.target
          is TrailDocument.Unified -> doc.trail.config.target
        }
        val trailmapDir = target?.let { trailmapDirByName[it] }
        if (trailmapDir == null) {
          val key = target ?: NO_TARGET_KEY
          skippedNoSurface[key] = (skippedNoSurface[key] ?: 0) + 1
          continue
        }
        val calls = extractRecordedCalls(doc)
        if (calls.isEmpty()) {
          skippedNoRecording++
          continue
        }
        val gen = generateGenFile(rel, calls)
        // Unique gen-file name per trail: a sanitized stem (readable) plus a stable hash of the
        // full relative path, so two distinct trails that sanitize to the same stem can't collide
        // (the gen files share one `tools/` dir and are keyed by basename in remap).
        val stem = rel.replace(Regex("[^A-Za-z0-9]"), "_")
        val unique = Integer.toHexString(rel.hashCode())
        val genPath = trailmapDir.resolve("tools").resolve("${stem}_$unique.trail.gen.ts")
        stagedByTrailmap.getOrPut(trailmapDir) { mutableListOf() }
          .add(Staged(genPath, gen.source, GenFileMeta(rel, gen.table, target), calls.size))
      } catch (e: Exception) {
        errors.add("$rel: ${e::class.simpleName}: ${e.message}")
      }
    }

    val findings = mutableListOf<Finding>()
    var validated = 0
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
        validated += staged.size
        toolCalls += staged.sumOf { it.callCount }
      } catch (e: Exception) {
        errors.add("${trailmapDir.fileName}: tsc run failed: ${e::class.simpleName}: ${e.message}")
      } finally {
        staged.forEach { runCatching { Files.deleteIfExists(it.genPath) } }
      }
    }

    val classification =
      classify(findings, skippedNoSurface, exemptTargets, allowedUnmodeledTools, failOnMissingSurface)

    return Report(
      trailsDiscovered = discovered,
      trailsValidated = validated,
      toolCallsChecked = toolCalls,
      findings = findings,
      skippedNoSurface = skippedNoSurface,
      skippedNoRecording = skippedNoRecording,
      errors = errors,
      fatalFindings = classification.fatalFindings,
      fatalMissingSurfaceTargets = classification.fatalMissingSurfaceTargets,
      allowlistedToolFindings = classification.allowlistedToolFindings,
    )
  }

  /** The fatal/non-fatal split of a validation pass — the output of [classify]. */
  data class Classification(
    val fatalFindings: List<Finding>,
    val allowlistedToolFindings: List<Finding>,
    val fatalMissingSurfaceTargets: Map<String, Int>,
  )

  /**
   * PURE. Apply the exemption rules to split findings and no-surface targets into fatal vs
   * non-fatal buckets. A finding is fatal unless its tool is on the framework-surface allow-list or
   * its target is exempt; a no-surface target is fatal unless it's exempt — and only when
   * [failOnMissingSurface] is set (see below).
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
    allowedUnmodeledTools: Set<String>,
    failOnMissingSurface: Boolean = true,
  ): Classification {
    val fatalFindings = mutableListOf<Finding>()
    val allowlistedToolFindings = mutableListOf<Finding>()
    for (f in findings) {
      when {
        // Allow-listed tools are non-fatal on ANY target — checked first so the allow-listed tally
        // stays accurate even for a finding that also sits on an exempt target (otherwise it would
        // be silently absorbed into the generic exempt-target count in the report).
        f.toolName in allowedUnmodeledTools -> allowlistedToolFindings.add(f)
        f.target != null && exemptTargets.containsKey(f.target) -> Unit // exempt: reported, non-fatal
        else -> fatalFindings.add(f)
      }
    }
    // A non-exempt target we couldn't validate at all (no generated surface) is a build failure ON AN
    // ALL-WORKSPACE PASS — it prevents a new uncovered target from slipping in unnoticed. On a scoped
    // pass we can't draw that conclusion (only the selected surface was loaded), so it's non-fatal.
    val fatalMissingSurfaceTargets =
      if (failOnMissingSurface) skippedNoSurface.filterKeys { it !in exemptTargets } else emptyMap()
    return Classification(fatalFindings, allowlistedToolFindings, fatalMissingSurfaceTargets)
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

  /**
   * PURE. Flatten a parsed trail's recorded tool calls into [RecordedCall]s, format-aware:
   *
   *  - **v1** — per-step recordings under `prompts:` ([TrailYamlItem.PromptsTrailItem]) and a
   *    top-level `tools:` block ([TrailYamlItem.ToolTrailItem], reported as step 0).
   *  - **Unified** — EVERY per-step `recording:` classifier slot (`android:`, `ios-iphone:`, …)
   *    contributes its tool list, each call tagged with its classifier for attribution. No
   *    closest-wins lowering here: validation checks all slots, not one device's resolution.
   *    The trailhead's per-classifier bootstrap tools are validated the same way as step 0.
   *
   * A trail with no recorded calls yields an empty list (caller treats it as skipped-no-recording).
   */
  internal fun extractRecordedCalls(doc: TrailDocument): List<RecordedCall> = when (doc) {
    is TrailDocument.V1 -> extractV1RecordedCalls(doc.items)
    is TrailDocument.Unified -> extractUnifiedRecordedCalls(doc.trail)
  }

  private fun extractV1RecordedCalls(items: List<TrailYamlItem>): List<RecordedCall> {
    val calls = mutableListOf<RecordedCall>()
    var stepIndex = 0
    items.forEach { item ->
      when (item) {
        is TrailYamlItem.PromptsTrailItem -> item.promptSteps.forEach { step ->
          stepIndex++ // one index per step; multiple tools in a step share it (matches how a human reads "step N")
          val label = step.prompt
          step.recording?.tools?.forEach { wrapper -> calls.add(wrapper.toRecordedCall(stepIndex, label)) }
        }
        is TrailYamlItem.ToolTrailItem -> item.tools.forEach { wrapper ->
          // Top-level `tools:` blocks have no prompt step; key them to step 0 with a generic label.
          calls.add(wrapper.toRecordedCall(stepIndex = 0, label = "tools block"))
        }
        is TrailYamlItem.TrailheadTrailItem -> item.trailhead.tools.forEach { wrapper ->
          // The trailhead is the deterministic step 0; type-check its bootstrap tool calls too.
          calls.add(wrapper.toRecordedCall(stepIndex = 0, label = item.trailhead.step ?: "trailhead"))
        }
        is TrailYamlItem.ConfigTrailItem -> Unit
      }
    }
    return calls
  }

  private fun extractUnifiedRecordedCalls(trail: UnifiedTrail): List<RecordedCall> {
    val calls = mutableListOf<RecordedCall>()
    // The trailhead is the deterministic step 0; type-check each classifier's bootstrap tool.
    trail.trailhead?.let { trailhead ->
      trailhead.recordings.forEach { (classifier, tools) ->
        tools.forEach { calls.add(it.toRecordedCall(stepIndex = 0, label = trailhead.step, classifier = classifier)) }
      }
    }
    trail.trail.forEachIndexed { index, step ->
      step.recordings.forEach { (classifier, tools) ->
        tools.forEach { calls.add(it.toRecordedCall(stepIndex = index + 1, label = step.step, classifier = classifier)) }
      }
    }
    return calls
  }

  private fun TrailblazeToolYamlWrapper.toRecordedCall(
    stepIndex: Int,
    label: String,
    classifier: String? = null,
  ): RecordedCall =
    RecordedCall(
      toolName = name,
      argsJson = toJsonArgs().toString(),
      stepIndex = stepIndex,
      stepLabel = label,
      classifier = classifier,
    )

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
    if (report.allowlistedToolFindings.isNotEmpty()) {
      val byTool = report.allowlistedToolFindings.groupingBy { it.toolName }.eachCount()
      appendLine("Allow-listed unmodeled-tool findings (non-fatal): $byTool")
    }
    val exemptTargetFindings = report.findings.size - report.fatalFindings.size - report.allowlistedToolFindings.size
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
      val slot = f.classifier?.let { " [${singleLine(it)}]" } ?: ""
      appendLine("     · step ${f.stepIndex}$slot \"${singleLine(f.stepLabel).take(48)}\" — tool ${f.toolName}: $short  [${f.tsCode}]")
    }
  }

  private const val DEFAULT_TSC_TIMEOUT_MS: Long = 300_000
}
