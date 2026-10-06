import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar

/**
 * Registers `packageUberJarForCurrentOS`: one runnable JAR holding the module's own classes and its
 * whole runtime classpath, flattened, at `build/compose/jars/Trailblaze-<os>-<arch>-<version>.jar`.
 *
 * This is the JAR the CLI ships as. The Compose Desktop plugin's task of the same name used to build
 * it; this reproduces that task, so the plugin is no longer needed to package a CLI. The task name,
 * directory and file name are kept unchanged on purpose: the dev launcher, the install scripts and
 * the release tasks all find the JAR by them.
 *
 * Packaging rules carried over from the Compose task, each of which changes the shipped bytes:
 * - [appJar] comes first and [DuplicatesStrategy.EXCLUDE] keeps the first copy of an entry, so the
 *   module's own classes and resources win over a dependency's, and for `META-INF/services` files
 *   that collide only the first survives (no merging).
 * - Only archives on the classpath are unpacked into it; anything else there (e.g. a POM-only
 *   dependency's `.pom`) is left out.
 * - zip64, because the JAR holds more than 65 535 entries.
 *
 * Also registers `pruneStaleUberJars`, which runs before every package and deletes the JARs earlier
 * builds left behind — see [StaleUberJarPruner] for why that can't be a `doFirst`.
 */
fun Project.registerPackageUberJarForCurrentOs(
  mainClass: String,
  appJar: TaskProvider<out Jar>,
  runtimeClasspath: FileCollection,
  version: String,
): TaskProvider<Jar> {
  val runtimeJars = files(appJar, runtimeClasspath)
  val jarsDir = layout.buildDirectory.dir("compose/jars")

  val packageUberJar =
    tasks.register("packageUberJarForCurrentOS", Jar::class.java) { task ->
      task.description = "Packages the module and its runtime classpath into one runnable JAR"
      task.group = "distribution"
      task.archiveBaseName.set("Trailblaze")
      task.archiveAppendix.set(currentOsTargetId())
      task.archiveVersion.set(version)
      task.archiveClassifier.set("")
      task.destinationDirectory.set(jarsDir)
      task.manifest.attributes(mapOf("Main-Class" to mainClass))
      task.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
      task.isZip64 = true
      task.dependsOn(runtimeJars)
      task.from(files({ runtimeJars.filter { it.isArchive() }.map { zipTree(it) } }))
    }

  val pruneStaleUberJars =
    tasks.register("pruneStaleUberJars") { task ->
      task.description = "Deletes stale uber JARs so compose/jars only ever holds the current output"
      val currentJar = packageUberJar.flatMap { it.archiveFile }
      task.doLast {
        val dir = jarsDir.get().asFile
        val pruned = StaleUberJarPruner.prune(dir, currentJar.get().asFile.name)
        if (pruned > 0) {
          it.logger.lifecycle("Pruned $pruned stale uber JAR artifact(s) from ${dir.path}")
        }
      }
    }
  packageUberJar.configure { it.dependsOn(pruneStaleUberJars) }
  return packageUberJar
}

// By name, not by `isFile`: the app JAR does not exist yet when the file list is first resolved.
private fun java.io.File.isArchive(): Boolean = extension == "jar" || extension == "zip"

/**
 * The `<os>-<arch>` part of the JAR name, spelled the way the Compose plugin spelled it
 * (`macos-arm64`, `linux-x64`), so a JAR's name still says which host built it.
 */
fun currentOsTargetId(
  osName: String = System.getProperty("os.name"),
  osArch: String = System.getProperty("os.arch"),
): String {
  val os =
    when {
      osName.startsWith("Mac", ignoreCase = true) -> "macos"
      osName.startsWith("Linux", ignoreCase = true) -> "linux"
      osName.startsWith("Win", ignoreCase = true) -> "windows"
      else -> error("Unsupported OS for the uber JAR: $osName")
    }
  val arch =
    when (osArch) {
      "x86_64", "amd64" -> "x64"
      "aarch64", "arm64" -> "arm64"
      else -> error("Unsupported architecture for the uber JAR: $osArch")
    }
  return "$os-$arch"
}
