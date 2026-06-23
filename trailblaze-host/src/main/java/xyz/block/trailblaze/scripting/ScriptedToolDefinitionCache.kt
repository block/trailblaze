package xyz.block.trailblaze.scripting

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.util.Console
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * File-hash-based cache for [ScriptedToolDefinitionAnalyzer] outputs.
 *
 * The analyzer's Node subprocess + `ts-json-schema-generator` walk is ~100ms per file
 * cold; with many trailmaps in a workspace, steady-state `trailblaze check` and daemon
 * starts re-do that work for every trailmap on every invocation even when nothing has
 * changed. This cache short-circuits the subprocess when the trailmap's `.ts` files AND
 * the analyzer's own dependencies (SDK `.d.ts` rollup, shim, `ts-json-schema-generator`
 * version) are byte-identical to a prior run.
 *
 * **Storage layout.** One file per (trailmap, content+deps) combo under
 * `<cacheRoot>/<trailmap-tools-dir-hash>/<content-hash>.json`. The trailmap subdirectory keeps
 * inspection + targeted `rm -rf` ergonomic (you can wipe one trailmap's cache without
 * touching the rest); the content hash filename means a content change writes a new
 * file rather than overwriting (old cache entries pile up but each is ~kB, so we
 * don't try to evict in v1).
 *
 * **Opportunistic, never load-bearing.** A read failure (corrupt JSON, IO error,
 * permission denied) logs under `[ScriptedToolDefinitionAnalyzer]` and returns null
 * so the analyzer re-runs the subprocess. A write failure logs and continues — the
 * caller already has the freshly-computed result. The cache is a perf optimization,
 * not a correctness boundary.
 *
 * **Cross-trailmap type-import limitation (v1).** The content hash covers only files
 * under [trailmapToolsDir]. If trailmap A does `import type { SharedFoo } from "@trailmaps/trailmapB"`
 * and uses `SharedFoo` in a typed-tool interface, trailmap A's emitted schema depends on
 * trailmap B's source — but trailmap B's changes don't invalidate trailmap A's cache here. The
 * staleness window is "until the author touches a file in trailmap A, or runs with
 * cache disabled." This pattern is rare today (composition usually happens via
 * `ctx.tools.*` runtime calls, not type imports). A future v2 could have the
 * subprocess report which absolute file paths contributed to each tool's type
 * closure and key on the closure's union hash instead; deferred until the gap
 * actually bites.
 *
 * **Why workspace-local, not user-home.** Different checkouts of the same workspace
 * on one machine may have different SDK versions, different bundled shim versions,
 * or different trailmap contents. Keeping the cache under `<workspace>/.trailblaze/`
 * ensures each checkout's cache reflects its own source tree.
 *
 * The cache directory is safely deletable: `rm -rf <workspace>/.trailblaze/cache/`
 * restores "first run" behavior on the next analyzer invocation. The
 * `TRAILBLAZE_TOOL_ANALYZER_NO_CACHE=1` env var (read at JVM start, like the other
 * `TRAILBLAZE_TOOL_ANALYZER_*` knobs on this analyzer) bypasses cache lookup
 * entirely without removing files on disk — useful for debugging suspected
 * cache-hit-but-stale scenarios without rebuilding the cache.
 *
 * **Long-lived workspace cleanup.** v1 doesn't evict superseded entries (every
 * content change writes a NEW file, the old one stays). For a workspace that's
 * been alive across many SDK bumps and tool edits, the cache may grow to
 * thousands of small `.json` files. To prune older entries without recomputing
 * everything, this is safe and incremental:
 *
 *     find <workspace>/.trailblaze/cache/analyzer/ -name '*.json' -mtime +30 -delete
 *
 * Anything entries an analyzer would have hit will be re-written on the next
 * cache miss; nothing on disk is load-bearing.
 */
internal class ScriptedToolDefinitionCache(
  private val cacheRoot: File,
  /**
   * Hex digest of the analyzer's ambient dependencies (SDK `.d.ts` rollup, extractor
   * shim, `ts-json-schema-generator` version pin). Computed once via
   * [computeDependencyKey] and threaded through [computeContentKey] so a dep change
   * invalidates every cache entry. Exposed read-only so [ScriptedToolDefinitionAnalyzer]
   * can pass it to [computeContentKey] without recomputing.
   */
  val dependencyKey: String,
) {

  /**
   * Look up a cache entry for [trailmapToolsDir] keyed on the content hash derived from
   * its `.ts` files (computed by [computeContentKey]). Returns `null` on miss, on
   * unreadable file, on parse failure, OR when the entry's format version doesn't
   * match this codebase's expected format. The caller treats every `null` as
   * "subprocess required."
   */
  fun lookup(trailmapToolsDir: File, contentKey: String): List<ScriptedToolDefinition>? {
    val file = cacheFileFor(trailmapToolsDir, contentKey)
    if (!file.isFile) return null
    return try {
      val text = file.readText()
      val entry = JSON.decodeFromString(CacheEntry.serializer(), text)
      if (entry.version != CACHE_FORMAT_VERSION) {
        Console.log(
          "[ScriptedToolDefinitionAnalyzer] cache file ${file.absolutePath} has format version " +
            "${entry.version} (expected $CACHE_FORMAT_VERSION); ignoring and re-running analyzer.",
        )
        return null
      }
      entry.tools.map { it.toDefinition() }
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
      // Bubble cancellation up — analyze() runs inside a coroutine and a
      // cancellation propagating through cache lookup must not be swallowed.
      // (Copilot review)
      throw e
    } catch (e: VirtualMachineError) {
      // Stack overflow / OOM are environment-fatal and not "cache is corrupt"
      // situations. Don't bury them.
      throw e
    } catch (e: Throwable) {
      // Console.error (not Console.log) so the CI quiet-mode path still surfaces
      // it — a corrupt cache file usually points at a real environment issue
      // (disk failure, concurrent edit, deliberate tamper) that an operator
      // should be able to find in CI logs without re-running with --verbose.
      Console.error(
        "[ScriptedToolDefinitionAnalyzer] cache file ${file.absolutePath} is unreadable " +
          "(${e::class.simpleName}: ${e.message ?: "(no message)"}); re-running analyzer.",
      )
      null
    }
  }

  /**
   * Atomically write [defs] to the cache file for ([trailmapToolsDir], [contentKey]).
   * Writes go to a sibling `.tmp` file first and are then renamed onto the target so a
   * concurrent reader never observes a partially-written file (and a crash between
   * `writeText` and `rename` leaves a stray `.tmp` rather than a corrupt cache entry).
   *
   * Returns silently on any I/O failure; the caller already has the freshly-computed
   * result so a write miss only means the next run re-spawns the subprocess.
   */
  fun put(trailmapToolsDir: File, contentKey: String, defs: List<ScriptedToolDefinition>) {
    val file = cacheFileFor(trailmapToolsDir, contentKey)
    val parent = file.parentFile
    var tmp: File? = null
    try {
      parent.mkdirs()
      val entry = CacheEntry(
        version = CACHE_FORMAT_VERSION,
        tools = defs.map { CachedTool.fromDefinition(it) },
      )
      // Unique tmp filename via `Files.createTempFile` so two concurrent writers
      // (parallel daemon starts, multiple `trailblaze check` invocations sharing
      // a workspace) can't race on the same `.tmp` path. With a fixed name, the
      // second writer's `Files.move` could observe `NoSuchFileException` once
      // the first writer renamed the tmp out from under it — caught by the outer
      // catch but logged as a spurious warning every time. (Automated review
      // feedback.) The tmp lives in the same parent directory so the subsequent
      // `Files.move` is a same-filesystem rename (atomic on POSIX), not a
      // cross-device copy.
      tmp = Files.createTempFile(parent.toPath(), "${file.name}.", ".tmp").toFile()
      tmp.writeText(JSON.encodeToString(CacheEntry.serializer(), entry))
      try {
        try {
          Files.move(
            tmp.toPath(),
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
          )
        } catch (_: AtomicMoveNotSupportedException) {
          // Some filesystems (e.g. cross-device tmpfs in CI) don't support atomic
          // moves. Fall back to a best-effort replace — still safer than writing
          // directly to the target file, since a concurrent reader catches at most
          // a brief window of "old or new" rather than a half-written byte stream.
          Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        tmp = null // ownership transferred to the target file via the rename.
      } catch (moveError: kotlin.coroutines.cancellation.CancellationException) {
        // Don't swallow cancellation. The finally clean-up still runs.
        throw moveError
      } catch (moveError: VirtualMachineError) {
        throw moveError
      } catch (moveError: Throwable) {
        // Both move paths failed — log + fall through to the finally so the
        // orphan .tmp is cleaned up.
        Console.error(
          "[ScriptedToolDefinitionAnalyzer] failed to move cache tmp to ${file.absolutePath} " +
            "(${moveError::class.simpleName}: ${moveError.message ?: "(no message)"}); continuing without caching.",
        )
      }
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
      throw e
    } catch (e: VirtualMachineError) {
      throw e
    } catch (e: Throwable) {
      // Console.error (not Console.log) so CI surfaces persistent write failures
      // — a read-only cache dir or full disk would otherwise silently drop every
      // cache entry while quiet-mode CI shows no breadcrumbs.
      Console.error(
        "[ScriptedToolDefinitionAnalyzer] failed to write cache file ${file.absolutePath} " +
          "(${e::class.simpleName}: ${e.message ?: "(no message)"}); continuing without caching.",
      )
    } finally {
      // If we never transferred ownership of the tmp (move failed, or earlier
      // write step threw before reaching the move), best-effort delete so the
      // cache dir doesn't accumulate stray `.tmp` files.
      tmp?.let { runCatching { it.delete() } }
    }
  }

  private fun cacheFileFor(trailmapToolsDir: File, contentKey: String): File {
    val trailmapBucket = trailmapBucketFor(trailmapToolsDir)
    return File(File(cacheRoot, trailmapBucket), "$contentKey.json")
  }

  /**
   * Per-trailmap subdirectory name derived from the trailmap tools dir's absolute path. The
   * short hex prefix avoids cross-trailmap collisions when two trailmaps share a `tools/`
   * basename, and the appended basename keeps `ls` output human-scannable.
   */
  private fun trailmapBucketFor(trailmapToolsDir: File): String {
    val pathHash = sha256Hex(trailmapToolsDir.absolutePath.toByteArray(Charsets.UTF_8)).take(16)
    // Strip non-alphanumeric chars from the basename so the directory name is safe
    // across filesystems (e.g. avoid quotes/colons that some CI sandboxes reject).
    val cleanedName = trailmapToolsDir.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
    return "$pathHash-$cleanedName"
  }

  companion object {
    /**
     * Bump when the on-disk cache file structure changes in a way that older readers
     * can't parse. New analyzer runs will then ignore older entries and overwrite
     * them on the next miss. Doesn't need to change for routine extractor or
     * generator config changes — those flow through [dependencyKey] which is already
     * part of the cache filename, so a content/dep change writes a new file rather
     * than colliding with an existing one of incompatible shape.
     */
    private const val CACHE_FORMAT_VERSION: Int = 1

    private val JSON = Json {
      ignoreUnknownKeys = true
      encodeDefaults = false
    }

    /**
     * Compose a per-trailmap content hash over [tsFiles] (the sorted list the analyzer
     * would feed the subprocess) and the analyzer's ambient [dependencyKey]. The
     * resulting hex string is stable across runs as long as every input is
     * byte-identical, and it's safe to use as a filename (lowercase hex only).
     *
     * The hash mixes in the relative path under [trailmapToolsDir] for each file (not
     * the absolute path) so moving a workspace to a different parent directory
     * doesn't invalidate the cache — the absolute path is already captured in the
     * trailmap-bucket directory name.
     */
    fun computeContentKey(
      trailmapToolsDir: File,
      tsFiles: List<File>,
      dependencyKey: String,
    ): String {
      val md = MessageDigest.getInstance("SHA-256")
      md.update("ANALYZER_CACHE_V$CACHE_FORMAT_VERSION\n".toByteArray(Charsets.UTF_8))
      md.update("DEPS=$dependencyKey\n".toByteArray(Charsets.UTF_8))
      // Relative-path computation: relativize against trailmapToolsDir's absolute path so
      // it's deterministic even if `tsFiles` carries paths in a non-canonical form.
      val trailmapBase = trailmapToolsDir.absoluteFile.toPath()
      for (file in tsFiles) {
        val relative = runCatching {
          trailmapBase.relativize(file.absoluteFile.toPath()).toString().replace('\\', '/')
        }.getOrElse { file.absolutePath.replace('\\', '/') }
        md.update("F:$relative\n".toByteArray(Charsets.UTF_8))
        val read = runCatching { file.readBytes() }
        if (read.isSuccess) {
          val bytes = read.getOrThrow()
          md.update("OK:L:${bytes.size}\n".toByteArray(Charsets.UTF_8))
          md.update(bytes)
        } else {
          // Permission-denied / file-deleted-mid-walk / I/O error. Mix an explicit
          // sentinel into the digest so an unreadable file does NOT collide with
          // a legitimately empty file (which would happen if we fell back to
          // `ByteArray(0)` — Copilot review). An unreadable file thus produces a
          // different cache key than the same path with empty content; the next
          // run after the file becomes readable produces yet another key, forcing
          // a fresh subprocess walk rather than serving a 0-byte-stand-in hit.
          val e = read.exceptionOrNull()
          md.update("ERR:${e?.javaClass?.simpleName ?: "Unknown"}\n".toByteArray(Charsets.UTF_8))
          Console.error(
            "[ScriptedToolDefinitionAnalyzer] failed to read ${file.absolutePath} while " +
              "computing cache content key (${e?.javaClass?.simpleName}: ${e?.message ?: "(no message)"}); " +
              "mixed in unreadable-file sentinel — cache will recompute once the file is readable.",
          )
        }
        md.update(byteArrayOf(0x0A))
      }
      return md.digest().toHex()
    }

    /**
     * Compose a stable hex hash over the analyzer's ambient dependencies — these are
     * the things that, if they change, could change the analyzer's emitted schemas
     * even when the trailmap's own `.ts` files don't:
     *
     *  - The SDK `.d.ts` rollup at `<sdkDir>/dist/index.d.ts`. Every trailmap effectively
     *    imports `@trailblaze/scripting` through it; a change to the rollup (e.g. a
     *    new field on `TrailblazeToolMap` or a renamed exported type) can change the
     *    schema closure of a typed tool whose `.ts` is unchanged.
     *  - The extractor shim itself at `<sdkDir>/tools/extract-tool-defs.mjs`. The
     *    shim's behavior — its AST walk, its `generatorConfigForAllTypes` shape, its
     *    `RECOGNIZED_SPEC_FIELDS` allowlist — fully determines the envelope shape; a
     *    change to ANY of these changes the output for the same input.
     *  - The `ts-json-schema-generator` version pin under
     *    `<sdkDir>/node_modules/ts-json-schema-generator/package.json` (when
     *    installed). Generator releases can subtly change emitted schemas (e.g.
     *    `additionalProperties` defaults, `$ref` heuristics, format inference).
     *  - The `typescript` compiler version under
     *    `<sdkDir>/node_modules/typescript/package.json` (when installed). The shim
     *    drives the TypeScript compiler API to resolve type references; a `typescript`
     *    bump can subtly change extracted types even when `ts-json-schema-generator`
     *    stays the same. (Codex review.)
     *  - The committed `<sdkDir>/bun.lock`. The two package.json pins above capture only
     *    the generator's and compiler's OWN versions; the lockfile resolves the FULL
     *    dependency tree, so a transitive dep of `ts-json-schema-generator` / `typescript`
     *    being refreshed (without bumping either's own version) still flips the key. This
     *    is the same input the bundled-config Gradle tasks track to re-run the analyzer on
     *    a lockfile change — mixing it here means the analyzer's OWN workspace cache can't
     *    then serve a stale entry and defeat that re-run. Absent in installed-CLI layouts
     *    that ship `node_modules` without the lockfile → stable `<absent>` sentinel, same
     *    as the package.json fallbacks. (Codex review on #3975.)
     *  - The `TRAILBLAZE_SDK_PACKAGE` env override. The shim recognizes
     *    `trailblaze.tool<...>(...)` calls only when the SDK is imported under the
     *    expected package name; flipping this env var changes which call sites count
     *    as recognized tools. Including it in the dep key invalidates cached results
     *    produced under the previous package name. (Codex review.)
     *
     * Each input that doesn't exist on disk contributes a sentinel hash so missing
     * files participate stably in the digest (a file appearing in a later run will
     * still flip the hash). Returns a 64-char lowercase hex string.
     */
    fun computeDependencyKey(sdkDir: File, extractorShim: File): String {
      val md = MessageDigest.getInstance("SHA-256")
      md.update("ANALYZER_DEP_V$CACHE_FORMAT_VERSION\n".toByteArray(Charsets.UTF_8))
      mixFile(md, "shim", extractorShim)
      mixFile(md, "dts", File(sdkDir, "dist/index.d.ts"))
      // ts-json-schema-generator's package.json carries the resolved version pin
      // after `npm install` / `bun install`. If the install hasn't been run yet
      // (fresh checkout), fall back to the SDK's own package.json, which carries
      // the *declared* version pin — still changes when an author bumps the dep.
      val tsjsgPackageJson = File(sdkDir, "node_modules/ts-json-schema-generator/package.json")
      if (tsjsgPackageJson.isFile) {
        mixFile(md, "tsjsg", tsjsgPackageJson)
      } else {
        mixFile(md, "tsjsg-fallback-sdk-pkg", File(sdkDir, "package.json"))
      }
      // TypeScript compiler version pin — same fallback shape as tsjsg.
      val tscPackageJson = File(sdkDir, "node_modules/typescript/package.json")
      if (tscPackageJson.isFile) {
        mixFile(md, "tsc", tscPackageJson)
      } else {
        mixFile(md, "tsc-fallback-sdk-pkg", File(sdkDir, "package.json"))
      }
      // Full resolved dependency tree via the committed lockfile — catches transitive-dep
      // refreshes the two package.json pins above miss. Absent → `<absent>` sentinel (mixFile).
      mixFile(md, "bunlock", File(sdkDir, "bun.lock"))
      // TRAILBLAZE_SDK_PACKAGE env override — null/empty when unset, in which case
      // we still mix in a stable sentinel so the digest captures "unset" as a
      // distinct state from "set to value X". Trim before digesting so a stray
      // leading/trailing space in a CI YAML (e.g. `TRAILBLAZE_SDK_PACKAGE: " foo"`)
      // doesn't produce a different cache key from the same package name authored
      // without whitespace — package names don't legitimately carry surrounding
      // whitespace, so normalizing here is unambiguous.
      val sdkPackageOverride = System.getenv("TRAILBLAZE_SDK_PACKAGE")?.trim()?.takeIf { it.isNotBlank() }
      md.update("SDK_PACKAGE:${sdkPackageOverride ?: "<unset>"}\n".toByteArray(Charsets.UTF_8))
      return md.digest().toHex()
    }

    /**
     * Resolve the workspace-local cache root for an analyzer run. Walks ancestors
     * of [searchFrom] looking for the marker that designates a Trailblaze workspace
     * — a `.trailblaze/` directory at the root (written by `trailblaze check` /
     * compile bootstrap on first run). When no marker is found, falls back to
     * `<searchFrom>/.trailblaze/cache/analyzer/` so a fresh workspace still gets a
     * deterministic location on first run.
     *
     * Returns `null` only when the path is unsuitable for a cache (e.g.
     * [searchFrom] is null). Callers that want to disable caching entirely should
     * pass `cacheDir = null` to the analyzer constructor, not check this returning
     * null.
     */
    fun resolveDefaultCacheDir(searchFrom: File? = File(System.getProperty("user.dir") ?: ".")): File? {
      if (searchFrom == null) return null
      var current: File? = searchFrom.absoluteFile
      while (current != null) {
        val marker = File(current, ".trailblaze")
        if (marker.isDirectory) return File(marker, "cache/analyzer")
        current = current.parentFile
      }
      return File(File(searchFrom.absoluteFile, ".trailblaze"), "cache/analyzer")
    }

    /**
     * Read the `TRAILBLAZE_TOOL_ANALYZER_NO_CACHE` env var once. Name follows the
     * `TRAILBLAZE_TOOL_ANALYZER_<flag>` shape established by
     * `TRAILBLAZE_TOOL_ANALYZER_TIMEOUT_SECONDS` so all analyzer knobs live under
     * the same prefix and `env | grep TRAILBLAZE_TOOL_ANALYZER` surfaces every
     * tunable in one place. Env reads happen at JVM start (same as the other
     * analyzer env vars), so toggling the var after the daemon is running
     * requires a restart.
     */
    val noCacheFromEnv: Boolean = System.getenv("TRAILBLAZE_TOOL_ANALYZER_NO_CACHE")
      ?.let { it == "1" || it.equals("true", ignoreCase = true) }
      ?: false

    private fun mixFile(md: MessageDigest, label: String, file: File) {
      md.update("$label:".toByteArray(Charsets.UTF_8))
      if (!file.isFile) {
        md.update("<absent>\n".toByteArray(Charsets.UTF_8))
        return
      }
      val read = runCatching { file.readBytes() }
      if (read.isSuccess) {
        val bytes = read.getOrThrow()
        md.update("OK:${bytes.size}:".toByteArray(Charsets.UTF_8))
        md.update(bytes)
      } else {
        // Distinct sentinel for read failure so an unreadable dependency file does
        // NOT collide with a legitimately empty one (Copilot review) — every cache
        // entry produced while a dep was unreadable will invalidate once the file
        // becomes readable, rather than silently reusing a 0-byte stand-in hash.
        val e = read.exceptionOrNull()
        md.update("ERR:${e?.javaClass?.simpleName ?: "Unknown"}:".toByteArray(Charsets.UTF_8))
      }
      md.update(byteArrayOf(0x0A))
    }

    private fun ByteArray.toHex(): String {
      val out = StringBuilder(size * 2)
      for (b in this) {
        val v = b.toInt() and 0xFF
        out.append(HEX_CHARS[v ushr 4])
        out.append(HEX_CHARS[v and 0x0F])
      }
      return out.toString()
    }

    private fun sha256Hex(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private val HEX_CHARS = "0123456789abcdef".toCharArray()
  }

  @Serializable
  private data class CacheEntry(
    val version: Int,
    val tools: List<CachedTool> = emptyList(),
  )

  @Serializable
  private data class CachedTool(
    val name: String,
    @SerialName("sourcePath") val sourcePath: String,
    val line: Int,
    val description: String? = null,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject,
    val spec: JsonObject? = null,
  ) {
    fun toDefinition(): ScriptedToolDefinition = ScriptedToolDefinition(
      name = name,
      sourcePath = sourcePath,
      line = line,
      description = description,
      inputSchema = inputSchema,
      outputSchema = outputSchema,
      spec = spec,
    )

    companion object {
      fun fromDefinition(def: ScriptedToolDefinition): CachedTool = CachedTool(
        name = def.name,
        sourcePath = def.sourcePath,
        line = def.line,
        description = def.description,
        inputSchema = def.inputSchemaObject,
        outputSchema = def.outputSchemaObject,
        spec = def.spec,
      )
    }
  }
}
