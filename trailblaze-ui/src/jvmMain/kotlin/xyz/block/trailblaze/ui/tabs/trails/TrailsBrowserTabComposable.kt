package xyz.block.trailblaze.ui.tabs.trails

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.ui.DesktopUtil
import xyz.block.trailblaze.yaml.TrailSourceType
import java.io.File
import java.nio.file.Paths
import javax.swing.JFileChooser

/**
 * Available sort options for trails.
 */
sealed class TrailSortOption(val displayName: String) {
  data object ById : TrailSortOption("ID")
  data object ByTitle : TrailSortOption("Title")
  data object ByPriority : TrailSortOption("Priority")
  data class ByMetadata(val key: String) : TrailSortOption("metadata:$key")
}

/**
 * Trails tab for browsing all trails in the configured trails directory.
 * Shows trails grouped by their directory ID with variant chips.
 *
 * @param trailsDirectoryPath The path to the trails directory to browse
 * @param onChangeDirectory Callback when the user wants to change the trails directory.
 *                          Receives the new directory path. If null, the change directory button is hidden.
 */
@Composable
fun TrailsBrowserTabComposable(
  trailsDirectoryPath: String,
  onChangeDirectory: ((String) -> Unit)? = null,
) {
  val trailsDirectory = remember(trailsDirectoryPath) { File(trailsDirectoryPath) }
  var trails by remember { mutableStateOf<List<Trail>>(emptyList()) }
  var selectedTrailId by remember { mutableStateOf<String?>(null) }
  var searchQuery by remember { mutableStateOf("") }
  var isLoading by remember { mutableStateOf(true) }
  var errorMessage by remember { mutableStateOf<String?>(null) }
  var showYamlViewer by remember { mutableStateOf(false) }
  var yamlViewerContent by remember { mutableStateOf("") }
  var yamlViewerVariant by remember { mutableStateOf<TrailVariant?>(null) }
  
  // Indexing state for background config parsing
  var isIndexing by remember { mutableStateOf(false) }
  var indexingProgress by remember { mutableStateOf(0f) } // 0.0 to 1.0
  
  // Filter state
  var selectedPlatform by remember { mutableStateOf<TrailblazeDevicePlatform?>(null) }
  var selectedClassifier by remember { mutableStateOf<String?>(null) } // e.g., "phone", "tablet"
  var selectedSourceType by remember { mutableStateOf<TrailSourceType?>(null) }
  var selectedMetadataKey by remember { mutableStateOf<String?>(null) }
  var metadataFilterValue by remember { mutableStateOf("") }
  var showFilters by remember { mutableStateOf(false) }
  
  // Sort state
  var selectedSortOption by remember { mutableStateOf<TrailSortOption>(TrailSortOption.ById) }
  var showSortMenu by remember { mutableStateOf(false) }
  
  // Scan directory on load
  LaunchedEffect(trailsDirectory) {
    isLoading = true
    errorMessage = null
    // Clear cache when switching directories
    TrailConfigCache.clearCache()
    try {
      val foundTrails = withContext(Dispatchers.IO) {
        TrailsDirectoryScanner.scanForTrails(trailsDirectory)
      }
      trails = foundTrails
    } catch (e: Exception) {
      errorMessage = "Error scanning trails directory: ${e.message}"
    } finally {
      isLoading = false
    }
  }
  
  // Background indexing for search functionality - runs after initial scan
  LaunchedEffect(trails) {
    if (trails.isNotEmpty() && !isLoading) {
      isIndexing = true
      indexingProgress = 0f
      try {
        TrailConfigCache.indexTrailsInBackground(trails) { indexed, total ->
          indexingProgress = indexed.toFloat() / total.toFloat()
        }
      } catch (e: Exception) {
        println("[TrailsTab] Error during background indexing: ${e.message}")
      } finally {
        isIndexing = false
        indexingProgress = 1f
      }
    }
  }
  
  // Periodic refresh to detect file changes (every 30 seconds)
  LaunchedEffect(trailsDirectory) {
    while (true) {
      delay(30_000) // 30 seconds
      if (!isLoading) {
        try {
          val foundTrails = withContext(Dispatchers.IO) {
            TrailsDirectoryScanner.scanForTrails(trailsDirectory)
          }
          // Only update if the trails actually changed
          if (foundTrails != trails) {
            println("[TrailsTab] Detected file system changes, updating trails")
            trails = foundTrails
            
            // Clear selection if the selected trail no longer exists
            selectedTrailId?.let { id ->
              if (foundTrails.none { it.id == id }) {
                selectedTrailId = null
              }
            }
          }
        } catch (e: Exception) {
          println("[TrailsTab] Error during periodic refresh: ${e.message}")
        }
      }
    }
  }
  
  // Filter and sort trails based on search query, filters, and sort option
  val filteredTrails = remember(trails, searchQuery, selectedPlatform, selectedClassifier, selectedSourceType, selectedMetadataKey, metadataFilterValue, selectedSortOption) {
    trails.filter { trail ->
      // Text search filter
      val matchesSearch = searchQuery.isBlank() || 
        trail.id.contains(searchQuery, ignoreCase = true) ||
        trail.displayName.contains(searchQuery, ignoreCase = true) ||
        trail.title?.contains(searchQuery, ignoreCase = true) == true ||
        trail.description?.contains(searchQuery, ignoreCase = true) == true ||
        trail.priority?.contains(searchQuery, ignoreCase = true) == true ||
        trail.source?.type?.name?.contains(searchQuery, ignoreCase = true) == true ||
        trail.metadata.any { (key, value) ->
          key.contains(searchQuery, ignoreCase = true) || 
            value.contains(searchQuery, ignoreCase = true)
        } ||
        trail.platforms.any { it.name.contains(searchQuery, ignoreCase = true) }
      
      // Platform filter - check if any variant matches the selected platform
      val matchesPlatform = selectedPlatform == null ||
        trail.variants.any { it.platform == selectedPlatform }
      
      // Classifier filter (e.g., phone, tablet) - check if any variant has this classifier
      val matchesClassifier = selectedClassifier == null ||
        trail.variants.any { variant ->
          variant.classifiers.any { it.classifier.equals(selectedClassifier, ignoreCase = true) }
        }
      
      // Source type filter
      val matchesSourceType = selectedSourceType == null ||
        trail.source?.type == selectedSourceType
      
      // Metadata filter
      val matchesMetadata = selectedMetadataKey == null || metadataFilterValue.isBlank() ||
        trail.metadata[selectedMetadataKey]?.contains(metadataFilterValue, ignoreCase = true) == true
      
      matchesSearch && matchesPlatform && matchesClassifier && matchesSourceType && matchesMetadata
    }.let { filtered ->
      val sortOption = selectedSortOption
      filtered.sortedWith { a, b ->
        when (sortOption) {
          is TrailSortOption.ById -> a.id.compareTo(b.id, ignoreCase = true)
          is TrailSortOption.ByTitle -> {
            val aTitle = a.title ?: a.id
            val bTitle = b.title ?: b.id
            aTitle.compareTo(bTitle, ignoreCase = true)
          }
          is TrailSortOption.ByPriority -> {
            val aPriority = a.priority ?: ""
            val bPriority = b.priority ?: ""
            aPriority.compareTo(bPriority, ignoreCase = true)
          }
          is TrailSortOption.ByMetadata -> {
            val aValue = a.metadata[sortOption.key] ?: ""
            val bValue = b.metadata[sortOption.key] ?: ""
            aValue.compareTo(bValue, ignoreCase = true)
          }
        }
      }
    }
  }
  
  // Get available classifiers from the data for the filter options
  val availableClassifiers = remember(trails) {
    trails.flatMap { trail ->
      trail.variants.flatMap { variant ->
        variant.classifiers.map { it.classifier.lowercase() }
      }
    }.distinct().filter { classifier ->
      // Exclude platform names since they have their own filter
      classifier !in listOf("android", "ios", "web")
    }.sorted()
  }
  
  // Get available source types from the data (only show source types that exist in scanned trails)
  val availableSourceTypes = remember(trails) {
    trails.flatMap { trail ->
      trail.variants.mapNotNull { variant ->
        variant.config?.source?.type
      }
    }.distinct().sortedBy { it.name }
  }
  
  // Get available metadata keys from the data for sort options
  val availableMetadataKeys = remember(trails) {
    trails.flatMap { trail ->
      trail.metadata.keys
    }.distinct().sorted()
  }
  
  // Build available sort options based on discovered data
  val availableSortOptions = remember(availableMetadataKeys) {
    buildList {
      add(TrailSortOption.ById)
      add(TrailSortOption.ByTitle)
      add(TrailSortOption.ByPriority)
      availableMetadataKeys.forEach { key ->
        add(TrailSortOption.ByMetadata(key))
      }
    }
  }
  
  val selectedTrail = remember(selectedTrailId, trails) {
    selectedTrailId?.let { id -> trails.find { it.id == id } }
  }
  
  Column(
    modifier = Modifier.fillMaxSize().padding(16.dp)
  ) {
    // Header
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "Trails Browser",
          style = MaterialTheme.typography.headlineMedium,
          fontWeight = FontWeight.Bold
        )
        
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text(
            text = trailsDirectory.absolutePath,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable {
              DesktopUtil.openInFileBrowser(trailsDirectory)
            }
          )
          
          // Open in Finder button
          IconButton(
            onClick = { DesktopUtil.openInFileBrowser(trailsDirectory) },
            modifier = Modifier.size(24.dp)
          ) {
            Icon(
              imageVector = Icons.Filled.FolderOpen,
              contentDescription = "Open in Finder",
              modifier = Modifier.size(16.dp),
              tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
          
          // Change directory button (only if callback is provided)
          if (onChangeDirectory != null) {
            IconButton(
              onClick = {
                val fileChooser = JFileChooser().apply {
                  fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                  dialogTitle = "Select Trails Directory"
                  currentDirectory = if (trailsDirectory.exists()) trailsDirectory else trailsDirectory.parentFile
                }
                val result = fileChooser.showOpenDialog(null)
                if (result == JFileChooser.APPROVE_OPTION) {
                  onChangeDirectory(fileChooser.selectedFile.absolutePath)
                }
              },
              modifier = Modifier.size(24.dp)
            ) {
              Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = "Change Directory",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
        }
      }
      
      // Refresh button
      IconButton(
        onClick = {
          isLoading = true
          errorMessage = null
          CoroutineScope(Dispatchers.IO).launch {
            try {
              val foundTrails = TrailsDirectoryScanner.scanForTrails(trailsDirectory)
              withContext(Dispatchers.Main) {
                trails = foundTrails
                selectedTrailId?.let { id ->
                  if (foundTrails.none { it.id == id }) {
                    selectedTrailId = null
                  }
                }
                isLoading = false
              }
            } catch (e: Exception) {
              withContext(Dispatchers.Main) {
                errorMessage = "Error scanning trails directory: ${e.message}"
                isLoading = false
              }
            }
          }
        },
        enabled = !isLoading
      ) {
        Icon(
          imageVector = Icons.Filled.Refresh,
          contentDescription = "Refresh"
        )
      }
    }
    
    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
    
    // Content
    when {
      isLoading -> {
        Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.Center
        ) {
          CircularProgressIndicator()
        }
      }
      errorMessage != null -> {
        Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.Center
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
          ) {
            Icon(
              imageVector = Icons.Filled.Error,
              contentDescription = null,
              modifier = Modifier.size(64.dp),
              tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
              text = "Error loading trails",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Medium,
              color = MaterialTheme.colorScheme.error
            )
            Text(
              text = errorMessage!!,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(
              onClick = {
                isLoading = true
                errorMessage = null
                CoroutineScope(Dispatchers.IO).launch {
                  try {
                    val foundTrails = TrailsDirectoryScanner.scanForTrails(trailsDirectory)
                    withContext(Dispatchers.Main) {
                      trails = foundTrails
                      isLoading = false
                    }
                  } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                      errorMessage = "Error scanning trails directory: ${e.message}"
                      isLoading = false
                    }
                  }
                }
              }
            ) {
              Text("Retry")
            }
          }
        }
      }
      trails.isEmpty() -> {
        Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.Center
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
          ) {
            Icon(
              imageVector = Icons.Filled.FolderOpen,
              contentDescription = null,
              modifier = Modifier.size(64.dp),
              tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
              text = "No trails found",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Medium
            )
            Text(
              text = "Add trail.yaml files to subdirectories",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(
              onClick = {
                DesktopUtil.openInFileBrowser(trailsDirectory)
              }
            ) {
              Text("Open Trails Directory")
            }
          }
        }
      }
      else -> {
        Row(modifier = Modifier.fillMaxSize()) {
          // Left side: Trail list
          Column(
            modifier = Modifier
              .weight(1f)
              .fillMaxHeight()
          ) {
            // Stats and search
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                val hasFilters = searchQuery.isNotBlank() || selectedPlatform != null || 
                  selectedClassifier != null || selectedSourceType != null
                Text(
                  text = if (hasFilters) {
                    "${filteredTrails.size} of ${trails.size} trail(s)"
                  } else {
                    "${trails.size} trail(s), ${TrailsDirectoryScanner.countVariants(trails)} variant(s)"
                  },
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Show indexing progress
                if (isIndexing) {
                  CircularProgressIndicator(
                    progress = { indexingProgress },
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp
                  )
                  Text(
                    text = "Indexing...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
              }
              
              // Sort dropdown
              Box {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier.clickable { showSortMenu = true }
                ) {
                  Icon(
                    imageVector = Icons.Filled.Sort,
                    contentDescription = "Sort",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                  Spacer(modifier = Modifier.width(4.dp))
                  Text(
                    text = "Sort: ${selectedSortOption.displayName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
                
                DropdownMenu(
                  expanded = showSortMenu,
                  onDismissRequest = { showSortMenu = false }
                ) {
                  availableSortOptions.forEach { sortOption ->
                    DropdownMenuItem(
                      text = { Text(sortOption.displayName) },
                      onClick = {
                        selectedSortOption = sortOption
                        showSortMenu = false
                      }
                    )
                  }
                }
              }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Search field
            OutlinedTextField(
              value = searchQuery,
              onValueChange = { searchQuery = it },
              modifier = Modifier.fillMaxWidth(),
              placeholder = { Text("Search by title, ID, priority...") },
              leadingIcon = {
                Icon(
                  imageVector = Icons.Filled.Search,
                  contentDescription = null,
                  modifier = Modifier.size(20.dp)
                )
              },
              singleLine = true
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Filter panel with visual container
            val activeFilterCount = listOfNotNull(
              selectedPlatform,
              selectedClassifier,
              selectedSourceType,
              selectedMetadataKey.takeIf { metadataFilterValue.isNotBlank() }
            ).size
            
            Card(
              modifier = Modifier.fillMaxWidth(),
              colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
              ),
              elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
              Column(
                modifier = Modifier.padding(12.dp)
              ) {
                // Filter header with toggle
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { showFilters = !showFilters }
                  ) {
                    Icon(
                      imageVector = Icons.Filled.FilterList,
                      contentDescription = null,
                      modifier = Modifier.size(18.dp),
                      tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                      text = "Filters",
                      style = MaterialTheme.typography.labelMedium,
                      fontWeight = FontWeight.Medium
                    )
                    if (activeFilterCount > 0) {
                      Spacer(modifier = Modifier.width(8.dp))
                      Text(
                        text = "($activeFilterCount active)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                      )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                      imageVector = if (showFilters) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                      contentDescription = if (showFilters) "Collapse" else "Expand",
                      modifier = Modifier.size(18.dp),
                      tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                  
                  if (activeFilterCount > 0) {
                    TextButton(
                      onClick = {
                        selectedPlatform = null
                        selectedClassifier = null
                        selectedSourceType = null
                        selectedMetadataKey = null
                        metadataFilterValue = ""
                      }
                    ) {
                      Text("Clear all", style = MaterialTheme.typography.labelSmall)
                    }
                  }
                }
                
                // Collapsible filter sections
                if (showFilters) {
                  Spacer(modifier = Modifier.height(12.dp))
                  HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                  Spacer(modifier = Modifier.height(12.dp))
                  
                  Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                  ) {
                    // Platform section
                    FilterSection(
                      label = "Platform",
                      hasSelection = selectedPlatform != null
                    ) {
                      @OptIn(ExperimentalLayoutApi::class)
                      FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                      ) {
                        TrailblazeDevicePlatform.entries.forEach { platform ->
                          FilterChip(
                            selected = selectedPlatform == platform,
                            onClick = {
                              selectedPlatform = if (selectedPlatform == platform) null else platform
                            },
                            label = { Text(platform.displayName) }
                          )
                        }
                      }
                    }
                    
                    // Device classifier section (only show if there are classifiers)
                    if (availableClassifiers.isNotEmpty()) {
                      FilterSection(
                        label = "Device Type",
                        hasSelection = selectedClassifier != null
                      ) {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                          horizontalArrangement = Arrangement.spacedBy(8.dp),
                          verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                          availableClassifiers.forEach { classifier ->
                            FilterChip(
                              selected = selectedClassifier == classifier,
                              onClick = {
                                selectedClassifier = if (selectedClassifier == classifier) null else classifier
                              },
                              label = { Text(classifier.replaceFirstChar { it.uppercase() }) }
                            )
                          }
                        }
                      }
                    }
                    
                    // Source type section (only show if there are source types)
                    if (availableSourceTypes.isNotEmpty()) {
                      FilterSection(
                        label = "Source",
                        hasSelection = selectedSourceType != null
                      ) {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                          horizontalArrangement = Arrangement.spacedBy(8.dp),
                          verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                          availableSourceTypes.forEach { sourceType ->
                            FilterChip(
                              selected = selectedSourceType == sourceType,
                              onClick = {
                                selectedSourceType = if (selectedSourceType == sourceType) null else sourceType
                              },
                              label = { Text(sourceType.name) }
                            )
                          }
                        }
                      }
                    }
                    
                    // Metadata filter section (only show if there are metadata keys)
                    if (availableMetadataKeys.isNotEmpty()) {
                      FilterSection(
                        label = "Metadata",
                        hasSelection = selectedMetadataKey != null && metadataFilterValue.isNotBlank()
                      ) {
                        Row(
                          horizontalArrangement = Arrangement.spacedBy(8.dp),
                          verticalAlignment = Alignment.CenterVertically
                        ) {
                          // Metadata key dropdown
                          Box {
                            var showMetadataKeyMenu by remember { mutableStateOf(false) }
                            OutlinedTextField(
                              value = selectedMetadataKey ?: "Select key...",
                              onValueChange = { },
                              readOnly = true,
                              modifier = Modifier
                                .width(180.dp)
                                .clickable { showMetadataKeyMenu = true },
                              textStyle = MaterialTheme.typography.bodySmall,
                              singleLine = true,
                              enabled = false // Makes it act like a dropdown
                            )
                            DropdownMenu(
                              expanded = showMetadataKeyMenu,
                              onDismissRequest = { showMetadataKeyMenu = false }
                            ) {
                              availableMetadataKeys.forEach { key ->
                                DropdownMenuItem(
                                  text = { Text(key) },
                                  onClick = {
                                    selectedMetadataKey = key
                                    showMetadataKeyMenu = false
                                  }
                                )
                              }
                            }
                            // Invisible clickable overlay
                            Box(
                              modifier = Modifier
                                .matchParentSize()
                                .clickable { showMetadataKeyMenu = true }
                            )
                          }
                          
                          Text("contains", style = MaterialTheme.typography.bodySmall)
                          
                          // Metadata value input
                          OutlinedTextField(
                            value = metadataFilterValue,
                            onValueChange = { metadataFilterValue = it },
                            modifier = Modifier.width(200.dp),
                            placeholder = { Text("value...") },
                            textStyle = MaterialTheme.typography.bodySmall,
                            singleLine = true,
                            enabled = selectedMetadataKey != null
                          )
                          
                          // Clear metadata filter button
                          if (selectedMetadataKey != null) {
                            IconButton(
                              onClick = {
                                selectedMetadataKey = null
                                metadataFilterValue = ""
                              },
                              modifier = Modifier.size(24.dp)
                            ) {
                              Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Clear metadata filter",
                                modifier = Modifier.size(16.dp)
                              )
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Trail list
            LazyColumn(
              verticalArrangement = Arrangement.spacedBy(8.dp),
              contentPadding = PaddingValues(bottom = 16.dp)
            ) {
              items(filteredTrails, key = { it.id }) { trail ->
                TrailCard(
                  trail = trail,
                  isSelected = trail.id == selectedTrailId,
                  onClick = { selectedTrailId = trail.id },
                )
              }
            }
          }
          
          // Right side: Details panel (if trail selected)
          selectedTrail?.let { trail ->
            Spacer(modifier = Modifier.width(16.dp))
            Column(
              modifier = Modifier
                .width(400.dp)
                .fillMaxHeight()
            ) {
              TrailDetailsView(
                trail = trail,
                onOpenFolder = {
                  DesktopUtil.openInFileBrowser(File(trail.absolutePath))
                },
                onViewVariant = { variant ->
                  try {
                    val content = File(variant.absolutePath).readText()
                    yamlViewerContent = content
                    yamlViewerVariant = variant
                    showYamlViewer = true
                  } catch (e: Exception) {
                    errorMessage = "Failed to read YAML file: ${e.message}"
                  }
                },
                onClose = {
                  selectedTrailId = null
                }
              )
            }
          }
        }
      }
    }
  }
  
  // YAML Editor Modal
  if (showYamlViewer && yamlViewerVariant != null) {
    TrailYamlEditorModal(
      variant = yamlViewerVariant!!,
      initialContent = yamlViewerContent,
      onSave = { newContent ->
        try {
          val file = File(yamlViewerVariant!!.absolutePath)
          file.writeText(newContent)
          // Invalidate the cache for this file to reflect changes
          TrailConfigCache.invalidate(yamlViewerVariant!!.absolutePath)
          // Update the local content to reflect saved state
          yamlViewerContent = newContent
          Result.success(Unit)
        } catch (e: Exception) {
          Result.failure(e)
        }
      },
      onDismiss = {
        showYamlViewer = false
        yamlViewerVariant = null
        yamlViewerContent = ""
      },
      relativePath = runCatching {
        Paths.get(trailsDirectory.absolutePath).relativize(Paths.get(yamlViewerVariant!!.absolutePath)).toString()
      }.getOrElse { yamlViewerVariant!!.fileName }
    )
  }
}

/**
 * A labeled filter section with optional active indicator.
 */
@Composable
private fun FilterSection(
  label: String,
  hasSelection: Boolean,
  content: @Composable () -> Unit,
) {
  Column {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = if (hasSelection) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
      )
      if (hasSelection) {
        Text(
          text = "•",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary
        )
      }
    }
    Spacer(modifier = Modifier.height(4.dp))
    content()
  }
}
