@file:Suppress("DEPRECATION")

package xyz.block.trailblaze.ui.tabs.recording

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.block.trailblaze.host.recording.RecordingLlmService
import xyz.block.trailblaze.recording.DeviceScreenStream
import xyz.block.trailblaze.recording.InteractionRecorder
import xyz.block.trailblaze.recording.RecordedInteraction
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.PressKeyTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SwipeWithRelativeCoordinatesTool
import xyz.block.trailblaze.toolcalls.commands.TapOnPointTrailblazeTool

/**
 * Full recording screen with a live device preview on the left and
 * per-action YAML cards + controls on the right.
 */
@Composable
fun RecordingScreenComposable(
  stream: DeviceScreenStream,
  recorder: InteractionRecorder,
  llmService: RecordingLlmService,
  onSaveTrail: (String) -> Unit,
) {
  var isRecording by remember { mutableStateOf(false) }
  val recordedActions = remember { mutableStateListOf<RecordedInteraction>() }
  val scope = rememberCoroutineScope()

  // LLM transformation state
  var isTransforming by remember { mutableStateOf(false) }
  var generatedTrailYaml by remember { mutableStateOf<String?>(null) }
  var transformError by remember { mutableStateOf<String?>(null) }

  // Replay state
  var replayingIndex by remember { mutableStateOf(-1) }

  // Poll recorder for new interactions — update atomically to avoid flicker
  LaunchedEffect(recorder) {
    kotlinx.coroutines.flow.flow {
      while (true) {
        emit(recorder.interactions)
        kotlinx.coroutines.delay(200)
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
    // Left: Device preview
    Box(
      modifier = Modifier
        .weight(0.6f)
        .fillMaxHeight()
        .padding(8.dp),
    ) {
      InteractiveDeviceComposable(
        stream = stream,
        buffer = recorder.buffer,
        isRecording = isRecording,
        modifier = Modifier.fillMaxSize(),
      )
    }

    // Right: Controls + action cards
    Column(
      modifier = Modifier
        .weight(0.4f)
        .fillMaxHeight()
        .padding(16.dp),
    ) {
      // Recording controls
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (isRecording) {
          Button(
            onClick = {
              recorder.stopRecording()
              isRecording = false
            },
            colors = ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.error,
            ),
          ) {
            Icon(Icons.Filled.Stop, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Stop")
          }
        } else {
          Button(
            onClick = {
              recorder.startRecording()
              isRecording = true
              recordedActions.clear()
              generatedTrailYaml = null
              transformError = null
            },
            colors = ButtonDefaults.buttonColors(
              containerColor = Color.Red,
            ),
          ) {
            Icon(Icons.Filled.FiberManualRecord, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Record")
          }
        }

        if (!isRecording && recordedActions.isNotEmpty()) {
          OutlinedButton(
            onClick = {
              val yaml = generatedTrailYaml ?: recorder.generateTrailYaml()
              onSaveTrail(yaml)
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
      }

      // Recording status
      if (isRecording) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .padding(end = 6.dp)
              .background(Color.Red, shape = RoundedCornerShape(50))
              .padding(4.dp),
          )
          Text(
            text = "Recording... ${recordedActions.size} action(s)",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Red,
          )
        }
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
        // Per-action YAML cards
        LazyColumn(
          modifier = Modifier.fillMaxWidth().weight(1f),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          itemsIndexed(
            items = recordedActions.toList(),
            key = { _, interaction -> "${interaction.toolName}_${interaction.timestamp}" },
          ) { index, interaction ->
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
                replayingIndex = index
                scope.launch {
                  try {
                    replayInteraction(interaction, stream)
                  } catch (_: Exception) {
                    // Replay errors are non-fatal
                  } finally {
                    replayingIndex = -1
                  }
                }
              },
            )
          }
        }
      }
    }
  }
}

/** Card displaying a single recorded action's YAML with delete and play controls. */
@Composable
private fun ActionYamlCard(
  index: Int,
  interaction: RecordedInteraction,
  isRecording: Boolean,
  isReplaying: Boolean,
  onDelete: () -> Unit,
  onReplay: () -> Unit,
) {
  val yaml = remember(interaction) {
    InteractionRecorder.singleToolToYaml(interaction)
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
        if (!isRecording) {
          Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconButton(
              onClick = onReplay,
              enabled = !isReplaying,
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
                  contentDescription = "Replay",
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

      // YAML content (read-only; edit via "Generate Trail" to get editable output)
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

/**
 * Replay a single recorded interaction on the device.
 * Maps the tool type back to the appropriate [DeviceScreenStream] call.
 */
private suspend fun replayInteraction(
  interaction: RecordedInteraction,
  stream: DeviceScreenStream,
) {
  when (val tool = interaction.tool) {
    is TapOnPointTrailblazeTool -> {
      if (tool.longPress) {
        stream.longPress(tool.x, tool.y)
      } else {
        stream.tap(tool.x, tool.y)
      }
    }
    is InputTextTrailblazeTool -> {
      stream.inputText(tool.text)
    }
    is PressKeyTrailblazeTool -> {
      val key = when (tool.keyCode) {
        PressKeyTrailblazeTool.PressKeyCode.BACK -> "Back"
        PressKeyTrailblazeTool.PressKeyCode.ENTER -> "Enter"
        PressKeyTrailblazeTool.PressKeyCode.HOME -> "Home"
      }
      stream.pressKey(key)
    }
    is SwipeWithRelativeCoordinatesTool -> {
      val (startXPct, startYPct) = parseRelativeCoords(tool.startRelative)
      val (endXPct, endYPct) = parseRelativeCoords(tool.endRelative)
      stream.swipe(
        startX = (startXPct * stream.deviceWidth / 100),
        startY = (startYPct * stream.deviceHeight / 100),
        endX = (endXPct * stream.deviceWidth / 100),
        endY = (endYPct * stream.deviceHeight / 100),
      )
    }
    else -> {
      // Unsupported tool type for replay — tap-on-element etc.
      // Could be extended to search the view hierarchy by text.
    }
  }
}

/** Parse a relative coordinate string like "50%, 75%" into a pair of integers. */
private fun parseRelativeCoords(relative: String): Pair<Int, Int> {
  val parts = relative.replace("%", "").split(",").map { it.trim().toIntOrNull() ?: 0 }
  return (parts.getOrElse(0) { 0 }) to (parts.getOrElse(1) { 0 })
}
