import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

abstract class ShrinkUberJarWithProguardTask @Inject constructor(objects: ObjectFactory) : DefaultTask() {
  @get:Classpath
  val proguardClasspath: ConfigurableFileCollection = objects.fileCollection()

  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val inputJarsDir: DirectoryProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val proguardRules: RegularFileProperty

  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.NAME_ONLY)
  abstract val jmodsDir: DirectoryProperty

  @get:Input
  abstract val injarsResourceFilter: Property<String>

  @get:OutputFile
  abstract val outputJar: RegularFileProperty

  @get:Inject
  abstract val execOperations: ExecOperations

  @TaskAction
  fun shrink() {
    val output = outputJar.get().asFile
    output.parentFile.mkdirs()
    val actualJar =
      inputJarsDir.get().asFile.listFiles()
        ?.filter { it.extension == "jar" }
        ?.maxByOrNull { it.lastModified() }
        ?: throw GradleException("No uber JAR found in ${inputJarsDir.get().asFile.absolutePath}")

    val jmodsArgs =
      jmodsDir.get().asFile.listFiles { f -> f.extension == "jmod" }
        ?.sorted()
        ?.flatMap { listOf("-libraryjars", "${it.absolutePath}(!**.jar;!module-info.class)") }
        ?: throw GradleException("No jmod files found in ${jmodsDir.get().asFile.absolutePath}")

    execOperations.javaexec { spec ->
      spec.classpath(proguardClasspath)
      spec.mainClass.set("proguard.ProGuard")
      spec.args(
        "-include",
        proguardRules.get().asFile.absolutePath,
        "-injars",
        "${actualJar.absolutePath}(${injarsResourceFilter.get()})",
        "-outjars",
        output.absolutePath,
        *jmodsArgs.toTypedArray(),
      )
    }.assertNormalExitValue()

    restoreArchiveEntries(actualJar, output)
  }

  private fun restoreArchiveEntries(originalJar: File, shrunkJar: File) {
    val archiveExtensions = listOf(".apk", ".zip")
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

    val existingEntryNames = mutableSetOf<String>()
    ZipFile(shrunkJar).use { zip ->
      zip.entries().asSequence().forEach { existingEntryNames.add(it.name) }
    }
    val missingDirs = directoryEntries - existingEntryNames

    val tempFile = File(shrunkJar.parentFile, "${shrunkJar.name}.tmp")
    ZipFile(shrunkJar).use { existingZip ->
      ZipOutputStream(tempFile.outputStream().buffered()).use { zos ->
        existingZip.entries().asSequence()
          .filter { it.name !in entries }
          .forEach { entry ->
            zos.putNextEntry(ZipEntry(entry.name))
            existingZip.getInputStream(entry).use { it.copyTo(zos) }
            zos.closeEntry()
          }
        missingDirs.sorted().forEach { dirName ->
          zos.putNextEntry(
            ZipEntry(dirName).apply {
              method = ZipEntry.STORED
              size = 0
              compressedSize = 0
              crc = 0
            },
          )
          zos.closeEntry()
        }
        entries.forEach { (name, bytes) ->
          zos.putNextEntry(
            ZipEntry(name).apply {
              method = ZipEntry.STORED
              size = bytes.size.toLong()
              compressedSize = bytes.size.toLong()
              crc = CRC32().apply { update(bytes) }.value
            },
          )
          zos.write(bytes)
          zos.closeEntry()
        }
      }
    }
    if (!tempFile.renameTo(shrunkJar)) {
      throw GradleException("Could not replace ${shrunkJar.absolutePath} with restored ProGuard output")
    }
    logger.lifecycle(
      "Restored ${entries.size} binary resource entries and ${missingDirs.size} directory entries into shrunk JAR",
    )
  }
}
