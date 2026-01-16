package xyz.block.trailblaze.ui.tabs.trails

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import xyz.block.trailblaze.ui.DesktopUtil
import xyz.block.trailblaze.ui.composables.FullScreenModalOverlay
import xyz.block.trailblaze.ui.editors.yaml.YamlEditorMode
import xyz.block.trailblaze.ui.editors.yaml.YamlTextEditor
import xyz.block.trailblaze.ui.editors.yaml.YamlVisualEditor
import xyz.block.trailblaze.ui.editors.yaml.validateYaml
import java.io.File

/**
 * Full-screen modal for editing trail YAML files.
 * Supports both text-based and visual editing modes, matching the functionality
 * of the YAML tab in the sessions panel.
 * 
 * @param variant The trail variant being edited
 * @param initialContent The initial YAML content
 * @param onSave Callback when the user saves - receives the new content, returns success/failure
 * @param onDismiss Callback when the modal is dismissed
 */
@Composable
fun TrailYamlEditorModal(
  variant: TrailVariant,
  initialContent: String,
  onSave: (String) -> Result<Unit>,
  onDismiss: () -> Unit,
  relativePath: String? = null,
) {
  var localContent by remember(initialContent) { mutableStateOf(initialContent) }
  var validationError by remember { mutableStateOf<String?>(null) }
  var isValidating by remember { mutableStateOf(false) }
  var saveSuccess by remember { mutableStateOf(false) }
  var saveError by remember { mutableStateOf<String?>(null) }
  var showCloseConfirmation by remember { mutableStateOf(false) }
  
  // Editor mode state - Visual is the default (matches YAML tab)
  var editorMode by remember { mutableStateOf(YamlEditorMode.VISUAL) }
  
  val hasUnsavedChanges = localContent != initialContent
  
  // Debounced validation
  LaunchedEffect(localContent) {
    isValidating = true
    delay(300) // 300ms debounce
    validationError = validateYaml(localContent)
    isValidating = false
  }
  
  // Auto-dismiss save success message
  LaunchedEffect(saveSuccess) {
    if (saveSuccess) {
      delay(2000)
      saveSuccess = false
    }
  }
  
  // Function to save changes
  fun saveChanges() {
    if (validationError != null) return
    
    val result = onSave(localContent)
    if (result.isSuccess) {
      saveSuccess = true
      saveError = null
    } else {
      saveError = result.exceptionOrNull()?.message ?: "Failed to save"
    }
  }
  
  fun requestClose() {
    if (hasUnsavedChanges) {
      showCloseConfirmation = true
    } else {
      onDismiss()
    }
  }
  
  FullScreenModalOverlay(
    onDismiss = { requestClose() },
    modifier = Modifier.onKeyEvent { keyEvent ->
      if (keyEvent.type == KeyEventType.KeyDown &&
        keyEvent.key == Key.S &&
        (keyEvent.isCtrlPressed || keyEvent.isMetaPressed)
      ) {
        if (hasUnsavedChanges && validationError == null) {
          saveChanges()
        }
        true
      } else {
        false
      }
    }
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(24.dp)
    ) {
      // Header with editor mode toggle
      TrailEditorHeader(
        variant = variant,
    relativePath = relativePath,
        editorMode = editorMode,
        onYamlEditorModeChange = { editorMode = it },
        hasUnsavedChanges = hasUnsavedChanges,
        validationError = validationError,
        saveSuccess = saveSuccess,
        saveError = saveError,
        onSave = { saveChanges() },
        onOpenFolder = {
          val file = File(variant.absolutePath)
          DesktopUtil.openInFileBrowser(file.parentFile)
        },
        onClose = { requestClose() }
      )
      
      HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
      
      // Editor area - switches between text and visual modes
      when (editorMode) {
        YamlEditorMode.TEXT -> {
          YamlTextEditor(
            yamlContent = localContent,
            onYamlContentChange = { localContent = it },
            validationError = validationError,
            isValidating = isValidating,
            enabled = true,
            showTitle = false,
            modifier = Modifier.weight(1f)
          )
        }
        YamlEditorMode.VISUAL -> {
          YamlVisualEditor(
            yamlContent = localContent,
            onYamlContentChange = { localContent = it },
            modifier = Modifier.weight(1f)
          )
        }
      }
      
      Spacer(modifier = Modifier.height(16.dp))
    }
    
    if (showCloseConfirmation) {
      AlertDialog(
        onDismissRequest = { showCloseConfirmation = false },
        title = { Text("Discard changes?") },
        text = {
          Text(
            "You have unsaved changes. Closing will discard them. Do you want to continue?"
          )
        },
        confirmButton = {
          TextButton(
            onClick = {
              showCloseConfirmation = false
              onDismiss()
            }
          ) {
            Text("Discard")
          }
        },
        dismissButton = {
          TextButton(onClick = { showCloseConfirmation = false }) {
            Text("Stay in Editor")
          }
        }
      )
    }
  }
}

/**
 * Header for the trail editor modal.
 */
@Composable
private fun TrailEditorHeader(
  variant: TrailVariant,
  relativePath: String?,
  editorMode: YamlEditorMode,
  onYamlEditorModeChange: (YamlEditorMode) -> Unit,
  hasUnsavedChanges: Boolean,
  validationError: String?,
  saveSuccess: Boolean,
  saveError: String?,
  onSave: () -> Unit,
  onOpenFolder: () -> Unit,
  onClose: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    // Left side: Title and file info
    Column(modifier = Modifier.weight(1f)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Icon(
          imageVector = Icons.Filled.Code,
          contentDescription = null,
          modifier = Modifier.size(24.dp),
          tint = MaterialTheme.colorScheme.primary
        )
        Text(
          text = "Edit Trail",
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold
        )
        if (hasUnsavedChanges) {
          Text(
            text = "● Unsaved",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp)
          )
        }
        if (saveSuccess) {
          Text(
            text = "✓ Saved",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF4CAF50),
            modifier = Modifier.padding(start = 8.dp)
          )
        }
        saveError?.let { error ->
          Text(
            text = "✗ $error",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 8.dp)
          )
        }
      }
      
      Spacer(modifier = Modifier.height(4.dp))
      
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Text(
          text = variant.displayLabel,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
          text = "•",
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
          text = relativePath ?: variant.fileName,
          style = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace
          ),
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        IconButton(
          onClick = onOpenFolder,
          modifier = Modifier.size(24.dp)
        ) {
          Icon(
            imageVector = Icons.Filled.FolderOpen,
            contentDescription = "Open folder",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    }
    
    // Right side: Editor mode toggle and actions
    Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      // Editor mode toggle
      FilterChip(
        selected = editorMode == YamlEditorMode.VISUAL,
        onClick = { onYamlEditorModeChange(YamlEditorMode.VISUAL) },
        label = { Text("Visual") },
        leadingIcon = {
          Icon(
            Icons.Filled.ViewModule,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
          )
        }
      )
      FilterChip(
        selected = editorMode == YamlEditorMode.TEXT,
        onClick = { onYamlEditorModeChange(YamlEditorMode.TEXT) },
        label = { Text("Text") },
        leadingIcon = {
          Icon(
            Icons.Filled.Code,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
          )
        }
      )
      
      Spacer(modifier = Modifier.width(8.dp))
      
      Button(
        onClick = onSave,
        enabled = hasUnsavedChanges && validationError == null,
        colors = ButtonDefaults.buttonColors(
          containerColor = if (hasUnsavedChanges && validationError == null)
            MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.surfaceVariant
        )
      ) {
        Icon(
          Icons.Filled.Save,
          contentDescription = "Save",
          modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text("Save")
      }
      
      IconButton(onClick = onClose) {
        Icon(
          imageVector = Icons.Filled.Close,
          contentDescription = "Close"
        )
      }
    }
  }
}

