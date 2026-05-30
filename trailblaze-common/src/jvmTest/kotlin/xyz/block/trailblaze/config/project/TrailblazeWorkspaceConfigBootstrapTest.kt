package xyz.block.trailblaze.config.project

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import xyz.block.trailblaze.llm.config.WorkspaceConfigDirHolder

/**
 * Pins the load-trigger contract on [TrailblazeWorkspaceConfigBootstrap.ensureInstalled]:
 *
 * The bootstrap exists to bridge a module boundary — `trailblaze-models` defines the holder,
 * `trailblaze-common` ships the resolver. The wiring lives in the bootstrap's static
 * `init {}` block, which only fires when something references the class. `ensureInstalled()`
 * is the cheapest way for an entry point (CLI, MCP server, desktop app) to force class load.
 *
 * Without this test the entire PR's invariant ("workspace tools surface through the platform
 * default") rests on an unverified `init {}` block. A future refactor that conditioned the
 * wiring, deleted it, or moved it would break workspace discovery for every CLI invocation,
 * and no existing unit test would catch the regression.
 */
class TrailblazeWorkspaceConfigBootstrapTest {

  private lateinit var priorResolver: () -> java.io.File?

  @BeforeTest
  fun setUp() {
    priorResolver = WorkspaceConfigDirHolder.resolver
  }

  @AfterTest
  fun tearDown() {
    WorkspaceConfigDirHolder.resolver = priorResolver
  }

  @Test
  fun `ensureInstalled mutates the holder away from the no-op default`() {
    // Reset the holder to the documented no-op default before exercising the bootstrap so
    // we're testing the load-trigger effect, not some prior installer state inherited from
    // another test sharing the JVM. A sentinel lambda value lets us assert "the holder is
    // no longer this exact instance" — stronger than asserting "non-null", because the
    // bootstrap is allowed to install a resolver that *returns* null when no workspace is
    // found, as long as the resolver *itself* is the real one.
    val sentinel: () -> java.io.File? = { null }
    WorkspaceConfigDirHolder.resolver = sentinel

    TrailblazeWorkspaceConfigBootstrap.ensureInstalled()

    assertNotSame(
      sentinel,
      WorkspaceConfigDirHolder.resolver,
      "ensureInstalled() must trigger the bootstrap's static init and replace the holder's resolver",
    )
  }

  @Test
  fun `ensureInstalled is idempotent — repeated calls keep the resolver installed`() {
    // The bootstrap's `init {}` runs once per class load; repeated `ensureInstalled()`
    // calls should be harmless no-ops. Pins the contract so a future "lazy re-install"
    // refactor can't silently change the behavior.
    TrailblazeWorkspaceConfigBootstrap.ensureInstalled()
    val firstInstall = WorkspaceConfigDirHolder.resolver
    TrailblazeWorkspaceConfigBootstrap.ensureInstalled()
    assertSame(
      firstInstall,
      WorkspaceConfigDirHolder.resolver,
      "repeated ensureInstalled() must not re-mutate the holder",
    )
  }
}
