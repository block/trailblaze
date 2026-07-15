package xyz.block.trailblaze.ui

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * Pins the target-selection decision a daemon-dispatched `run` makes in
 * `TrailblazeDesktopApp.handleCliRunRequest` (extracted to [resolveDaemonRunTargetApp]).
 *
 * The resolver correctness itself — how a given caller cwd anchors the workspace
 * `defaults.target` rung — is covered by [TrailblazeSettingsRepoTargetPrecedenceTest]. These
 * tests pin the WIRING that makes it fire end-to-end, which is otherwise inspection-only:
 *  - the trail's `config.target` wins over the caller-cwd fallback,
 *  - a `config.target` naming no loaded target degrades to the fallback (not an error),
 *  - and, critically, the forwarded `callerWorkspaceDir` is threaded verbatim to the
 *    caller-cwd resolver — NOT dropped or replaced by the daemon-anchored no-arg path, which
 *    would silently revert the fix this guards.
 */
class CliRunTargetResolutionTest {

  private fun target(id: String): TrailblazeHostAppTarget = object : TrailblazeHostAppTarget(
    id = id,
    displayName = "Target $id",
  ) {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? = null

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()
  }

  @Test
  fun `config target wins over the caller-cwd fallback`() {
    val alpha = target("alpha")
    val beta = target("beta")
    var fallbackInvoked = false

    val resolved = resolveDaemonRunTargetApp(
      configTarget = "alpha",
      callerWorkspaceDir = "/caller/workspace",
      findTargetById = { id -> alpha.takeIf { id == "alpha" } ?: beta.takeIf { id == "beta" } },
      resolveForCallerCwd = { fallbackInvoked = true; beta },
    )

    assertEquals(alpha, resolved)
    assertFalse(fallbackInvoked, "config.target resolved, so the caller-cwd fallback must not run")
  }

  @Test
  fun `unknown config target falls through to the caller-cwd resolver`() {
    val beta = target("beta")
    var seenCallerDir: String? = "unset"

    val resolved = resolveDaemonRunTargetApp(
      configTarget = "does-not-exist",
      callerWorkspaceDir = "/caller/workspace",
      findTargetById = { null }, // names no loaded target
      resolveForCallerCwd = { dir -> seenCallerDir = dir; beta },
    )

    assertEquals(beta, resolved, "a stale/mistyped config.target must degrade to the fallback, not error")
    assertEquals("/caller/workspace", seenCallerDir, "the forwarded caller cwd must reach the resolver")
  }

  @Test
  fun `absent config target threads the forwarded caller cwd to the resolver`() {
    val beta = target("beta")
    var seenCallerDir: String? = "unset"

    val resolved = resolveDaemonRunTargetApp(
      configTarget = null,
      callerWorkspaceDir = "/caller/workspace",
      findTargetById = { error("must not be consulted when config.target is null") },
      resolveForCallerCwd = { dir -> seenCallerDir = dir; beta },
    )

    assertEquals(beta, resolved)
    assertEquals("/caller/workspace", seenCallerDir)
  }

  @Test
  fun `null caller cwd is forwarded to the resolver unchanged`() {
    // The resolver (not this function) is what maps null -> daemon anchor; this pins that the
    // handler forwards null verbatim rather than substituting its own value.
    var resolverSawNull = false

    val resolved = resolveDaemonRunTargetApp(
      configTarget = null,
      callerWorkspaceDir = null,
      findTargetById = { error("must not be consulted when config.target is null") },
      resolveForCallerCwd = { dir -> resolverSawNull = dir == null; null },
    )

    assertNull(resolved)
    assertTrue(resolverSawNull, "a null callerWorkspaceDir must pass through to the resolver unchanged")
  }
}
