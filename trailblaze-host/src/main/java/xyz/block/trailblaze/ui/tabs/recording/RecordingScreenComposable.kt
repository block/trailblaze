@file:Suppress("DEPRECATION")

package xyz.block.trailblaze.ui.tabs.recording

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator
import xyz.block.trailblaze.config.ToolYamlConfig
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.recording.OnDeviceRpcDeviceScreenStream
import xyz.block.trailblaze.playwright.recording.PlaywrightDeviceScreenStream
import xyz.block.trailblaze.host.recording.RecordingLlmService
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.model.TrailExecutionResult
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeClickTool
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeNavigateTool
import xyz.block.trailblaze.recording.DeviceScreenStream
import xyz.block.trailblaze.recording.InteractionRecorder
import xyz.block.trailblaze.recording.RecordedInteraction
import xyz.block.trailblaze.recording.RecordingYamlCodec
import xyz.block.trailblaze.recording.WebDeviceScreenStream
import xyz.block.trailblaze.toolcalls.toolName
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.host.recording.resolveDescriptorAndValues
import xyz.block.trailblaze.ui.recording.InsertHerePlus
import xyz.block.trailblaze.ui.recording.ToolParamForm
import xyz.block.trailblaze.ui.recording.TrailheadSlot
import xyz.block.trailblaze.ui.recording.buildSingleToolYaml
import androidx.compose.runtime.mutableStateMapOf
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.toolcalls.commands.TapOnByElementSelector
import xyz.block.trailblaze.toolcalls.commands.TapOnPointTrailblazeTool

/**
 * Full recording screen with a live device preview on the left and
 * per-action YAML cards + controls on the right.
 *
 * @param onConnectionLost Forwarded to [InteractiveDeviceComposable]; the parent uses it
 *   to flip back into the device-picker / Connect-button state when a stream call throws
 *   (Maestro iOS XCUITest socket flapping is the typical case).
 */
@Composable
fun RecordingScreenComposable(
  stream: DeviceScreenStream,
  recorder: InteractionRecorder,
  llmService: RecordingLlmService,
  /**
   * Device manager whose `runYaml` is reused for single-step replay. Routing through it lets
   * the Replay button dispatch via the same agent pipeline that runs full trails — no more
   * hand-rolled per-tool `when` to keep in sync.
   */
  deviceManager: TrailblazeDeviceManager,
  /** ID of the currently-connected device, used as the replay target. */
  trailblazeDeviceId: TrailblazeDeviceId,
  /**
   * Driver type for the connected device. Required by the Tool Palette so the toolbox query
   * filters tools to those the live driver actually accepts (Android-only tools don't appear
   * in a web session, etc.).
   */
  driverType: TrailblazeDriverType,
  /**
   * Trailhead the author picked, or null for "current state — no trailhead." When non-null,
   * "Verify Trail" prefixes this tool to the dispatched YAML so the device gets reset to a
   * clean starting state before the recorded tools replay. When null, the Verify button
   * surfaces a nudge ("trail starts mid-app — won't reproduce for teammates"). The trailhead
   * itself is owned by `RecordingTabState` so the choice survives tab navigation.
   */
  selectedTrailhead: ToolYamlConfig?,
  /**
   * Per-instance parameter values for [selectedTrailhead] — the user-supplied values from
   * the trailhead picker form. Empty when the trailhead has no parameters or hasn't been
   * configured yet. Stitched into the Verify Trail YAML so a trailhead like
   * `launch-signed-in(email = …)` runs with the right account.
   */
  selectedTrailheadParams: Map<String, String>,
  /**
   * Available trailheads for the current (target, driver) combo. Rendered in the dashed-
   * outline slot at the top of the right panel; the slot opens a dropdown of these when
   * clicked. Empty list is fine — the slot still shows the "None" placeholder and the
   * dropdown surfaces "No trailheads available for this target."
   */
  availableTrailheads: List<ToolYamlConfig>,
  onTrailheadSelected: (config: ToolYamlConfig?, paramValues: Map<String, String>) -> Unit,
  onSaveTrail: (String) -> String?,
  onConnectionLost: (Throwable) -> Unit = { throw it },
) {
  // Recording is implicit (started on Connect by `RecordingTabComposable`) so this is
  // effectively `true` for the recorder's whole lifetime. Kept as a flag rather than a
  // hard-coded `true` because [InteractiveDeviceComposable] and [WebChromeBar] both take an
  // `isRecording` parameter (used to flip a "(recording)" suffix and a poll-cadence change
  // respectively); passing the recorder's own flag keeps both surfaces honest if the
  // implicit-recording assumption ever changes again. The `onConnectionLost` callback flips
  // it false when the device drops, but the surrounding composable is about to unmount in
  // that case anyway.
  var isRecording by remember(recorder) { mutableStateOf(recorder.isRecording) }
  val recordedActions = remember(recorder) { mutableStateListOf<RecordedInteraction>() }
  val scope = rememberCoroutineScope()

  // LLM transformation state
  var isTransforming by remember { mutableStateOf(false) }
  var generatedTrailYaml by remember { mutableStateOf<String?>(null) }
  var transformError by remember { mutableStateOf<String?>(null) }

  // Replay state. Single-step replay tracks `replayingIndex`; "replay from here" tracks
  // `replayingFromIndex` independently so the UI can show distinct spinners on the originating
  // card without confusing it with a single-step replay of the same step.
  var replayingIndex by remember { mutableStateOf(-1) }
  var replayingFromIndex by remember { mutableStateOf(-1) }

  // Tool Palette insertion target. `null` = append (the default — the toolbar's "+ Tool"
  // button leaves it null). A non-null value comes from a "+" between-cards button and tells
  // `recorder.insertInteraction` to splice at exactly that index instead of appending.
  var paletteInsertPosition by remember { mutableStateOf<Int?>(null) }

  // Verify Trail confirm dialog state. Two purposes:
  //   - Without a trailhead: surface the reproducibility nudge ("trail starts mid-app — won't
  //     work for teammates without setup"). User can still proceed.
  //   - With a trailhead: warn the run is destructive (the trailhead typically force-quits
  //     and relaunches the app, blowing away whatever state the user just built up).
  // Either way, an explicit confirm is the right gate for a multi-minute, side-effect-heavy
  // operation that the per-card Replay button intentionally avoids.
  var verifyConfirmOpen by remember { mutableStateOf(false) }
  var isVerifying by remember { mutableStateOf(false) }

  // Save-without-trailhead nudge. The first time the author tries to save a trail with no
  // trailhead selected, surface the reproducibility warning. After acknowledging once per
  // recording, subsequent Save clicks bypass the dialog (the user gets the point — they
  // know what they're doing). Same friction-discovery pattern as Verify, but Save shouldn't
  // gate every click after the first acknowledgement; that's why we track the flag.
  var saveConfirmOpen by remember { mutableStateOf(false) }
  var noTrailheadSaveAcknowledged by remember(recorder) { mutableStateOf(false) }

  // Last save outcome — drives a transient confirmation panel below the action toolbar so
  // the user actually sees that Save Trail did something (and where the file landed).
  // Without this, clicking Save was indistinguishable from a no-op since the recording
  // surface stays open and the captured action cards are still visible. Cleared on every
  // new recording.
  var lastSavedPath by remember(recorder) { mutableStateOf<String?>(null) }
  var lastSaveError by remember(recorder) { mutableStateOf<String?>(null) }
  // Wraps `onSaveTrail` so the click handlers don't have to repeat the path-or-error
  // bookkeeping. Returns nothing because the click handlers don't act on it.
  fun saveTrailWithFeedback(yaml: String) {
    val path = onSaveTrail(yaml)
    if (path != null) {
      lastSavedPath = path
      lastSaveError = null
    } else {
      lastSavedPath = null
      lastSaveError = "Save Trail failed — see the log for details."
    }
  }

  // Poll recorder for new interactions — update atomically to avoid flicker
  LaunchedEffect(recorder) {
    flow {
      while (true) {
        emit(recorder.interactions)
        delay(200)
      }
    }.collect { interactions ->
      if (interactions.size != recordedActions.size || interactions != recordedActions.toList()) {
        // Apply changes without clear/addAll to avoid intermediate empty state
        val newSize = interactions.size
        val oldSize = recordedActions.size
        // Remove extra items from the end
        while (recordedActions.size > newSize) {
          recordedActions.removeAt(recordedActions.lastIndex)
        }
        // Update existing items and add new ones
        for (i in interactions.indices) {
          if (i < oldSize) {
            if (recordedActions[i] != interactions[i]) {
              recordedActions[i] = interactions[i]
            }
          } else {
            recordedActions.add(interactions[i])
          }
        }
      }
    }
  }

  Row(Modifier.fillMaxSize()) {
    // Left: Device preview, with optional web chrome (URL bar + back/forward) above it.
    Column(
      modifier = Modifier
        .weight(0.6f)
        .fillMaxHeight()
        .padding(8.dp),
    ) {
      val webStream = stream as? WebDeviceScreenStream
      if (webStream != null) {
        WebChromeBar(
          stream = webStream,
          isRecording = isRecording,
          recorder = recorder,
          onConnectionLost = onConnectionLost,
        )
        Spacer(Modifier.height(6.dp))
      }
      InteractiveDeviceComposable(
        stream = stream,
        buffer = recorder.buffer,
        isRecording = isRecording,
        modifier = Modifier.fillMaxSize(),
        onConnectionLost = { error ->
          // Flush any pending text-debounce against a dead stream and stop the buffer's
          // emission gate. The parent will tear down this composable and recreate the
          // recorder on next Connect, but cleaning up here keeps the buffer's debounce
          // coroutine from firing into the void after this composable has unmounted.
          recorder.stopRecording()
          isRecording = false
          onConnectionLost(error)
        },
      )
    }

    // Right: Controls + action cards
    Column(
      modifier = Modifier
        .weight(0.4f)
        .fillMaxHeight()
        .padding(16.dp),
    ) {
      // Tool palette dialog state. Lifted to the screen scope so the parent owns recorder
      // mutation (Insert) and runYaml dispatch (Run) — the dialog itself stays narrow,
      // emitting `(toolName, yaml)` callbacks rather than reaching into recorder/manager.
      //
      // Two open modes:
      //  - "tool" (paletteTrailheadMode == null) — the regular `+ Tool` button. Insert/Run.
      //  - "trailhead" (paletteTrailheadMode != null) — opened from the trailhead slot. The
      //    dialog re-themes itself; clicking a tool sets the trailhead and dismisses.
      var paletteOpen by remember { mutableStateOf(false) }
      var paletteTrailheadMode by remember { mutableStateOf<TrailheadPickerMode?>(null) }
      if (paletteOpen) {
        ToolPaletteDialog(
          deviceManager = deviceManager,
          driverType = driverType,
          insertPosition = paletteInsertPosition,
          onDismissRequest = {
            paletteOpen = false
            paletteInsertPosition = null
            paletteTrailheadMode = null
          },
          trailheadPickerMode = paletteTrailheadMode,
          onInsert = { toolName, singleToolYaml ->
            // Decode through the same path as the in-card YAML editor and splice at the
            // requested position (null = append). Failures bubble up to Console.log; the
            // dialog stays open on failure so the author can correct the YAML rather than
            // starting over.
            val result = runCatching { interactionFromSingleToolYaml(singleToolYaml) }
            result.fold(
              onSuccess = { interaction ->
                recorder.insertInteraction(interaction, position = paletteInsertPosition)
                generatedTrailYaml = null // Invalidate stale LLM output
                paletteOpen = false
                paletteInsertPosition = null
              },
              onFailure = { e ->
                Console.log("Tool Palette Insert failed for $toolName: ${e.message ?: e::class.simpleName}")
              },
            )
          },
          onRun = { toolName, singleToolYaml ->
            // Decode the same way Insert does, then re-encode through the recorder's
            // single-interaction trail wrapper so runYaml gets the exact trail format the
            // Replay button uses. Avoids hand-rolling YAML indent math here.
            val result = runCatching {
              val interaction = interactionFromSingleToolYaml(singleToolYaml)
              InteractionRecorder.singleInteractionToTrailYaml(interaction)
            }
            result.fold(
              onSuccess = { trailYaml ->
                dispatchYamlOnDevice(
                  scope = scope,
                  deviceManager = deviceManager,
                  trailblazeDeviceId = trailblazeDeviceId,
                  yaml = trailYaml,
                  label = "Tool Palette Run of $toolName",
                  stream = stream,
                )
                paletteOpen = false
                paletteInsertPosition = null
              },
              onFailure = { e ->
                Console.log("Tool Palette Run of $toolName failed to encode: ${e.message ?: e::class.simpleName}")
              },
            )
          },
        )
      }

      // Trailhead slot: dashed-outline section sitting above the recording controls. The
      // dashed outline reads as "configurable slot," the empty-state copy ("None — pick one
      // to make this trail reproducible") teaches the concept inline, and clicking opens the
      // unified Tool Palette dialog in trailhead-picker mode. Lives here rather than in the
      // top device/target selectors so the trailhead reads as part of the recording's setup
      // ("where do I start from?") rather than as device configuration.
      //
      // Reusing the Tool Palette dialog (rather than its own dropdown) is deliberate: the
      // user's framing was "a trailhead is just a type of tool", so the gesture for picking
      // a trailhead matches the gesture for adding any other tool — same search box, same
      // alphabetical list, same row-click-to-select. The dialog's trailhead-picker mode
      // restricts the list to the names in `availableTrailheads` and replaces Insert/Run
      // with a single "Use as Trailhead" action.
      TrailheadSlot(
        selectedTrailhead = selectedTrailhead,
        selectedTrailheadParams = selectedTrailheadParams,
        onClick = {
          val trailheadNames = availableTrailheads.map { it.id }.toSet()
          val byName = availableTrailheads.associateBy { it.id }
          paletteInsertPosition = null
          paletteTrailheadMode = TrailheadPickerMode(
            trailheadToolNames = trailheadNames,
            onPick = { toolName, paramValues ->
              onTrailheadSelected(byName[toolName], paramValues)
              paletteOpen = false
              paletteTrailheadMode = null
            },
            onClear = {
              onTrailheadSelected(null, emptyMap())
              paletteOpen = false
              paletteTrailheadMode = null
            },
            allowClear = selectedTrailhead != null,
            // Pre-fill the picker with the currently-selected trailhead + its params so
            // re-opening to tweak doesn't lose what the user already configured.
            initialToolName = selectedTrailhead?.id,
            initialParamValues = selectedTrailheadParams,
          )
          paletteOpen = true
        },
        modifier = Modifier.fillMaxWidth(),
      )

      Spacer(Modifier.height(12.dp))

      // Recording is implicit while connected — no Record/Stop toggle. Capture starts on
      // Connect (set up by RecordingTabComposable) and continues until the user saves,
      // discards, or disconnects. The mental model is "the recording tab is a view of the
      // current session"; Save promotes the ephemeral session to a kept trail, Discard
      // wipes the in-memory list to start over without bouncing the device connection.
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // Always-on indicator. Pill + step count, low-contrast so it doesn't fight with the
        // action buttons next to it. The pulse-red dot keeps the "this is live capture"
        // signal that the old Record button used to carry.
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(end = 8.dp),
        ) {
          Box(
            modifier = Modifier
              .padding(end = 6.dp)
              .size(8.dp)
              .background(Color.Red, shape = RoundedCornerShape(50)),
          )
          Text(
            text = "Recording · ${recordedActions.size}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        // Tool Palette button. Always available — use it to fire a one-off tool against the
        // device, or to compose steps into the recording. Explicitly null the insert position
        // so an in-flight position from a between-cards "+" doesn't carry over.
        OutlinedButton(onClick = {
          paletteInsertPosition = null
          paletteOpen = true
        }) {
          Icon(Icons.Filled.Add, contentDescription = null)
          Spacer(Modifier.width(4.dp))
          Text("Tool")
        }

        // Save / Generate / Discard need something to act on — no point showing them with
        // an empty capture. Verify is split off below: it's also useful before any actions
        // are captured (when a trailhead is picked, you can verify the trailhead lands you
        // where you expect before starting to record).
        if (recordedActions.isNotEmpty()) {
          OutlinedButton(
            onClick = {
              // Friction-discovery moment for trailheads on Save: if the author hasn't picked
              // one and hasn't already acknowledged the warning for this recording, surface
              // the reproducibility nudge. Once acknowledged (Save Anyway), subsequent Save
              // clicks for the same recording bypass — the warning's job is to teach, not to
              // gate every click.
              if (selectedTrailhead == null && !noTrailheadSaveAcknowledged) {
                saveConfirmOpen = true
              } else {
                val yaml = generatedTrailYaml ?: recorder.generateTrailYaml()
                saveTrailWithFeedback(yaml)
              }
            },
          ) {
            Icon(Icons.Filled.Save, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Save Trail")
          }

          OutlinedButton(
            onClick = {
              isTransforming = true
              transformError = null
              generatedTrailYaml = null
              val yamlToTransform = recorder.generateTrailYaml()
              scope.launch {
                try {
                  val result = llmService.transformToNaturalLanguageTrail(yamlToTransform)
                  generatedTrailYaml = result
                } catch (e: Exception) {
                  transformError = e.message ?: "LLM transformation failed"
                } finally {
                  isTransforming = false
                }
              }
            },
            enabled = !isTransforming,
          ) {
            if (isTransforming) {
              CircularProgressIndicator(
                modifier = Modifier.padding(end = 4.dp).height(16.dp).width(16.dp),
                strokeWidth = 2.dp,
              )
            } else {
              Icon(Icons.Filled.AutoAwesome, contentDescription = null)
              Spacer(Modifier.width(4.dp))
            }
            Text("Generate Trail")
          }
        }

        // Verify Trail: replays end-to-end from a clean state by running the chosen trailhead
        // first (if any) and then the recorded tools. Distinct from per-card Replay /
        // replay-from-here in two ways: it's destructive (typically force-quits + relaunches
        // the app via the trailhead) and it always restarts from the trailhead's known state,
        // not from current device state. The two-button separation matches the two-mode
        // workflow: per-card Replay is the fast inner loop for "iterate on the thing I'm
        // working on"; Verify is the outer-loop "I'm done, prove it works end-to-end."
        //
        // Visible when there's anything meaningful to verify: either captured tools, or a
        // trailhead the author wants to sanity-check on its own ("does this trailhead actually
        // land me on the screen I expect?") before starting to record.
        if (recordedActions.isNotEmpty() || selectedTrailhead != null) {
          OutlinedButton(
            onClick = { verifyConfirmOpen = true },
            enabled = !isVerifying,
          ) {
            if (isVerifying) {
              CircularProgressIndicator(
                modifier = Modifier.padding(end = 4.dp).height(16.dp).width(16.dp),
                strokeWidth = 2.dp,
              )
            } else {
              Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null)
              Spacer(Modifier.width(4.dp))
            }
            Text("Verify Trail")
          }
        }

        // Discard wipes the captured list and keeps the recorder going (re-calling
        // startRecording is the existing primitive that clears + sets isRecording=true).
        // Useful when the author has explored down a wrong branch and wants a clean slate
        // without bouncing the device connection. Distinct from Save (which exports) and
        // distinct from Disconnect (which would tear down the stream). Resetting the
        // save-nudge ack means the next save-without-trailhead surfaces the warning again,
        // since Discard logically starts a new recording.
        if (recordedActions.isNotEmpty()) {
          OutlinedButton(onClick = {
            recorder.startRecording()
            // Mirror the recorder's clear into the local list immediately so the action
            // cards disappear without waiting for the 200ms poll to catch up. Without this
            // explicit clear, Save Trail / Verify / etc. stay visible for a frame or two
            // pointing at a recorder that has nothing to export.
            recordedActions.clear()
            generatedTrailYaml = null
            transformError = null
            noTrailheadSaveAcknowledged = false
          }) {
            Icon(Icons.Filled.Delete, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Discard")
          }
        }
      }

      // Save outcome confirmation. Stays visible until Discard or another save replaces it
      // — staying parked instead of auto-fading lets the user copy/click the path without
      // racing a timer. Without this panel, Save Trail looked indistinguishable from a
      // no-op since the recording UI doesn't change shape on save.
      val savedPath = lastSavedPath
      val saveErr = lastSaveError
      if (savedPath != null) {
        Spacer(Modifier.height(8.dp))
        Text(
          text = "Saved to $savedPath",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary,
        )
      } else if (saveErr != null) {
        Spacer(Modifier.height(8.dp))
        Text(
          text = saveErr,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }

      // Verify confirm dialog. Two distinct messages depending on whether a trailhead is
      // selected — the no-trailhead path is the friction-discovery moment for the trailhead
      // nudge (mentioned in PR #2721's design conversation: "let trailhead be the answer to
      // a question users naturally ask"). User can still proceed without one; the warning is
      // a teaching moment, not a block.
      if (verifyConfirmOpen) {
        AlertDialog(
          onDismissRequest = { verifyConfirmOpen = false },
          title = { Text("Verify Trail") },
          text = {
            Column {
              if (selectedTrailhead != null) {
                val stepsLine = when (recordedActions.size) {
                  // Trailhead-only verify: useful as a sanity check that the trailhead lands
                  // in the expected state before recording starts.
                  0 -> "This will run the ${selectedTrailhead.id} trailhead by itself to " +
                    "verify it lands you on the expected screen. Any current app state will " +
                    "be lost."
                  1 -> "This will run the ${selectedTrailhead.id} trailhead to reset the " +
                    "device to a known state, then replay the 1 recorded step. Any current " +
                    "app state will be lost."
                  else -> "This will run the ${selectedTrailhead.id} trailhead to reset the " +
                    "device to a known state, then replay all ${recordedActions.size} " +
                    "recorded steps. Any current app state will be lost."
                }
                Text(text = stepsLine, style = MaterialTheme.typography.bodyMedium)
              } else {
                Text(
                  text = "No trailhead is selected. Verify will replay from the device's " +
                    "current state — fine for solo iteration, but the trail won't be " +
                    "reproducible for teammates running it later.",
                  style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                  text = "Pick a trailhead in the Trailhead dropdown above to make this " +
                    "trail reproducible.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          },
          confirmButton = {
            Button(onClick = {
              verifyConfirmOpen = false
              isVerifying = true
              val tail = recordedActions.toList()
              val yaml = if (selectedTrailhead != null) {
                RecordingYamlCodec.interactionsToTrailYamlWithTrailhead(
                  trailheadToolId = selectedTrailhead.id,
                  interactions = tail,
                  trailheadParamValues = selectedTrailheadParams,
                )
              } else {
                InteractionRecorder.interactionsToTrailYaml(tail)
              }
              val label = if (selectedTrailhead != null) {
                "Verify Trail (trailhead=${selectedTrailhead.id}, ${tail.size} steps)"
              } else {
                "Verify Trail (no trailhead, ${tail.size} steps from current state)"
              }
              dispatchYamlOnDevice(
                scope = scope,
                deviceManager = deviceManager,
                trailblazeDeviceId = trailblazeDeviceId,
                yaml = yaml,
                label = label,
                // Thread the stream through so the web fast-path (sendSessionEndLog=false →
                // keepBrowserAlive=true → cached `BasePlaywrightNativeTest` reused) fires.
                // Without this, dispatchYamlOnDevice's null-stream branch sends
                // sendSessionEndLog=true, the runYaml cache lookup is skipped, and a fresh
                // headed Chrome spawns while the recording surface stays wired to the
                // original pageManager — same trap per-card Replay used to hit before the
                // fast-path branches were added.
                stream = stream,
                onSettled = { isVerifying = false },
              )
            }) {
              Text(if (selectedTrailhead != null) "Reset and Verify" else "Verify Anyway")
            }
          },
          dismissButton = {
            OutlinedButton(onClick = { verifyConfirmOpen = false }) { Text("Cancel") }
          },
        )
      }

      // Save-without-trailhead nudge. Same teaching moment as the Verify dialog's no-trailhead
      // path, but Save is an export action — we want to inform, not block. The confirm fires
      // ONCE per recording (acknowledged via the flag) so frequent-save users don't get nagged
      // every click. Picking a trailhead via the dropdown is the suggested-but-not-required path.
      if (saveConfirmOpen) {
        AlertDialog(
          onDismissRequest = { saveConfirmOpen = false },
          title = { Text("Save without trailhead?") },
          text = {
            Column {
              Text(
                text = "This trail starts mid-app, from whatever state the device happened " +
                  "to be in when you started recording. It'll work for you while you're " +
                  "iterating, but a teammate running it later will start from their device's " +
                  "state — likely failing.",
                style = MaterialTheme.typography.bodyMedium,
              )
              Spacer(Modifier.height(8.dp))
              Text(
                text = "Pick a trailhead in the Trailhead dropdown above to make this trail " +
                  "reproducible — or save anyway if it's just for you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          },
          confirmButton = {
            Button(onClick = {
              noTrailheadSaveAcknowledged = true
              saveConfirmOpen = false
              val yaml = generatedTrailYaml ?: recorder.generateTrailYaml()
              saveTrailWithFeedback(yaml)
            }) {
              Text("Save Anyway")
            }
          },
          dismissButton = {
            OutlinedButton(onClick = { saveConfirmOpen = false }) { Text("Cancel") }
          },
        )
      }

      Spacer(Modifier.height(8.dp))

      // Error display
      if (transformError != null) {
        Text(
          text = transformError!!,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(4.dp))
      }

      // Content area
      if (generatedTrailYaml != null) {
        // Show generated trail YAML in editable area
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "Generated Trail",
            style = MaterialTheme.typography.titleSmall,
          )
          OutlinedButton(
            onClick = { generatedTrailYaml = null },
          ) {
            Text("Back to Actions")
          }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
          value = generatedTrailYaml!!,
          onValueChange = { generatedTrailYaml = it },
          modifier = Modifier.fillMaxWidth().weight(1f),
          textStyle = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
          ),
        )
      } else if (recordedActions.isEmpty() && !isRecording) {
        Text(
          text = "Click Record to start",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        // Per-action YAML cards interleaved with mid-list "+ insert" rows. Position semantics:
        // a "+" at index N inserts BEFORE the card currently rendered at index N (i.e. shifts
        // the existing card to N+1). The trailing "+" after the last card targets `size`,
        // which `insertInteraction` clamps to a true append.
        val snapshot = recordedActions.toList()
        val listState = rememberLazyListState()

        // Auto-scroll to the newest card whenever the list grows. Without this the LazyColumn
        // is scrollable but doesn't move on its own, so as the user records more actions the
        // newest cards land below the visible area and the user has to manually scroll to
        // confirm what was captured. Only fire when *count* increases (not on edits or
        // deletes) so an in-place modification doesn't yank scroll position.
        var lastSeenSize by remember { mutableStateOf(0) }
        LaunchedEffect(snapshot.size) {
          if (snapshot.size > lastSeenSize && snapshot.isNotEmpty()) {
            // +1 accounts for the trailing "Insert" row past the last card so the most
            // recent card lands fully in view rather than peeking from the bottom edge.
            listState.animateScrollToItem(snapshot.size + 1)
          }
          lastSeenSize = snapshot.size
        }

        // Wrap in a Row so a Compose Desktop scrollbar can sit next to the list. Without a
        // visible scrollbar the user can mouse-wheel scroll but has no affordance telling
        // them there's more content above — that's what surfaced the "items just end up off
        // screen" complaint. The scrollbar's adapter binds to the same `listState` the list
        // uses, so dragging the thumb scrolls the LazyColumn directly.
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
          LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
          item(key = "insert_top") {
            InsertHerePlus(onClick = {
              paletteInsertPosition = 0
              paletteOpen = true
            })
          }
          itemsIndexed(
            items = snapshot,
            key = { _, interaction -> "${interaction.toolName}_${interaction.timestamp}" },
          ) { index, interaction ->
            // Each LazyColumn item carries the card PLUS its trailing "+" affordance, so the
            // visible vertical order is: top-plus, card[0], plus[after-0], card[1], plus[after-1], ...
            // The plus rendered here targets `index + 1` because Insert means "before the
            // card at this index" — placing one *after* card[index] is equivalent to inserting
            // before what would-be-card[index + 1].
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              ActionYamlCard(
              index = index,
              interaction = interaction,
              isRecording = isRecording,
              isReplaying = replayingIndex == index,
              onDelete = {
                recorder.removeInteraction(interaction)
                generatedTrailYaml = null // Invalidate stale LLM output
              },
              onReplay = {
                // Single-step replay wraps the recorded tool as a one-step trail and feeds
                // it through `TrailblazeDeviceManager.runYaml` — same path as the Sessions
                // tab's Rerun button, and the production path for any trail YAML. The old
                // hand-rolled per-tool dispatcher silently no-op'd on tool types it didn't
                // know (web_click, custom MCP tools, novel selector flavors); routing through
                // runYaml means whatever the runner can dispatch, Replay can replay too.
                // Tradeoff: one Sessions-tab session per click, same as Rerun.
                replayingIndex = index
                dispatchYamlOnDevice(
                  scope = scope,
                  deviceManager = deviceManager,
                  trailblazeDeviceId = trailblazeDeviceId,
                  yaml = InteractionRecorder.singleInteractionToTrailYaml(interaction),
                  label = "Replay of ${interaction.toolName} (#${index + 1})",
                  // Per-card replay should feel as immediate as the user's own tap. The
                  // fast-path branch in dispatchYamlOnDevice fires the YAML straight at
                  // the on-device handler when the stream supports it.
                  stream = stream,
                  onSettled = { replayingIndex = -1 },
                )
              },
              isReplayingFromHere = replayingFromIndex == index,
              onReplayFromHere = {
                // Same dispatch path as single-step Replay, but bundles every interaction from
                // this index forward into a single `tools:` block. Each from-here run still
                // creates one session in the Sessions tab — no different from running the same
                // slice via Sessions/Rerun, just with the slice scoped to "tail of recording".
                val tail = recordedActions.toList().drop(index)
                replayingFromIndex = index
                dispatchYamlOnDevice(
                  scope = scope,
                  deviceManager = deviceManager,
                  trailblazeDeviceId = trailblazeDeviceId,
                  yaml = InteractionRecorder.interactionsToTrailYaml(tail),
                  label = "Replay-from-here starting at ${interaction.toolName} (#${index + 1}, ${tail.size} steps)",
                  stream = stream,
                  onSettled = { replayingFromIndex = -1 },
                )
              },
              onSelectorChosen = { candidate ->
                // The picker rewrites three tap/click-shaped tools today:
                //   - `TapOnByElementSelector`: mobile — swap to a different selector
                //     strategy.
                //   - `TapOnPointTrailblazeTool`: mobile — promote a coordinate tap to a
                //     selector tap. The author opted into this when their original cascade
                //     fell through to `tapOnPoint` (round-trip validation failed at record
                //     time) — the warning chip in the dropdown header tells them they're
                //     overriding that fallback.
                //   - `PlaywrightNativeClickTool`: web — same shape as `TapOnByElementSelector`
                //     but stays a `web_click` so the LLM keeps the platform-specific signal
                //     (mobile is a tap, web is a click). The `nodeSelector` carries the
                //     `web` branch so `PlaywrightExecutableTool.validateAndResolveRef`
                //     resolves it through the same node-selector-first → ref-fallback path.
                //     Captured tree + tap point on the interaction guarantee the picker
                //     only surfaces selectors that resolve back to the user's actual
                //     target. The else branch logs and no-ops so any future tool type that
                //     wires up candidates without a swap branch surfaces loudly.
                when (val original = interaction.tool) {
                  is TapOnByElementSelector -> {
                    recorder.replaceInteractionTool(
                      interaction = interaction,
                      newTool = TapOnByElementSelector(
                        nodeSelector = candidate.selector,
                        longPress = original.longPress,
                      ),
                      newToolName = TapOnByElementSelector::class.toolName().toolName,
                    )
                    generatedTrailYaml = null // Invalidate stale LLM output
                  }
                  is TapOnPointTrailblazeTool -> {
                    recorder.replaceInteractionTool(
                      interaction = interaction,
                      newTool = TapOnByElementSelector(
                        nodeSelector = candidate.selector,
                        longPress = original.longPress,
                      ),
                      newToolName = TapOnByElementSelector::class.toolName().toolName,
                    )
                    generatedTrailYaml = null // Invalidate stale LLM output
                  }
                  is PlaywrightNativeClickTool -> {
                    recorder.replaceInteractionTool(
                      interaction = interaction,
                      newTool = PlaywrightNativeClickTool(
                        ref = null,
                        reasoning = original.reasoning,
                        nodeSelector = candidate.selector,
                      ),
                      newToolName = PlaywrightNativeClickTool::class.toolName().toolName,
                    )
                    generatedTrailYaml = null // Invalidate stale LLM output
                  }
                  else -> Console.log(
                    "WARNING: selector picker invoked on unsupported tool type " +
                      "${original::class.simpleName}; swap suppressed. Add a branch in " +
                      "onSelectorChosen for this tool type.",
                  )
                }
              },
              onEditYaml = { editedYaml ->
                recorder.replaceInteractionFromYaml(interaction, editedYaml)
                  .onSuccess { generatedTrailYaml = null /* invalidate stale LLM output */ }
              },
              )
              InsertHerePlus(onClick = {
                paletteInsertPosition = index + 1
                paletteOpen = true
              })
            }
          }
        }
          VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.fillMaxHeight(),
          )
        }
      }
    }
  }
}

// `InsertHerePlus` moved to `xyz.block.trailblaze.ui.recording.RecordingWidgets` in
// trailblaze-ui/commonMain so the same widget compiles to wasmJs.

/**
 * Active editor inside [ActionYamlCard]'s edit mode. Both modes are present when the tool's
 * descriptor resolves to a flat-form-compatible shape; YAML is the only viable mode for
 * tools the form can't represent (`isForLlm = false` wrappers, tools with nested params).
 * The toggle button only renders when both are viable.
 */
private enum class EditorMode { FORM, YAML }

/** Card displaying a single recorded action's YAML with delete, play, edit, and selector-swap controls. */
@Composable
private fun ActionYamlCard(
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
) {
  val yaml = remember(interaction) {
    InteractionRecorder.singleToolToYaml(interaction)
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
    if (tool is TapOnPointTrailblazeTool) {
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
      is TapOnPointTrailblazeTool -> {
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
    is TapOnPointTrailblazeTool -> tapResolution != null
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

  // Try to resolve the tool's descriptor + current values for the rich form. Returns null
  // when the tool is `isForLlm = false` (`SwipeWithRelativeCoordinatesTool` and other
  // internal/wrapper tools) OR when any field is non-primitive (nested selectors, nested
  // params, lists). In both cases we fall back to the raw YAML editor below — that path
  // round-trips arbitrary structure without losing data, which the form can't promise.
  val richEditState = remember(interaction) {
    resolveDescriptorAndValues(interaction.tool)
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
    mutableStateOf(if (richEditState != null) EditorMode.FORM else EditorMode.YAML)
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
        // recorder mutations (`removeInteraction`, `replaceInteractionTool`) are guarded by the
        // recorder's lock, and Replay calls go through `DeviceScreenStream` directly — they
        // execute on the device but don't bounce back through the gesture handler, so the
        // session log doesn't pick them up as new taps. Locking these behind Stop made the
        // recording feel one-shot when the rest of the UX is a workbench.
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
                  EditorMode.FORM -> {
                    val descriptor = richEditState?.first
                      ?: error("Form mode active without a resolved descriptor.")
                    buildSingleToolYaml(descriptor, richEditValues.toMap())
                  }
                  EditorMode.YAML -> draft
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
                    EditorMode.FORM -> {
                      // Going to YAML — serialize the current form values so the textarea
                      // shows what the form was about to commit, not the stale `draft`
                      // from before edit mode opened. Without this sync the user would
                      // see their form edits "disappear" the moment they toggle.
                      draft = buildSingleToolYaml(richEditState.first, richEditValues.toMap())
                      editError = null
                      EditorMode.YAML
                    }
                    EditorMode.YAML -> {
                      // Going to FORM — try to parse the current YAML draft and pull
                      // values into the form. If the YAML doesn't decode (mid-edit syntax
                      // error) or contains nested params the form can't render, surface
                      // the issue and stay on YAML so we don't silently drop the user's
                      // in-progress textarea edits.
                      val parsed = runCatching {
                        val wrapper = xyz.block.trailblaze.recording.RecordingYamlCodec.decodeSingleToolYaml(draft)
                        resolveDescriptorAndValues(wrapper.trailblazeTool)
                      }.getOrNull()
                      if (parsed == null) {
                        editError = "YAML doesn't decode to a form-compatible tool — fix the syntax or save as YAML."
                        EditorMode.YAML
                      } else {
                        richEditValues.clear()
                        richEditValues.putAll(parsed.second)
                        editError = null
                        EditorMode.FORM
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
                    EditorMode.FORM -> Icons.Filled.Code
                    EditorMode.YAML -> Icons.Filled.Tune
                  },
                  contentDescription = when (editorMode) {
                    EditorMode.FORM -> "Switch to YAML editor"
                    EditorMode.YAML -> "Switch to form editor"
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

      if (isEditing && editorMode == EditorMode.FORM && richEditState != null) {
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

/**
 * Browser chrome above a web device's rendered viewport: address bar + Go/Back/Forward.
 *
 * Renders nothing for non-web streams — the parent passes a [WebDeviceScreenStream] only when
 * `stream as? WebDeviceScreenStream` succeeds, so the bar is naturally scoped to the web tab.
 *
 * The displayed URL is polled (every 500ms) rather than only updated on explicit navigation,
 * because most page transitions happen via in-page link clicks — those fire through
 * [InteractiveDeviceComposable]'s tap path, not through the bar — and we still want the
 * displayed URL to track them. Polling cost is one cheap RPC per device per half second; the
 * same primitive the daemon mode will use anyway.
 *
 * Each navigation action is also recorded as a [PlaywrightNativeNavigateTool] via
 * [InteractionEventBuffer.recordCustomTool] so the trail YAML carries `web_navigate` entries
 * for the chrome operations the user performed during recording.
 */
@Composable
private fun WebChromeBar(
  stream: WebDeviceScreenStream,
  isRecording: Boolean,
  recorder: InteractionRecorder,
  onConnectionLost: (Throwable) -> Unit,
) {
  val scope = rememberCoroutineScope()
  // Two URLs: the live page URL we poll from Playwright, and the editable text the user is
  // currently typing into the field. Editing locally without overwriting on every poll lets
  // the user finish typing before the field "snaps" to the page's actual URL.
  var liveUrl by remember { mutableStateOf("") }
  var fieldUrl by remember { mutableStateOf("") }
  var fieldEditing by remember { mutableStateOf(false) }
  var urlError by remember { mutableStateOf<String?>(null) }

  // Poll fast (500ms) while recording so in-page link clicks update the address bar
  // promptly, slow (2000ms) when idle so an open Recording tab the user isn't actively
  // driving doesn't generate one device round-trip every 500ms forever. Re-keying on
  // `isRecording` restarts the LaunchedEffect when recording starts/stops so the cadence
  // applies immediately rather than on the next tick.
  LaunchedEffect(stream, isRecording) {
    val pollIntervalMs = if (isRecording) 500L else 2000L
    while (true) {
      try {
        val url = stream.currentUrl()
        if (url != liveUrl) {
          liveUrl = url
          if (!fieldEditing) fieldUrl = url
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        onConnectionLost(e)
        return@LaunchedEffect
      }
      delay(pollIntervalMs)
    }
  }

  fun runChrome(
    tool: PlaywrightNativeNavigateTool,
    block: suspend () -> Unit,
  ) {
    scope.launch {
      try {
        block()
        urlError = null
        if (isRecording) {
          recorder.buffer.recordCustomTool(tool = tool, toolName = PlaywrightNativeNavigateTool::class.toolName().toolName)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        // A nav-level failure (unreachable host, bad TLS, 4xx-as-error, etc.) is NOT a
        // browser/stream disconnect — the page is still alive and ready for another
        // attempt. Going through `onConnectionLost` here was wrong: the parent treats
        // that as a stream-down event and tears the entire recording surface down,
        // forcing the user to pick a device and Connect again to keep working. Surface
        // it inline on the address bar instead so the user gets immediate feedback and
        // can edit + retry the URL without losing the recording session.
        val firstLine = e.message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
          ?: e::class.simpleName
          ?: "navigation failed"
        urlError = firstLine
        Console.log("[Recording chrome bar] ${tool.action} failed: $firstLine")
      }
    }
  }

  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    IconButton(
      onClick = {
        runChrome(PlaywrightNativeNavigateTool(action = PlaywrightNativeNavigateTool.NavigationAction.BACK)) {
          stream.back()
        }
      },
      modifier = Modifier.size(32.dp),
    ) {
      Icon(
        Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = "Back",
        modifier = Modifier.size(18.dp),
      )
    }
    IconButton(
      onClick = {
        runChrome(PlaywrightNativeNavigateTool(action = PlaywrightNativeNavigateTool.NavigationAction.FORWARD)) {
          stream.forward()
        }
      },
      modifier = Modifier.size(32.dp),
    ) {
      Icon(
        Icons.AutoMirrored.Filled.ArrowForward,
        contentDescription = "Forward",
        modifier = Modifier.size(18.dp),
      )
    }
    // Submit-on-Enter is the obvious affordance for an address bar; the trailing icon is
    // a redundant click-to-submit for mouse users. While the field has focus, key events
    // are consumed by the TextField — they don't bubble to the device's onKeyEvent — so
    // typing a URL doesn't accidentally generate `pressKey`/`inputText` actions on the
    // device.
    //
    // Validation: require an explicit scheme. Auto-prepending `https://` would feel helpful
    // until the user actually wanted `localhost:8080` or `file://...` and we silently picked
    // the wrong protocol; surfacing "include https://" puts them in control.
    fun submit() {
      val target = fieldUrl.trim()
      if (target.isEmpty()) return
      if (!HTTP_URL_REGEX.containsMatchIn(target)) {
        urlError = "URL must start with http:// or https://"
        return
      }
      urlError = null
      runChrome(
        PlaywrightNativeNavigateTool(
          action = PlaywrightNativeNavigateTool.NavigationAction.GOTO,
          url = target,
        ),
      ) {
        stream.navigate(target)
      }
      fieldEditing = false
    }

    OutlinedTextField(
      value = fieldUrl,
      onValueChange = {
        fieldUrl = it
        fieldEditing = true
        if (urlError != null) urlError = null
      },
      // onPreviewKeyEvent (not the soft-keyboard ImeAction.Go path) is what actually fires for
      // hardware Enter on Compose Desktop — there's no IME translating keystrokes for us.
      // Consume the event by returning true so it doesn't bubble out and risk inserting a
      // newline in the field.
      modifier = Modifier
        .weight(1f)
        .onPreviewKeyEvent { keyEvent ->
          if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Enter) {
            submit()
            true
          } else {
            false
          }
        },
      singleLine = true,
      isError = urlError != null,
      textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
      placeholder = {
        Text("https://...", style = MaterialTheme.typography.bodySmall)
      },
      supportingText = urlError?.let { error ->
        { Text(error, style = MaterialTheme.typography.bodySmall) }
      },
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
      keyboardActions = KeyboardActions(onGo = { submit() }),
      trailingIcon = {
        IconButton(onClick = { submit() }) {
          Icon(
            Icons.Filled.PlayArrow,
            contentDescription = "Navigate",
            modifier = Modifier.size(18.dp),
          )
        }
      },
    )
  }
}

/**
 * Address bar accepts only HTTP(S). The runtime [PlaywrightNativeNavigateTool] resolves any RFC
 * 3986 scheme (and treats scheme-less input as a relative file path), but the chrome bar is
 * scoped tighter on purpose: in the recording UI the user is driving a real browser session, and
 * `file://` / `data:` / custom schemes either don't make sense for recordings or would silently
 * pick a target the author didn't mean. Surface a clear error instead and let the author type
 * the protocol they want.
 */
private val HTTP_URL_REGEX = Regex("^https?://", RegexOption.IGNORE_CASE)

/**
 * Run a trail YAML on the connected device and surface failures via [Console.log]. Single
 * dispatch path for every "fire YAML at the device" UI affordance in the recording surface
 * — single-step Replay, Replay-from-here, and Tool Palette Run all share these semantics:
 *
 *  - One session per click (`existingSessionId = null`), matching how `Sessions/Rerun` behaves.
 *  - Errors and cancellations log a `$label` line to the user's Console; success is silent.
 *  - [onSettled] fires regardless of outcome, including coroutine cancellation, so the UI
 *    can clear its spinner state without each call site duplicating that try/catch/finally.
 *    Idempotent state setters (`replayingIndex = -1`) make the double-fire on cancel-after-
 *    onComplete safe.
 */
private fun dispatchYamlOnDevice(
  scope: CoroutineScope,
  deviceManager: TrailblazeDeviceManager,
  trailblazeDeviceId: TrailblazeDeviceId,
  yaml: String,
  label: String,
  /**
   * Optional fast-path stream — when non-null and an [OnDeviceRpcDeviceScreenStream], the
   * dispatch goes through its `dispatchYaml` (direct rpcCall, fire-and-forget) instead of
   * `deviceManager.runYaml`. Replay from the recording UI feels almost instant on this
   * path, vs. seconds of session-bookkeeping wait through runYaml. iOS / Web / no-stream
   * callers fall through to the production runYaml path, which preserves Sessions-tab
   * visibility for recorded test runs.
   */
  stream: DeviceScreenStream? = null,
  onSettled: () -> Unit = {},
) {
  if (stream is OnDeviceRpcDeviceScreenStream) {
    scope.launch {
      try {
        stream.dispatchYaml(yaml)
      } catch (e: CancellationException) {
        onSettled()
        throw e
      } catch (e: Throwable) {
        Console.log("$label failed: ${e.message ?: e::class.simpleName}")
      } finally {
        onSettled()
      }
    }
    return
  }
  // Web fast-path: route through `runYaml` BUT with `sendSessionEndLog = false` so
  // `runPlaywrightNativeYaml` reuses the cached `BasePlaywrightNativeTest` registered at
  // connect time (recording tab adopts the live pageManager into the cache). Without that
  // flag, `keepBrowserAlive = !sendSessionEndLog` is false, the cache lookup is skipped,
  // and a fresh `BasePlaywrightNativeTest` is built — which launches a NEW headed browser
  // and runs the YAML there while the recording surface stays empty.
  //
  // We bundle replays into one logical session via `getOrCreateSessionResolution` (same
  // pattern as MCP `blaze()`): first replay creates a session and emits a start log,
  // subsequent replays re-use it. Matches the iOS/Android fast-path semantics where every
  // tap/replay accumulates into one session rather than spawning one-per-click.
  val isWebStream = stream is PlaywrightDeviceScreenStream
  scope.launch {
    try {
      val (sendStartLog, existingSessionId) = if (isWebStream) {
        val resolution = deviceManager.getOrCreateSessionResolution(
          trailblazeDeviceId = trailblazeDeviceId,
          sessionIdPrefix = "recording",
        )
        resolution.isNewSession to resolution.sessionId
      } else {
        true to null
      }
      deviceManager.runYaml(
        yamlToRun = yaml,
        trailblazeDeviceId = trailblazeDeviceId,
        sendSessionStartLog = sendStartLog,
        sendSessionEndLog = !isWebStream,
        existingSessionId = existingSessionId,
        forceStopTargetApp = false,
        referrer = TrailblazeReferrer.RECORDING_TAB_REPLAY,
        onComplete = { result ->
          when (result) {
            is TrailExecutionResult.Success -> Unit
            is TrailExecutionResult.Failed -> Console.log("$label failed: ${result.errorMessage}")
            is TrailExecutionResult.Cancelled -> Console.log("$label was cancelled.")
          }
          onSettled()
        },
      )
    } catch (e: CancellationException) {
      onSettled()
      throw e
    } catch (e: Throwable) {
      Console.log("$label failed before dispatch: ${e.message ?: e::class.simpleName}")
      onSettled()
    }
  }
}

/**
 * Decode a single-tool YAML block (the shape the Tool Palette emits) into a [RecordedInteraction].
 * Routes through [InteractionRecorder.decodeSingleToolYaml] so the palette and the in-card
 * YAML editor share one decoder. Screenshot/hierarchy/tree stay null because palette-built
 * tools have no recorded screen state by definition (the user never tapped anything to
 * produce them).
 */
private fun interactionFromSingleToolYaml(singleToolYaml: String): RecordedInteraction {
  val wrapper = InteractionRecorder.decodeSingleToolYaml(singleToolYaml)
  return RecordedInteraction(
    tool = wrapper.trailblazeTool,
    toolName = wrapper.name,
    screenshotBytes = null,
    viewHierarchyText = null,
    timestamp = Clock.System.now().toEpochMilliseconds(),
  )
}
