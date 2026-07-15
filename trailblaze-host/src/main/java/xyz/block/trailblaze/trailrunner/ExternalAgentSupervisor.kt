package xyz.block.trailblaze.trailrunner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.cli.CliConfigHelper
import xyz.block.trailblaze.host.networkcapture.AndroidNetworkCaptureRegistry
import xyz.block.trailblaze.scripting.callback.JsScriptingCallbackBaseUrl
import xyz.block.trailblaze.util.Console
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// Widened from `private` to `internal`: ExternalAgentRoutes.kt's SSE loop polls on this same
// interval, and Kotlin `private` on a top-level declaration is file-scoped, not package-scoped.
internal const val EXTERNAL_AGENT_POLL_INTERVAL_MS = 250L

// Bounded per-run retention: a long session with large tool outputs must not grow the daemon
// heap without limit. Oldest events are dropped past the cap (the run keeps its true total in
// `eventCount` via the seq counter); oversized payload fields are truncated at emit time.
private const val EXTERNAL_AGENT_MAX_EVENTS = 2000
private const val EXTERNAL_AGENT_MAX_FINISHED_RUNS = 50
private const val EXTERNAL_AGENT_MAX_FIELD_CHARS = 32_000
private const val DEMONSTRATION_PREAMBLE_MAX_ACTIONS = 40

// A generation run that ends a turn without delivering a trail_output is auto-continued in-process,
// up to this many times, before the daemon gives up and leaves the run for the human.
internal const val DEMO_GENERATE_MAX_TURNS = 3

// The in-process nudge appended when a generation run exits without a terminal trail_output.
private const val DEMO_GENERATE_CONTINUE_PROMPT =
  "Continue: you have not delivered a trail_output yet. Follow the trailblaze-author skill: finish " +
    "authoring, verify by running the trail with the trail MCP tool (action=RUN), and emit the " +
    "trail_output line with an honest status."

// Inactivity watchdog: a child CLI that produces no output for this long is presumed wedged and
// killed (measured from the last emitted event, so a chatty long run is never cut short —
// same watchdog-not-wall-clock policy as TRAILBLAZE_RUN_POLL_TIMEOUT_MS).
private const val EXTERNAL_AGENT_IDLE_TIMEOUT_ENV = "TRAILRUNNER_EXTERNAL_AGENT_IDLE_TIMEOUT_MS"
private const val EXTERNAL_AGENT_IDLE_TIMEOUT_DEFAULT_MS = 600_000L

private val TRAILRUNNER_UI_CONTRACT = """
You are running as an external coding-agent CLI supervised by Trail Runner, helping a human author
Trailblaze trails: automated UI tests written as short, observable steps against a real device.
Trail Runner does not execute your tools; your own CLI decides which tools/MCP servers to call.

How to help, whatever the task:
- Interview before you build. If the goal is even slightly underspecified, ask 2-3 pointed questions
  and wait for answers before acting. The one that matters most: what must be observably TRUE for
  this to pass — a specific value, screen, or confirmation, not "it works". Also worth asking: where
  the flow starts and what account/data/state it assumes; the app and platform; variations and edge
  cases; what to leave alone. Ask before you guess.
- Do not open by probing the environment. Skip status/version/"where am I" commands; they burn the
  human's attention and answer nothing they asked. Start by talking to them.
- Ground every step on the real device. Look at the actual screen before proposing or recording a
  step; never invent UI you have not seen. If something on screen is ambiguous, ask.
- Narrate briefly. The human is following along on the device screen and an event log of your tool
  calls and their taps.
- Never end your turn promising to wait, sleep, or "retry in N seconds". Nothing resumes you when
  your turn ends - the human sees a finished conversation and waits forever. Either keep working
  inside the turn until the retry succeeds, or end by telling the human plainly what is blocked and
  what to do (e.g. "the device driver isn't up yet - reply 'retry' when you want me to try again").

When the task is to compose or build a trail, run it as a guided session, a stage at a time:
1. Pin the intent, and ask which trailhead to start from - a trailhead is the named entry state the
   trail begins in. Ask this with the ask_user command and params.source "trailheads": Trail Runner
   fills in this workspace's trailheads as clickable options, so lead with that question and let the
   human pick one.
2. Scaffold the trail as soon as the trailhead is chosen: agree where it lives (see "Your trail
   directory" below), write the blaze.yaml with the config and that trailhead as the entry, and
   emit trail_output so the trail is visible from the start; you append each step to this same
   file as you go. Then connect the device: when the UI context includes a deviceId, that is the
   one the human picked - connect to it directly, do not list devices first. Start a capture
   session so the screen, the view hierarchy, and the device event and log streams are recorded
   while the human works.
3. Ask the human to turn on Record and demonstrate the flow. Everything they demonstrate arrives
   with their next message: each action's recorded step YAML plus evidence files on disk (a
   before/after screenshot and view hierarchy per action). Read the hierarchy files to ground
   selectors and assertions, then transcribe each action into a do/verify step.
4. Assemble the trail and refine the steps and assertions with the human.
5. Replay from the trailhead to verify the outcome actually happened. Replay with the trail MCP
   tool (action=RUN) - it executes the recorded steps without AI. Then hand the final proof to the
   human: emit open_trail for the saved trail and invite them to press Run there - the app records
   those runs so they can audit a green result themselves instead of taking your word for it.
   You MAY run the trailblaze CLI through your shell tool - approval prompts now surface in Trail
   Runner for the human to approve, so the command no longer dead-ends. A pending approval pauses the
   tool call until the human decides, so do not treat a slow tool call as a hang. Still, prefer the
   trailblaze MCP tools when an equivalent exists.
Do your part of a stage, then wait for them.

Your trail directory:
- Each composing conversation owns ONE trail directory at its FINAL home in the library: a folder
  like <area>/<kebab-slug>/ under the trails root. There is no staging area - the folder you write
  is the trail the human keeps. Suggest a short path from the objective (mirror how neighboring
  trails are organized), confirm it with the human alongside the trailhead question, create the
  directory on first write, and keep every file for this trail inside it. Do not write trail
  content anywhere else.
- The files you own there:
  - blaze.yaml - the spec plus prompt steps; the source of truth. Read an existing trail for the
    schema before you first write it.
  - intent.md — optional: the plain-language intent and what "pass" means, once you have captured it.
  - <platform>.trail.yaml — optional deterministic/recorded variants.
- To modify the trail, read the current file, change it, and write it back whole. Prefer small,
  reviewable edits over rewrites.

Giving your output — the ONE clear way to hand back a result:
- Whenever you create or meaningfully change the trail, declare it with exactly one standalone line:
  TRAILRUNNER_UI {"version":1,"action":"trail_output","trailId":"0/<area>/<slug>","message":"<one-line summary of what you produced or changed>","params":{"status":"draft","files":"blaze.yaml,android.trail.yaml"}}
- status is "draft" while you are still working and "ready" when you believe the trail is complete.
  files is a comma-separated list of the files in the folder you touched.
- This is how the human sees your result: the trail opens in the details panel and your summary is
  shown as an output card. Emit it on every meaningful change, not only at the very end.

When changing what else the human should see in Trail Runner would help, emit one standalone line:
TRAILRUNNER_UI {"version":1,"action":"navigate","route":"active","params":{"sel":"<session id>"}}

Supported actions:
- navigate: route is one of home, prompt, create, interact, trails, tools, toolsets, waypoints, shortcuts, trailheads, active, completed, runs, settings, agents. params is optional.
- open_session: set sessionId, and optionally params.view to active, completed, or runs.
- open_trail: set trailId.
- trail_output: set trailId and message; optional params.status and params.files. Your primary result channel - declares a trail you produced or changed without navigating away.
- ask_user: set message (the question) to ask the human a question with clickable answers. Give the choices in params.options as a pipe-separated list, OR set params.source to "trailheads" to have Trail Runner fill the workspace's trailheads as the options. Clicking an answer replies to you; the human can also type instead. Use it for the starting-point question and any either/or decision. Clicking an option ONLY sends its text back as the human's reply - it cannot grant permissions, run commands, or change any setting, so never offer an option that implies the system will perform an action (e.g. "Approve X access").
- show_message: set message and optional severity info, success, warning, or error.
- focus_external_agent: set params.runId to the external-agent run to focus.

Emit UI commands only when they are useful. Keep the command JSON compact and valid.
All params values must be strings.
""".trimIndent()

internal object ExternalAgentSupervisor {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  // Widened from `private` to `internal` (Task B testability seam): ExternalAgentSupervisorTest
  // seeds a MutableExternalAgentRun directly to exercise emitHumanAction without spawning a real
  // vendor CLI process. Compile-time visibility widening only — zero runtime behavior change.
  internal val runs = ConcurrentHashMap<String, MutableExternalAgentRun>()

  // The provider registry. Adding a provider in a later PR means: a new ExternalAgentType enum
  // value, one entry here (display + executable + setup hints), a commandFor branch in
  // externalAgentCommand, and a stream parser in parseExternalAgentLine. The UI (picker, setup
  // page) renders whatever this list serves — no client change needed.
  fun supportedAgents(): List<ExternalAgentOptionDto> = listOf(
    option(
      type = ExternalAgentType.CLAUDE,
      display = "Claude Code",
      executable = "claude",
      installHint = "npm install -g @anthropic-ai/claude-code",
      authHint = "Run `claude` once and sign in with your Anthropic account (or set ANTHROPIC_API_KEY).",
      modelsHint = "Model aliases like sonnet, opus, or haiku can be set per session; the CLI's default is used otherwise.",
      docsUrl = "https://docs.anthropic.com/en/docs/claude-code/overview",
      // Version-stable vendor aliases, so the picker doesn't rot as point releases ship.
      models = listOf(
        ExternalAgentModelOptionDto(id = "", display = "Default"),
        ExternalAgentModelOptionDto(id = "opus", display = "Opus"),
        ExternalAgentModelOptionDto(id = "sonnet", display = "Sonnet"),
        ExternalAgentModelOptionDto(id = "haiku", display = "Haiku"),
      ),
    ),
    option(
      type = ExternalAgentType.CODEX,
      display = "Codex CLI",
      executable = "codex",
      installHint = "npm install -g @openai/codex",
      authHint = "Run `codex` once and sign in with your OpenAI account (or set OPENAI_API_KEY).",
      modelsHint = "Model can be set per session; the CLI's configured default is used otherwise.",
      docsUrl = "https://github.com/openai/codex",
      models = listOf(
        ExternalAgentModelOptionDto(id = "", display = "Default"),
        ExternalAgentModelOptionDto(id = "gpt-5-codex", display = "GPT-5 Codex"),
        ExternalAgentModelOptionDto(id = "gpt-5", display = "GPT-5"),
      ),
    ),
  )

  fun runs(): List<ExternalAgentRunDto> = runs.values.map { it.dto() }.sortedByDescending { it.startedAtMs }

  // The run map lives for the daemon's lifetime, so finished runs (and their event lists) must
  // not accumulate without bound. Oldest finished runs are dropped past the cap when a new run
  // starts; in-flight runs are never pruned.
  private fun pruneFinishedRuns() {
    runsToEvict(runs.values.toList()).forEach { run ->
      // A demo run that is being evicted (its phase is done) may still have network capture
      // running; stop it before the tape dir goes away. Idempotent, so a no-op after finish.
      stopDemoCapture(run)
      // The tape dir is only reachable through the run map (the evidence route resolves the
      // path via evidenceDir), so an evicted run's evidence files are dead weight - delete them.
      // A demo run's draft dir lives in the workspace (outside agent-runs) and is durable working
      // data the user keeps, so it is NEVER deleted here.
      val tape = if (run.demo != null) null else run.artifactsRoot?.let { File(it, "agent-runs/${run.id}/tape") }
      runs.remove(run.id)
      tape?.let { runCatching { it.deleteRecursively() } }
    }
  }

  /**
   * Which finished runs are eligible for eviction, oldest-first past the retention cap. Pure over
   * the input list (no map mutation) so the selection is unit-testable. An ACTIVE demonstration
   * (phase positioning or recording) is exempt: deleting its tape dir mid-flight would drop the
   * bundle the human is still building. A done demo is evictable like any other finished run.
   */
  internal fun runsToEvict(all: List<MutableExternalAgentRun>): List<MutableExternalAgentRun> =
    all
      .filter { it.status != ExternalAgentSessionStatus.RUNNING }
      .filterNot { isActiveDemo(it) }
      .sortedByDescending { it.startedAtMs }
      .drop(EXTERNAL_AGENT_MAX_FINISHED_RUNS)

  /** True while a demonstration is still being positioned or recorded (must not be evicted). */
  internal fun isActiveDemo(run: MutableExternalAgentRun): Boolean =
    when (run.demo?.phase) {
      DemoPhase.POSITIONING, DemoPhase.RECORDING -> true
      else -> false
    }

  fun run(id: String): ExternalAgentRunDto? = runs[id]?.dto()

  fun events(id: String): List<ExternalAgentEventDto>? = runs[id]?.eventsSnapshot()

  fun cancel(id: String): Boolean {
    val run = runs[id] ?: return false
    run.cancelRequested = true
    run.process?.let { process ->
      run.emit(
        kind = ExternalAgentEventKind.LIFECYCLE,
        status = ExternalAgentSessionStatus.CANCELLED,
        title = "Cancellation requested",
        text = "Stopping ${run.agentType.displayName()}",
      )
      killProcessTree(process)
    }
    if (run.status == ExternalAgentSessionStatus.RUNNING) {
      run.finish(ExternalAgentSessionStatus.CANCELLED, exitCode = null, error = null)
    }
    // A cancelled run must not leave a permission request suspended on a human decision.
    run.permissions.failAllPending("The run ended before this was approved").forEach { emitPermissionDecision(run, it) }
    // A cancelled demonstration should not leave its network capture running.
    run.demo?.let { stopDemoCapture(run) }
    return true
  }

  /**
   * A spawned CLI is asking permission to run a tool it can't auto-approve (routed here by the MCP
   * proxy's approval_prompt interception). Auto-resolves when the run has auto-approve on or the tool
   * was previously allow_always'd; otherwise records a pending request, emits a PERMISSION_REQUEST
   * event, and suspends until the human decides (or the run ends, which denies it). Unknown run ->
   * deny.
   */
  suspend fun requestPermission(
    runId: String,
    toolName: String,
    inputJson: String?,
    toolUseId: String?,
  ): PermissionDecision {
    val run = runs[runId] ?: return PermissionDecision.Deny("unknown run: $runId")
    val perms = run.permissions
    if (perms.isPreApproved(toolName)) {
      run.emit(
        kind = ExternalAgentEventKind.PERMISSION_DECISION,
        title = toolName,
        text = if (perms.autoApprove) "Auto-approved (auto-approve is on)" else "Auto-approved (always allow this tool)",
      )
      return PermissionDecision.Allow(inputJson)
    }
    val request = perms.register(toolName, inputJson, toolUseId)
    run.emit(
      kind = ExternalAgentEventKind.PERMISSION_REQUEST,
      title = toolName,
      text = permissionInputPreview(inputJson),
    )
    return request.deferred.await()
  }

  /** Records a human's decision on a pending permission request. False when the run/request is unknown. */
  fun decidePermission(runId: String, requestId: String, decision: String): Boolean {
    val run = runs[runId] ?: return false
    val resolved = run.permissions.resolve(requestId, decision) ?: return false
    emitPermissionDecision(run, resolved)
    return true
  }

  /** Turns per-run auto-approve on/off. Enabling immediately allows everything currently pending. */
  fun setAutoApprove(runId: String, enabled: Boolean): Boolean {
    val run = runs[runId] ?: return false
    val autoResolved = run.permissions.setAutoApprove(enabled)
    run.emit(
      kind = ExternalAgentEventKind.LIFECYCLE,
      title = if (enabled) "Auto-approve enabled" else "Auto-approve disabled",
      text = if (enabled && autoResolved.isNotEmpty()) "Allowed ${autoResolved.size} pending request(s)" else null,
    )
    autoResolved.forEach { emitPermissionDecision(run, it) }
    return true
  }

  private fun emitPermissionDecision(run: MutableExternalAgentRun, resolved: ResolvedPermission) {
    run.emit(
      kind = ExternalAgentEventKind.PERMISSION_DECISION,
      title = resolved.request.toolName,
      text = when (resolved.outcome) {
        PermissionOutcome.ALLOW -> "Allowed"
        PermissionOutcome.ALLOW_ALWAYS -> "Allowed (always allow this tool)"
        PermissionOutcome.DENY -> "Denied"
        PermissionOutcome.AUTO_ALLOW -> "Auto-approved"
        PermissionOutcome.RUN_ENDED -> "The run ended before this was approved"
      },
    )
  }

  private fun permissionInputPreview(inputJson: String?): String? =
    inputJson?.takeIf { it.isNotBlank() }?.let { if (it.length <= 200) it else it.take(200) + "…" }

  /**
   * The device the run's UI context was bound to at start, if any. Killing the child CLI alone
   * does NOT stop a device operation the agent already dispatched - MCP tools execute in the
   * daemon, so the cancel route also has to cancel the device session (the caller owns that;
   * this map lookup is all the supervisor knows about devices).
   */
  fun deviceFor(id: String): TrailblazeDeviceId? =
    runs[id]?.let { it.demo?.device ?: it.request.uiContext?.deviceId }

  /**
   * Whether stopping [id] should also cancel its device's session. True only while the run has a
   * tool call in flight - the window where daemon-side device work can be holding the device. An
   * idle conversation's device may since have been acquired by an unrelated run, and cancelling
   * blindly would kill that run's session. Codex runs always answer true: their MCP tool items
   * don't parse into TOOL_CALL/TOOL_RESULT events, so an in-flight device operation is invisible
   * and skipping the cancel would leave the device wedged (the dead-end the cancel exists to fix).
   */
  fun mayHoldDeviceWork(id: String): Boolean {
    val run = runs[id] ?: return false
    if (run.agentType == ExternalAgentType.CODEX) return true
    return run.hasOpenToolCalls()
  }

  /** Appends a lifecycle note to a run's transcript through the normal seq/retention/SSE path. */
  fun emitLifecycle(id: String, title: String, text: String? = null): Boolean {
    val run = runs[id] ?: return false
    run.emit(kind = ExternalAgentEventKind.LIFECYCLE, title = title, text = text)
    return true
  }

  /**
   * Kill the child AND its descendants. `Process.destroy()` signals one PID: the vendor CLI's
   * own children (the `trailblaze mcp` stdio proxy, Bash-tool shells) would be orphaned - and the
   * previous `destroy(); if (isAlive) destroyForcibly()` was an effectively-unconditional instant
   * SIGKILL (a process is virtually always still alive microseconds after an async SIGTERM),
   * denying the CLI any chance to reap its own children. SIGTERM first with a 2s grace, then
   * SIGKILL stragglers; the descendant snapshot is taken BEFORE killing the root so it can't
   * empty itself.
   */
  private fun killProcessTree(process: Process) {
    val descendants = runCatching { process.toHandle().descendants().toList() }.getOrDefault(emptyList())
    process.destroy()
    descendants.forEach { runCatching { it.destroy() } }
    val exited = runCatching { process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false)
    if (!exited) runCatching { process.destroyForcibly() }
    descendants.forEach { if (it.isAlive) runCatching { it.destroyForcibly() } }
  }

  /**
   * Mirrors a human-driven action (e.g. a recorded gesture from the interactive Record feature)
   * into an external-agent conversation as a [ExternalAgentEventKind.HUMAN_ACTION] event. Goes
   * through the same [MutableExternalAgentRun.emit] path as every other event, so seq assignment,
   * retention capping, and SSE flush all apply identically. Works regardless of run status.
   */
  fun emitHumanAction(runId: String, title: String, input: JsonElement?, output: JsonElement?): Boolean {
    val run = runs[runId] ?: return false
    run.emit(
      kind = ExternalAgentEventKind.HUMAN_ACTION,
      title = title,
      input = input,
      output = output,
    )
    return true
  }

  /**
   * Where a run's demonstration evidence lands: the per-action before/after screenshots and view
   * hierarchy dumps captured by the record gesture route. For a DEMO run this is the CURRENT
   * platform's bundle dir (`<draftDir>/demos/<platformKey>/`) - the single seam every demo
   * write/read/serve path (manifest, actions.ndjson, start-state, evidence frames, network capture,
   * the evidence-serving route) follows, so switching platforms via add-platform reroutes them all
   * at once. A regular agent run keeps its evidence under the artifacts root. Null when the run
   * doesn't exist, or has neither a demo draft dir nor an artifacts root (e.g. a test-seeded run).
   */
  fun evidenceDir(runId: String): File? {
    val run = runs[runId] ?: return null
    run.demo?.let { demo ->
      val draftDir = demo.draftDir ?: return null
      return File(draftDir, "demos/${demo.platformKey}")
    }
    val root = run.artifactsRoot ?: return null
    return File(root, "agent-runs/${run.id}/tape")
  }

  /** Monotonic ordinal for a run's demonstrated actions; names that action's evidence files. */
  fun nextTapeSeq(runId: String): Int? = runs[runId]?.tapeSeq?.incrementAndGet()

  // Deferred evidence work (settle + after-frame capture) finishes in data-dependent time, so
  // launching it unchained would emit HUMAN_ACTION events in completion order, not gesture order -
  // and the agent would transcribe demonstrated steps in the wrong sequence. Each run keeps a
  // chain: a job waits for its predecessor before emitting, and reply() drains the tail so the
  // action the human just performed rides THIS reply's preamble, not the next one.
  private val evidenceTails = ConcurrentHashMap<String, Job>()

  /** Runs [block] after the run's previous evidence job, so evidence events land in gesture order. */
  fun enqueueEvidence(runId: String, scope: CoroutineScope, block: suspend () -> Unit) {
    synchronized(evidenceTails) {
      val prev = evidenceTails[runId]
      val job = scope.launch {
        prev?.join()
        runCatching { block() }
      }
      evidenceTails[runId] = job
      job.invokeOnCompletion { evidenceTails.remove(runId, job) }
    }
  }

  /** Bounded wait for the run's in-flight evidence jobs; returns early when there are none. */
  suspend fun awaitPendingEvidence(runId: String, timeoutMs: Long = 8_000L) {
    withTimeoutOrNull(timeoutMs) {
      while (true) {
        val tail = evidenceTails[runId] ?: break
        tail.join()
        // The completion handler removes the tail; make progress even when join resumes first.
        evidenceTails.remove(runId, tail)
      }
    }
  }

  fun start(
    request: ExternalAgentRunRequest,
    fallbackCwd: File,
    artifactsRoot: File? = null,
  ): Result<ExternalAgentRunDto> = runCatching {
    val prompt = request.prompt.trim()
    if (request.agentType == ExternalAgentType.SOLO) {
      return@runCatching startSolo(request, prompt, fallbackCwd, artifactsRoot)
    }
    require(prompt.isNotEmpty()) { "prompt is required" }
    val executable = request.agentType.executable()
    ExternalAgentExecutables.healthDetail(executable)?.let { reason -> throw IllegalArgumentException(reason) }
    val executablePath = requireNotNull(ExternalAgentExecutables.resolve(executable))

    val cwd = request.cwd
      ?.takeIf { it.isNotBlank() }
      ?.let { File(it).takeIf { f -> f.isDirectory } }
      ?: fallbackCwd
    // The child CLI discovers skills by walking up from its cwd; a workspace without the
    // trailblaze authoring skill anywhere in its ancestry gets the bundled copy unpacked in.
    AgentSkillMaterializer.ensureTrailblazeSkill(cwd)
    val id = "agent-" + UUID.randomUUID().toString()
    val title = request.title?.trim()?.takeIf { it.isNotEmpty() }
      ?: prompt.lineSequence().firstOrNull()?.take(80)
      ?: request.agentType.displayName()
    val run = MutableExternalAgentRun(
      id = id,
      request = request,
      title = title,
      prompt = prompt,
      cwd = cwd.canonicalFile,
      artifactsRoot = artifactsRoot,
    )
    runs[id] = run
    pruneFinishedRuns()
    run.emit(
      kind = ExternalAgentEventKind.LIFECYCLE,
      status = ExternalAgentSessionStatus.RUNNING,
      title = "Starting ${request.agentType.displayName()}",
      text = cwd.canonicalPath,
    )
    run.emit(kind = ExternalAgentEventKind.USER_MESSAGE, title = "You", text = prompt)
    val command = externalAgentCommand(request, run.cwd, executablePath, runId = run.id, artifactsRoot = artifactsRoot)
    scope.launch { runProcess(run, command) }
    run.dto()
  }

  /**
   * A solo session: the same run/event machinery with no child CLI attached. The human drives the
   * device and confirms steps; those land as HUMAN_ACTION events through the normal emit path
   * (which works regardless of run status). There is no process whose lifetime a RUNNING status
   * would describe, so the run is born finished - that keeps the rail free of a phantom spinner
   * and the stop/reply surfaces disabled, while the UI's follow-mode event polling (the same one
   * that picks up taps demonstrated after an agent's turn ends) still delivers every action.
   */
  private fun startSolo(
    request: ExternalAgentRunRequest,
    prompt: String,
    fallbackCwd: File,
    artifactsRoot: File?,
  ): ExternalAgentRunDto {
    val cwd = request.cwd
      ?.takeIf { it.isNotBlank() }
      ?.let { File(it).takeIf { f -> f.isDirectory } }
      ?: fallbackCwd
    val id = "agent-" + UUID.randomUUID().toString()
    val title = request.title?.trim()?.takeIf { it.isNotEmpty() }
      ?: prompt.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }?.take(80)
      ?: "Solo session"
    val run = MutableExternalAgentRun(
      id = id,
      request = request,
      title = title,
      prompt = prompt,
      cwd = cwd.canonicalFile,
      artifactsRoot = artifactsRoot,
    )
    run.status = ExternalAgentSessionStatus.COMPLETED
    run.endedAtMs = run.startedAtMs
    runs[id] = run
    pruneFinishedRuns()
    run.emit(
      kind = ExternalAgentEventKind.LIFECYCLE,
      status = ExternalAgentSessionStatus.COMPLETED,
      title = "Solo session - no agent attached",
      text = cwd.canonicalPath,
    )
    if (prompt.isNotEmpty()) run.emit(kind = ExternalAgentEventKind.USER_MESSAGE, title = "You", text = prompt)
    return run.dto()
  }

  /**
   * A demonstration session: a solo-style (agent-less, born-finished) run in demo mode. It hosts the
   * same HUMAN_ACTION event stream a solo run does, but additionally carries a [DemoRunState] and
   * writes a durable bundle (manifest + actions.ndjson + evidence frames) into its tape dir. Starts
   * in the [DemoPhase.POSITIONING] phase; [markDemoStart] moves it to recording, [finishDemo] to done.
   * The device rides the run's UI context so [deviceFor] (the cancel route's device release) resolves it.
   */
  fun startDemo(
    target: String?,
    platform: String?,
    deviceId: TrailblazeDeviceId,
    title: String?,
    fallbackCwd: File,
    artifactsRoot: File? = null,
  ): Result<ExternalAgentRunDto> = runCatching {
    val request = ExternalAgentRunRequest(
      agentType = ExternalAgentType.SOLO,
      prompt = "",
      title = title,
      uiContext = TrailRunnerUiContextDto(target = target, platform = platform, deviceId = deviceId),
    )
    val cwd = fallbackCwd.canonicalFile
    val id = "agent-" + UUID.randomUUID().toString()
    val runTitle = title?.trim()?.takeIf { it.isNotEmpty() } ?: "Demonstration"
    val run = MutableExternalAgentRun(
      id = id,
      request = request,
      title = runTitle,
      prompt = "",
      cwd = cwd,
      artifactsRoot = artifactsRoot,
    )
    // Drafts (demonstration data) are local working data: they live in the workspace, gitignored,
    // and feed the generation that produces the checked-in trail. The self-ignoring `*` .gitignore
    // guarantees they can never be committed regardless of the workspace's own ignore rules.
    val draftsRoot = File(cwd, ".trailblaze/drafts")
    ensureDraftsGitignore(draftsRoot)
    val demoState = DemoRunState(deviceId = deviceId, target = target, platform = platform)
    demoState.draftDir = File(draftsRoot, id)
    demoState.demonstratedPlatforms.addIfAbsent(demoState.platformKey)
    run.demo = demoState
    // Born finished like a solo run: there is no child process whose lifetime a RUNNING status
    // would describe. HUMAN_ACTION events still land through the normal emit path.
    run.status = ExternalAgentSessionStatus.COMPLETED
    run.endedAtMs = run.startedAtMs
    runs[id] = run
    pruneFinishedRuns()
    run.emit(
      kind = ExternalAgentEventKind.LIFECYCLE,
      status = ExternalAgentSessionStatus.COMPLETED,
      title = "Demonstration session started",
      text = cwd.canonicalPath,
    )
    run.dto()
  }

  /**
   * The trailhead moment: capture the start-state evidence, write the bundle manifest, start network
   * capture (when available), and move the demo from positioning to recording. Only valid from the
   * positioning phase. Returns the new phase's wire value.
   */
  suspend fun markDemoStart(runId: String, trailhead: DemoTrailheadDto?): Result<String> = runCatching {
    val run = runs[runId] ?: error("demonstration run not found: $runId")
    val demo = run.demo ?: error("this run is not a demonstration: $runId")
    require(demo.phase == DemoPhase.POSITIONING) {
      "mark-start is only valid from the positioning phase (current: ${demo.phase.wire()})"
    }
    demo.trailhead = trailhead
    demo.startedAtMs = System.currentTimeMillis()
    captureStartState(run)
    startDemoCapture(run)
    writeDemoManifest(run)
    writeDraftManifest(run)
    demo.phase = DemoPhase.RECORDING
    run.emit(
      kind = ExternalAgentEventKind.LIFECYCLE,
      title = "Recording started",
      text = trailhead?.name ?: "manual positioning",
    )
    demo.phase.wire()
  }

  /**
   * End the demonstration: record the objective/notes, rewrite the manifests, stop network capture,
   * move the demo to done. Only valid from the recording phase. Returns the durable bundle dir path.
   *
   * The first platform requires an objective. Platform 2+ (an objective already exists) accepts a
   * blank objective and keeps the existing one; a non-blank objective replaces it. The run is
   * retitled to the objective so its single Create-sidebar entry reads like the trail it produces.
   */
  fun finishDemo(runId: String, objective: String, notes: String?): Result<String> = runCatching {
    val run = runs[runId] ?: error("demonstration run not found: $runId")
    val demo = run.demo ?: error("this run is not a demonstration: $runId")
    require(demo.phase == DemoPhase.RECORDING) {
      "finish is only valid from the recording phase (current: ${demo.phase.wire()})"
    }
    val trimmed = objective.trim()
    if (demo.objective.isNullOrBlank()) {
      require(trimmed.isNotEmpty()) { "objective is required" }
      demo.objective = trimmed
    } else if (trimmed.isNotEmpty()) {
      demo.objective = trimmed
    }
    // Blank notes on a later platform keep the existing notes rather than clearing them.
    notes?.trim()?.takeIf { it.isNotEmpty() }?.let { demo.notes = it }
    demo.finishedAtMs = System.currentTimeMillis()
    demo.completedPlatforms.add(demo.platformKey)
    demo.phase = DemoPhase.DONE
    demo.objective?.take(60)?.let { run.title = it }
    writeDemoManifest(run)
    writeDraftManifest(run)
    stopDemoCapture(run)
    run.emit(kind = ExternalAgentEventKind.LIFECYCLE, title = "Demonstration finished", text = demo.objective)
    evidenceDir(runId)?.absolutePath ?: error("no bundle dir for demonstration: $runId")
  }

  /**
   * Add another platform to a finished demonstration: point the demo at [deviceId], derive its
   * platform key, wipe and recreate that platform's bundle (so re-demonstrating a platform starts
   * clean), and reset the phase to positioning. The markStart -> finish cycle then repeats for the
   * new device. Only valid once the current demonstration is done and no generation run is in flight.
   */
  fun addDemoPlatform(runId: String, deviceId: TrailblazeDeviceId): Result<String> = runCatching {
    val run = runs[runId] ?: error("demonstration run not found: $runId")
    val demo = run.demo ?: error("this run is not a demonstration: $runId")
    require(demo.phase == DemoPhase.DONE) {
      "add-platform is only valid after the current demonstration is finished (current: ${demo.phase.wire()})"
    }
    val existing = demo.generationRunId?.let { runs[it] }
    require(existing == null || existing.status != ExternalAgentSessionStatus.RUNNING) {
      "a generation run is in progress for this demonstration"
    }
    demo.device = deviceId
    demo.platform = deviceId.trailblazeDevicePlatform.name.lowercase()
    val key = demoPlatformKey(deviceId)
    demo.platformKey = key
    demo.demonstratedPlatforms.addIfAbsent(key)
    // Re-demonstrating a platform starts clean: drop its completed mark and its old bundle.
    demo.completedPlatforms.remove(key)
    evidenceDir(runId)?.let { bundle -> runCatching { bundle.deleteRecursively() } }
    // Reset the per-platform demonstration state; the shared cross-platform facts (objective,
    // notes, delivered trail) stay so platform 2+ generation runs in merge mode.
    demo.trailhead = null
    demo.startedAtMs = null
    demo.finishedAtMs = null
    demo.captureStarted = false
    // The previous platform's generation run is not this one's: keep the delivered trail facts
    // (merge mode reads them) but drop the run link, or the UI would attach the old generation's
    // transcript and status to the new demonstration.
    demo.generationRunId = null
    demo.phase = DemoPhase.POSITIONING
    writeDraftManifest(run)
    run.emit(kind = ExternalAgentEventKind.LIFECYCLE, title = "Adding a platform", text = key)
    key
  }

  /**
   * Launches the generation agent for a finished demonstration: a NEW external-agent run whose
   * prompt preamble hands the trailblaze-author skill the bundle dir and the demonstration facts,
   * runs autonomously (workspace-write, so it can write the trail into the library), and self-
   * verifies. Links the new run back to the demonstration via [DemoRunState.generationRunId] and
   * marks it as a generation run BEFORE [start] can spawn its process. Returns the new run id.
   *
   * Only valid once the demonstration is [DemoPhase.DONE], and rejected while a prior generation
   * run for the same demonstration is still running.
   */
  fun generateFromDemo(
    demoRunId: String,
    agentType: ExternalAgentType,
    model: String?,
    sandbox: String?,
    fallbackCwd: File,
    artifactsRoot: File? = null,
  ): Result<String> = runCatching {
    val demoRun = runs[demoRunId] ?: error("demonstration run not found: $demoRunId")
    val demo = demoRun.demo ?: error("this run is not a demonstration: $demoRunId")
    require(agentType != ExternalAgentType.SOLO) { "generation needs a real agent, not a solo session" }
    require(demo.phase == DemoPhase.DONE) {
      "generate is only valid after the demonstration is finished (current phase: ${demo.phase.wire()})"
    }
    val existing = demo.generationRunId?.let { runs[it] }
    require(existing == null || existing.status != ExternalAgentSessionStatus.RUNNING) {
      "a generation run is already in progress for this demonstration"
    }
    val bundleDir = evidenceDir(demoRunId)?.let { File(it.absolutePath) }
      ?: error("no bundle dir for demonstration: $demoRunId")
    val request = ExternalAgentRunRequest(
      agentType = agentType,
      prompt = "Generate the trail from my demonstration.",
      title = "Generate trail" + (demo.objective?.let { ": $it" } ?: ""),
      model = model?.trim()?.takeIf { it.isNotEmpty() },
      sandbox = sandbox?.trim()?.takeIf { it.isNotEmpty() } ?: "workspace-write",
      includeUiContract = true,
      promptPreamble = generationPreamble(demo, bundleDir, fallbackCwd),
      uiContext = TrailRunnerUiContextDto(
        target = demo.target,
        platform = demo.platform,
        deviceId = demo.device,
      ),
      // The bundle lives under the artifacts root, outside the agent's cwd (the workspace root).
      // reply() resumes this same run's request, so the grant carries across auto-continue turns.
      extraDirs = listOf(bundleDir.absolutePath),
    )
    // Run the generation agent from the WORKSPACE ROOT (where `./trailblaze` and the skills live),
    // not the inner trails/ dir - so the trailblaze CLI, the MCP proxy launcher, and skill discovery
    // all resolve. generationPreamble still points at fallbackCwd (the trails root) for the write
    // destination.
    val started = start(request, workspaceRootForGeneration(fallbackCwd), artifactsRoot).getOrThrow()
    // Mark the new run and link it to the demonstration now: start() launches runProcess
    // asynchronously, but the agent's first trail_output / trail TOOL_RESULT is many seconds away,
    // so the generation gate is armed well before any event it needs to act on.
    runs[started.id]?.generation = GenerationRunState(demoRunId = demoRunId)
    demo.generationRunId = started.id
    started.id
  }

  /**
   * The generation-run policy applied at emit time (generation runs only): watch trail-run
   * TOOL_RESULTs to record whether a passing verification run was actually observed, and rewrite
   * any `trail_output` UI command so `ready` is trusted only when a passing run was seen - an
   * overclaiming agent's unverified `ready` is downgraded to `draft` with `verified=false`.
   */
  internal fun applyGenerationPolicy(
    run: MutableExternalAgentRun,
    draft: ExternalAgentEventDraft,
  ): ExternalAgentEventDraft {
    val gen = run.generation ?: return draft
    when (draft.kind) {
      ExternalAgentEventKind.TOOL_RESULT -> {
        if (classifyTrailRunResult(draft.toolName, draft.text)) gen.verifiedPassObserved = true
        return draft
      }
      ExternalAgentEventKind.UI_COMMAND -> {
        val cmd = draft.uiCommand ?: return draft
        if (cmd.action != "trail_output") return draft
        val rewritten = rewriteTrailOutputStatus(cmd, gen.verifiedPassObserved)
        if (rewritten.params["status"] != cmd.params["status"]) {
          Console.log(
            "[demo-generate] ${run.id}: downgraded unverified trail_output " +
              "'${cmd.params["status"]}' -> '${rewritten.params["status"]}' (no passing trail run observed)",
          )
        }
        // Record the delivered trail onto the parent demonstration so the web can surface it and a
        // platform 2+ generation run authors in merge mode against the existing files.
        recordDeliveredTrail(gen.demoRunId, rewritten, gen.verifiedPassObserved)
        return draft.copy(uiCommand = rewritten)
      }
      else -> return draft
    }
  }

  /**
   * When a generation run's turn ends without a terminal `trail_output`, nudge it to continue in-
   * process - bounded by [DEMO_GENERATE_MAX_TURNS]. Never fires for a non-generation run, a run the
   * human has already replied to, a cancelled run, or once the budget is spent. The decision itself
   * is the pure [shouldAutoContinue]; this method only gathers the inputs and drives [reply].
   */
  private suspend fun maybeAutoContinueGeneration(run: MutableExternalAgentRun) {
    val gen = run.generation ?: return
    val inputs = GenerationContinueInputs(
      cancelled = run.cancelRequested || run.status == ExternalAgentSessionStatus.CANCELLED,
      humanReplied = gen.humanReplied,
      terminalTrailOutputSeen = hasTerminalTrailOutput(run.eventsSnapshot()),
      autoTurnsUsed = gen.autoTurns.get(),
    )
    if (!shouldAutoContinue(inputs)) return
    val turn = gen.autoTurns.incrementAndGet()
    run.emit(
      kind = ExternalAgentEventKind.LIFECYCLE,
      title = "Continuing generation",
      text = "No trail delivered yet; nudging the agent to continue ($turn/$DEMO_GENERATE_MAX_TURNS)",
    )
    reply(run.id, DEMO_GENERATE_CONTINUE_PROMPT, auto = true).onFailure {
      Console.log("[demo-generate] auto-continue failed for ${run.id}: ${it.message}")
    }
  }

  /**
   * The phase to stamp on an action dispatched right now: "setup" while positioning, "step" once
   * recording has begun. Null when the run is not a demonstration (no actions.ndjson line is written).
   * Read on the gesture request path so an action keeps the phase it was dispatched in, even if its
   * evidence settles (and emits) after a phase change.
   */
  fun demoActionPhase(runId: String): String? {
    val demo = runs[runId]?.demo ?: return null
    return if (demo.phase == DemoPhase.POSITIONING) "setup" else "step"
  }

  /** Appends one already-encoded action line to the demo run's actions.ndjson. No-op for non-demo runs. */
  fun appendDemoAction(runId: String, line: JsonObject) {
    val run = runs[runId] ?: return
    run.demo ?: return
    val dir = evidenceDir(runId) ?: return
    runCatching {
      dir.mkdirs()
      File(dir, "actions.ndjson").appendText(line.toString() + "\n")
    }.onFailure { Console.log("[demo] actions.ndjson append failed: ${it.message}") }
  }

  /**
   * Deletes one demonstrated step - a mistake made mid-recording - by its HUMAN_ACTION event id.
   * The durable bundle is scrubbed first (the actions.ndjson line matched by the tape seq the
   * event's output carries, then that seq's evidence files), and only then does the event leave
   * the run's transcript - so a failed rewrite leaves the step visible rather than silently
   * keeping the mistake in the bundle the generation agent reads. Only valid while the
   * demonstration is recording, and only for step-phase human actions (setup taps stay).
   */
  fun deleteDemoStep(runId: String, eventId: String): Result<Unit> = runCatching {
    val run = runs[runId] ?: error("demonstration run not found: $runId")
    val demo = run.demo ?: error("this run is not a demonstration: $runId")
    require(demo.phase == DemoPhase.RECORDING) {
      "steps can only be deleted while recording (current: ${demo.phase.wire()})"
    }
    val event = run.findEvent(eventId) ?: error("step not found: $eventId")
    val output = runCatching { event.output?.let { JSON.parseToJsonElement(it).jsonObject } }.getOrNull()
    require(
      event.kind == ExternalAgentEventKind.HUMAN_ACTION &&
        output?.get("phase")?.jsonPrimitive?.contentOrNull == "step",
    ) { "only demonstrated steps can be deleted" }
    // A step without a tape seq never wrote an actions.ndjson line (see the gesture route), so
    // there is nothing durable to scrub for it.
    val seq = output["seq"]?.jsonPrimitive?.intOrNull
    if (seq != null) {
      val dir = evidenceDir(runId)
      val actions = dir?.let { File(it, "actions.ndjson") }
      if (actions?.isFile == true) {
        val kept = actions.readLines().filter { line ->
          line.isNotBlank() &&
            runCatching { JSON.parseToJsonElement(line).jsonObject["seq"]?.jsonPrimitive?.intOrNull }.getOrNull() != seq
        }
        actions.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n")
      }
      // Evidence frames are advisory context; a leftover file must not block the deletion.
      dir?.listFiles { file -> file.name.startsWith("$seq-") }?.forEach { file ->
        runCatching { file.delete() }
      }
    }
    run.removeEvent(eventId)
    run.emit(kind = ExternalAgentEventKind.LIFECYCLE, title = "Step deleted", text = event.title)
    Unit
  }

  /** Reads back the on-disk manifest for a demo run, or null when it hasn't been written yet. */
  internal fun readDemoManifest(runId: String): DemoManifest? {
    val dir = evidenceDir(runId) ?: return null
    val file = File(dir, DEMO_MANIFEST_NAME).takeIf { it.isFile } ?: return null
    return runCatching { DEMO_YAML.decodeFromString(DemoManifest.serializer(), file.readText()) }.getOrNull()
  }

  /** Serializes the current platform's demo state to `demo.yaml` in its bundle dir. Best-effort. */
  private fun writeDemoManifest(run: MutableExternalAgentRun) {
    val demo = run.demo ?: return
    val dir = evidenceDir(run.id) ?: return
    val manifest = DemoManifest(
      target = demo.target,
      platform = demo.platform,
      deviceId = demo.device,
      classifiers = listOf(demo.platformKey),
      trailhead = demo.trailhead
        ?.let { DemoManifestTrailhead(name = it.name, args = it.args, yaml = it.yaml) }
        ?: DemoManifestTrailhead(manual = true),
      startedAtMs = demo.startedAtMs,
      finishedAtMs = demo.finishedAtMs,
      objective = demo.objective,
      notes = demo.notes,
    )
    runCatching {
      dir.mkdirs()
      File(dir, DEMO_MANIFEST_NAME).writeText(DEMO_YAML.encodeToString(DemoManifest.serializer(), manifest))
    }.onFailure { Console.log("[demo] manifest write failed: ${it.message}") }
  }

  /** Serializes the cross-platform `draft.yaml` at the draft dir root. Best-effort. */
  private fun writeDraftManifest(run: MutableExternalAgentRun) {
    val demo = run.demo ?: return
    val draftDir = demo.draftDir ?: return
    val manifest = DraftManifest(
      target = demo.target,
      objective = demo.objective,
      notes = demo.notes,
      platforms = demo.demonstratedPlatforms.toList(),
      trailId = demo.trailId,
      trailFiles = demo.trailFiles,
      trailVerified = demo.trailVerified,
    )
    runCatching {
      draftDir.mkdirs()
      File(draftDir, DRAFT_MANIFEST_NAME).writeText(DEMO_YAML.encodeToString(DraftManifest.serializer(), manifest))
    }.onFailure { Console.log("[demo] draft manifest write failed: ${it.message}") }
  }

  /**
   * Records the trail a generation run delivered onto its parent demonstration (id + files from the
   * `trail_output`, verified from the server's own observation), and re-persists `draft.yaml`.
   */
  private fun recordDeliveredTrail(demoRunId: String, cmd: TrailRunnerUiCommandDto, verified: Boolean) {
    val demoRun = runs[demoRunId] ?: return
    val demo = demoRun.demo ?: return
    cmd.trailId?.takeIf { it.isNotBlank() }?.let { demo.trailId = it }
    cmd.params["files"]?.takeIf { it.isNotBlank() }?.let { demo.trailFiles = it }
    demo.trailVerified = verified
    writeDraftManifest(demoRun)
  }

  /**
   * The trail files behind a demonstration's live rail. Once a generation run has delivered a trail
   * ([DemoRunState.trailId] + [DemoRunState.trailFiles], comma-joined names), reads those files under
   * [trailsRoot]. Before any trail_output is observed, falls back to listing the [suggestedTrailDir]
   * folder if it exists on disk - so the rail fills in WHILE the agent writes, not only after it
   * declares. Merge mode (trailFiles pre-set from an existing trail) reads the same way. Every
   * resolved file must stay under [trailsRoot] (canonical prefix check); per-file content is capped.
   * Null for an unknown or non-demonstration run.
   */
  fun demoTrailContent(runId: String, trailsRoot: File): DemoTrailContentResult? {
    val run = runs[runId] ?: return null
    val demo = run.demo ?: return null
    val rootCanon = runCatching { trailsRoot.canonicalFile }.getOrNull()
      ?: return DemoTrailContentResult(trailId = demo.trailId, files = emptyList())
    val folder = trailFolderFromId(rootCanon, demo.trailId)
    val relNames = demo.trailFiles?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    val files = if (folder != null && relNames.isNotEmpty()) {
      relNames.mapNotNull { name -> readContainedTrailFile(rootCanon, File(folder, name)) }
    } else {
      peekSuggestedTrailDir(rootCanon, demo)
    }
    return DemoTrailContentResult(trailId = demo.trailId, files = files)
  }

  /**
   * Captures the start-state screenshot + view hierarchy into the tape dir, using the same live
   * connection the gesture path drives (via [TrailRunnerRecordingHolder]). Best-effort: a daemon
   * without a recording service, or a transient capture miss, simply leaves the frame out.
   */
  private suspend fun captureStartState(run: MutableExternalAgentRun) {
    val demo = run.demo ?: return
    val dir = evidenceDir(run.id) ?: return
    val service = TrailRunnerRecordingHolder.service ?: return
    val frame = runCatching { service.captureEvidenceFrame(demo.device) }.getOrNull() ?: return
    runCatching {
      dir.mkdirs()
      frame.screenshot?.let { bytes -> File(dir, "start-state.${extensionOf(frame.mime)}").writeBytes(bytes) }
      frame.hierarchy?.let { text -> File(dir, "start-state-hierarchy.txt").writeText(text) }
    }.onFailure { Console.log("[demo] start-state capture failed: ${it.message}") }
  }

  /**
   * Starts optional network capture into the tape dir for an Android demonstration. A silent no-op
   * when no activator is registered or the device is not Android; its absence must not degrade the
   * flow (the bundle simply lacks the events/ streams).
   */
  private fun startDemoCapture(run: MutableExternalAgentRun) {
    val demo = run.demo ?: return
    if (demo.captureStarted) return
    val activator = AndroidNetworkCaptureRegistry.activator ?: return
    if (demo.device.trailblazeDevicePlatform != TrailblazeDevicePlatform.ANDROID) return
    val dir = evidenceDir(run.id) ?: return
    runCatching {
      dir.mkdirs()
      activator.start(sessionId = run.id, sessionDir = dir, deviceId = demo.device, targetAppId = null)
      demo.captureStarted = true
    }.onFailure { Console.log("[demo] network capture start failed: ${it.message}") }
  }

  /** Stops network capture for a demo run. Idempotent: safe to call at finish, cancel, and eviction. */
  private fun stopDemoCapture(run: MutableExternalAgentRun) {
    val demo = run.demo ?: return
    if (!demo.captureStarted) return
    AndroidNetworkCaptureRegistry.activator?.let { activator ->
      runCatching { activator.stop(run.id) }.onFailure { Console.log("[demo] network capture stop failed: ${it.message}") }
    }
    demo.captureStarted = false
  }

  /**
   * A follow-up turn: resume the vendor thread with a fresh CLI invocation on the SAME run, so the
   * conversation accumulates in one event stream. Claude re-captures a new session id per resumed
   * turn (resume forks); Codex keeps its thread id — both flow through [MutableExternalAgentRun.emit]'s
   * thread-id capture, so the next reply always resumes the latest turn.
   */
  suspend fun reply(id: String, prompt: String, auto: Boolean = false): Result<ExternalAgentRunDto> = runCatching {
    val run = runs[id]
    requireNotNull(run) { "external agent run not found: $id" }
    require(run.agentType != ExternalAgentType.SOLO) { "this is a solo session - there is no agent to reply to" }
    val text = prompt.trim()
    require(text.isNotEmpty()) { "prompt is required" }
    require(run.status != ExternalAgentSessionStatus.RUNNING) {
      "the agent is still working — wait for the current turn to finish (or stop it)"
    }
    val threadId = run.externalThreadId
    requireNotNull(threadId) {
      "no ${run.agentType.displayName()} session id was captured for this run; start a new session"
    }
    // A human reply takes a generation run out of the auto-continue loop: once the human has spoken,
    // the daemon must not keep nudging the agent on its own.
    if (!auto) run.generation?.humanReplied = true
    ExternalAgentExecutables.healthDetail(run.agentType.executable())?.let { reason -> throw IllegalArgumentException(reason) }
    val executablePath = requireNotNull(ExternalAgentExecutables.resolve(run.agentType.executable()))
    // The demonstration bridge: HUMAN_ACTION events are UI-side only, so everything the human
    // demonstrated since the agent's last turn rides into this reply as a preamble (collected
    // BEFORE the new USER_MESSAGE event lands, which is where the scan-back stops). A just-tapped
    // action's evidence may still be settling - drain it first, or the action would miss this
    // turn (and, landing between snapshot and USER_MESSAGE, could miss every future preamble).
    awaitPendingEvidence(id)
    val demonstration = demonstrationPreamble(run.eventsSnapshot())
    // Claude re-applies the UI contract per invocation (--append-system-prompt); Codex only saw it
    // in the turn-1 prompt, where it decays with context distance. Ride the one rule that keeps
    // breaking (the anti-wait rule) on every Codex resume - argv-only, like the demonstration
    // preamble, so the transcript stays the human's own words.
    val contractReminder = if (run.agentType == ExternalAgentType.CODEX && run.request.includeUiContract) {
      "Reminder: never end your turn promising to wait, sleep, or retry later - nothing resumes " +
        "you when your turn ends. Finish the work in this turn or ask the human a question.\n\n"
    } else {
      ""
    }
    val preamble = contractReminder + demonstration
    run.beginTurn()
    run.emit(kind = ExternalAgentEventKind.USER_MESSAGE, title = "You", text = text)
    val command = externalAgentCommand(
      request = run.request,
      cwd = run.cwd,
      executable = executablePath,
      runId = run.id,
      resume = ExternalAgentResume(threadId = threadId, prompt = preamble + text),
      artifactsRoot = run.artifactsRoot,
    )
    scope.launch { runProcess(run, command) }
    run.dto()
  }

  private suspend fun runProcess(
    run: MutableExternalAgentRun,
    command: List<String>,
  ) = coroutineScope {
    val builder = ProcessBuilder(command)
      .directory(run.cwd)
      .redirectErrorStream(false)
    // When this daemon runs from an uber jar, hand that exact jar to any `trailblaze` the child
    // invokes (its MCP server, CLI calls inside the conversation). Without it, the dev launcher
    // re-fingerprints the source tree from the child's cwd and can conclude the running daemon
    // is "stale" — stopping THIS daemon mid-conversation.
    currentUberJarPath()?.let { builder.environment()["TRAILBLAZE_JAR"] = it }
    // Put a daemon-matched `trailblaze` first on the child's PATH so any CLI call the agent makes
    // hits this daemon's exact build — never a version-skewed install or a source checkout's
    // `./trailblaze` wrapper (which rebuilds jars and stops "stale" daemons as a side effect).
    sessionCliDir?.let { dir ->
      val env = builder.environment()
      val pathKey = env.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
      env[pathKey] = dir.absolutePath + File.pathSeparator + (env[pathKey] ?: "")
    }
    // A human approving a permission prompt can take minutes; keep the child's MCP client from
    // timing out the approval_prompt tool call while it waits. Only where permissions are enforced
    // (Claude below danger-full-access) - the level that actually routes prompts through Trail Runner.
    if (run.agentType == ExternalAgentType.CLAUDE && run.request.accessLevel() != "danger-full-access") {
      builder.environment()["MCP_TOOL_TIMEOUT"] = "86400000"
    }
    val process = try {
      builder.start()
    } catch (e: Exception) {
      run.emit(
        kind = ExternalAgentEventKind.ERROR,
        status = ExternalAgentSessionStatus.FAILED,
        title = "Could not start ${run.agentType.displayName()}",
        text = e.message ?: e.toString(),
      )
      run.finish(ExternalAgentSessionStatus.FAILED, exitCode = null, error = e.message ?: e.toString())
      return@coroutineScope
    }
    run.process = process
    // The child gets no piped input; leaving its stdin open makes claude wait ~3s for possible
    // piped data before starting ("no stdin data received in 3s" warning).
    runCatching { process.outputStream.close() }
    if (run.cancelRequested) {
      // Cancelled before the process came up: kill it and DON'T emit "Process started" — a
      // RUNNING lifecycle event after the cancellation would read as cancelled → running. The
      // waitFor path below still runs and finishes the run as CANCELLED.
      killProcessTree(process)
    } else {
      run.emit(
        kind = ExternalAgentEventKind.LIFECYCLE,
        status = ExternalAgentSessionStatus.RUNNING,
        title = "Process started",
        text = redactedCommand(command),
      )
    }

    val stdout = launch(Dispatchers.IO) {
      process.inputStream.bufferedReader().useLines { lines ->
        lines.forEach { line ->
          parseExternalAgentLine(run, line).forEach { draft -> run.emit(applyGenerationPolicy(run, draft)) }
        }
      }
    }
    val stderr = launch(Dispatchers.IO) {
      process.errorStream.bufferedReader().useLines { lines ->
        lines.forEach { line ->
          if (line.isNotBlank()) {
            run.noteStderr(line)
            run.emit(
              kind = ExternalAgentEventKind.STDERR,
              title = "stderr",
              text = line,
            )
          }
        }
      }
    }

    val idleTimeoutMs = idleTimeoutMs()
    val watchdog = launch {
      while (true) {
        delay(EXTERNAL_AGENT_POLL_INTERVAL_MS * 20)
        // A child blocked on a human permission decision is waiting, not wedged - the human sets
        // the pace there. Keep the clock current so the agent also gets a fresh idle window once
        // the decision lands, instead of being killed the instant the queue empties.
        if (run.permissions.pendingSnapshot().isNotEmpty()) {
          run.lastActivityAtMs = System.currentTimeMillis()
          continue
        }
        val idle = System.currentTimeMillis() - run.lastActivityAtMs
        if (idle < idleTimeoutMs) continue
        run.timedOut = true
        run.emit(
          kind = ExternalAgentEventKind.ERROR,
          title = "Inactivity timeout",
          text = "No output from ${run.agentType.displayName()} for ${idle / 1000}s; stopping the process",
        )
        killProcessTree(process)
        break
      }
    }

    val exit = withContext(Dispatchers.IO) { process.waitFor() }
    watchdog.cancel()
    stdout.join()
    stderr.join()
    run.process = null
    // The child (and its MCP proxy) is gone: any permission request still suspended on a human
    // decision would hang its route handler forever. Resolve them all as denied.
    run.permissions.failAllPending("The run ended before this was approved").forEach { emitPermissionDecision(run, it) }
    if (run.status == ExternalAgentSessionStatus.CANCELLED || run.cancelRequested) {
      run.finishIfNeeded(ExternalAgentSessionStatus.CANCELLED, exitCode = exit, error = null)
    } else if (run.timedOut) {
      run.finishIfNeeded(
        ExternalAgentSessionStatus.FAILED,
        exitCode = exit,
        error = "timed out: no output for ${idleTimeoutMs / 1000}s (override with $EXTERNAL_AGENT_IDLE_TIMEOUT_ENV)",
      )
    } else if (exit == 0) {
      run.finishIfNeeded(ExternalAgentSessionStatus.COMPLETED, exitCode = exit, error = null)
      maybeEmitWaitPromiseNudge(run)
      // A generation run that finished its turn without delivering a trail is nudged to continue
      // in-process (bounded). Only the clean-exit path: a crashed/timed-out CLI is not re-invoked.
      maybeAutoContinueGeneration(run)
    } else {
      val tail = run.stderrTail()
      run.finishIfNeeded(
        ExternalAgentSessionStatus.FAILED,
        exitCode = exit,
        error = "process exited with code $exit" + (if (tail.isEmpty()) "" else "\n$tail"),
      )
    }
  }

  /**
   * The vendor CLIs cannot resume themselves: a finished turn just sits there. When the agent's
   * final message promises to wait/continue on its own anyway (the contract forbids it, but
   * prompt-only enforcement demonstrably slips), surface an ask_user nudge chip so the human has
   * a one-click way to un-wedge the conversation. COMPLETED turns only - a failed or cancelled
   * turn should not invite a "Continue now".
   */
  private fun maybeEmitWaitPromiseNudge(run: MutableExternalAgentRun) {
    val lastTurn = run.eventsSnapshot().takeLastWhile { it.kind != ExternalAgentEventKind.USER_MESSAGE }
    val finalText = lastTurn.lastOrNull {
      it.kind == ExternalAgentEventKind.FINAL_RESULT || it.kind == ExternalAgentEventKind.ASSISTANT_MESSAGE
    }?.text
    if (!detectWaitPromise(finalText)) return
    val message = "The agent said it would continue on its own, but a finished turn can't resume itself. Nudge it to continue?"
    run.emit(
      kind = ExternalAgentEventKind.UI_COMMAND,
      title = "ask_user",
      text = message,
      uiCommand = TrailRunnerUiCommandDto(
        action = "ask_user",
        message = message,
        params = mapOf("options" to "Continue now"),
      ),
    )
  }

  // Read per-run (not cached at JVM start), matching the repo's flippable-on-a-running-daemon
  // pattern for triage knobs. Malformed / non-positive values fall back to the default.
  private fun idleTimeoutMs(): Long =
    System.getenv(EXTERNAL_AGENT_IDLE_TIMEOUT_ENV)?.toLongOrNull()?.takeIf { it > 0 }
      ?: EXTERNAL_AGENT_IDLE_TIMEOUT_DEFAULT_MS

  private fun option(
    type: ExternalAgentType,
    display: String,
    executable: String,
    installHint: String? = null,
    authHint: String? = null,
    modelsHint: String? = null,
    docsUrl: String? = null,
    models: List<ExternalAgentModelOptionDto> = emptyList(),
  ): ExternalAgentOptionDto {
    val health = ExternalAgentExecutables.healthDetail(executable)
    return ExternalAgentOptionDto(
      id = type,
      display = display,
      executable = executable,
      available = health == null,
      detail = health,
      installHint = installHint,
      authHint = authHint,
      modelsHint = modelsHint,
      docsUrl = docsUrl,
      models = models,
    )
  }
}

// ─── Demonstrate-first Create ────────────────────────────────────────────────

/** File name of the per-platform demonstration bundle manifest inside `demos/<platform>/`. */
private const val DEMO_MANIFEST_NAME = "demo.yaml"

/** File name of the cross-platform draft manifest at the draft dir root. */
private const val DRAFT_MANIFEST_NAME = "draft.yaml"

/**
 * Ensures the drafts root exists and carries a self-ignoring `*` `.gitignore`, so demonstration
 * working data can never be committed regardless of the workspace's own ignore rules. Best-effort
 * and idempotent (only writes the file when it is missing).
 */
private fun ensureDraftsGitignore(draftsRoot: File) {
  runCatching {
    draftsRoot.mkdirs()
    val gitignore = File(draftsRoot, ".gitignore")
    if (!gitignore.exists()) gitignore.writeText("*\n")
  }.onFailure { Console.log("[demo] drafts .gitignore setup failed: ${it.message}") }
}

// Manifest YAML: encode defaults so `version` always lands; lenient decode so an older/newer
// bundle with an unknown extra key still reads back.
private val DEMO_YAML: Yaml = Yaml(configuration = YamlConfiguration(strictMode = false, encodeDefaults = true))

/** The three demonstration phases; the wire form is the lowercase name. */
internal enum class DemoPhase {
  POSITIONING,
  RECORDING,
  DONE,
  ;

  fun wire(): String = name.lowercase()
}

/**
 * In-memory demonstration state carried on a demo run. Draft files on disk are the durable copy.
 *
 * A trail is demonstrable on multiple platforms: [device]/[platformKey] track the platform being
 * demonstrated right now, [demonstratedPlatforms] every platform demonstrated so far, and
 * [completedPlatforms] which of those have a finished demonstration. [draftDir] is the whole draft
 * dir (`<trailsRoot>/.trailblaze/drafts/<runId>/`); the current platform's bundle lives at
 * `<draftDir>/demos/<platformKey>/` (resolved via [ExternalAgentSupervisor.evidenceDir]).
 */
internal class DemoRunState(
  deviceId: TrailblazeDeviceId,
  val target: String?,
  platform: String?,
) {
  /** The device currently being demonstrated; reassigned by add-platform for platform 2+. */
  @Volatile var device: TrailblazeDeviceId = deviceId

  /**
   * The lowercase platform name of [device] (`android`/`ios`/`web`); reassigned by add-platform so
   * the manifest and the generation preamble describe the platform actually being demonstrated.
   */
  @Volatile var platform: String? = platform

  /** The platform key of [device]; names the `demos/<key>/` bundle dir. See [demoPlatformKey]. */
  @Volatile var platformKey: String = demoPlatformKey(deviceId)

  /** The draft dir under the workspace; null only for test-seeded states that never touch disk. */
  @Volatile var draftDir: File? = null

  @Volatile var phase: DemoPhase = DemoPhase.POSITIONING
  @Volatile var trailhead: DemoTrailheadDto? = null
  @Volatile var objective: String? = null
  @Volatile var notes: String? = null
  @Volatile var startedAtMs: Long? = null
  @Volatile var finishedAtMs: Long? = null

  /** Every platform demonstrated for this trail, in first-demonstrated order. */
  val demonstratedPlatforms = CopyOnWriteArrayList<String>()

  /** Platform keys whose demonstration has finished (or whose bundle has recorded actions). */
  val completedPlatforms: MutableSet<String> = ConcurrentHashMap.newKeySet()

  /** The trail a generation run delivered for this demonstration (observed from its trail_output). */
  @Volatile var trailId: String? = null
  @Volatile var trailFiles: String? = null
  @Volatile var trailVerified: Boolean? = null

  /** Set when the generation agent is launched for this demonstration. */
  @Volatile var generationRunId: String? = null

  /** Whether network capture was started for this session (so stop is idempotent). */
  @Volatile var captureStarted: Boolean = false

  /** True when [key]'s demonstration finished, or its bundle already holds recorded actions. */
  fun isPlatformDone(key: String): Boolean =
    completedPlatforms.contains(key) ||
      (draftDir?.let { File(it, "demos/$key/actions.ndjson").let { f -> f.isFile && f.length() > 0 } } ?: false)
}

/**
 * The platform bundle key a demonstration is filed under (`demos/<key>/`). Vocabulary: `iphone`,
 * `ipad`, `android`, `android-tablet`, `web`. The device's platform plus a best-effort form-factor
 * read of its instance id decides the key; when the form factor cannot be told apart, a mobile
 * platform uses its phone-class key (`android`, `iphone`) and any other platform falls back to the
 * bare lowercase platform name (`web`, and `ios` only when even the phone/tablet split is unknown).
 *
 * This is a fresh derivation, not the manifest's existing `platform.name.lowercase()` classifier:
 * that vocabulary (`android`/`ios`/`web`) does not distinguish phone from tablet, which the
 * per-platform draft layout needs.
 */
internal fun demoPlatformKey(deviceId: TrailblazeDeviceId): String {
  val hint = deviceId.instanceId.lowercase()
  return when (deviceId.trailblazeDevicePlatform) {
    TrailblazeDevicePlatform.ANDROID -> if (hint.contains("tablet")) "android-tablet" else "android"
    TrailblazeDevicePlatform.IOS -> when {
      hint.contains("ipad") -> "ipad"
      hint.contains("iphone") -> "iphone"
      else -> deviceId.trailblazeDevicePlatform.name.lowercase()
    }
    else -> deviceId.trailblazeDevicePlatform.name.lowercase()
  }
}

/**
 * The durable `demo.yaml` manifest, serialized into the run's tape dir at mark-start and rewritten
 * at finish. A later slice reads it to author a trail from the demonstration.
 */
@Serializable
internal data class DemoManifest(
  val version: Int = 1,
  val target: String? = null,
  val platform: String? = null,
  val deviceId: TrailblazeDeviceId,
  val classifiers: List<String> = emptyList(),
  val trailhead: DemoManifestTrailhead? = null,
  val startedAtMs: Long? = null,
  val finishedAtMs: Long? = null,
  val objective: String? = null,
  val notes: String? = null,
)

/** The positioning trailhead in the manifest; [manual] true means the human positioned by hand. */
@Serializable
internal data class DemoManifestTrailhead(
  val name: String? = null,
  val args: Map<String, String> = emptyMap(),
  val yaml: String? = null,
  val manual: Boolean = false,
)

/**
 * The durable `draft.yaml` manifest at the draft dir root: the platform-independent facts of a
 * trail-in-progress (target, objective, notes), the platforms demonstrated so far, and - once a
 * generation run delivers it - the trail it produced. Per-platform capture lives in the sibling
 * `demos/<platform>/` bundles (each with its own [DemoManifest]); this is the cross-platform record.
 */
@Serializable
internal data class DraftManifest(
  val version: Int = 1,
  val target: String? = null,
  val objective: String? = null,
  val notes: String? = null,
  val platforms: List<String> = emptyList(),
  val trailId: String? = null,
  val trailFiles: String? = null,
  val trailVerified: Boolean? = null,
)

/**
 * State carried on the external-agent run that authors a trail from a demonstration. [demoRunId] is
 * the demonstration this run generates from; [verifiedPassObserved] flips true the first time a
 * passing trail-run TOOL_RESULT is seen (the server's own check, not trusted from the agent's
 * self-reported status); [autoTurns] counts how many in-process auto-continue nudges have been
 * spent; [humanReplied] latches once a human replies, taking the run out of the auto-continue loop.
 */
internal class GenerationRunState(val demoRunId: String) {
  @Volatile var verifiedPassObserved: Boolean = false
  val autoTurns = AtomicInteger(0)
  @Volatile var humanReplied: Boolean = false
}

/** The pure inputs to [shouldAutoContinue] - a snapshot of a generation run at process exit. */
internal data class GenerationContinueInputs(
  val cancelled: Boolean,
  val humanReplied: Boolean,
  val terminalTrailOutputSeen: Boolean,
  val autoTurnsUsed: Int,
)

/**
 * Whether a generation run whose turn just ended should be auto-continued. Pure so the firing rules
 * are unit-testable without a process: fire only when the run was not cancelled, the human has not
 * replied, no terminal `trail_output` was delivered, and the auto-turn budget is not yet spent. The
 * caller has already established this is a generation run.
 */
internal fun shouldAutoContinue(inputs: GenerationContinueInputs): Boolean =
  !inputs.cancelled &&
    !inputs.humanReplied &&
    !inputs.terminalTrailOutputSeen &&
    inputs.autoTurnsUsed < DEMO_GENERATE_MAX_TURNS

/** True once the run has emitted a `trail_output` UI command whose status is a terminal ready/draft. */
internal fun hasTerminalTrailOutput(events: List<ExternalAgentEventDto>): Boolean =
  events.any { e ->
    e.kind == ExternalAgentEventKind.UI_COMMAND &&
      e.uiCommand?.action == "trail_output" &&
      e.uiCommand.params["status"].let { it == "ready" || it == "draft" }
  }

/**
 * Conservative server-side signal that a TOOL_RESULT is a PASSING trail-run ([TrailMcpTool] `trail`
 * action=RUN) result: the result JSON parses, carries `success: true`, AND looks like a run result
 * specifically (a `duration` field or the run's completion message) rather than a save/edit result
 * that also has a `success` field. Anything absent or unparseable is not a pass - false, never a
 * false trust of `ready`. [toolName] is accepted for callers that have it (Claude tool_result
 * events don't), but the JSON marker is what decides.
 */
internal fun classifyTrailRunResult(toolName: String?, resultJson: String?): Boolean {
  val obj = resultJson?.let { runCatching { JSON.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return false
  if (obj.bool("success") != true) return false
  val looksLikeRun = obj.containsKey("duration") ||
    (obj.string("message")?.contains("Trail completed successfully") == true)
  return looksLikeRun
}

/**
 * Rewrites a `trail_output` UI command against the server's own verification observation. Always
 * annotates `params.verified`; downgrades an unverified `ready` to `draft` so an overclaiming agent
 * cannot mark work ready without a passing run the server actually saw. A no-op for non-trail_output
 * commands. Pure.
 */
internal fun rewriteTrailOutputStatus(cmd: TrailRunnerUiCommandDto, verified: Boolean): TrailRunnerUiCommandDto {
  if (cmd.action != "trail_output") return cmd
  val params = cmd.params.toMutableMap()
  params["verified"] = verified.toString()
  if (!verified && cmd.params["status"] == "ready") params["status"] = "draft"
  return cmd.copy(params = params)
}

/**
 * The generation run's prompt preamble: point the agent at the trailblaze-author skill and hand it
 * the demonstration facts. Terse by design - the skill carries the methodology, this carries the
 * facts (bundle dir, target/platform/device, objective/notes, where to write) and the autonomy
 * contract (no human will reply until it finishes). Pure over its inputs.
 *
 * When the demonstration already has a delivered trail (a prior platform authored it), this switches
 * to MERGE mode: the trail exists, so the agent adds this platform's recordings to it rather than
 * authoring from scratch. Otherwise it is the first-platform preamble (author a new trail).
 */
internal fun generationPreamble(demo: DemoRunState, bundleDir: File, trailsRoot: File): String {
  val device = demo.device
  val existingFiles = demo.trailFiles?.takeIf { it.isNotBlank() }
  val existingTrailId = demo.trailId?.takeIf { it.isNotBlank() }
  val merge = existingFiles != null && existingTrailId != null
  return buildString {
    if (merge) {
      appendLine("Invoke the trailblaze-author skill now in its \"Adding a platform to an existing trail\" mode.")
      appendLine()
      appendLine("The trail already exists at $existingFiles (id $existingTrailId). This demonstration covers the ${demo.platformKey} platform.")
      appendLine("Add recordings for this platform's classifier (${demo.platformKey}) to the existing trail. Do NOT restructure or reword the existing steps - only add this platform's recordings alongside them.")
    } else {
      appendLine("Invoke the trailblaze-author skill now: author a durable, verified Trailblaze trail from the captured human demonstration in the bundle below.")
      appendLine("This demonstration covers the ${demo.platformKey} platform.")
    }
    appendLine()
    appendLine("Demonstration bundle (read every file inside it):")
    appendLine("  ${bundleDir.absolutePath}")
    appendLine("  It holds demo.yaml (manifest: objective, trailhead, target, platform, device), actions.ndjson (one JSON line per demonstrated interaction, in order), start-state and per-action screenshots + view hierarchies, and any captured app event streams under events/*.ndjson.")
    appendLine()
    appendLine("Facts from the demonstration:")
    demo.target?.let { appendLine("  target: $it") }
    demo.platform?.let { appendLine("  platform: $it") }
    appendLine("  device: ${device.instanceId} (${device.trailblazeDevicePlatform})")
    demo.objective?.let { appendLine("  objective: $it") }
    demo.notes?.takeIf { it.isNotBlank() }?.let { appendLine("  notes: $it") }
    appendLine()
    if (merge) {
      appendLine("Keep every file for this trail in its existing folder. Verify by running the trail on THIS device with the trail MCP tool (action=RUN).")
    } else {
      appendLine("Write the trail under the trails root (${trailsRoot.absolutePath}) in a new folder like ${suggestedTrailDir(demo)} - adjust the slug to fit how neighboring trails are organized. Create the folder on first write and keep every file for this trail inside it.")
    }
    appendLine()
    appendLine("Method:")
    appendLine("- For schema, target/toolbox lookups, and listing existing trails, use the `trailblaze` CLI already on your PATH (a build matched to the running daemon) and the materialized trailblaze skill's references. Never invoke `./trailblaze` from the workspace root - it rebuilds and can restart the daemon mid-run. Do NOT grep through individual trail files to reverse-engineer the format.")
    appendLine("- Prefer the trailblaze MCP and CLI surfaces over raw file spelunking - run and verify with the trail MCP tool (action=RUN) - so your work shows up as meaningful Trail Runner steps.")
    appendLine("- Emit the trail_output line after each meaningful change, not only at the end, so the human watches the trail fill in live.")
    appendLine("- You are a high-capability coordinator. For parallel read-only legwork (reading bundle evidence across many actions, checking selector uniqueness across hierarchies, scanning existing trails for conventions), delegate to your agent/subagent tool with cheaper models (haiku or sonnet) run in parallel. Keep authoring decisions, selector choices, and verification on yourself.")
    appendLine()
    append("This run is autonomous: no human will reply until you finish. Proceed through every phase of the skill without waiting for a human - ")
    append(if (merge) "add this platform's recordings, " else "author the trail, ")
    append("run both audit passes, verify by running the trail with the trail MCP tool (action=RUN) on the connected device, then emit the trail_output line. Never end your turn promising to continue later.")
  }
}

/**
 * The workspace root a generation run should cwd into: the directory that owns `./trailblaze` and
 * `.claude/skills`, one level above the `trails/` dir. When [trailsRoot] is a configured `trails/`
 * dir (it carries `config/trailblaze.yaml`), the root is its parent; otherwise [trailsRoot] itself.
 *
 * Mirrors findWorkspaceRoot's anchor rule (WorkspaceRoot.kt) but returns the directory ABOVE
 * `trails/`, which is what the CLI/MCP-proxy launcher and skill walk-up need - the helper returns the
 * `trails/` dir itself, so it is not reusable here directly.
 */
internal fun workspaceRootForGeneration(trailsRoot: File): File =
  if (File(trailsRoot, "config/trailblaze.yaml").isFile) trailsRoot.parentFile ?: trailsRoot else trailsRoot

/** Suggested destination folder for a generated trail: `<target-or-area>/<kebab-slug-of-objective>/`. */
internal fun suggestedTrailDir(demo: DemoRunState): String {
  val area = kebabSlug(demo.target ?: demo.platform ?: "app").ifBlank { "app" }
  val slug = demo.objective?.let { kebabSlug(it) }?.takeIf { it.isNotBlank() } ?: "trail"
  return "$area/$slug/"
}

private fun kebabSlug(value: String): String =
  value.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60)

/** The delivered-trail files served to the live trail rail; [files] is empty until any are known. */
internal data class DemoTrailContentResult(val trailId: String?, val files: List<DemoTrailFileDto>)

// Per-file content cap for the trail rail (~200KB). Read via a bounded stream so a giant file a
// child wrote can't be slurped whole into the daemon heap.
private const val DEMO_TRAIL_CONTENT_MAX_BYTES = 200 * 1024

/** The trail's folder from its `<rootIdx>/<relPath>` id, under [rootCanon]; null when unusable. */
private fun trailFolderFromId(rootCanon: File, trailId: String?): File? {
  val id = trailId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
  val relPath = id.substringAfter('/', "").trim().takeIf { it.isNotEmpty() } ?: return null
  return File(rootCanon, relPath)
}

/** Lists `*.yaml`/`*.md` in the suggested trail folder (when it exists) for the fill-in-while-writing case. */
private fun peekSuggestedTrailDir(rootCanon: File, demo: DemoRunState): List<DemoTrailFileDto> {
  val dirCanon = runCatching { File(rootCanon, suggestedTrailDir(demo)).canonicalFile }.getOrNull() ?: return emptyList()
  if (!dirCanon.isDirectory || !dirCanon.path.startsWith(rootCanon.path + File.separator)) return emptyList()
  return (dirCanon.listFiles()?.toList().orEmpty())
    .filter { it.isFile && (it.extension.equals("yaml", ignoreCase = true) || it.extension.equals("md", ignoreCase = true)) }
    .sortedBy { it.name }
    .mapNotNull { readContainedTrailFile(rootCanon, it) }
}

/** Reads a file's content (capped) iff it canonically resolves under [rootCanon]; null otherwise. */
private fun readContainedTrailFile(rootCanon: File, file: File): DemoTrailFileDto? {
  val canon = runCatching { file.canonicalFile }.getOrNull() ?: return null
  if (!canon.path.startsWith(rootCanon.path + File.separator) || !canon.isFile) return null
  val bytes = runCatching {
    canon.inputStream().use { it.readNBytes(DEMO_TRAIL_CONTENT_MAX_BYTES) }
  }.getOrNull() ?: return null
  return DemoTrailFileDto(name = canon.name, content = String(bytes))
}

private val EXTERNAL_AGENT_ACCESS_LEVELS = setOf("read-only", "workspace-write", "danger-full-access")

private fun ExternalAgentRunRequest.accessLevel(): String =
  sandbox?.takeIf { it in EXTERNAL_AGENT_ACCESS_LEVELS } ?: "workspace-write"

/**
 * Builds the child CLI command. Both vendors get the Trailblaze MCP server, and the request's
 * cross-vendor access level is translated into each CLI's own permission mechanism:
 *
 * | access level       | Claude Code                             | Codex CLI                      |
 * |---------------------|-----------------------------------------|--------------------------------|
 * | read-only           | (default mode: only read tools + MCP)   | `--sandbox read-only`          |
 * | workspace-write     | `--permission-mode acceptEdits` + MCP    | `--sandbox workspace-write`    |
 * | danger-full-access  | `--dangerously-skip-permissions`         | `--sandbox danger-full-access` |
 *
 * Claude's headless (`-p`) mode cannot prompt, so any tool not pre-allowed is silently denied —
 * without the explicit `--allowedTools mcp__trailblaze` grant the injected MCP server is dead
 * weight. The trailblaze tools are allowed at every level (driving the device under test is the
 * point of the integration); the access level governs the host machine (files/shell) only.
 *
 * [ExternalAgentRunRequest.extraDirs] grants Claude read access outside its cwd via `--add-dir`
 * (one flag pair per entry); Codex sandboxing does not restrict reads outside the workspace, so
 * the Codex branch ignores it.
 */
/** A follow-up turn: resume this vendor thread with this prompt instead of starting fresh. */
internal data class ExternalAgentResume(val threadId: String, val prompt: String)

internal fun externalAgentCommand(
  request: ExternalAgentRunRequest,
  cwd: File,
  executable: String,
  runId: String = "run",
  resume: ExternalAgentResume? = null,
  artifactsRoot: File? = null,
): List<String> {
  val extraContext = artifactsPrompt(artifactsRoot)
  // On resume the reply goes verbatim: the UI contract already reached the thread on turn one
  // (Codex carries it in the turn-1 prompt; Claude re-applies it per invocation below).
  val prompt = resume?.prompt ?: request.promptForChild(extraContext)
  val access = request.accessLevel()
  return when (request.agentType) {
    // Unreachable: start() branches to startSolo before command construction.
    ExternalAgentType.SOLO -> error("a solo session spawns no process")
    ExternalAgentType.CLAUDE -> buildList {
      add(executable)
      add("-p")
      add("--output-format")
      add("stream-json")
      add("--verbose")
      resume?.let {
        add("--resume")
        add(it.threadId)
      }
      request.model?.trim()?.takeIf { it.isNotEmpty() }?.let { model ->
        add("--model")
        add(model)
      }
      add("--mcp-config")
      // Every level below danger-full-access enforces permissions, so the spawned MCP server
      // carries the run id that lets its approval_prompt tool route a prompt back to Trail Runner.
      add(trailblazeMcpConfig(permissionRunId = runId.takeIf { access != "danger-full-access" }))
      request.extraDirs.forEach { dir ->
        if (dir.isNotBlank()) {
          add("--add-dir")
          add(dir)
        }
      }
      when (access) {
        "danger-full-access" -> add("--dangerously-skip-permissions")
        "workspace-write" -> {
          add("--permission-mode")
          add("acceptEdits")
        }
      }
      if (access != "danger-full-access") {
        // `mcp__<server>` allows every tool from that server (MCP permission rules take no
        // wildcards). danger-full-access already bypasses all permission checks.
        add("--allowedTools")
        add("mcp__trailblaze")
        // Route the "requires approval" prompt to the proxy-injected approval_prompt tool so the
        // human can approve it in Trail Runner instead of the headless CLI dead-ending on it.
        add("--permission-prompt-tool")
        add("mcp__trailblaze__approval_prompt")
      }
      if (request.includeUiContract) {
        // Claude rebuilds the system prompt per invocation, so the contract must ride every turn.
        add("--append-system-prompt")
        add(TRAILRUNNER_UI_CONTRACT + request.uiContextPrompt() + extraContext)
      }
      add("--")
      add(prompt)
    }
    ExternalAgentType.CODEX -> buildList {
      add(executable)
      add("exec")
      add("--json")
      if (resume == null) {
        // The resumed thread keeps its original working directory.
        add("-C")
        add(cwd.absolutePath)
      }
      add("--sandbox")
      add(access)
      request.model?.trim()?.takeIf { it.isNotEmpty() }?.let { model ->
        add("--model")
        add(model)
      }
      // Same Trailblaze MCP server the Claude branch gets; codex has no --mcp-config flag, so it
      // rides the -c TOML config overrides (string values share JSON's escaping rules).
      add("-c")
      add("mcp_servers.trailblaze.command=" + daemonJavaBin().jsonString())
      add("-c")
      add("""mcp_servers.trailblaze.args=["-cp",${daemonClasspath().jsonString()},"$MCP_PROXY_MAIN_CLASS"]""")
      add("-c")
      add("""mcp_servers.trailblaze.env={TRAILRUNNER_DAEMON_PORT="${daemonPort()}"}""")
      // The subcommand goes last: `codex exec resume` accepts only its own small option set (no
      // --sandbox, no -C), while everything above is an exec-level option that clap only parses
      // before the subcommand token.
      resume?.let {
        add("resume")
        add(it.threadId)
      }
      // Same terminator the Claude branch uses: a reply that starts with "-" ("- step 3 is
      // wrong…") must reach codex as the prompt, not be parsed as CLI flags.
      add("--")
      add(prompt)
    }
  }
}

/**
 * Resolves a vendor CLI to an absolute path. The daemon's own PATH is tried first, then a login
 * shell (`$SHELL -lc 'command -v <exe>'`) — a Finder-launched desktop app inherits a minimal PATH
 * that misses Homebrew/npm install locations. Results (including misses) are cached for the daemon
 * lifetime, so the poll-driven availability endpoint never repeats the probe.
 */
private object ExternalAgentExecutables {
  private const val NOT_FOUND = ""
  private const val PROBE_OK = ""
  private val cache = ConcurrentHashMap<String, String>()
  private val probeCache = ConcurrentHashMap<String, String>()

  fun resolve(executable: String): String? =
    cache.getOrPut(executable) { onPath(executable) ?: loginShellLookup(executable) ?: NOT_FOUND }
      .takeIf { it != NOT_FOUND }

  /**
   * Null when the CLI is present AND actually runs; otherwise a human-readable reason. "On PATH"
   * is not enough — a broken install (e.g. an npm wrapper whose vendored binary is missing) is
   * executable, spawns, dumps a stack trace, and exits non-zero, which showed up for users as a
   * failed 57-event conversation instead of an unavailable agent. Probe result is cached for the
   * daemon lifetime, same as resolution.
   */
  fun healthDetail(executable: String): String? {
    val path = resolve(executable) ?: return "$executable not found (checked PATH and login shell)"
    val result = probeCache.getOrPut(path) {
      runCatching {
        val process = ProcessBuilder(path, "--version").redirectErrorStream(true).start()
        process.outputStream.close()
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
          process.destroyForcibly()
          return@runCatching "$executable at $path did not respond to --version; reinstall it"
        }
        if (process.exitValue() != 0) {
          "$executable at $path fails to run (--version exited ${process.exitValue()}); reinstall it"
        } else {
          PROBE_OK
        }
      }.getOrElse { e -> "$executable at $path could not be launched: ${e.message}" }
    }
    return result.takeIf { it != PROBE_OK }
  }

  private fun onPath(executable: String): String? =
    System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
      .map { dir -> File(dir, executable) }
      .firstOrNull { it.isFile && it.canExecute() }
      ?.absolutePath

  private fun loginShellLookup(executable: String): String? = runCatching {
    val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
    val process = ProcessBuilder(shell, "-lc", "command -v $executable").start()
    if (!process.waitFor(5, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      return@runCatching null
    }
    if (process.exitValue() != 0) return@runCatching null
    process.inputStream.bufferedReader().readText()
      .lineSequence()
      .map(String::trim)
      // `command -v` in an interactive-config shell can print alias/function lines; only a real path counts.
      .firstOrNull { it.startsWith("/") }
      ?.takeIf { File(it).canExecute() }
  }.getOrNull()
}

internal data class ExternalAgentEventDraft(
  val kind: ExternalAgentEventKind,
  val status: ExternalAgentSessionStatus? = null,
  val title: String? = null,
  val text: String? = null,
  val toolName: String? = null,
  val toolCallId: String? = null,
  val input: JsonElement? = null,
  val output: JsonElement? = null,
  val uiCommand: TrailRunnerUiCommandDto? = null,
  val usage: JsonElement? = null,
  val raw: JsonElement? = null,
)

// Widened from `private` to `internal` (Task B testability seam): ExternalAgentSupervisorTest
// constructs a MutableExternalAgentRun directly to seed a run without spawning a real vendor CLI
// process. Compile-time visibility widening only — zero runtime behavior change.
internal class MutableExternalAgentRun(
  val id: String,
  val request: ExternalAgentRunRequest,
  /** Mutable so finishDemo can retitle a demonstration run to its objective. */
  var title: String,
  val prompt: String,
  val cwd: File,
  /** Logs root the artifacts hint points the agent at; carried so resume turns keep it. */
  val artifactsRoot: File? = null,
) {
  val agentType: ExternalAgentType get() = request.agentType
  val model: String? get() = request.model?.trim()?.takeIf { it.isNotEmpty() }

  private val seq = AtomicInteger(0)
  private val events = Collections.synchronizedList(mutableListOf<ExternalAgentEventDto>())

  @Volatile var status: ExternalAgentSessionStatus = ExternalAgentSessionStatus.RUNNING
  @Volatile var endedAtMs: Long? = null
  @Volatile var externalThreadId: String? = null
  @Volatile var exitCode: Int? = null
  @Volatile var error: String? = null
  @Volatile var process: Process? = null
  @Volatile var cancelRequested: Boolean = false
  @Volatile var timedOut: Boolean = false

  /** Demonstrate-first Create state, when this run is a demonstration session (else null). */
  @Volatile var demo: DemoRunState? = null

  /** Generation-agent state, when this run authors a trail from a demonstration (else null). */
  @Volatile var generation: GenerationRunState? = null

  /**
   * Human-approvable permissions for this run's spawned CLI. A `val` (never reset by [beginTurn]),
   * so an `allow_always` grant or an auto-approve toggle persists for the whole run's lifetime.
   */
  val permissions = ExternalAgentPermissionState()

  /** Last time the run produced an event; the inactivity watchdog measures idleness from here. */
  @Volatile var lastActivityAtMs: Long = System.currentTimeMillis()

  /** Ordinal for demonstrated actions; names each action's evidence files (see [ExternalAgentSupervisor.evidenceDir]). */
  val tapeSeq = AtomicInteger(0)

  // Tool calls whose result hasn't come back yet. Any open call can be device work (an MCP
  // `trail` RUN, or a shell tool driving the trailblaze CLI), so the cancel route only releases
  // the run's device while this is non-empty - stopping an idle conversation must not kill a
  // device session some other run has since acquired.
  private val openToolCallIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

  fun hasOpenToolCalls(): Boolean = openToolCallIds.isNotEmpty()

  /** Tail of recent stderr, so a failed exit can say WHY in its error event. */
  val recentStderr = ArrayDeque<String>()

  fun noteStderr(line: String) {
    synchronized(recentStderr) {
      recentStderr.addLast(line)
      while (recentStderr.size > 8) recentStderr.removeFirst()
    }
  }

  fun stderrTail(): String = synchronized(recentStderr) { recentStderr.joinToString("\n") }

  val startedAtMs: Long = System.currentTimeMillis()

  /** Resets per-turn state so a resumed conversation turn runs with fresh finish/cancel semantics. */
  fun beginTurn() {
    status = ExternalAgentSessionStatus.RUNNING
    endedAtMs = null
    exitCode = null
    error = null
    timedOut = false
    cancelRequested = false
    lastActivityAtMs = System.currentTimeMillis()
  }

  fun dto(): ExternalAgentRunDto = ExternalAgentRunDto(
    id = id,
    agentType = agentType,
    title = title,
    prompt = prompt,
    cwd = cwd.absolutePath,
    model = model,
    status = status,
    startedAtMs = startedAtMs,
    endedAtMs = endedAtMs,
    externalThreadId = externalThreadId,
    exitCode = exitCode,
    error = error,
    // Total emitted (the seq counter), not the retained-list size — retention capping must not
    // make a long run under-report how much it did.
    eventCount = seq.get(),
    demo = demo?.let { d ->
      DemoStateDto(
        phase = d.phase.wire(),
        // The CURRENT platform's bundle dir; add-platform reroutes it by moving platformKey.
        bundleDir = d.draftDir?.let { File(it, "demos/${d.platformKey}").absolutePath },
        objective = d.objective,
        generationRunId = d.generationRunId,
        platform = d.platformKey,
        platforms = d.demonstratedPlatforms.map { key -> DemoPlatformDto(key = key, done = d.isPlatformDone(key)) },
        draftDir = d.draftDir?.absolutePath,
        trailId = d.trailId,
        trailFiles = d.trailFiles,
        trailVerified = d.trailVerified,
      )
    },
    // Present only on a generation run; the web hides it from the sidebar and embeds its transcript.
    demoRunId = generation?.demoRunId,
    pendingPermissions = permissions.pendingSnapshot(),
    autoApprove = permissions.autoApprove,
  )

  fun eventsSnapshot(): List<ExternalAgentEventDto> = synchronized(events) { events.toList() }

  fun findEvent(eventId: String): ExternalAgentEventDto? = synchronized(events) { events.firstOrNull { it.id == eventId } }

  /** Removes one event from the retained transcript (demo step deletion); the seq counter never rewinds. */
  fun removeEvent(eventId: String): Boolean = synchronized(events) { events.removeAll { it.id == eventId } }

  fun emit(draft: ExternalAgentEventDraft): ExternalAgentEventDto = emit(
    kind = draft.kind,
    status = draft.status,
    title = draft.title,
    text = draft.text,
    toolName = draft.toolName,
    toolCallId = draft.toolCallId,
    input = draft.input,
    output = draft.output,
    uiCommand = draft.uiCommand,
    usage = draft.usage,
    raw = draft.raw,
  )

  fun emit(
    kind: ExternalAgentEventKind,
    status: ExternalAgentSessionStatus? = null,
    title: String? = null,
    text: String? = null,
    toolName: String? = null,
    toolCallId: String? = null,
    input: JsonElement? = null,
    output: JsonElement? = null,
    uiCommand: TrailRunnerUiCommandDto? = null,
    usage: JsonElement? = null,
    raw: JsonElement? = null,
  ): ExternalAgentEventDto {
    when (kind) {
      ExternalAgentEventKind.TOOL_CALL -> toolCallId?.let { openToolCallIds.add(it) }
      ExternalAgentEventKind.TOOL_RESULT -> toolCallId?.let { openToolCallIds.remove(it) }
      else -> {}
    }
    val n = seq.getAndIncrement()
    val event = ExternalAgentEventDto(
      id = "$id-$n",
      runId = id,
      seq = n,
      timeMs = System.currentTimeMillis(),
      agentType = agentType,
      kind = kind,
      status = status,
      title = title,
      text = text?.boundForRetention(),
      toolName = toolName,
      toolCallId = toolCallId,
      input = input?.toString()?.boundForRetention(),
      output = output?.toString()?.boundForRetention(),
      uiCommand = uiCommand,
      usage = usage?.toString()?.boundForRetention(),
      raw = raw?.toString()?.boundForRetention(),
    )
    synchronized(events) {
      events += event
      while (events.size > EXTERNAL_AGENT_MAX_EVENTS) events.removeAt(0)
    }
    lastActivityAtMs = event.timeMs
    raw?.let { captureExternalThreadId(it) }
    return event
  }

  // Truncated payloads stop being valid JSON; the UI's JsonBlock already falls back to plain-text
  // rendering for unparseable strings, so a truncated blob still displays (with the marker).
  private fun String.boundForRetention(): String =
    if (length <= EXTERNAL_AGENT_MAX_FIELD_CHARS) this
    else take(EXTERNAL_AGENT_MAX_FIELD_CHARS) + "…[truncated ${length - EXTERNAL_AGENT_MAX_FIELD_CHARS} chars]"

  fun finish(status: ExternalAgentSessionStatus, exitCode: Int?, error: String?) {
    applyFinish(status, exitCode, error)
  }

  fun finishIfNeeded(status: ExternalAgentSessionStatus, exitCode: Int?, error: String?) {
    if (endedAtMs != null) return
    applyFinish(status, exitCode, error)
  }

  private fun applyFinish(status: ExternalAgentSessionStatus, exitCode: Int?, error: String?) {
    this.status = status
    this.exitCode = exitCode
    this.error = error
    this.endedAtMs = System.currentTimeMillis()
    emit(
      // A failure must surface in the transcript as a red error card with its reason — a
      // lifecycle event would fold into the collapsed system group and bury the WHY.
      kind = if (status == ExternalAgentSessionStatus.FAILED) ExternalAgentEventKind.ERROR else ExternalAgentEventKind.LIFECYCLE,
      status = status,
      title = when (status) {
        ExternalAgentSessionStatus.COMPLETED -> "Process completed"
        ExternalAgentSessionStatus.CANCELLED -> "Process cancelled"
        ExternalAgentSessionStatus.FAILED -> "Process failed"
        ExternalAgentSessionStatus.RUNNING -> "Process running"
      },
      text = error ?: exitCode?.let { "exit code $it" },
    )
  }

  private fun captureExternalThreadId(raw: JsonElement) {
    val obj = raw as? JsonObject ?: return
    obj.string("session_id")?.let { externalThreadId = it }
    obj.string("thread_id")?.let { externalThreadId = it }
  }
}

// Both must fire for a nudge: a first-person continue/wait promise AND a concrete duration.
// Conservative on purpose - a false nudge on a genuinely finished answer is worse than a missed
// one (the human can always reply by hand).
private val WAIT_PROMISE_PHRASE = Regex(
  "(?i)\\bI(?:'|\\u2019)ll\\s+(?:wait|continue|retry|resume|check(?:\\s+back)?|circle\\s+back)\\b" +
    "|\\bwill\\s+(?:continue|resume|retry)\\s+automatically\\b" +
    "|\\bwhen\\s+the\\s+wait\\s+(?:completes|finishes)\\b",
)
private val WAIT_PROMISE_DURATION = Regex(
  "(?i)\\b\\d+\\s*(?:(?:-|\\u2013|to)\\s*\\d+\\s*)?(?:s|secs?|seconds?|m|mins?|minutes?)\\b",
)

/**
 * True when a turn's final assistant text promises to keep working on its own after the turn ends
 * (e.g. "I'll check back in 30 seconds") - a promise the process model can't keep, because nothing
 * resumes a finished CLI turn. Pure over the text; internal for direct unit testing.
 */
internal fun detectWaitPromise(finalText: String?): Boolean {
  val text = finalText?.takeIf { it.isNotBlank() } ?: return false
  return WAIT_PROMISE_PHRASE.containsMatchIn(text) && WAIT_PROMISE_DURATION.containsMatchIn(text)
}

/**
 * The compact "what the human demonstrated" block prepended to a reply: one entry per
 * [ExternalAgentEventKind.HUMAN_ACTION] emitted after the last user message, carrying the recorded
 * step YAML and the on-disk evidence paths (before/after screenshot + view hierarchy). Empty when
 * nothing was demonstrated. Internal for direct unit testing.
 */
internal fun demonstrationPreamble(events: List<ExternalAgentEventDto>): String {
  val all = events
    .takeLastWhile { it.kind != ExternalAgentEventKind.USER_MESSAGE }
    .filter { it.kind == ExternalAgentEventKind.HUMAN_ACTION }
  if (all.isEmpty()) return ""
  // The preamble rides the child's argv, which has a hard OS limit - an unbounded action list
  // would fail the exec and lose the whole demonstration. Keep the most recent actions.
  val actions = all.takeLast(DEMONSTRATION_PREAMBLE_MAX_ACTIONS)
  return buildString {
    append("The human demonstrated ")
    append(if (all.size == 1) "an action" else "${all.size} actions")
    append(" on the live device since your last turn, in order. ")
    if (actions.size < all.size) {
      append("Only the most recent ${actions.size} fit here (the earlier ${all.size - actions.size} were dropped). ")
    }
    append("Transcribe them into draft steps now. ")
    append("Each action lists its recorded step YAML and its evidence files (before/after screenshot ")
    append("and view hierarchy); read the hierarchy files to ground selectors and assertions.\n")
    actions.forEachIndexed { i, e ->
      val out = runCatching { JSON.parseToJsonElement(e.output.orEmpty()) }.getOrNull() as? JsonObject
      append("\n${i + 1}. ${e.title ?: "Action"}")
      val element = out?.obj("element")
      val label = element?.string("label") ?: element?.string("resourceId")
      if (label != null) {
        append(" - on \"$label\"")
        element?.string("type")?.let { append(" ($it)") }
      }
      val evidence = out?.obj("evidence")
      when (evidence?.bool("screenChanged")) {
        true -> append(" [the screen changed]")
        false -> append(" [the screen did NOT change - this action may not have landed]")
        null -> {}
      }
      out?.string("yaml")?.takeIf { it.isNotBlank() }?.let { yaml ->
        append("\n   recorded step:\n")
        append(yaml.trim().prependIndent("   "))
      }
      val dir = evidence?.string("dir")
      val files = listOf("before", "after").flatMap { side ->
        evidence?.obj(side)?.let { listOfNotNull(it.string("screenshot"), it.string("hierarchy")) }.orEmpty()
      }
      if (dir != null && files.isNotEmpty()) {
        append("\n   evidence: ")
        append(files.joinToString(", ") { "$dir/$it" })
      }
      append("\n")
    }
    append("\nHuman's message:\n")
  }
}

private fun parseExternalAgentLine(
  run: MutableExternalAgentRun,
  line: String,
): List<ExternalAgentEventDraft> = parseExternalAgentLine(run.agentType, line)

internal fun parseExternalAgentLine(
  agentType: ExternalAgentType,
  line: String,
): List<ExternalAgentEventDraft> {
  val raw = runCatching { JSON.parseToJsonElement(line) }.getOrNull()
  if (raw == null) {
    return buildList {
      add(ExternalAgentEventDraft(kind = ExternalAgentEventKind.STDOUT, title = "stdout", text = line))
      addAll(uiCommandEventsFromText(line, raw = null))
    }
  }
  return when (agentType) {
    ExternalAgentType.CLAUDE -> parseClaudeEvent(raw)
    ExternalAgentType.CODEX -> parseCodexEvent(raw)
    // Unreachable: a solo run never spawns a process, so there is no stream to parse.
    ExternalAgentType.SOLO -> error("a solo session has no agent stream to parse")
  }
}

private fun parseClaudeEvent(raw: JsonElement): List<ExternalAgentEventDraft> {
  val obj = raw as? JsonObject ?: return listOf(genericRawEvent(raw, "Claude event"))
  return when (val type = obj.string("type")) {
    "system" -> when (val subtype = obj.string("subtype")) {
      "init" -> listOf(
        ExternalAgentEventDraft(
          kind = ExternalAgentEventKind.LIFECYCLE,
          status = ExternalAgentSessionStatus.RUNNING,
          title = "Claude initialized",
          text = listOfNotNull(obj.string("model"), obj.string("cwd")).joinToString(" · "),
          raw = raw,
        ),
      )
      // Per-token progress ticks — one line per thinking batch would flood the feed; the run's
      // token totals arrive with the final result's usage.
      "thinking_tokens" -> emptyList()
      else -> listOf(genericRawEvent(raw, "Claude ${subtype ?: "system"}"))
    }
    "assistant" -> {
      val message = obj.obj("message")
      val content = message?.get("content")?.jsonArray.orEmpty()
      buildList {
        content.forEach { part ->
          val partObj = part as? JsonObject ?: return@forEach
          when (partObj.string("type")) {
            "text" -> {
              val text = partObj.string("text").orEmpty()
              add(
                ExternalAgentEventDraft(
                  kind = ExternalAgentEventKind.ASSISTANT_MESSAGE,
                  title = "Claude",
                  text = text,
                  raw = raw,
                ),
              )
              addAll(uiCommandEventsFromText(text, raw))
            }
            "tool_use" -> {
              add(
                ExternalAgentEventDraft(
                  kind = ExternalAgentEventKind.TOOL_CALL,
                  title = partObj.string("name") ?: "tool call",
                  toolName = partObj.string("name"),
                  toolCallId = partObj.string("id"),
                  input = partObj["input"],
                  raw = raw,
                ),
              )
              // Claude's native AskUserQuestion never gets answered in headless stream-json mode
              // (there's no terminal UI attached), so without this mapping the transcript showed
              // a bare "Pick one:" with no options and the run stalled. Surface each question as
              // the same ask_user UI command the TRAILRUNNER_UI text contract produces - the
              // composer renders clickable answer chips and the click flows back via /reply.
              if (partObj.string("name") == "AskUserQuestion") {
                addAll(askUserCommandsFromClaudeToolUse(partObj, raw))
              }
            }
          }
        }
        if (isEmpty()) add(genericRawEvent(raw, "Claude assistant event"))
      }
    }
    "user" -> {
      val message = obj.obj("message")
      val content = message?.get("content")?.jsonArray.orEmpty()
      buildList {
        content.forEach { part ->
          val partObj = part as? JsonObject ?: return@forEach
          if (partObj.string("type") == "tool_result") {
            add(
              ExternalAgentEventDraft(
                kind = ExternalAgentEventKind.TOOL_RESULT,
                title = "Tool result",
                text = claudeContentText(partObj["content"]),
                toolCallId = partObj.string("tool_use_id"),
                output = obj["tool_use_result"] ?: part,
                raw = raw,
              ),
            )
          }
        }
        if (isEmpty()) add(genericRawEvent(raw, "Claude user event"))
      }
    }
    "result" -> listOf(
      ExternalAgentEventDraft(
        kind = if (obj.bool("is_error") == true) ExternalAgentEventKind.ERROR else ExternalAgentEventKind.FINAL_RESULT,
        status = if (obj.bool("is_error") == true) ExternalAgentSessionStatus.FAILED else ExternalAgentSessionStatus.COMPLETED,
        title = obj.string("terminal_reason") ?: obj.string("subtype") ?: "Result",
        text = obj.string("result"),
        usage = obj["usage"],
        raw = raw,
      ),
    )
    else -> listOf(genericRawEvent(raw, "Claude $type"))
  }
}

private fun parseCodexEvent(raw: JsonElement): List<ExternalAgentEventDraft> {
  val obj = raw as? JsonObject ?: return listOf(genericRawEvent(raw, "Codex event"))
  return when (val type = obj.string("type")) {
    "thread.started" -> listOf(
      ExternalAgentEventDraft(
        kind = ExternalAgentEventKind.LIFECYCLE,
        status = ExternalAgentSessionStatus.RUNNING,
        title = "Codex thread started",
        text = obj.string("thread_id"),
        raw = raw,
      ),
    )
    "turn.started" -> listOf(
      ExternalAgentEventDraft(
        kind = ExternalAgentEventKind.LIFECYCLE,
        status = ExternalAgentSessionStatus.RUNNING,
        title = "Codex turn started",
        raw = raw,
      ),
    )
    "turn.completed" -> listOf(
      ExternalAgentEventDraft(
        kind = ExternalAgentEventKind.USAGE,
        title = "Codex turn completed",
        usage = obj["usage"],
        raw = raw,
      ),
    )
    "item.started", "item.completed" -> parseCodexItem(raw, obj, started = type == "item.started")
    else -> listOf(genericRawEvent(raw, "Codex $type"))
  }
}

private fun parseCodexItem(
  raw: JsonElement,
  obj: JsonObject,
  started: Boolean,
): List<ExternalAgentEventDraft> {
  val item = obj.obj("item") ?: return listOf(genericRawEvent(raw, "Codex item"))
  return when (val itemType = item.string("type")) {
    "agent_message" -> {
      val text = item.string("text").orEmpty()
      buildList {
        add(
          ExternalAgentEventDraft(
            kind = ExternalAgentEventKind.ASSISTANT_MESSAGE,
            title = "Codex",
            text = text,
            raw = raw,
          ),
        )
        addAll(uiCommandEventsFromText(text, raw))
      }
    }
    "command_execution" -> listOf(
      ExternalAgentEventDraft(
        kind = if (started) ExternalAgentEventKind.TOOL_CALL else ExternalAgentEventKind.TOOL_RESULT,
        title = item.string("command") ?: "command",
        toolName = "shell",
        toolCallId = item.string("id"),
        input = if (started) item else null,
        output = if (started) null else item,
        text = if (started) item.string("command") else item.string("aggregated_output"),
        raw = raw,
      ),
    )
    "reasoning" -> listOf(
      ExternalAgentEventDraft(
        kind = ExternalAgentEventKind.REASONING,
        title = "Reasoning",
        text = item.string("text"),
        raw = raw,
      ),
    )
    else -> listOf(genericRawEvent(raw, "Codex $itemType"))
  }
}

private fun genericRawEvent(raw: JsonElement, title: String): ExternalAgentEventDraft =
  ExternalAgentEventDraft(kind = ExternalAgentEventKind.STDOUT, title = title, text = raw.toString(), raw = raw)

// Claude message content is either a plain string or an array of typed blocks; for display, a
// block array reads as its text blocks joined (MCP tool results commonly arrive as block arrays).
private fun claudeContentText(element: JsonElement?): String? = when (element) {
  null -> null
  is JsonArray -> element
    .mapNotNull { block -> (block as? JsonObject)?.takeIf { it.string("type") == "text" }?.string("text") }
    .joinToString("\n")
    .takeIf { it.isNotEmpty() }
  is JsonPrimitive -> element.contentOrNull
  else -> null
}

private fun uiCommandEventsFromText(text: String, raw: JsonElement?): List<ExternalAgentEventDraft> =
  UI_COMMAND_LINE.findAll(text).mapNotNull { match ->
    val json = match.groupValues[1]
    val element = runCatching { JSON.parseToJsonElement(json) }.getOrNull() ?: return@mapNotNull null
    val command = runCatching {
      JSON.decodeFromJsonElement(TrailRunnerUiCommandDto.serializer(), element)
    }.getOrNull() ?: return@mapNotNull null
    ExternalAgentEventDraft(
      kind = ExternalAgentEventKind.UI_COMMAND,
      title = command.action,
      text = command.message,
      uiCommand = command,
      raw = raw ?: element,
    )
  }.toList()

private val UI_COMMAND_LINE = Regex("""(?m)^\s*TRAILRUNNER_UI\s*:?\s*(\{.*})\s*$""")

/**
 * Maps a Claude `AskUserQuestion` tool_use block (`input.questions[]`, each with `question` and
 * `options[].label`) onto ask_user [TrailRunnerUiCommandDto] drafts - one per question, options
 * pipe-joined into `params.options`, the same wire shape [uiCommandEventsFromText] parses from
 * the text-marker contract, so the web composer's answer-chip rendering needs no second path.
 */
private fun askUserCommandsFromClaudeToolUse(partObj: JsonObject, raw: JsonElement): List<ExternalAgentEventDraft> {
  val questions = partObj.obj("input")?.get("questions")?.jsonArray.orEmpty()
  return questions.mapNotNull { q ->
    val qObj = q as? JsonObject ?: return@mapNotNull null
    val message = qObj.string("question")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    val options = qObj["options"]?.jsonArray.orEmpty().mapNotNull { opt ->
      when (opt) {
        is JsonObject -> opt.string("label")
        is JsonPrimitive -> opt.contentOrNull
        else -> null
      }?.takeIf { it.isNotBlank() }
    }
    ExternalAgentEventDraft(
      kind = ExternalAgentEventKind.UI_COMMAND,
      title = "ask_user",
      text = message,
      uiCommand = TrailRunnerUiCommandDto(
        action = "ask_user",
        message = message,
        params = if (options.isEmpty()) emptyMap() else mapOf("options" to options.joinToString("|")),
      ),
      raw = raw,
    )
  }
}

private fun ExternalAgentRunRequest.promptForChild(extraContext: String): String {
  // The hidden preamble (a session recipe the UI armed) rides ahead of the human's words in the
  // child prompt only - the USER_MESSAGE event carries just `prompt`, so the transcript stays
  // the human's own message.
  val preamble = promptPreamble?.trim()?.takeIf { it.isNotEmpty() }
  val task = if (preamble == null) prompt else preamble + "\n\n" + prompt
  val base = if (includeUiContract && agentType == ExternalAgentType.CODEX) {
    TRAILRUNNER_UI_CONTRACT + uiContextPrompt() + extraContext + "\n\nUser task:\n" + task
  } else {
    task
  }
  return base
}

// Tells the agent where run artifacts land, so it can read them to debug a run and iterate on a
// trail. Every Trailblaze run (including ones the agent itself starts via MCP or the CLI) writes
// its session folder here: screenshots, logs, LLM request logs, and captured event streams.
private fun artifactsPrompt(artifactsRoot: File?): String {
  if (artifactsRoot == null) return ""
  return "\n\nRun artifacts: every Trailblaze run writes its artifacts under " +
    "${artifactsRoot.absolutePath}/<session id>/ — screenshots, device logs, LLM request logs, and " +
    "captured event streams (events/*.ndjson). The newest directory is the most recent run; read " +
    "these files to debug a failing run and iterate on the trail."
}

private fun ExternalAgentRunRequest.uiContextPrompt(): String {
  val ctx = uiContext ?: return ""
  val parts = listOfNotNull(
    ctx.route?.let { "route=$it" },
    ctx.trailId?.let { "trailId=$it" },
    ctx.sessionId?.let { "sessionId=$it" },
    ctx.target?.let { "target=$it" },
    ctx.platform?.let { "platform=$it" },
    ctx.deviceId?.let { "deviceId=${it.instanceId}/${it.trailblazeDevicePlatform}" },
  )
  return if (parts.isEmpty()) "" else "\n\nCurrent Trail Runner UI context: ${parts.joinToString(", ")}"
}

private fun ExternalAgentType.executable(): String = when (this) {
  ExternalAgentType.CLAUDE -> "claude"
  ExternalAgentType.CODEX -> "codex"
  ExternalAgentType.SOLO -> error("a solo session has no agent executable")
}

private fun ExternalAgentType.displayName(): String = when (this) {
  ExternalAgentType.CLAUDE -> "Claude Code"
  ExternalAgentType.CODEX -> "Codex CLI"
  ExternalAgentType.SOLO -> "Solo"
}

// The daemon's own uber jar, when it was launched as one (single-entry .jar classpath). Null in
// Gradle-run mode — children then fall back to the launcher's normal resolution.
private fun currentUberJarPath(): String? =
  System.getProperty("java.class.path")
    ?.takeIf { !it.contains(File.pathSeparatorChar) }
    ?.takeIf { it.endsWith(".jar") }
    ?.let { path -> File(path).takeIf(File::isFile)?.absolutePath }

private fun redactedCommand(command: List<String>): String {
  if (command.isEmpty()) return ""
  return command.mapIndexed { index, token ->
    val previous = command.getOrNull(index - 1)
    when {
      index == command.lastIndex -> "<prompt>"
      previous == "--append-system-prompt" -> "<ui-contract>"
      previous == "--mcp-config" -> "<mcp-config>"
      // Codex TOML override carrying the daemon's full classpath — pages of jar paths.
      token.startsWith("mcp_servers.trailblaze.args=") -> "mcp_servers.trailblaze.args=<daemon-classpath>"
      else -> token
    }
  }.joinToString(" ")
}

// The spawned MCP server re-enters THIS daemon's own JVM + classpath (see McpProxyMain.kt). A
// PATH/cwd-resolved `trailblaze` can be a different build than the running daemon (Homebrew
// release next to a source-checkout daemon - its proxy then lacks the approval_prompt injection,
// so `--permission-prompt-tool` fatally fails at claude startup), and a source checkout's
// `./trailblaze` wrapper rebuilds jars and stops running daemons as a side effect.
internal const val MCP_PROXY_MAIN_CLASS = "xyz.block.trailblaze.cli.McpProxyMainKt"

internal fun daemonJavaBin(): String {
  val exe = if (File.separatorChar == '\\') "java.exe" else "java"
  return File(File(System.getProperty("java.home"), "bin"), exe).absolutePath
}

internal fun daemonClasspath(): String = System.getProperty("java.class.path").orEmpty()

/**
 * The port this daemon is actually serving on, read from the same holder the scripting-callback
 * subprocesses use ([JsScriptingCallbackBaseUrl], set on every server-init path and after port
 * overrides). Falls back to the CLI resolution when unset (unit tests, pre-init calls).
 */
internal fun daemonPort(): Int =
  JsScriptingCallbackBaseUrl.get()
    ?.let { runCatching { java.net.URI(it).port }.getOrNull() }
    ?.takeIf { it > 0 }
    ?: CliConfigHelper.resolveEffectiveHttpPort()

private fun trailblazeMcpConfig(permissionRunId: String? = null): String {
  val env = buildList {
    // Dedicated variable (not TRAILBLAZE_PORT): the CLI's normal resolution lets a persisted
    // non-default `serverPort` outrank the environment, which could aim the proxy at a daemon
    // other than the one that spawned it.
    add(""""TRAILRUNNER_DAEMON_PORT":"${daemonPort()}"""")
    // When permissions are enforced, the run id lets the proxy's approval_prompt interception
    // route a prompt back to the right Trail Runner run.
    permissionRunId?.takeIf { it.isNotBlank() }?.let {
      add(""""TRAILRUNNER_PERMISSION_RUN_ID":${it.jsonString()}""")
    }
  }.joinToString(",")
  val args = """["-cp",${daemonClasspath().jsonString()},"$MCP_PROXY_MAIN_CLASS"]"""
  return """{"mcpServers":{"trailblaze":{"command":${daemonJavaBin().jsonString()},"args":$args,"env":{$env}}}}"""
}

/**
 * `["-jar", <jar>]` or `["-cp", <classpath>, <main class>]` - whichever re-creates this daemon's
 * own CLI entry point. `sun.java.command` is `"<main class> <args>"` for `-cp` launches and
 * `"<jar path> <args>"` for `-jar` launches; null on JVMs that don't expose it (no shim then).
 */
private fun daemonCliEntry(): List<String>? {
  val first = System.getProperty("sun.java.command")
    ?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() } ?: return null
  return if (first.endsWith(".jar")) listOf("-jar", first) else listOf("-cp", daemonClasspath(), first)
}

/**
 * Directory holding a `trailblaze` shim that runs the CLI from this daemon's own classpath,
 * prepended to every child's PATH. Same rationale as [MCP_PROXY_MAIN_CLASS]: the agent's shell
 * `trailblaze` must match the daemon build, and must never be the source checkout's wrapper
 * (which rebuilds jars and stops running daemons mid-conversation). Written once per daemon
 * process - the content depends only on the daemon's own JVM, classpath, and port.
 */
internal val sessionCliDir: File? by lazy {
  if (File.separatorChar == '\\') return@lazy null // sh shim; Windows children keep PATH resolution
  val entry = daemonCliEntry() ?: return@lazy null
  runCatching {
    val dir = File(File(System.getProperty("user.home"), ".trailblaze"), "agent-cli/${daemonPort()}")
    dir.mkdirs()
    val shim = File(dir, "trailblaze")
    val exec = (listOf(daemonJavaBin()) + entry).joinToString(" ") { shellQuote(it) }
    shim.writeText(
      """
      #!/bin/sh
      # Generated by the Trail Runner daemon on port ${daemonPort()}: runs the Trailblaze CLI from
      # the daemon's own classpath, so agent shell calls always match the daemon build and never
      # trigger a source checkout wrapper's rebuild-and-restart side effects.
      export TRAILBLAZE_PORT=${daemonPort()}
      exec $exec "${'$'}@"
      """.trimIndent() + "\n",
    )
    shim.setExecutable(true)
    dir
  }.getOrNull()
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

private fun String.jsonString(): String =
  "\"" + flatMap { c ->
    when (c) {
      '\\' -> "\\\\".asIterable()
      '"' -> "\\\"".asIterable()
      '\n' -> "\\n".asIterable()
      '\r' -> "\\r".asIterable()
      '\t' -> "\\t".asIterable()
      else -> listOf(c)
    }
  }.joinToString("") + "\""

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.bool(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
