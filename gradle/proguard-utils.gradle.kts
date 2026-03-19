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
 *   apply(from = rootProject.file("opensource/gradle/proguard-utils.gradle.kts"))
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

// Restores binary resource entries that were excluded from ProGuard processing.
// Call as: restoreArchiveEntries(originalUberJar, shrunkOutputJar)
extra["restoreArchiveEntries"] = fun(originalJar: File, shrunkJar: File) {
  val entries = mutableMapOf<String, ByteArray>()
  ZipFile(originalJar).use { zip ->
    zip.entries().asSequence()
      .filter { entry -> archiveExtensions.any { entry.name.endsWith(it) } }
      .forEach { entry -> entries[entry.name] = zip.getInputStream(entry).readBytes() }
  }

  if (entries.isEmpty()) return

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
  println("Restored ${entries.size} binary resource entries into shrunk JAR: ${entries.keys}")
}
