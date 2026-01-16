package xyz.block.trailblaze.ui.tabs.trails

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.ui.composables.LocalDeviceClassifierIcons
import xyz.block.trailblaze.ui.composables.getIcon

/**
 * Details view showing a selected trail and its variants.
 *
 * @param trail The selected trail
 * @param onOpenFolder Callback to open the trail folder in Finder
 * @param onViewVariant Callback when a variant is clicked to view its YAML
 * @param onClose Callback when the close button is clicked
 */
@Composable
fun TrailDetailsView(
  trail: Trail,
  onOpenFolder: () -> Unit,
  onViewVariant: (TrailVariant) -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Card(
    modifier = modifier.fillMaxWidth(),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(16.dp)
    ) {
      // Header with close button
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "Trail Details",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold
        )
        IconButton(
          onClick = onClose,
          modifier = Modifier.size(24.dp)
        ) {
          Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Close",
            modifier = Modifier.size(18.dp)
          )
        }
      }
      
      Spacer(modifier = Modifier.height(12.dp))
      
      // Title (from config)
      trail.title?.let {
        DetailRow(label = "Title", value = it)
      }
      
      // Trail ID
      DetailRow(label = "ID", value = trail.id, isMonospace = true)
      
      // Display name (only if different from title and ID)
      if (trail.displayName != trail.id && trail.displayName != trail.title) {
        DetailRow(label = "Name", value = trail.displayName)
      }
      
      // Parent path
      trail.parentPath?.let {
        DetailRow(label = "Path", value = it, isMonospace = true)
      }
      
      // Priority (from config)
      trail.priority?.let {
        DetailRow(label = "Priority", value = it)
      }
      
      // Platforms
      if (trail.platforms.isNotEmpty()) {
        DetailRow(label = "Platforms", value = trail.platforms.joinToString(", ") { it.displayName })
      }
      
      // Variant count
      DetailRow(label = "Variants", value = "${trail.variants.size}")
      
      // Description (from config)
      trail.description?.let { desc ->
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = "Description",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium
        )
        Text(
          text = desc,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 4.dp)
        )
      }
      
      // Metadata (from config)
      if (trail.metadata.isNotEmpty()) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
          text = "Metadata",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        trail.metadata.forEach { (key, value) ->
          DetailRow(label = key, value = value, isMonospace = true)
        }
      }
      
      Spacer(modifier = Modifier.height(16.dp))
      
      // Open folder button
      Button(
        onClick = onOpenFolder,
        modifier = Modifier.fillMaxWidth()
      ) {
        Icon(
          imageVector = Icons.Filled.FolderOpen,
          contentDescription = null,
          modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("Open Folder")
      }
      
      Spacer(modifier = Modifier.height(16.dp))
      HorizontalDivider()
      Spacer(modifier = Modifier.height(16.dp))
      
      // Variants section
      Text(
        text = "Variants",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
      )
      
      Spacer(modifier = Modifier.height(8.dp))
      
      // Variant list grouped by platform/first classifier
      val groupedVariants = trail.variants.groupBy { variant ->
        variant.classifiers.firstOrNull()?.classifier?.lowercase() ?: "default"
      }.toSortedMap(compareBy { groupKey ->
        // Sort order: default first, then known platforms alphabetically, then others
        when (groupKey) {
          "default" -> "0_default"
          "android" -> "1_android"
          "ios" -> "2_ios"
          "web" -> "3_web"
          else -> "4_$groupKey"
        }
      })
      
      Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        groupedVariants.forEach { (groupKey, variants) ->
          // Group header
          Text(
            text = groupKey.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
          )
          
          // Variants in this group
          variants.forEach { variant ->
            VariantRow(
              variant = variant,
              onClick = { onViewVariant(variant) }
            )
          }
        }
      }
    }
  }
}

@Composable
private fun DetailRow(
  label: String,
  value: String,
  isMonospace: Boolean = false,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 2.dp)
  ) {
    Text(
      text = "$label:",
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.Medium,
      modifier = Modifier.width(80.dp)
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodyMedium.let {
        if (isMonospace) it.copy(fontFamily = FontFamily.Monospace) else it
      },
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
  }
}

@Composable
private fun VariantRow(
  variant: TrailVariant,
  onClick: () -> Unit,
) {
  val deviceClassifierIconProvider = LocalDeviceClassifierIcons.current
  // Use platform's getIcon() for known platforms (case-insensitive), fall back to provider for custom classifiers
  val iconPainter = variant.platform?.let { rememberVectorPainter(it.getIcon()) }
    ?: variant.platformClassifier?.let { deviceClassifierIconProvider.getPainter(it) }
    ?: rememberVectorPainter(Icons.Filled.Description)
  val sourceTypeIcon = variant.config?.source?.type?.getIcon()
  
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
      .clickable(onClick = onClick)
      .padding(12.dp)
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth()
    ) {
      Image(
        painter = iconPainter,
        contentDescription = null,
        modifier = Modifier.size(24.dp),
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant)
      )
      
      Spacer(modifier = Modifier.width(12.dp))
      
      Column(modifier = Modifier.weight(1f)) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Text(
            text = variant.displayLabel,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          // Source type icon per file
          sourceTypeIcon?.let { srcIcon ->
            Icon(
              imageVector = srcIcon,
              contentDescription = variant.config?.source?.type?.name,
              modifier = Modifier.size(16.dp),
              tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
          }
        }
        Text(
          text = variant.fileName,
          style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace
          ),
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
      }
      
      Icon(
        imageVector = Icons.Filled.Code,
        contentDescription = "View YAML",
        modifier = Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
}
