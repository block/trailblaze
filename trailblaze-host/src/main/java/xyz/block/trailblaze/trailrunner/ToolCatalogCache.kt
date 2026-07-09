package xyz.block.trailblaze.trailrunner

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.block.trailblaze.llm.config.WorkspaceConfigDirHolder
import xyz.block.trailblaze.util.Console
import java.io.File

/**
 * Process-wide cache of [ToolCatalogBuilder.build]'s result, used by the Trail Runner LSP schema routes.
 *
 * Building the catalog is expensive — a recursive scan that reads every `.tool.yaml` / `.ts` under the
 * workspace's trailmaps PLUS a bun-subprocess scripted-tool analysis per trailmap — and yaml-language-server
 * fetches the trail / tool schema on EVERY editor open (every Steps↔Edit toggle, every trail switch).
 * Rebuilding from scratch each time was the dominant server-side cost of opening the Edit tab (the
 * `ToolCatalogBuilder` call site even carried a "if it ever shows up, cache per-trailmap" note).
 *
 * The cache rebuilds only when the workspace's tool sources actually change, detected via a cheap
 * fingerprint — the sorted (relative path, mtime, size) of every `.tool.yaml` / `.ts` under the trailmaps
 * dir, purely stat-based (no file reads, no parsing, no subprocess). An unchanged fingerprint returns the
 * cached catalog instantly; a changed one triggers exactly one rebuild. Concurrent callers (the tool-schema
 * and trail-schema routes both fetch on a single open) are serialized so a burst does at most one build.
 */
internal object ToolCatalogCache {
  private val core = ToolCatalogCacheCore(
    fingerprint = {
      runCatching { workspaceToolFingerprint() }.getOrElse {
        // A fingerprint failure must never be fatal — fall back to always-rebuild (the pre-cache behavior).
        Console.log("[ToolCatalogCache] fingerprint failed; rebuilding: ${it.message}")
        null
      }
    },
    build = { ToolCatalogBuilder.build() },
  )

  /** Return the tool catalog, rebuilding only if the workspace's tool sources changed since the last build. */
  suspend fun get(): List<ToolCatalogEntry> = core.get()

  // Directories to skip when fingerprinting — build outputs / VCS / deps that never hold authored tools.
  private val SKIP = setOf("build", "node_modules", ".gradle", ".git", ".idea", "dist")
  private const val MAX_DEPTH = 25
  private const val TRAILMAPS_SUBDIR = "trailmaps"

  /**
   * A cheap change-detection signature over the workspace's tool sources: the sorted set of
   * (relative path, last-modified, size) for every `.tool.yaml` / `.ts` under `<workspace>/trailmaps`.
   * Purely `stat`-based (no content reads), so it's orders of magnitude cheaper than [ToolCatalogBuilder.build]
   * yet flips whenever an authored tool is added, edited, or removed — the only inputs that can change while
   * the daemon runs (classpath-bundled tools are immutable within a session). An unresolvable workspace
   * yields a constant signature, so a classpath-only setup caches stably (correctly).
   */
  private fun workspaceToolFingerprint(): String {
    val root = WorkspaceConfigDirHolder.resolver()
      ?.let { File(it, TRAILMAPS_SUBDIR) }
      ?.takeIf { it.isDirectory }
      ?: return "no-workspace"
    val rootPath = root.path
    val parts = ArrayList<String>()
    val stack = ArrayDeque<Pair<File, Int>>().apply { add(root to 0) }
    while (stack.isNotEmpty()) {
      val (dir, depth) = stack.removeLast()
      if (depth > MAX_DEPTH) continue
      val entries = dir.listFiles() ?: continue
      for (e in entries) {
        if (e.isDirectory) {
          if (e.name !in SKIP && !e.name.startsWith(".")) stack.add(e to depth + 1)
          continue
        }
        // `.tool.yaml` + `.ts` are the only inputs to [ToolCatalogBuilder.build]. Exclude `.test.ts`:
        // test files are never imported by a tool, so their content can't change the extracted catalog.
        // We deliberately do NOT drop the other files the builder skips as catalog ENTRIES (`_*.ts`,
        // `*_shared.ts`, `tools.ts`, `.d.ts`) — a tool can IMPORT those, so editing one can change an
        // analyzer-extracted param schema. Over-including only costs an occasional extra rebuild;
        // under-including would serve a STALE schema, which the cache must never do.
        val name = e.name
        if (name.endsWith(".tool.yaml") || (name.endsWith(".ts") && !name.endsWith(".test.ts"))) {
          parts.add("${e.path.removePrefix(rootPath)}|${e.lastModified()}|${e.length()}")
        }
      }
    }
    parts.sort()
    return parts.joinToString("\n")
  }
}

/**
 * Pure caching core of [ToolCatalogCache], with its two side effects — the change-detection [fingerprint]
 * and the expensive [build] — injected so the "rebuild iff the inputs changed" contract is unit-testable
 * without a real workspace on disk. A null fingerprint means "couldn't detect changes": treat every call as
 * a miss (always rebuild) so a broken detector degrades to the pre-cache behavior rather than serving stale.
 */
internal class ToolCatalogCacheCore(
  private val fingerprint: suspend () -> String?,
  private val build: suspend () -> List<ToolCatalogEntry>,
) {
  private val mutex = Mutex()
  private var cachedFingerprint: String? = null
  private var cached: List<ToolCatalogEntry>? = null

  suspend fun get(): List<ToolCatalogEntry> = mutex.withLock {
    val fp = fingerprint()
    val c = cached
    if (c != null && fp != null && fp == cachedFingerprint) return@withLock c
    val built = build()
    cached = built
    cachedFingerprint = fp
    built
  }
}
