package xyz.block.trailblaze.trailrunner

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ─── Companion sessions ──────────────────────────────────────────────────────
//
// The inverse of a spawned run: the agent CLI already exists OUTSIDE Trail Runner (the human's own
// Claude Code / Codex session, running in their own repo) and attaches here as a companion. The
// daemon owns no process - the external agent streams its narration as events and declares the
// trail folder it is authoring on disk, which the live trail rail watches.

/** Companion attachment details, present only on companion runs. */
@Serializable
data class CompanionStateDto(
  /** How the external agent introduced itself, e.g. "Claude Code · myapp". */
  val agentLabel: String? = null,
  /** Trail folder the agent is authoring, relative to the primary trails root; null until declared. */
  val folder: String? = null,
  /**
   * The standing directives (latest per name), keyed by directive. The daemon owns this state so
   * it survives event retention and window reloads - the companion screen derives its guidance
   * cards from here, not by replaying the transcript. Never contains `navigate` (live-only).
   */
  val directives: Map<String, CompanionDirectiveDto> = emptyMap(),
  /**
   * The shared-brain request queue (all requests this session, keyed by requestId). Daemon-owned
   * like [directives] so a reloaded window reconciles its spinners from here, not the transcript.
   */
  val requests: Map<String, CompanionRequestDto> = emptyMap(),
)

/** One standing companion directive: the payload the agent last sent under this name. */
@Serializable
data class CompanionDirectiveDto(
  /** Seq of the UI_COMMAND event that set it - the correlation id quick replies carry back. */
  val seq: Int,
  /** The directive's payload as compact JSON, same encoding as the event's `input` field. */
  val payload: String? = null,
)

/**
 * One shared-brain request: a Trail Runner "brainy" UI action (propose steps, review my trail)
 * deferred to the attached agent instead of a second LLM. Enqueued only by the daemon's defer
 * path; the agent may only settle it via respond.
 */
@Serializable
data class CompanionRequestDto(
  val requestId: String,
  /** What is being asked, e.g. "review-trail" or "propose-steps". */
  val kind: String,
  /** Request context as compact JSON, same encoding as the agent-request event's `input`. */
  val payload: String? = null,
  /** pending | done | error | cancelled. */
  val status: String,
  /** Optional agent-supplied note carried on the respond. */
  val note: String? = null,
)

/** Body of `/companion/{id}/respond`: the agent settling a pending shared-brain request. */
@Serializable
data class CompanionRespondRequest(
  val requestId: String? = null,
  /** done | error. */
  val status: String? = null,
  val note: String? = null,
)

/**
 * Body of `/companion/connect`. [folder] is validated (containment under the primary trails root)
 * but does not need to exist yet - the agent may attach before its first write.
 */
@Serializable
data class CompanionConnectRequest(
  /** Which vendor CLI is attaching (display/labeling only - no process is spawned). Default claude. */
  val agentType: ExternalAgentType? = null,
  val agentLabel: String? = null,
  val title: String? = null,
  val folder: String? = null,
)

/**
 * Response to `/companion/connect`. [primaryRoot] is the daemon's effective primary trails root so
 * the attaching CLI can detect a workspace mismatch (daemon rooted at a different workspace) and
 * warn the human instead of silently watching the wrong tree. [runId] is flat and first so the
 * shell launcher can extract it with a plain regex.
 */
@Serializable
data class CompanionConnectResponse(
  val ok: Boolean,
  val runId: String? = null,
  val primaryRoot: String? = null,
  val error: String? = null,
)

/** Body of `/companion/{id}/event`: one narration event from the attached external agent. */
@Serializable
data class CompanionEventRequest(
  /** assistant_message (default), lifecycle, or error. */
  val kind: String? = null,
  val title: String? = null,
  val text: String? = null,
)

/** Body of `/companion/{id}/disconnect`. */
@Serializable
data class CompanionDisconnectRequest(val note: String? = null)

/**
 * Body of `/companion/{id}/directive`: one UI directive from the attached external agent - the
 * agent steering what the companion window shows (a banner, a checklist, quick replies, an armed
 * recording). Rides the run's event stream as a UI_COMMAND event whose title is the directive and
 * whose input is [payload], and (except `navigate`, which is live-only) also lands in the run's
 * standing-directive state ([CompanionStateDto.directives]), which is what the companion screen
 * renders - so a reloaded window rebuilds the same view without replaying the transcript.
 */
@Serializable
data class CompanionDirectiveRequest(
  /** navigate, banner, checklist, actions, select-device, select-app-target, or arm-recording. */
  val directive: String? = null,
  /** Directive-specific fields (text, items, route, …). An empty or absent payload RETRACTS the directive. */
  val payload: JsonElement? = null,
)

/**
 * Body of `/companion/{id}/user-action`: something the human did in the companion window, streamed
 * back to the attached agent (which tails the run's SSE stream / journal). Rides the event stream
 * as a HUMAN_ACTION event whose title is the [type] and whose input is [payload].
 */
@Serializable
data class CompanionUserActionRequest(
  /** user-action (a quick reply), handback, or device-connected. `recording-saved` is NOT postable: only the daemon's own save path emits it, so hearing it means the write really landed. */
  val type: String? = null,
  val payload: JsonElement? = null,
)

/**
 * Body of `/companion/{id}/save-recording`: the single sanctioned UI write in companion mode - a
 * recorded variant saved into the session's declared folder. The daemon resolves the destination
 * itself and emits the recording-saved user action after the write lands.
 */
@Serializable
data class CompanionSaveRecordingRequest(
  /** Variant name, normally the platform key (ios, android); becomes `<variant>.trail.yaml`. */
  val variant: String? = null,
  val yaml: String? = null,
  /** Device platform that recorded it (ios, android); echoed on the recording-saved event so the agent needn't infer it from the variant name. */
  val platform: String? = null,
)

@Serializable
data class CompanionSaveRecordingResponse(
  val ok: Boolean,
  val savedPath: String? = null,
  val error: String? = null,
)

/** One entry of the demo-files tree: a file or directory inside the declared trail folder. */
@Serializable
data class CompanionFolderEntryDto(
  /** Path relative to the declared folder, `/`-separated. */
  val path: String,
  val dir: Boolean = false,
  /** Size in bytes; 0 for directories. */
  val size: Long = 0,
)

/** Response to `/companion/{id}/folder-tree`: the declared folder's recursive listing. */
@Serializable
data class CompanionFolderTreeResponse(
  val ok: Boolean,
  val entries: List<CompanionFolderEntryDto> = emptyList(),
  val error: String? = null,
)

/** Body of `/companion/{id}/open-file`: open one file of the declared folder in the human's editor. */
@Serializable
data class CompanionOpenFileRequest(val path: String? = null)
