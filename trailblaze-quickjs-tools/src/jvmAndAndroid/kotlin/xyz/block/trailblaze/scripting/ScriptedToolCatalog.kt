package xyz.block.trailblaze.scripting

import xyz.block.trailblaze.config.ScriptedToolNameDiscoverer
import xyz.block.trailblaze.config.ScriptedToolNameDiscoverer.DiscoveredDescriptor
import xyz.block.trailblaze.toolcalls.ToolName

/**
 * The scripted-tool descriptor index, walked at most once for the life of this object.
 *
 * Building the index means reading every descriptor YAML under every trailmap's `tools/` directory,
 * on the classpath and in the workspace. That walk is the whole cost of describing a scripted tool, and a single
 * discovery request describes tools for every target on every platform — dozens of walks of the
 * same unchanged tree. Callers that fan out like that create one catalog per request and hand it
 * to each [InProcessScriptedToolLauncher.describe] call; the first call pays for the walk and the
 * rest read the index.
 *
 * Scoped to one request on purpose. A workspace edit must show up on the next request, so nothing
 * here outlives the call that created it, and the default [loader] is the same discovery a bare
 * `describe` runs — a caller that never needs the index never pays for it.
 */
class ScriptedToolCatalog(
  loader: () -> Map<ToolName, DiscoveredDescriptor> = { ScriptedToolNameDiscoverer.discoverDescriptorsByName() },
) {
  // The Result is what's memoized, not the map: `lazy` re-runs its initializer after that
  // initializer throws, and discovery throws on a duplicate scripted-tool name. Callers catch that
  // per description, so a plain `lazy(loader)` would repeat the failing walk once per target — the
  // fan-out this class exists to remove, on exactly the workspace that is already broken. Holding
  // the Result walks once either way and rethrows the same failure to every caller.
  private val loaded: Result<Map<ToolName, DiscoveredDescriptor>> by lazy { runCatching(loader) }

  val descriptorsByName: Map<ToolName, DiscoveredDescriptor> get() = loaded.getOrThrow()
}
