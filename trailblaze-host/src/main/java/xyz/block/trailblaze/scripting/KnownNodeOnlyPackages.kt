package xyz.block.trailblaze.scripting

/**
 * Conservative starter list of npm packages whose runtime requires Node APIs and therefore
 * can't load in the on-device QuickJS bundle. Used by [ScriptedToolImportAnalyzer] to flag
 * a scripted tool as host-only without having to walk into every package's `node_modules`
 * dep chain looking for a `node:*` import.
 *
 * Maintenance: keep the set tight. False positives here (a pure-ES package wrongly flagged
 * as Node-only) push the author to set `runtime: subprocess` manually for what should have
 * shipped on-device. Easier to discover and add a real Node-only package than to back out
 * a hasty addition. When in doubt, leave it off — the metafile dep-walk in
 * [ScriptedToolImportAnalyzer] catches anything that eventually imports a `node:*` builtin
 * anyway, so the only entries worth listing here are top-level packages whose dep walk is
 * either too noisy to surface a clean chain string or too deep to be worth the I/O.
 */
internal object KnownNodeOnlyPackages {
  val DEFAULT: Set<String> = setOf(
    // HTTP clients with Node-only transports (axios uses `node:http`, got/undici/node-fetch
    // pull `node:net` + `node:tls`). Authors who want an on-device HTTP call should use the
    // platform-provided `fetch` — QuickJS-NG exposes it natively.
    "axios",
    "node-fetch",
    "undici",
    "got",
    "request",
    // Database drivers. All of these open native sockets / load native bindings, none of
    // which are reachable from the on-device QuickJS engine.
    "pg",
    "mysql",
    "mysql2",
    "mongodb",
    "redis",
    "sqlite3",
  )
}
