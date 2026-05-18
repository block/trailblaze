@file:Suppress("DEPRECATION")

package xyz.block.trailblaze.ui.recording

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator
import xyz.block.trailblaze.recording.RecordedInteraction
import xyz.block.trailblaze.recording.RecordingYamlCodec
import xyz.block.trailblaze.toolcalls.RawCoordinateTapTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor

/**
 * Active editor inside [ActionYamlCard]'s edit mode. Both modes are present when the tool's
 * descriptor resolves to a flat-form-compatible shape; YAML is the only viable mode for
 * tools the form can't represent (`isForLlm = false` wrappers, tools with nested params).
 * The toggle button only renders when both are viable.
 *
 * Internal to [ActionYamlCard]; exposed only because the composable's body branches on it.
 */
enum class ActionEditorMode { FORM, YAML }

/**
 * Card displaying a single recorded action's YAML with delete, play, edit, and selector-swap
 * controls. Shared between the desktop recording tab and the web `/devices` viewer: both
 * surfaces use this exact composable so insert / change-selector / replay / edit feel
 * identical regardless of which front-end the user opened.
 *
 * **Host-only descriptor lookup**: the rich-form editor needs a JVM-only descriptor reflection
 * pipeline (see `RichEditorSupport.resolveDescriptorAndValues`) — that lookup is passed in as
 * [descriptorResolver] rather than baked in, so wasm callers can pass `{ _ -> null }` and the
 * card gracefully degrades to YAML-only editing for tools that would otherwise be form-editable.
 * Desktop callers pass the real reflection-backed lookup.
 *
 * The selector-swap dropdown is unconditionally available on both platforms — it only needs
 * the [RecordedInteraction.selectorCandidates] / [RecordedInteraction.capturedTree] that
 * the platform-neutral [TrailblazeNodeSelectorGenerator] already produces.
 *
 * @param descriptorResolver returns `(descriptor, current-values)` for the tool, or null when
 *   the tool can't be rendered as a flat form (`isForLlm = false` tools, nested params, etc.).
 *   Pass `{ _ -> null }` on platforms without JVM reflection (Compose Web / WASM) to disable
 *   the rich-form editor while keeping every other control available.
 */
@Composable
fun ActionYamlCard(
  index: Int,
  interaction: RecordedInteraction,
  isRecording: Boolean,
  isReplaying: Boolean,
  /** True when a "replay from this index onward" run is in progress (cascading spinner). */
  isReplayingFromHere: Boolean,
  onDelete: () -> Unit,
  onReplay: () -> Unit,
  /** Replay this card AND every card after it as a single multi-tool trail run. */
  onReplayFromHere: () -> Unit,
  onSelectorChosen: (TrailblazeNodeSelectorGenerator.NamedSelector) -> Unit,
  onEditYaml: (String) -> Result<Unit>,
  descriptorResolver: (TrailblazeTool) -> Pair<TrailblazeToolDescriptor, Map<String, String>>?,
) {
  val yaml = remember(interaction) {
    RecordingYamlCodec.singleToolToYaml(interaction)
  }
  var showSelectorMenu by remember(interaction) { mutableStateOf(false) }

  // Cheap eager hit-test, only for tapOnPoint cards. Drives the Tune icon's visibility AND the
  // round-trip warning chip — both need to know "does this tap point land on a selectable node,
  // and would the resolved selector round-trip back?" before the user decides to open the
  // dropdown. Keyed off `tool.x` / `tool.y` rather than `interaction.tapPoint` so a YAML
  // coordinate edit drives a fresh resolution against the new coords (the original tap point
  // stays on the interaction for posterity but isn't authoritative once the user has edited).
  val tapResolution = remember(interaction) {
    val tool = interaction.tool
    if (tool is RawCoordinateTapTool) {
      interaction.capturedTree?.let { tree ->
        TrailblazeNodeSelectorGenerator.resolveFromTap(tree, tool.x, tool.y)
      }
    } else {
      null
    }
  }

  // Lazy: full strategy enumeration is only computed when the dropdown actually opens. Findall-
  // ValidSelectors walks the tree several times (once per strategy) and validates uniqueness
  // for each — keeping that out of the per-frame composition path matters as the recording grows
  // (a 50-card session with 5 strategies × tree-walk-per-strategy was a noticeable composition
  // pause before this split).
  //   - TapOnByElementSelector: precomputed candidates from the recorder. We already emitted
  //     candidates[0]; the picker is only useful when there's a *different* option to pick.
  //   - TapOnPointTrailblazeTool with a target node: recompute against the captured tree on
  //     menu open. Even one candidate is an upgrade over the bare coordinate.
  //   - Anything else (inputText, swipe, navigate, ...): no picker.
  val effectiveCandidates: List<TrailblazeNodeSelectorGenerator.NamedSelector> = remember(interaction, showSelectorMenu) {
    if (!showSelectorMenu) return@remember emptyList()
    when (interaction.tool) {
      is RawCoordinateTapTool -> {
        val tree = interaction.capturedTree
        val target = tapResolution?.targetNode
        if (tree != null && target != null) {
          TrailblazeNodeSelectorGenerator.findAllValidSelectors(tree, target, maxResults = 5)
        } else {
          emptyList()
        }
      }
      else -> interaction.selectorCandidates
    }
  }

  // For TapOnByElementSelector, candidates[0] matches the emitted tool — only worth surfacing
  // the picker when there's at least one *different* option (size > 1). For TapOnPointTrailblaze-
  // Tool, every candidate is an upgrade over the bare coordinate, so a single hit is enough —
  // gate on the cheap `tapResolution` so we don't have to materialize the full candidate list
  // just to decide whether to show the icon.
  val canSwapSelector = when (interaction.tool) {
    is RawCoordinateTapTool -> tapResolution != null
    else -> interaction.selectorCandidates.size > 1
  }

  // Show the warning when the live (re-)resolution against the current tool coords doesn't
  // round-trip — which is exactly the case where a swap could pick a different element than
  // the user expects. Driving off `tapResolution.roundTripValid` rather than `tool is
  // TapOnPointTrailblazeTool` keeps the message honest for tools the user manually edited
  // into a tapOnPoint shape on a layout where the resolution does round-trip cleanly.
  val showRoundTripWarning = tapResolution != null && !tapResolution.roundTripValid

  // Inline edit state. Reset when the underlying interaction changes (e.g. selector swap or
  // a fresh tap recording lands at this index) so a stale draft doesn't survive.
  var isEditing by remember(interaction) { mutableStateOf(false) }
  var draft by remember(interaction) { mutableStateOf(yaml) }
  var editError by remember(interaction) { mutableStateOf<String?>(null) }

  // Try to resolve the tool's descriptor + current values for the rich form. Returns null when
  // the tool is `isForLlm = false` (`SwipeWithRelativeCoordinatesTool` and other internal/
  // wrapper tools), when any field is non-primitive (nested selectors, nested params, lists),
  // or on platforms where the resolver lambda is a no-op stub (wasmJs has no JVM reflection,
  // so the web callers pass `{ _ -> null }`). In all of those cases we fall back to the raw
  // YAML editor below — that path round-trips arbitrary structure without losing data.
  val richEditState = remember(interaction) {
    descriptorResolver(interaction.tool)
  }
  // Working copy of param values — survives the descriptor lookup but resets when the
  // interaction changes (selector swap, fresh recording at this index).
  val richEditValues = remember(interaction) {
    val initial = richEditState?.second ?: emptyMap()
    mutableStateMapOf<String, String>().apply { putAll(initial) }
  }
  // Toggle between the form and the YAML textarea while editing. Defaults to the form when
  // a descriptor resolves (richer surface for the common case), falls back to YAML
  // automatically for tools the form can't represent. The toggle button is only rendered
  // when both modes are viable — for YAML-only tools (form null) flipping to a non-existent
  // form would be confusing.
  var editorMode by remember(interaction) {
    mutableStateOf(if (richEditState != null) ActionEditorMode.FORM else ActionEditorMode.YAML)
  }

  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(8.dp)) {
      // Header: action label + buttons
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "#${index + 1} ${interaction.toolName}",
          style = MaterialTheme.typography.labelMedium,
        )
        // Action buttons (Edit / Replay / Delete / Tune) stay live during recording too. The
        // recorder mutations are guarded by the recorder's lock, and Replay calls go through
        // `DeviceScreenStream` directly — they execute on the device but don't bounce back
        // through the gesture handler, so the session log doesn't pick them up as new taps.
        // Locking these behind Stop made the recording feel one-shot when the rest of the UX
        // is a workbench.
        if (isEditing) {
          // Edit mode: only Save / Cancel. Replay / Delete / Tune don't make sense on a
          // draft that hasn't been committed yet.
          Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconButton(
              onClick = {
                // Form mode: serialize the current value bag to a single-tool YAML and
                // reuse the same `onEditYaml` parse path the textarea fallback uses. The
                // round-trip (form → YAML → recorder.replaceInteractionFromYaml) means the
                // recorder sees the same shape regardless of which editor produced the
                // edit, so downstream code (selector swap, replay, save) doesn't need to
                // branch on edit-mode. Textarea mode hands `draft` straight through.
                val pendingYaml = when (editorMode) {
                  ActionEditorMode.FORM -> {
                    val descriptor = richEditState?.first
                      ?: error("Form mode active without a resolved descriptor.")
                    buildSingleToolYaml(descriptor, richEditValues.toMap())
                  }
                  ActionEditorMode.YAML -> draft
                }
                val result = onEditYaml(pendingYaml)
                result.fold(
                  onSuccess = {
                    isEditing = false
                    editError = null
                  },
                  onFailure = { e ->
                    editError = e.message ?: e::class.simpleName ?: "Failed to parse YAML"
                  },
                )
              },
              modifier = Modifier.size(28.dp),
            ) {
              Icon(
                Icons.Filled.Check,
                contentDescription = "Save",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
              )
            }
            // Editor toggle. Only shown when both modes are viable — for tools the form
            // can't represent (richEditState == null) flipping to a non-existent form would
            // confuse rather than help. The icon mirrors what the *other* mode looks like
            // so the user knows what tapping it switches to.
            if (richEditState != null) {
              IconButton(
                onClick = {
                  editorMode = when (editorMode) {
                    ActionEditorMode.FORM -> {
                      // Going to YAML — serialize the current form values so the textarea
                      // shows what the form was about to commit, not the stale `draft`
                      // from before edit mode opened. Without this sync the user would
                      // see their form edits "disappear" the moment they toggle.
                      draft = buildSingleToolYaml(richEditState.first, richEditValues.toMap())
                      editError = null
                      ActionEditorMode.YAML
                    }
                    ActionEditorMode.YAML -> {
                      // Going to FORM — try to parse the current YAML draft and pull
                      // values into the form. If the YAML doesn't decode (mid-edit syntax
                      // error) or contains nested params the form can't render, surface
                      // the issue and stay on YAML so we don't silently drop the user's
                      // in-progress textarea edits.
                      val parsed = runCatching {
                        val wrapper = RecordingYamlCodec.decodeSingleToolYaml(draft)
                        descriptorResolver(wrapper.trailblazeTool)
                      }.getOrNull()
                      if (parsed == null) {
                        editError = "YAML doesn't decode to a form-compatible tool — fix the syntax or save as YAML."
                        ActionEditorMode.YAML
                      } else {
                        richEditValues.clear()
                        richEditValues.putAll(parsed.second)
                        editError = null
                        ActionEditorMode.FORM
                      }
                    }
                  }
                },
                modifier = Modifier.size(28.dp),
              ) {
                Icon(
                  // FORM-mode shows a "switch to YAML" code/braces icon; YAML-mode shows
                  // a "switch to FORM" tune/sliders icon.
                  imageVector = when (editorMode) {
                    ActionEditorMode.FORM -> Icons.Filled.Code
                    ActionEditorMode.YAML -> Icons.Filled.Tune
                  },
                  contentDescription = when (editorMode) {
                    ActionEditorMode.FORM -> "Switch to YAML editor"
                    ActionEditorMode.YAML -> "Switch to form editor"
                  },
                  modifier = Modifier.size(16.dp),
                )
              }
            }
            IconButton(
              onClick = {
                // Discard edits. Reset both editor sources back to the latest committed
                // state so re-entering edit mode shows what's actually on the recorder, not
                // the draft we just abandoned.
                draft = yaml
                richEditValues.clear()
                richEditValues.putAll(richEditState?.second ?: emptyMap())
                editError = null
                isEditing = false
              },
              modifier = Modifier.size(28.dp),
            ) {
              Icon(
                Icons.Filled.Close,
                contentDescription = "Cancel edit",
                modifier = Modifier.size(16.dp),
              )
            }
          }
        } else {
          Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (canSwapSelector) {
              Box {
                IconButton(
                  onClick = { showSelectorMenu = true },
                  modifier = Modifier.size(28.dp),
                ) {
                  Icon(
                    Icons.Filled.Tune,
                    contentDescription = "Choose selector",
                    modifier = Modifier.size(16.dp),
                  )
                }
                DropdownMenu(
                  expanded = showSelectorMenu,
                  onDismissRequest = { showSelectorMenu = false },
                ) {
                  Text(
                    text = "Pick a selector",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                  )
                  if (showRoundTripWarning) {
                    Text(
                      text = "⚠ Original cascade didn't round-trip — verify before saving.",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.error,
                      modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                  }
                  effectiveCandidates.forEach { candidate ->
                    DropdownMenuItem(
                      text = {
                        Column {
                          Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                              text = candidate.strategy,
                              style = MaterialTheme.typography.labelMedium,
                            )
                            if (candidate.isBest) {
                              Spacer(Modifier.width(6.dp))
                              Text(
                                text = "(best)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                              )
                            }
                          }
                          Text(
                            text = candidate.selector.description(),
                            style = MaterialTheme.typography.bodySmall.copy(
                              fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                          )
                        }
                      },
                      onClick = {
                        showSelectorMenu = false
                        onSelectorChosen(candidate)
                      },
                    )
                  }
                }
              }
            }
            IconButton(
              onClick = {
                draft = yaml
                editError = null
                isEditing = true
              },
              modifier = Modifier.size(28.dp),
            ) {
              Icon(
                Icons.Filled.Edit,
                contentDescription = "Edit",
                modifier = Modifier.size(16.dp),
              )
            }
            IconButton(
              onClick = onReplay,
              enabled = !isReplaying && !isReplayingFromHere,
              modifier = Modifier.size(28.dp),
            ) {
              if (isReplaying) {
                CircularProgressIndicator(
                  modifier = Modifier.size(16.dp),
                  strokeWidth = 2.dp,
                )
              } else {
                Icon(
                  Icons.Filled.PlayArrow,
                  contentDescription = "Replay this step",
                  modifier = Modifier.size(16.dp),
                )
              }
            }
            // Replay-from-here. Same dispatch path as single-step Replay (a trail YAML
            // through `runYaml`), just with all subsequent tools bundled into the same
            // trail. Disabled while either replay variant is in flight on this card so the
            // user can't kick off two parallel runs against the same device.
            IconButton(
              onClick = onReplayFromHere,
              enabled = !isReplaying && !isReplayingFromHere,
              modifier = Modifier.size(28.dp),
            ) {
              if (isReplayingFromHere) {
                CircularProgressIndicator(
                  modifier = Modifier.size(16.dp),
                  strokeWidth = 2.dp,
                )
              } else {
                Icon(
                  Icons.AutoMirrored.Filled.PlaylistPlay,
                  contentDescription = "Replay from here",
                  modifier = Modifier.size(16.dp),
                )
              }
            }
            IconButton(
              onClick = onDelete,
              modifier = Modifier.size(28.dp),
            ) {
              Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.error,
              )
            }
          }
        }
      }

      Spacer(Modifier.height(4.dp))

      if (isEditing && editorMode == ActionEditorMode.FORM && richEditState != null) {
        // Rich form editor — schema-driven typed inputs (text/number/enum dropdown) per
        // the tool's `@TrailblazeToolClass` constructor params. Save serializes the value
        // bag back through `buildSingleToolYaml` and reuses the exact YAML decode path
        // the textarea fallback uses, so the recorder always sees the same shape.
        ToolParamForm(
          descriptor = richEditState.first,
          paramValues = richEditValues,
          onValueChange = { name, value ->
            richEditValues[name] = value
            if (editError != null) editError = null
          },
        )
        editError?.let { err ->
          Spacer(Modifier.height(4.dp))
          Text(
            text = err,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
      } else if (isEditing) {
        // YAML editor — either the user toggled into it explicitly, or the tool can't be
        // represented as a flat form (`isForLlm = false` tools like
        // `SwipeWithRelativeCoordinatesTool`, anything with nested objects/lists). Free-
        // text editing preserves whatever structure the form widgets would silently drop.
        OutlinedTextField(
          value = draft,
          onValueChange = {
            draft = it
            if (editError != null) editError = null
          },
          modifier = Modifier.fillMaxWidth(),
          textStyle = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
          ),
          isError = editError != null,
          supportingText = editError?.let { error ->
            { Text(error, style = MaterialTheme.typography.bodySmall) }
          },
        )
      } else {
        Text(
          text = yaml,
          style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
          ),
          modifier = Modifier
            .fillMaxWidth()
            .background(
              MaterialTheme.colorScheme.surfaceVariant,
              RoundedCornerShape(4.dp),
            )
            .padding(8.dp),
        )
      }
    }
  }
}
