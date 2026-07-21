package xyz.block.trailblaze.trailrunner

import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * On-disk operations for **trail bundles**. A bundle is simply a library folder that contains a
 * `blaze.yaml` spec (config + prompts, no recordings) and, as recordings accumulate, sibling
 * `<platform>.trail.yaml` variants - the same folder-with-platform-sibling convention regular
 * trails already use. Bundles are created directly at their final home in the library; there is
 * no staging area.
 *
 * Ids are `"<rootIdx>/<relPath>"`, matching the trail id scheme ([resolveTrailFile]). All resolution
 * is canonical-path contained behind the trail roots, same as [TrailRoutes].
 */
internal object BundleStore {
  private const val BLAZE_FILE = "blaze.yaml"

  private fun roots(primary: File, extras: List<File>): List<File> = listOf(primary) + extras

  fun detail(dir: File, id: String, home: String, root: File): BundleDetailResponse {
    val blaze = File(dir, BLAZE_FILE)
    val base = TrailDetailBuilder.build(root, blaze)
    val config = runCatching { createTrailblazeYaml().extractTrailConfig(blaze.readText()) }.getOrNull()
    val objective = config?.metadata?.get("objective")
    val variants = variantFiles(dir).map { f ->
      val platform = runCatching {
        createTrailblazeYaml().extractTrailConfig(f.readText())?.platform
      }.getOrNull() ?: f.name.removeSuffix(".trail.yaml")
      BundleVariant(name = f.name, platform = platform)
    }
    return BundleDetailResponse(
      id = id,
      name = base.title.ifBlank { dir.name },
      objective = objective,
      target = config?.target,
      platform = config?.platform,
      context = config?.context,
      home = home,
      blazeYaml = base.yaml,
      steps = base.steps,
      variants = variants,
    )
  }

  /** Resolves a bundle folder from its id, validating containment + (when [requireBlaze]) presence
   *  of a `blaze.yaml`. The folder-editing endpoints pass `requireBlaze = false` to operate on a
   *  library folder before its `blaze.yaml` exists. */
  fun resolve(id: String, primary: File, extras: List<File>, requireBlaze: Boolean = true): ResolvedBundle? {
    val idx = id.substringBefore('/').toIntOrNull() ?: return null
    val rel = id.substringAfter('/', "")
    if (rel.isEmpty() || rel.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
    val root = roots(primary, extras).getOrNull(idx) ?: return null
    val dir = File(root, rel)
    val rootCanon = root.canonicalPath
    if (!dir.canonicalPath.startsWith("$rootCanon/")) return null
    if (requireBlaze && !File(dir, BLAZE_FILE).isFile) return null
    return ResolvedBundle(dir = dir, root = root, rootIdx = idx, home = rel)
  }

  /**
   * Creates a new trail bundle folder directly at [destination] (a relative path under the primary
   * workspace, e.g. `myapp/login-flow`) and writes its `blaze.yaml`. Each path segment is slugified
   * to a safe charset; a taken destination dedupes with `-2`, `-3`… suffixes on the last segment.
   * Returns the id (`"0/<relPath>"`).
   */
  fun createAt(primary: File, destination: String, yaml: String): String {
    val segments = destination.trim().trim('/').split('/')
      .map { it.lowercase().replace(Regex("[^a-z0-9._]+"), "-").trim('-') }
      .filter { it.isNotEmpty() && it != "." && it != ".." }
    require(segments.isNotEmpty()) { "invalid destination" }
    val rel = segments.joinToString("/")
    var dir = File(primary, rel)
    var n = 2
    while (dir.exists()) {
      dir = File(primary, "$rel-$n")
      n++
    }
    require(dir.canonicalPath.startsWith(primary.canonicalPath + "/")) { "destination escapes the trails workspace" }
    dir.mkdirs()
    File(dir, BLAZE_FILE).writeText(yaml)
    return "0/${dir.relativeTo(primary).invariantSeparatorsPath}"
  }

  /** Reads a single file inside the bundle folder (blaze.yaml or a `<platform>.trail.yaml`). The
   *  name is a plain filename kept inside [dir]; returns null when it's missing or would escape. */
  fun readFile(dir: File, name: String): String? {
    if (name.isEmpty() || name.contains('/') || name.contains('\\') || name.contains("..")) return null
    val file = File(dir, name)
    if (!file.canonicalPath.startsWith(dir.canonicalPath + "/") || !file.isFile) return null
    return file.readText()
  }

  /** Writes any single file inside the bundle folder (blaze.yaml or a `<platform>.trail.yaml`). The
   *  name is a plain filename kept inside [dir]; returns false if it would escape the folder. */
  fun writeFile(dir: File, name: String, content: String): Boolean {
    if (name.isEmpty() || name.contains('/') || name.contains('\\') || name.contains("..")) return false
    val file = File(dir, name)
    if (!file.canonicalPath.startsWith(dir.canonicalPath + "/")) return false
    file.writeText(content)
    return true
  }

  /** Deletes a single recorded variant inside the bundle folder. Refuses to delete `blaze.yaml` (the
   *  source) and anything that would escape the folder. */
  fun deleteFile(dir: File, name: String): Boolean {
    if (name.isEmpty() || name == BLAZE_FILE || name.contains('/') || name.contains('\\') || name.contains("..")) return false
    val file = File(dir, name)
    if (!file.canonicalPath.startsWith(dir.canonicalPath + "/")) return false
    return runCatching { file.delete() }.getOrDefault(false)
  }

  /** Recursively deletes the bundle folder. Returns false if the OS reports the delete didn't fully succeed. */
  fun delete(dir: File): Boolean = dir.deleteRecursively()

  /** The file-name slug a variant writes as: `<variantSlug(v)>.trail.yaml`. */
  fun variantSlug(variant: String): String =
    variant.lowercase().replace(Regex("[^a-z0-9_-]"), "-").trim('-').ifEmpty { "variant" }

  /** Writes a recorded variant produced from a finished run into the bundle folder. Writes to a temp
   *  sibling then atomically moves it into place, so a concurrent reader (the board reload, an inline
   *  edit) never observes a partially-written YAML and a re-record can't interleave a half file.
   *  Returns the written file, or null when the write failed (callers that must announce the save -
   *  the companion recording-saved event - gate on it; the run paths ignore it). */
  fun writeVariant(dir: File, variant: String, yaml: String): File? {
    val safe = variantSlug(variant)
    // Unique tmp name per write so two concurrent re-records of the same platform each own their own
    // scratch file (a shared `.<safe>.trail.yaml.tmp` would let them interleave bytes before the move).
    val tmp = File(dir, ".$safe.trail.yaml.${java.util.UUID.randomUUID()}.tmp")
    return runCatching {
      tmp.writeText(yaml)
      val target = File(dir, "$safe.trail.yaml")
      Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      target
    }.getOrElse {
      runCatching { tmp.delete() } // don't leak the scratch file if the move fails (e.g. cross-device workspace)
      Console.log("[BundleStore] failed to write variant $safe into ${dir.name}: ${it.message}")
      null
    }
  }

  private fun variantFiles(dir: File): List<File> =
    (dir.listFiles { f -> f.isFile && f.name.endsWith(".trail.yaml") } ?: emptyArray()).sortedBy { it.name }

  data class ResolvedBundle(
    val dir: File,
    val root: File,
    val rootIdx: Int,
    val home: String,
  )
}
