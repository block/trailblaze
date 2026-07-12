package xyz.block.trailblaze.trailrunner

import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * On-disk lifecycle for **draft blazes**. A draft is simply a folder that contains a `blaze.yaml`
 * spec (config + prompts, no recordings) and, as recordings accumulate, sibling
 * `<platform>.trail.yaml` variants — the same folder-with-platform-sibling convention regular
 * trails already use. A draft has a nullable "home": until promoted it lives under the workspace
 * `drafts/` staging dir; "Save to…" moves the whole folder to its final home.
 *
 * Ids are `"<rootIdx>/<relPath>"`, matching the trail id scheme ([resolveTrailFile]). All resolution
 * is canonical-path contained behind the trail roots, same as [TrailRoutes].
 */
internal object DraftStore {
  const val DRAFTS_DIR = "drafts"
  private const val BLAZE_FILE = "blaze.yaml"
  private const val MAX_SCAN_DEPTH = 6

  private fun roots(primary: File, extras: List<File>): List<File> = listOf(primary) + extras

  /**
   * Lists draft blazes — folders under the workspace `drafts/` staging dir ONLY. We deliberately do
   * NOT scan the whole workspace for `blaze.yaml`: the repo uses `blaze.yaml` as a per-folder prompt
   * skeleton across hundreds of existing trail folders, so scanning everything would flood this list
   * with the entire trail tree. Once a draft is promoted ("Save to…") it leaves `drafts/` and shows
   * up in the normal Trails list instead.
   */
  fun list(primary: File, extras: List<File>): List<DraftSummary> {
    val draftsRoot = File(primary, DRAFTS_DIR)
    if (!draftsRoot.isDirectory) return emptyList()
    return findBlazeFolders(draftsRoot).mapNotNull { dir ->
      val rel = dir.relativeTo(primary).invariantSeparatorsPath // "drafts/<slug>"
      if (rel.isEmpty()) return@mapNotNull null
      val variants = variantFiles(dir).map { it.name }
      DraftSummary(
        id = "0/$rel",
        name = readTitle(dir) ?: dir.name,
        home = rel,
        inDrafts = true,
        variants = variants,
        hasRecordings = variants.isNotEmpty(),
      )
    }.sortedBy { it.name.lowercase() }
  }

  fun detail(dir: File, id: String, home: String, inDrafts: Boolean, root: File): DraftDetailResponse {
    val blaze = File(dir, BLAZE_FILE)
    val base = TrailDetailBuilder.build(root, blaze)
    val config = runCatching { createTrailblazeYaml().extractTrailConfig(blaze.readText()) }.getOrNull()
    val objective = config?.metadata?.get("objective")
    val destination = config?.metadata?.get("destination")
    val variants = variantFiles(dir).map { f ->
      val platform = runCatching {
        createTrailblazeYaml().extractTrailConfig(f.readText())?.platform
      }.getOrNull()
        // A bare unified trail.yaml has no classifier in its name to fall back on — label it
        // "unified" rather than echoing the raw filename as a platform.
        ?: if (TrailRecordings.isUnifiedTrailFile(f.name)) "unified" else f.name.removeSuffix(".trail.yaml")
      DraftVariant(name = f.name, platform = platform)
    }
    return DraftDetailResponse(
      id = id,
      name = base.title.ifBlank { dir.name },
      objective = objective,
      target = config?.target,
      platform = config?.platform,
      destination = destination,
      context = config?.context,
      home = home,
      inDrafts = inDrafts,
      blazeYaml = base.yaml,
      steps = base.steps,
      variants = variants,
    )
  }

  /** Resolves a draft folder from its id, validating containment + (when [requireBlaze]) presence of
   *  a `blaze.yaml`. The folder-editing endpoints pass `requireBlaze = false` to operate on a
   *  library folder before its `blaze.yaml` exists. */
  fun resolve(id: String, primary: File, extras: List<File>, requireBlaze: Boolean = true): ResolvedDraft? {
    val idx = id.substringBefore('/').toIntOrNull() ?: return null
    val rel = id.substringAfter('/', "")
    if (rel.isEmpty() || rel.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
    val root = roots(primary, extras).getOrNull(idx) ?: return null
    val dir = File(root, rel)
    val rootCanon = root.canonicalPath
    if (!dir.canonicalPath.startsWith("$rootCanon/")) return null
    if (requireBlaze && !File(dir, BLAZE_FILE).isFile) return null
    val inDrafts = rel == DRAFTS_DIR || rel.startsWith("$DRAFTS_DIR/")
    return ResolvedDraft(dir = dir, root = root, rootIdx = idx, home = rel, inDrafts = inDrafts)
  }

  /** Creates a new draft under `<primary>/drafts/<slug>/` and writes its `blaze.yaml`. Returns the id. */
  fun create(primary: File, name: String, yaml: String): String {
    val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "blaze" }.take(60)
    var dir = File(primary, "$DRAFTS_DIR/$slug")
    var n = 2
    while (dir.exists()) {
      dir = File(primary, "$DRAFTS_DIR/$slug-$n")
      n++
    }
    require(dir.canonicalPath.startsWith(primary.canonicalPath + "/")) { "path escapes the trails workspace" }
    dir.mkdirs()
    File(dir, BLAZE_FILE).writeText(yaml)
    return "0/${dir.relativeTo(primary).invariantSeparatorsPath}"
  }

  fun updateBlaze(dir: File, yaml: String) {
    File(dir, BLAZE_FILE).writeText(yaml)
  }

  /** Writes any single file inside the draft folder (blaze.yaml or a `<platform>.trail.yaml`). The
   *  name is a plain filename kept inside [dir]; returns false if it would escape the folder. */
  fun writeFile(dir: File, name: String, content: String): Boolean {
    if (name.isEmpty() || name.contains('/') || name.contains('\\') || name.contains("..")) return false
    val file = File(dir, name)
    if (!file.canonicalPath.startsWith(dir.canonicalPath + "/")) return false
    file.writeText(content)
    return true
  }

  /** Deletes a single recorded variant inside the draft folder. Refuses to delete `blaze.yaml` (the
   *  source) and anything that would escape the folder. */
  fun deleteFile(dir: File, name: String): Boolean {
    if (name.isEmpty() || name == BLAZE_FILE || name.contains('/') || name.contains('\\') || name.contains("..")) return false
    val file = File(dir, name)
    if (!file.canonicalPath.startsWith(dir.canonicalPath + "/")) return false
    return runCatching { file.delete() }.getOrDefault(false)
  }

  /**
   * Commits the draft: the `destination` IS the trail folder's final path under the workspace
   * (e.g. `myapp/login` → `<primary>/myapp/login/`), matching the trails convention where a trail
   * folder holds `blaze.yaml` + the `<platform>.trail.yaml` siblings. The draft folder becomes that
   * folder (its `drafts/<slug>` name is dropped). Returns the new id.
   */
  fun saveTo(src: File, primary: File, destination: String): String {
    val destRel = destination.trim().trim('/')
    require(destRel.isNotEmpty() && destRel.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
      "invalid destination"
    }
    val target = File(primary, destRel)
    val rootCanon = primary.canonicalPath
    require(target.canonicalPath.startsWith("$rootCanon/")) { "destination escapes the trails workspace" }
    require(!target.exists()) { "$destRel already exists" }
    target.parentFile?.mkdirs()
    // Prefer an atomic rename, but some filesystems (overlay FS, network mounts) reject ATOMIC_MOVE
    // even for same-volume renames — fall back to a plain move so committing a draft still works.
    try {
      Files.move(src.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
      Files.move(src.toPath(), target.toPath())
    }
    return "0/${target.relativeTo(primary).invariantSeparatorsPath}"
  }

  /** Recursively deletes the draft folder. Returns false if the OS reports the delete didn't fully succeed. */
  fun delete(dir: File): Boolean = dir.deleteRecursively()

  /** Writes a recorded variant produced from a finished run into the draft folder. Writes to a temp
   *  sibling then atomically moves it into place, so a concurrent reader (the board reload, an inline
   *  edit) never observes a partially-written YAML and a re-record can't interleave a half file. */
  fun writeVariant(dir: File, variant: String, yaml: String) {
    val safe = variant.lowercase().replace(Regex("[^a-z0-9_-]"), "-").trim('-').ifEmpty { "variant" }
    // Unique tmp name per write so two concurrent re-records of the same platform each own their own
    // scratch file (a shared `.<safe>.trail.yaml.tmp` would let them interleave bytes before the move).
    val tmp = File(dir, ".$safe.trail.yaml.${java.util.UUID.randomUUID()}.tmp")
    runCatching {
      tmp.writeText(yaml)
      Files.move(tmp.toPath(), File(dir, "$safe.trail.yaml").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }.onFailure {
      runCatching { tmp.delete() } // don't leak the scratch file if the move fails (e.g. cross-device workspace)
      Console.log("[BlazeRoutes] failed to write variant $safe into ${dir.name}: ${it.message}")
    }
  }

  // Recorded variants are per-platform recordings (`<classifier>.trail.yaml`) or the bare unified
  // `trail.yaml` (which a `.trail.yaml` suffix check can't see). NOT isTrailFile: that would also
  // match the draft's own blaze.yaml and every draft would read as having recordings.
  private fun variantFiles(dir: File): List<File> =
    (
      dir.listFiles { f ->
        f.isFile && (TrailRecordings.isRecordingFile(f.name) || TrailRecordings.isUnifiedTrailFile(f.name))
      } ?: emptyArray()
      ).sortedBy { it.name }

  private fun readTitle(dir: File): String? = runCatching {
    createTrailblazeYaml().extractTrailConfig(File(dir, BLAZE_FILE).readText())?.title?.takeIf { it.isNotBlank() }
  }.getOrNull()

  private fun findBlazeFolders(root: File): List<File> {
    val out = mutableListOf<File>()
    val stack = ArrayDeque<Pair<File, Int>>()
    stack.add(root to 0)
    while (stack.isNotEmpty()) {
      val (cur, depth) = stack.removeLast()
      if (File(cur, BLAZE_FILE).isFile) out += cur
      if (depth < MAX_SCAN_DEPTH) {
        cur.listFiles()?.forEach { c -> if (c.isDirectory && !c.name.startsWith(".")) stack.add(c to depth + 1) }
      }
    }
    return out
  }

  data class ResolvedDraft(
    val dir: File,
    val root: File,
    val rootIdx: Int,
    val home: String,
    val inDrafts: Boolean,
  )
}
