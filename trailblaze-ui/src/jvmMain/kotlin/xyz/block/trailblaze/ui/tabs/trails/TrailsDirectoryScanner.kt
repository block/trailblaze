package xyz.block.trailblaze.ui.tabs.trails

import xyz.block.trailblaze.recordings.TrailRecordings
import java.io.File

/**
 * Utility for scanning the trails directory and finding trails.
 *
 * A trail is a directory containing one or more .trail.yaml files.
 * Each trail is identified by its relative path from the trails root.
 */
object TrailsDirectoryScanner {
  
  /**
   * Scans a trails directory and returns a flat list of trails.
   * Each trail represents a directory containing trail.yaml files.
   *
   * @param trailsDir The root trails directory to scan
   * @return List of trails found, sorted alphabetically by ID
   */
  fun scanForTrails(trailsDir: File): List<Trail> {
    if (!trailsDir.exists() || !trailsDir.isDirectory) {
      return emptyList()
    }
    
    val trails = mutableListOf<Trail>()
    scanDirectoryForTrails(trailsDir, trailsDir, trails)
    return trails.sortedBy { it.id.lowercase() }
  }
  
  /**
   * Recursively scans directories to find trails.
   * A directory is considered a trail if it contains at least one .trail.yaml file.
   */
  private fun scanDirectoryForTrails(
    currentDir: File,
    rootDir: File,
    trails: MutableList<Trail>,
  ) {
    val files = currentDir.listFiles() ?: return
    
    // Find trail files in this directory
    val trailFiles = files.filter { it.isFile && it.name.endsWith(TrailRecordings.TRAIL_DOT_YAML) }
    
    // If this directory has trail files, it's a trail
    if (trailFiles.isNotEmpty()) {
      val relativePath = currentDir.relativeTo(rootDir).path
      val trailId = relativePath.ifEmpty { currentDir.name }
      
      val variants = trailFiles
        .map { TrailVariant.fromFile(it) }
        .sortedWith(compareBy(
          { !it.isDefault }, // Default first
          { it.platform?.name ?: "" }, // Then by platform
          { it.deviceClassifiers.firstOrNull()?.classifier ?: "" } // Then by device classifier
        ))
      
      trails.add(
        Trail(
          id = trailId,
          absolutePath = currentDir.absolutePath,
          variants = variants,
        )
      )
    }
    
    // Recursively scan child directories
    files
      .filter { it.isDirectory }
      .sortedBy { it.name }
      .forEach { childDir ->
        scanDirectoryForTrails(childDir, rootDir, trails)
      }
  }
  
  /**
   * Counts the total number of trails.
   */
  fun countTrails(trails: List<Trail>): Int = trails.size
  
  /**
   * Counts the total number of trail variants across all trails.
   */
  fun countVariants(trails: List<Trail>): Int = trails.sumOf { it.variants.size }
  
  /**
   * Finds a trail by its ID.
   */
  fun findTrailById(trails: List<Trail>, id: String): Trail? {
    return trails.find { it.id == id }
  }
}
