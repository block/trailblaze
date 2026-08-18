import java.io.File
import java.io.IOException

/**
 * Deletes stale packaged uber-JAR artifacts from a jar output directory, leaving only the current
 * build's output.
 *
 * Compose's `packageUberJarForCurrentOS` names its output after the working tree's state (e.g. the
 * commit timestamp), so successive builds accumulate siblings in the same directory, and consumers
 * that pick "the newest JAR" by mtime (`dev_find_jar` in `scripts/dev-jar-cache.sh`) can
 * hand a stale build's bytes to whatever asked for the current one. Wiring this as the action of a
 * task with no declared outputs (never UP-TO-DATE, so it runs on every invocation) closes the gap a
 * `doFirst` wipe on the package task leaves open: `doFirst` runs only when the task executes, and
 * the dangerous state - stale JARs beside an UP-TO-DATE current one - is exactly when Gradle skips
 * it.
 *
 * Everything that would silently defeat that guarantee throws instead of returning: a directory
 * that cannot be listed, and a stale artifact whose delete does not take ([File.delete] reports
 * failure by returning `false`, not throwing). An artifact that is already gone is the
 * postcondition, not a failure; a missing directory means there is nothing to prune.
 */
object StaleUberJarPruner {
  /**
   * Deletes every `*.jar` / `*.jsa` in [dir] except [keepJar] and its CDS `.jsa` sibling (the
   * pairing `dev_prune_stale_siblings` maintains). Non-JAR files - e.g. the dev launcher's
   * `.blaze-source-hash` staleness marker, which `dev_ensure_jar` reads to skip rebuilds - are
   * never touched. Returns how many artifacts were deleted.
   *
   * @throws IOException when [dir] exists but cannot be listed, or a stale artifact survives its
   *   delete.
   */
  fun prune(dir: File, keepJar: String): Int {
    if (!dir.exists()) return 0
    val entries =
      dir.listFiles()
        ?: throw IOException(
          "Could not list $dir while pruning stale uber JARs (not a directory, or an I/O error);" +
            " a stale JAR could survive and shadow the current build."
        )
    val keepJsa = keepJar.removeSuffix(".jar") + ".jsa"
    val stale =
      entries.filter {
        (it.name.endsWith(".jar") || it.name.endsWith(".jsa")) &&
          it.name != keepJar &&
          it.name != keepJsa
      }
    val undeleted = stale.filter { !it.delete() && it.exists() }
    if (undeleted.isNotEmpty()) {
      throw IOException(
        "Could not delete stale uber JAR artifact(s) in $dir: " +
          undeleted.joinToString { it.name }
      )
    }
    return stale.size
  }
}
