package xyz.block.trailblaze.host

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import xyz.block.trailblaze.agent.trail.toJsonArgs
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.createTrailblazeYaml

/**
 * Type-validates `.trail.yaml` recordings against each trailmap's generated typed tool surface
 * (`tools/trailblaze-client.d.ts`) by transpiling every recorded tool call into a throwaway
 * TypeScript file and compiling it with the bundled `tsc`.
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
 * ## Scope / known limitations (this is a report-only shadow gate today)
 *
 * The emitted `trailblaze-client.d.ts` is the FULL, ungated tool surface — every class-backed tool a
 * trailmap resolves is typed there (the `surfaceToScriptedTools` visibility gate has been removed).
 * Validation is therefore bounded not by a visibility gate but by how *faithfully* that surface
 * models a recorded call. Two fidelity gaps keep this report-only rather than a hard-fail gate:
 *
 * - **Filtered args.** Selector-backed tools (`tapOn`, `assertVisibleBySelector`, …) carry a
 *   `TrailblazeNodeSelector` arg that the descriptor builder filters out (it can't lower the
 *   self-referential selector type). A recorded call that passes `selector:` then type-checks
 *   against a selector-less binding and reports a spurious "unexpected property `selector`" — a
 *   false positive on an otherwise-fine recording. (Scripted authors get correct selector typing
 *   from the hand-curated `built-in-tools.ts`; modeling those args on the raw tools is the follow-up
 *   that would let this become a hard-fail gate.)
 * - **Tools absent from the surface.** A recordable tool that no resolved toolset surfaces shows up
 *   as "does not exist." A trail whose `target:` resolves to no filesystem trailmap in the workspace
 *   (e.g. a classpath-bundled target with no writable `tools/` dir) is skipped with a distinct
 *   status.
 *
 * Runs by default on every `trailblaze check`; it is strictly **report-only** (it prints findings
 * and never changes the exit code), so it can't break a build while the residual fidelity gaps are
 * worked off. Set `TRAILBLAZE_DISABLE_TRAIL_RECORDING_VALIDATION=1` to skip the phase entirely.
 */
object TrailTscValidator {

  /** Env var that opts a `check` run OUT of the (report-only) trail-recording validation phase. */
  const val DISABLE_ENV_VAR: String = "TRAILBLAZE_DISABLE_TRAIL_RECORDING_VALIDATION"

  /**
   * Subdirectory under the workspace's `<trails>/.trailblaze/` where per-classpath-trailmap
   * validation surfaces are materialized (`<base>/<trailmapId>/tools/{tsconfig.json,
   * trailblaze-client.d.ts}`). Written by the compile phase (see
   * [PerTrailmapClientDtsEmitter.emitClasspathValidationSurfaces] +
   * [PerTrailmapTsconfigEmitter.emitClasspathValidationTsconfigs]) and discovered by the check
   * phase, which appends each surface dir to the trailmap list handed to [validate] — so a trail
   * whose `target:` is a JAR-bundled trailmap (e.g. `square`) type-checks against a real surface
   * instead of reading as skipped-no-surface. Lives under `.trailblaze/` so it's already
   * gitignored alongside the extracted SDK bundle.
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

  /** A single recorded tool call, flattened to the shape codegen needs. */
  data class RecordedCall(
    val toolName: String,
    /** The flat executor args as a JSON object literal (valid TS object-literal syntax). */
    val argsJson: String,
    val stepIndex: Int,
    val stepLabel: String,
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
  )

  /** Aggregate outcome of validating a workspace's trails. */
  data class Report(
    val trailsDiscovered: Int,
    val trailsValidated: Int,
    val toolCallsChecked: Int,
    val findings: List<Finding>,
    val skippedNoSurface: Map<String, Int>,
    val skippedNoRecording: Int,
    val errors: List<String>,
  )

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
      val label = call.stepLabel.replace('\n', ' ').take(70)
      lines.add("  ${calleeExpr(call.toolName)}(${call.argsJson}); // step ${call.stepIndex}: $label")
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
  data class GenFileMeta(val trailRelPath: String, val table: Map<Int, RecordedCall>)

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
   * Validate every `.trail.yaml` under [trailsRoot] against the typed surfaces of [trailmaps]
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

    val trailFiles = trailsRoot.walkTopDown().filter { it.isFile && it.name.endsWith(".trail.yaml") }.toList()
    for (trailFile in trailFiles) {
      discovered++
      val rel = trailsRoot.parentFile?.toPath()?.relativize(trailFile.toPath())?.toString() ?: trailFile.name
      try {
        val text = trailFile.readText()
        val target = yaml.extractTrailConfig(text)?.target
        val trailmapDir = target?.let { trailmapDirByName[it] }
        if (trailmapDir == null) {
          val key = if (target == null) "<no target:>" else target
          skippedNoSurface[key] = (skippedNoSurface[key] ?: 0) + 1
          continue
        }
        // Strip the full `.trail.yaml` suffix (NOT just `.yaml`) so the classifier derivation sees
        // `ios-iphone`, not `ios-iphone.trail` (which would add a spurious `trail` classifier).
        val stemForClassifiers = trailFile.name.removeSuffix(".trail.yaml")
        val calls = extractRecordedCalls(yaml, text, stemForClassifiers)
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
          .add(Staged(genPath, gen.source, GenFileMeta(rel, gen.table), calls.size))
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

    return Report(
      trailsDiscovered = discovered,
      trailsValidated = validated,
      toolCallsChecked = toolCalls,
      findings = findings,
      skippedNoSurface = skippedNoSurface,
      skippedNoRecording = skippedNoRecording,
      errors = errors,
    )
  }

  // Tool names that are valid JS identifiers can be emitted as `client.tools.<name>`; any other
  // name (legitimately containing `-`/`.`, or a malformed recording) goes through bracket access
  // with an escaped string key — see [calleeExpr].
  private val VALID_TOOL_NAME = Regex("""^[A-Za-z_][A-Za-z0-9_]*$""")

  /**
   * PURE. Derive device classifiers from a per-device trail filename stem (e.g. `ios-iphone` ->
   * [ios, iphone]). Unified (v3) trails use these to lower their closest-wins recordings; v1 trails
   * ignore them. Split on `-`/`.` and lowercased so `iOS-iPhone` and `ios-iphone` agree.
   *
   * Callers must pass the stem WITHOUT the `.trail.yaml` suffix — passing `ios-iphone.trail` would
   * add a spurious `trail` classifier that can change which unified recording is selected.
   */
  internal fun deviceClassifiersFromStem(fileStem: String): List<TrailblazeDeviceClassifier> =
    fileStem.split('-', '.').filter { it.isNotBlank() }.map { TrailblazeDeviceClassifier(it.lowercase()) }

  /**
   * Flatten a trail's recorded tool calls into [RecordedCall]s, covering both shapes that carry
   * executable recordings: per-step recordings under `prompts:` ([TrailYamlItem.PromptsTrailItem])
   * and a top-level `tools:` block ([TrailYamlItem.ToolTrailItem], reported as step 0). A trail with
   * no recorded calls yields an empty list (caller treats it as skipped-no-recording).
   */
  private fun extractRecordedCalls(
    yaml: xyz.block.trailblaze.yaml.TrailblazeYaml,
    text: String,
    fileStem: String,
  ): List<RecordedCall> {
    val items = yaml.decodeTrail(text, deviceClassifiers = deviceClassifiersFromStem(fileStem))
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

  private fun TrailblazeToolYamlWrapper.toRecordedCall(stepIndex: Int, label: String): RecordedCall =
    RecordedCall(
      toolName = name,
      argsJson = toJsonArgs().toString(),
      stepIndex = stepIndex,
      stepLabel = label,
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

  /** Render [report] as a human-readable, YAML-keyed summary. */
  fun renderReport(report: Report): String = buildString {
    appendLine("── trail recording type-validation (report-only) ──────────────────")
    appendLine("Trails discovered:        ${report.trailsDiscovered}")
    appendLine("Trails validated:         ${report.trailsValidated}")
    appendLine("Tool calls type-checked:  ${report.toolCallsChecked}")
    if (report.skippedNoSurface.isNotEmpty()) {
      appendLine("Skipped (no typed surface for target): ${report.skippedNoSurface}")
    }
    if (report.skippedNoRecording > 0) appendLine("Skipped (no recording):   ${report.skippedNoRecording}")
    if (report.errors.isNotEmpty()) {
      appendLine("Load/run errors: ${report.errors.size}")
      report.errors.take(10).forEach { appendLine("    ! $it") }
    }
    val byCode = report.findings.groupingBy { it.tsCode }.eachCount()
    val trailsWith = report.findings.map { it.trailRelPath }.distinct().size
    appendLine("")
    appendLine("Type findings: ${report.findings.size} across $trailsWith trail(s)  ${if (byCode.isNotEmpty()) "— by tsc code: $byCode" else ""}")
    var currentTrail: String? = null
    report.findings.sortedWith(compareBy({ it.trailRelPath }, { it.stepIndex })).forEach { f ->
      if (f.trailRelPath != currentTrail) {
        appendLine("")
        appendLine("  ${f.trailRelPath}")
        currentTrail = f.trailRelPath
      }
      val short = f.message.substringBefore(". ").take(140)
      appendLine("     · step ${f.stepIndex} \"${f.stepLabel.take(48)}\" — tool ${f.toolName}: $short  [${f.tsCode}]")
    }
  }

  private const val DEFAULT_TSC_TIMEOUT_MS: Long = 300_000
}
