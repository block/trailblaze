@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.block.trailblaze.ui.editors.yaml

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailSource
import xyz.block.trailblaze.yaml.TrailSourceType
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.VerificationStep
import kotlin.math.roundToInt

/**
 * Sealed class representing the result of YAML parsing for the visual editor.
 */
private sealed class VisualEditorParseResult {
  data class Success(val items: List<TrailYamlItem>) : VisualEditorParseResult()
  data class Error(val message: String) : VisualEditorParseResult()
  data object Empty : VisualEditorParseResult()
}

/**
 * Visual editor content for the YAML tab - provides drag-and-drop editing of YAML structure.
 */
@Composable
fun YamlVisualEditor(
  yamlContent: String,
  onYamlContentChange: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  // Parse YAML content
  val parseResult = remember(yamlContent) {
    if (yamlContent.isBlank()) {
      VisualEditorParseResult.Empty
    } else {
      try {
        val trailblazeYaml = TrailblazeYaml.Default
        val items = trailblazeYaml.decodeTrail(yamlContent)
        VisualEditorParseResult.Success(items)
      } catch (e: Exception) {
        VisualEditorParseResult.Error(e.message ?: "Unknown error parsing YAML")
      }
    }
  }

  // Separate Config from other items - Config is always present and first
  // If there are no items at all, create an empty PromptsTrailItem so users can start adding steps
  val (configItem, otherItems) = remember(parseResult) {
    if (parseResult is VisualEditorParseResult.Success) {
      val config = parseResult.items.filterIsInstance<TrailYamlItem.ConfigTrailItem>().firstOrNull()
        ?: TrailYamlItem.ConfigTrailItem(config = TrailConfig())
      val others = parseResult.items.filterNot { it is TrailYamlItem.ConfigTrailItem }
      // If no other items exist, create an empty prompts section so users can add steps
      val finalOthers = others.ifEmpty {
        listOf(TrailYamlItem.PromptsTrailItem(promptSteps = emptyList()))
      }
      Pair(config, finalOthers)
    } else {
      // For empty/error state, create default config and empty prompts section
      Pair(
        TrailYamlItem.ConfigTrailItem(config = TrailConfig()),
        listOf(TrailYamlItem.PromptsTrailItem(promptSteps = emptyList()))
      )
    }
  }

  var editedConfig by remember(configItem) { mutableStateOf(configItem) }
  var editedItems by remember(otherItems) { mutableStateOf(otherItems) }

  // Function to update the YAML content when items change
  fun updateYamlFromItems() {
    try {
      val trailblazeYaml = TrailblazeYaml.Default
      val allItems = listOf(editedConfig) + editedItems
      val newYamlContent = trailblazeYaml.encodeToString(allItems)
      onYamlContentChange(newYamlContent)
    } catch (e: Exception) {
      // If encoding fails, don't update
    }
  }

  // Sub-view toggle state
  var visualEditorView by remember { mutableStateOf(YamlVisualEditorView.STEPS) }

  Column(
    modifier = modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    // Sub-view toggle header
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      FilterChip(
        selected = visualEditorView == YamlVisualEditorView.CONFIG,
        onClick = { visualEditorView = YamlVisualEditorView.CONFIG },
        label = { Text("Config") }
      )
      FilterChip(
        selected = visualEditorView == YamlVisualEditorView.STEPS,
        onClick = { visualEditorView = YamlVisualEditorView.STEPS },
        label = { Text("Steps") }
      )
    }

    // Helper to render the config editor
    @Composable
    fun ConfigEditor() {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        item {
          TrailYamlItemCard(
            item = editedConfig,
            index = -1,
            canMoveUp = false,
            canMoveDown = false,
            onMoveUp = {},
            onMoveDown = {},
            onItemUpdate = { updatedItem ->
              editedConfig = updatedItem as TrailYamlItem.ConfigTrailItem
              updateYamlFromItems()
            },
            onItemDelete = {},
            modifier = Modifier.fillMaxWidth()
          )
        }
      }
    }

    // Helper to render the steps editor
    @Composable
    fun StepsEditor() {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        itemsIndexed(editedItems) { index, item ->
          TrailYamlItemCard(
            item = item,
            index = index,
            canMoveUp = index > 0,
            canMoveDown = index < editedItems.size - 1,
            onMoveUp = {
              if (index > 0) {
                editedItems = editedItems.toMutableList().apply {
                  val temp = this[index]
                  this[index] = this[index - 1]
                  this[index - 1] = temp
                }
                updateYamlFromItems()
              }
            },
            onMoveDown = {
              if (index < editedItems.size - 1) {
                editedItems = editedItems.toMutableList().apply {
                  val temp = this[index]
                  this[index] = this[index + 1]
                  this[index + 1] = temp
                }
                updateYamlFromItems()
              }
            },
            onItemUpdate = { updatedItem ->
              editedItems = editedItems.toMutableList().apply {
                set(index, updatedItem)
              }
              updateYamlFromItems()
            },
            onItemDelete = {
              editedItems = editedItems.toMutableList().apply {
                removeAt(index)
              }
              updateYamlFromItems()
            },
            modifier = Modifier.fillMaxWidth()
          )
        }
      }
    }

    // Content based on parse result and selected sub-view
    when (parseResult) {
      is VisualEditorParseResult.Success, is VisualEditorParseResult.Empty -> {
        when (visualEditorView) {
          YamlVisualEditorView.CONFIG -> ConfigEditor()
          YamlVisualEditorView.STEPS -> StepsEditor()
        }
      }

      is VisualEditorParseResult.Error -> {
        OutlinedCard(
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(
            modifier = Modifier.padding(12.dp)
          ) {
            Text(
              text = "✗ YAML Parse Error",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.error,
              fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = parseResult.message,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.error
            )
          }
        }

        Text(
          text = "Please fix the YAML syntax in the text editor and return here.",
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(top = 8.dp)
        )
      }
    }
  }
}

/**
 * Displays a single TrailYamlItem as a card with type-specific content.
 */
@Composable
private fun TrailYamlItemCard(
  item: TrailYamlItem,
  index: Int,
  canMoveUp: Boolean,
  canMoveDown: Boolean,
  onMoveUp: () -> Unit,
  onMoveDown: () -> Unit,
  onItemUpdate: (TrailYamlItem) -> Unit,
  onItemDelete: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var showDeleteDialog by remember { mutableStateOf(false) }

  // Only show reorder controls for non-config items
  val showReorderControls = item !is TrailYamlItem.ConfigTrailItem

  OutlinedCard(
    modifier = modifier
  ) {
    Row(
      modifier = Modifier.fillMaxWidth()
    ) {
      // Reorder controls (only for non-config items)
      if (showReorderControls) {
        Column(
          modifier = Modifier
            .width(40.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(vertical = 8.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          IconButton(
            onClick = onMoveUp,
            enabled = canMoveUp,
            modifier = Modifier.size(32.dp)
          ) {
            Icon(
              Icons.Filled.KeyboardArrowUp,
              contentDescription = "Move up",
              tint = if (canMoveUp) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
              modifier = Modifier.size(20.dp)
            )
          }

          IconButton(
            onClick = onMoveDown,
            enabled = canMoveDown,
            modifier = Modifier.size(32.dp)
          ) {
            Icon(
              Icons.Filled.KeyboardArrowDown,
              contentDescription = "Move down",
              tint = if (canMoveDown) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
              modifier = Modifier.size(20.dp)
            )
          }
        }
      }

      Column(
        modifier = Modifier
          .weight(1f)
          .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        when (item) {
          is TrailYamlItem.ConfigTrailItem -> ConfigItemContent(
            item = item,
            onUpdate = { onItemUpdate(it) },
          )

          is TrailYamlItem.PromptsTrailItem -> PromptsItemContent(
            item = item,
            onUpdate = { onItemUpdate(it) },
            onDelete = { showDeleteDialog = true }
          )

          is TrailYamlItem.ToolTrailItem -> ToolItemContent(
            item = item,
            onDelete = { showDeleteDialog = true }
          )

          is TrailYamlItem.MaestroTrailItem -> MaestroItemContent(
            item = item,
            onDelete = { showDeleteDialog = true }
          )
        }
      }
    }
  }

  if (showDeleteDialog) {
    DeleteConfirmationDialog(
      itemType = item::class.simpleName ?: "Item",
      onConfirm = {
        onItemDelete()
        showDeleteDialog = false
      },
      onDismiss = { showDeleteDialog = false }
    )
  }
}

/**
 * Displays the content for a ConfigTrailItem with inline editing.
 */
@Composable
private fun ConfigItemContent(
  item: TrailYamlItem.ConfigTrailItem,
  onUpdate: (TrailYamlItem.ConfigTrailItem) -> Unit,
) {
  Text(
    text = "Configuration",
    style = MaterialTheme.typography.titleMedium,
    fontWeight = FontWeight.Bold
  )

  HorizontalDivider()

  // Inline editable fields
  OutlinedTextField(
    value = item.config.id ?: "",
    onValueChange = { newValue ->
      onUpdate(item.copy(config = item.config.copy(id = newValue.takeIf { it.isNotBlank() })))
    },
    label = { Text("ID") },
    modifier = Modifier.fillMaxWidth(),
    singleLine = true
  )

  OutlinedTextField(
    value = item.config.title ?: "",
    onValueChange = { newValue ->
      onUpdate(item.copy(config = item.config.copy(title = newValue.takeIf { it.isNotBlank() })))
    },
    label = { Text("Title") },
    modifier = Modifier.fillMaxWidth(),
    singleLine = true
  )

  OutlinedTextField(
    value = item.config.description ?: "",
    onValueChange = { newValue ->
      onUpdate(item.copy(config = item.config.copy(description = newValue.takeIf { it.isNotBlank() })))
    },
    label = { Text("Description") },
    modifier = Modifier.fillMaxWidth(),
    maxLines = 3
  )

  OutlinedTextField(
    value = item.config.context ?: "",
    onValueChange = { newValue ->
      onUpdate(item.copy(config = item.config.copy(context = newValue.takeIf { it.isNotBlank() })))
    },
    label = { Text("Context") },
    modifier = Modifier.fillMaxWidth(),
    maxLines = 3
  )

  OutlinedTextField(
    value = item.config.priority ?: "",
    onValueChange = { newValue ->
      onUpdate(item.copy(config = item.config.copy(priority = newValue.takeIf { it.isNotBlank() })))
    },
    label = { Text("Priority") },
    modifier = Modifier.fillMaxWidth(),
    singleLine = true
  )

  // Source section
  Spacer(modifier = Modifier.height(8.dp))
  Text(
    text = "Source",
    style = MaterialTheme.typography.titleSmall,
    fontWeight = FontWeight.Medium
  )

  var sourceTypeExpanded by remember { mutableStateOf(false) }
  val currentSourceTypeName = item.config.source?.type?.name ?: ""

  ExposedDropdownMenuBox(
    expanded = sourceTypeExpanded,
    onExpandedChange = { sourceTypeExpanded = it }
  ) {
    OutlinedTextField(
      value = currentSourceTypeName,
      onValueChange = {},
      readOnly = true,
      label = { Text("Source Type") },
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sourceTypeExpanded) },
      modifier = Modifier
        .fillMaxWidth()
        .menuAnchor()
    )
    ExposedDropdownMenu(
      expanded = sourceTypeExpanded,
      onDismissRequest = { sourceTypeExpanded = false }
    ) {
      DropdownMenuItem(
        text = { Text("") },
        onClick = {
          val newSource = (item.config.source ?: TrailSource()).copy(type = null)
          onUpdate(item.copy(config = item.config.copy(source = newSource)))
          sourceTypeExpanded = false
        }
      )
      TrailSourceType.entries.forEach { sourceType ->
        DropdownMenuItem(
          text = { Text(sourceType.name) },
          onClick = {
            val newSource = (item.config.source ?: TrailSource()).copy(type = sourceType)
            onUpdate(item.copy(config = item.config.copy(source = newSource)))
            sourceTypeExpanded = false
          }
        )
      }
    }
  }

  OutlinedTextField(
    value = item.config.source?.reason ?: "",
    onValueChange = { newValue ->
      val newSource = (item.config.source ?: TrailSource()).copy(reason = newValue.takeIf { it.isNotBlank() })
      onUpdate(item.copy(config = item.config.copy(source = newSource)))
    },
    label = { Text("Source Reason") },
    modifier = Modifier.fillMaxWidth(),
    singleLine = true
  )

  // Metadata section
  Spacer(modifier = Modifier.height(8.dp))
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = "Metadata",
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.Medium
    )
    FilledTonalButton(
      onClick = {
        val currentMetadata = item.config.metadata ?: emptyMap()
        val newKey = "key${currentMetadata.size + 1}"
        val newMetadata = currentMetadata + (newKey to "")
        onUpdate(item.copy(config = item.config.copy(metadata = newMetadata)))
      }
    ) {
      Icon(
        Icons.Filled.Add,
        contentDescription = "Add Metadata",
        modifier = Modifier.size(16.dp).padding(end = 4.dp)
      )
      Text("Add")
    }
  }

  val metadata = item.config.metadata ?: emptyMap()
  metadata.forEach { (key, value) ->
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      OutlinedTextField(
        value = key,
        onValueChange = { newKey ->
          if (newKey != key && newKey.isNotBlank()) {
            val newMetadata = metadata.toMutableMap().apply {
              remove(key)
              put(newKey, value)
            }
            onUpdate(item.copy(config = item.config.copy(metadata = newMetadata.takeIf { it.isNotEmpty() })))
          }
        },
        label = { Text("Key") },
        modifier = Modifier.weight(1f),
        singleLine = true
      )
      OutlinedTextField(
        value = value,
        onValueChange = { newValue ->
          val newMetadata = metadata.toMutableMap().apply {
            put(key, newValue)
          }
          onUpdate(item.copy(config = item.config.copy(metadata = newMetadata.takeIf { it.isNotEmpty() })))
        },
        label = { Text("Value") },
        modifier = Modifier.weight(1f),
        singleLine = true
      )
      IconButton(
        onClick = {
          val newMetadata = metadata.toMutableMap().apply { remove(key) }
          onUpdate(item.copy(config = item.config.copy(metadata = newMetadata.takeIf { it.isNotEmpty() })))
        }
      ) {
        Icon(
          Icons.Filled.Delete,
          contentDescription = "Remove",
          tint = MaterialTheme.colorScheme.error,
          modifier = Modifier.size(20.dp)
        )
      }
    }
  }
}

/**
 * Displays the content for a PromptsTrailItem.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun PromptsItemContent(
  item: TrailYamlItem.PromptsTrailItem,
  onUpdate: (TrailYamlItem.PromptsTrailItem) -> Unit,
  onDelete: () -> Unit,
) {
  // Drag and drop state
  var draggedIndex by remember { mutableStateOf<Int?>(null) }
  var draggedOverIndex by remember { mutableStateOf<Int?>(null) }
  var dragOffset by remember { mutableStateOf(Offset.Zero) }

  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Text(
      text = "${item.promptSteps.size} Steps",
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold
    )

    Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      // Add new Direction Step button
      FilledTonalButton(
        onClick = {
          onUpdate(
            item.copy(
              promptSteps = item.promptSteps + DirectionStep(step = "New step")
            )
          )
        }
      ) {
        Icon(
          Icons.Filled.Add,
          contentDescription = "Add Step",
          modifier = Modifier.size(16.dp).padding(end = 4.dp)
        )
        Text("Step")
      }

      // Add new Verification Step button
      FilledTonalButton(
        onClick = {
          onUpdate(
            item.copy(
              promptSteps = item.promptSteps + VerificationStep(verify = "New verification")
            )
          )
        }
      ) {
        Icon(
          Icons.Filled.Add,
          contentDescription = "Add Verify",
          modifier = Modifier.size(16.dp).padding(end = 4.dp)
        )
        Text("Verify")
      }

      IconButton(onClick = onDelete) {
        Icon(
          Icons.Filled.Delete,
          contentDescription = "Delete Prompts",
          tint = MaterialTheme.colorScheme.error
        )
      }
    }
  }

  HorizontalDivider()

  item.promptSteps.forEachIndexed { stepIndex, step ->
    var showDeleteDialog by remember { mutableStateOf(false) }

    val isDragging = draggedIndex == stepIndex

    // Show insertion indicator when dragging
    val showInsertionIndicatorAbove = draggedIndex != null && draggedOverIndex == stepIndex &&
        draggedIndex != stepIndex && (draggedIndex ?: 0) > stepIndex
    val showInsertionIndicatorBelow = draggedIndex != null && draggedOverIndex == stepIndex &&
        draggedIndex != stepIndex && (draggedIndex ?: 0) < stepIndex

    // Insertion indicator line above this item
    if (showInsertionIndicatorAbove) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(3.dp)
          .background(MaterialTheme.colorScheme.primary)
      )
      Spacer(modifier = Modifier.height(4.dp))
    }

    // Two-line layout: content on first line, recording info on second line
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .then(
          if (isDragging) {
            Modifier.graphicsLayer {
              alpha = 0.5f
              translationY = dragOffset.y
            }
          } else {
            Modifier
          }
        )
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
        .padding(vertical = 4.dp, horizontal = 8.dp)
    ) {
      // First line: Main step content
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        // Drag handle - can drag to reorder
        Icon(
          Icons.Filled.DragHandle,
          contentDescription = "Drag to reorder",
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier
            .size(28.dp)
            .padding(2.dp)
            .pointerInput(stepIndex) {
              detectDragGestures(
                onDragStart = {
                  draggedIndex = stepIndex
                  dragOffset = Offset.Zero
                },
                onDrag = { change, dragAmount ->
                  change.consume()
                  dragOffset = dragOffset.copy(y = dragOffset.y + dragAmount.y)

                  // Calculate which index we're hovering over
                  val itemHeight = 70.dp.toPx() // Approximate height per item (adjusted for two-line layout)
                  val offsetSteps = (dragOffset.y / itemHeight).roundToInt()
                  val targetIndex = (stepIndex + offsetSteps).coerceIn(0, item.promptSteps.size - 1)
                  draggedOverIndex = targetIndex
                },
                onDragEnd = {
                  // Perform the reorder
                  val fromIndex = draggedIndex
                  val toIndex = draggedOverIndex

                  if (fromIndex != null && toIndex != null && fromIndex != toIndex) {
                    val updatedSteps = item.promptSteps.toMutableList().apply {
                      val movedItem = removeAt(fromIndex)
                      add(toIndex, movedItem)
                    }
                    onUpdate(item.copy(promptSteps = updatedSteps))
                  }

                  // Reset drag state
                  draggedIndex = null
                  draggedOverIndex = null
                  dragOffset = Offset.Zero
                },
                onDragCancel = {
                  // Reset drag state
                  draggedIndex = null
                  draggedOverIndex = null
                  dragOffset = Offset.Zero
                }
              )
            }
        )

        // Badge
        when (step) {
          is DirectionStep -> ItemTypeBadge(text = "STEP", color = Color(0xFF9C27B0))
          is VerificationStep -> ItemTypeBadge(text = "VERIFY", color = Color(0xFFFF9800))
        }

        // Text content - always editable
        OutlinedTextField(
          value = step.prompt,
          onValueChange = { newValue ->
            val updatedSteps = item.promptSteps.toMutableList().apply {
              set(
                stepIndex, when (step) {
                  is DirectionStep -> step.copy(step = newValue)
                  is VerificationStep -> step.copy(verify = newValue)
                }
              )
            }
            onUpdate(item.copy(promptSteps = updatedSteps))
          },
          modifier = Modifier.weight(1f),
          textStyle = MaterialTheme.typography.bodyMedium,
          singleLine = false,
          maxLines = 3
        )

        // Delete button
        IconButton(
          onClick = { showDeleteDialog = true },
          modifier = Modifier.size(32.dp)
        ) {
          Icon(
            Icons.Filled.Delete,
            contentDescription = "Delete",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp)
          )
        }
      }

      // Second line: Recording information (indented to align with text)
      val recording = step.recording // Capture for smart cast
      val hasRecording = recording != null && recording.tools.isNotEmpty()
      val showRecordingInfo = hasRecording || !step.recordable

      if (showRecordingInfo) {
        FlowRow(
          modifier = Modifier
            .padding(
              start = 92.dp,
              top = 4.dp,
              bottom = 2.dp
            ) // Indent to align with text (24dp reorder + 60dp badge + 8dp gaps)
            .fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          if (hasRecording) {
            // Show individual tool chips
            recording.tools.forEach { tool ->
              Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(4.dp),
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                  Icon(
                    Icons.Filled.FiberManualRecord,
                    contentDescription = "Recorded",
                    tint = Color(0xFFE53935), // Red recording icon
                    modifier = Modifier.size(10.dp)
                  )
                  Text(
                    text = tool.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.Medium
                  )
                }
              }
            }
          }

          if (!step.recordable) {
            // Show not recordable chip
            Surface(
              color = MaterialTheme.colorScheme.surfaceVariant,
              shape = MaterialTheme.shapes.small,
              modifier = Modifier
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
              ) {
                Icon(
                  Icons.Filled.Block,
                  contentDescription = "Not recordable",
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(14.dp)
                )
                Text(
                  text = "Not Recordable",
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  fontWeight = FontWeight.Medium
                )
              }
            }
          }
        }
      }
    }

    // Insertion indicator line below this item
    if (showInsertionIndicatorBelow) {
      Spacer(modifier = Modifier.height(4.dp))
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(3.dp)
          .background(MaterialTheme.colorScheme.primary)
      )
    }

    if (showDeleteDialog) {
      DeleteConfirmationDialog(
        itemType = "this step",
        onConfirm = {
          val updatedSteps = item.promptSteps.toMutableList().apply {
            removeAt(stepIndex)
          }
          onUpdate(item.copy(promptSteps = updatedSteps))
          showDeleteDialog = false
        },
        onDismiss = { showDeleteDialog = false }
      )
    }
  }
}

/**
 * Displays the content for a ToolTrailItem.
 */
@Composable
private fun ToolItemContent(
  item: TrailYamlItem.ToolTrailItem,
  onDelete: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      ItemTypeBadge(text = "TOOLS", color = Color(0xFFFF5722))
      Text(
        text = "${item.tools.size} Tools",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
      )
    }

    IconButton(onClick = onDelete) {
      Icon(
        Icons.Filled.Delete,
        contentDescription = "Delete Tools",
        tint = MaterialTheme.colorScheme.error
      )
    }
  }

  HorizontalDivider()

  item.tools.forEach { tool ->
    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
      )
    ) {
      Row(
        modifier = Modifier.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = tool.name,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium
        )
      }
    }
  }
}

/**
 * Displays the content for a MaestroTrailItem.
 */
@Composable
private fun MaestroItemContent(
  item: TrailYamlItem.MaestroTrailItem,
  onDelete: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      ItemTypeBadge(text = "MAESTRO", color = Color(0xFF9E9E9E))
      Text(
        text = "Maestro Commands",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
      )
    }

    IconButton(onClick = onDelete) {
      Icon(
        Icons.Filled.Delete,
        contentDescription = "Delete Maestro",
        tint = MaterialTheme.colorScheme.error
      )
    }
  }

  HorizontalDivider()

  Text(
    text = "Maestro command list",
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant
  )
}

/**
 * A badge showing the item type with fixed width for consistent alignment.
 */
@Composable
private fun ItemTypeBadge(
  text: String,
  color: Color,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.width(60.dp), // Fixed width for consistent alignment
    color = color,
    shape = MaterialTheme.shapes.small
  ) {
    Box(
      modifier = Modifier.fillMaxWidth(),
      contentAlignment = Alignment.Center
    ) {
      Text(
        text = text,
        modifier = Modifier.padding(vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        fontWeight = FontWeight.Bold
      )
    }
  }
}
