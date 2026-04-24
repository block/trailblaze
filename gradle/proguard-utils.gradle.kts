/**
 * Shared ProGuard post-processing utilities for trailblaze
 *
 * ProGuard treats .apk and .zip files as nested archives — it opens them, processes class
 * files inside, and repackages them. This strips APK signing certificates and corrupts iOS
 * XCTest bundles. Both modules must:
 *   1. Exclude these extensions from -injars  (using INJARS_RESOURCE_FILTER)
 *   2. Restore them verbatim afterward         (using restoreArchiveEntries)
 *
 * Resources preserved:
 *   - maestro-app.apk, maestro-server.apk        (Maestro Android instrumentation)
 *   - apks/trailblaze-ondevice-runner.apk         (Trailblaze on-device runner)
 *   - driver-&#42;/maestro-driver-ios*.zip            (Maestro iOS XCTest bundles)
 *
 * Usage in a build.gradle.kts:
 *   apply(from = rootProject.file("gradle/proguard-utils.gradle.kts"))
 *   val proguardInjarsResourceFilter: String by extra
 *   val restoreArchiveEntries: (File, File) -> Unit by extra
 */

import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

val archiveExtensions = listOf(".apk", ".zip")

// ProGuard -injars filter that excludes nested archive resources from processing.
// Use as: "-injars", "${jarPath}($proguardInjarsResourceFilter)"
extra["proguardInjarsResourceFilter"] = archiveExtensions.joinToString(",") { "!**$it" }

// Restores binary resource entries that were excluded from ProGuard processing,
// plus directory entries that ProGuard strips (needed by classLoader.getResource()
// for directory lookups, e.g. Maestro's IOSBuildProductsExtractor).
// Call as: restoreArchiveEntries(originalUberJar, shrunkOutputJar)
extra["restoreArchiveEntries"] = fun(originalJar: File, shrunkJar: File) {
  val entries = mutableMapOf<String, ByteArray>()
  val directoryEntries = mutableSetOf<String>()
  ZipFile(originalJar).use { zip ->
    zip.entries().asSequence().forEach { entry ->
      if (archiveExtensions.any { entry.name.endsWith(it) }) {
        entries[entry.name] = zip.getInputStream(entry).readBytes()
      } else if (entry.isDirectory) {
        directoryEntries.add(entry.name)
      }
    }
  }

  if (entries.isEmpty() && directoryEntries.isEmpty()) return

  // Collect directory entries already in the shrunk JAR so we don't duplicate
  val existingEntryNames = mutableSetOf<String>()
  ZipFile(shrunkJar).use { zip ->
    zip.entries().asSequence().forEach { existingEntryNames.add(it.name) }
  }
  val missingDirs = directoryEntries - existingEntryNames

  val tempFile = File(shrunkJar.parentFile, "${shrunkJar.name}.tmp")
  ZipFile(shrunkJar).use { existingZip ->
    ZipOutputStream(tempFile.outputStream().buffered()).use { zos ->
      // Copy all existing entries from the shrunk JAR, skipping any we'll restore
      existingZip.entries().asSequence()
        .filter { it.name !in entries }
        .forEach { entry ->
          zos.putNextEntry(ZipEntry(entry.name))
          existingZip.getInputStream(entry).use { it.copyTo(zos) }
          zos.closeEntry()
        }
      // Restore directory entries that ProGuard stripped
      missingDirs.sorted().forEach { dirName ->
        zos.putNextEntry(ZipEntry(dirName).apply {
          method = ZipEntry.STORED
          size = 0
          compressedSize = 0
          crc = 0
        })
        zos.closeEntry()
      }
      // Add the archive entries verbatim from the original JAR (STORED for byte-for-byte integrity)
      entries.forEach { (name, bytes) ->
        zos.putNextEntry(
          ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
          }
        )
        zos.write(bytes)
        zos.closeEntry()
      }
    }
  }
  tempFile.renameTo(shrunkJar)
  println("Restored ${entries.size} binary resource entries and ${missingDirs.size} directory entries into shrunk JAR")
}
