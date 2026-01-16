package xyz.block.trailblaze.ui.tabs.trails

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Panel displaying details about a selected trail file.
 *
 * @param trailFile The selected trail file node
 * @param onViewYaml Callback when View YAML button is clicked
 * @param onOpenInFinder Callback when Open Folder button is clicked
 */
@Composable
fun TrailDetailsPanel(
  trailFile: TrailNode.TrailFile,
  onViewYaml: (TrailNode.TrailFile) -> Unit,
  onOpenInFinder: (TrailNode.TrailFile) -> Unit,
) {
  val trail = trailFile.existingTrail
  
  Card(
    modifier = Modifier.fillMaxWidth(),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp)
    ) {
      Text(
        text = "Trail Details",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
      )
      
      Spacer(modifier = Modifier.height(12.dp))
      
      // File name
      DetailRow(label = "File", value = trail.fileName)
      
      // Platform
      trail.platform?.let {
        DetailRow(label = "Platform", value = it.toString())
      }
      
      // Classifiers
      if (trail.classifiers.isNotEmpty()) {
        DetailRow(label = "Classifiers", value = trail.classifiers.joinToString(", "))
      }
      
      // Path
      DetailRow(label = "Path", value = trail.relativePath)
      
      Spacer(modifier = Modifier.height(16.dp))
      
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Button(
          onClick = { onViewYaml(trailFile) },
          modifier = Modifier.weight(1f)
        ) {
          Icon(
            imageVector = Icons.Filled.Code,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text("View YAML")
        }
        
        Button(
          onClick = { onOpenInFinder(trailFile) },
          modifier = Modifier.weight(1f)
        ) {
          Icon(
            imageVector = Icons.Filled.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text("Open Folder")
        }
      }
    }
  }
}

@Composable
private fun DetailRow(label: String, value: String) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 4.dp)
  ) {
    Text(
      text = "$label:",
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.Medium,
      modifier = Modifier.width(100.dp)
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
  }
}
