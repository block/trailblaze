package xyz.block.trailblaze.cli

import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.ToolNameResolver
import xyz.block.trailblaze.config.YamlBackedHostAppTarget
import xyz.block.trailblaze.logs.server.endpoints.CliDaemonCapabilities
import xyz.block.trailblaze.logs.server.endpoints.CliStatusResponse
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CheckDaemonBeforeConnectTest {

  private val port = 52_525
  private val root = Files.createTempDirectory("check-daemon-before-connect").toFile().canonicalFile

  @AfterTest
  fun cleanUp() {
    root.deleteRecursively()
  }

  /** A directory holding a workspace anchor, and the anchor's path. */
  private fun workspace(name: String): Pair<File, String> {
    val dir = File(root, name)
    val anchor = File(dir, "trails/config/trailblaze.yaml").apply {
      parentFile.mkdirs()
      writeText("targets: []\n")
    }
    return dir to anchor.absolutePath
  }

  private fun status(
    anchor: String?,
    hash: String? = null,
    servedTrailmaps: Map<String, Map<String, String?>>? = null,
    capabilities: Set<String> = CliDaemonCapabilities.ALL,
  ) = CliStatusResponse(
    running = true,
    port = port,
    connectedDevices = 0,
    uptimeSeconds = 0,
    workspaceAnchor = anchor,
    workspaceContentHash = hash,
    servedTrailmaps = servedTrailmaps,
    capabilities = capabilities,
  )

  /** Creates a target trailmap named [id] in [workspaceDir] and returns its canonical path. */
  private fun trailmap(workspaceDir: File, id: String): String =
    File(workspaceDir, "trails/config/trailmaps/$id").apply {
      mkdirs()
      resolve("trailmap.yaml").writeText("id: $id\ntarget:\n  display_name: $id\n")
    }.canonicalPath

  /** Each status request scans every attached device, which is what made a forwarded call slow. */
  @Test
  fun `a command running inside the daemon it connects to asks that daemon nothing over HTTP`() {
    val (dir, anchor) = workspace("a")
    var statusRequests = 0

    val proceed = CliCallerContext.withServingPort(port) {
      CliCallerContext.withCallerCwd(dir.toPath()) {
        checkDaemonBeforeConnect(
          port,
          fetchStatus = { statusRequests++; status(anchor) },
          thisDaemon = { DaemonWorkspace(anchor, contentHash = null) },
          warn = {},
        )
      }
    }

    assertTrue(proceed)
    assertEquals(0, statusRequests)
  }

  /**
   * Inside the daemon, the current directory is the daemon's, so comparing it with the daemon's own
   * workspace always matched and a forwarded command never warned. The caller's directory is the one
   * the shell forwarded.
   */
  @Test
  fun `a forwarded command from another workspace is warned about the daemon's`() {
    val (_, daemonAnchor) = workspace("daemon")
    val (callerDir, callerAnchor) = workspace("caller")
    val warnings = mutableListOf<WorkspaceMismatch>()

    CliCallerContext.withServingPort(port) {
      CliCallerContext.withCallerCwd(callerDir.toPath()) {
        checkDaemonBeforeConnect(
          port,
          fetchStatus = { error("no status request expected") },
          thisDaemon = { DaemonWorkspace(daemonAnchor, contentHash = null) },
          warn = { warnings += it },
        )
      }
    }

    assertEquals(listOf<WorkspaceMismatch>(WorkspaceMismatch.Anchor(daemonAnchor, callerAnchor)), warnings)
  }

  /** A command forwarded to one daemon that connects to another must still check that other one. */
  @Test
  fun `a connection to a different daemon still reads its status`() {
    val (dir, anchor) = workspace("a")
    var statusRequests = 0

    CliCallerContext.withServingPort(port + 1) {
      CliCallerContext.withCallerCwd(dir.toPath()) {
        checkDaemonBeforeConnect(
          port,
          fetchStatus = { statusRequests++; status(anchor) },
          thisDaemon = { error("this process is not the daemon on $port") },
          warn = {},
        )
      }
    }

    assertEquals(1, statusRequests)
  }

  @Test
  fun `a separate process fetches the status once and warns from it`() {
    val (_, daemonAnchor) = workspace("daemon")
    val (callerDir, callerAnchor) = workspace("caller")
    var statusRequests = 0
    val warnings = mutableListOf<WorkspaceMismatch>()

    val proceed = CliCallerContext.withCallerCwd(callerDir.toPath()) {
      checkDaemonBeforeConnect(
        port,
        fetchStatus = { statusRequests++; status(daemonAnchor) },
        thisDaemon = { error("this process is not a daemon") },
        warn = { warnings += it },
      )
    }

    assertTrue(proceed)
    assertEquals(1, statusRequests)
    assertEquals(listOf<WorkspaceMismatch>(WorkspaceMismatch.Anchor(daemonAnchor, callerAnchor)), warnings)
  }

  @Test
  fun `no daemon running means nothing to warn about`() {
    val (dir, _) = workspace("a")
    val warnings = mutableListOf<WorkspaceMismatch>()

    val proceed = CliCallerContext.withCallerCwd(dir.toPath()) {
      checkDaemonBeforeConnect(port, fetchStatus = { null }, thisDaemon = { error("unused") }, warn = { warnings += it })
    }

    assertTrue(proceed)
    assertEquals(emptyList(), warnings)
  }

  @Test
  fun `the same workspace edited since the daemon loaded it is drift`() {
    val (dir, anchor) = workspace("a")
    val daemon = DaemonWorkspace(anchor, contentHash = "loaded")

    assertEquals(
      WorkspaceMismatch.Drift(anchor),
      findWorkspaceMismatch(daemon, dir.toPath(), contentHashOf = { "edited" }),
    )
    assertNull(findWorkspaceMismatch(daemon, dir.toPath(), contentHashOf = { "loaded" }))
  }

  @Test
  fun `a caller in a subdirectory of the daemon's workspace matches it`() {
    val (dir, anchor) = workspace("a")
    val nested = File(dir, "trails/some/deep/dir").apply { mkdirs() }

    assertNull(findWorkspaceMismatch(DaemonWorkspace(anchor, null), nested.toPath()))
  }

  @Test
  fun `a caller outside any workspace is not compared`() {
    val (_, anchor) = workspace("a")
    val outside = File(root, "no-workspace").apply { mkdirs() }

    assertNull(findWorkspaceMismatch(DaemonWorkspace(anchor, null), outside.toPath()))
  }

  /**
   * The daemon would run the caller's trail against a copy of the trailmap the caller is not
   * editing, and a stale copy that runs looks like a pass — so the command stops instead of warning.
   */
  @Test
  fun `a forwarded command refuses a daemon serving a bundled copy of the caller's trailmap`() {
    val (callerDir, callerAnchor) = workspace("caller")
    trailmap(callerDir, "app")
    val refusals = mutableListOf<String>()
    val warnings = mutableListOf<WorkspaceMismatch>()
    val bundledApp = YamlBackedHostAppTarget(
      config = AppTargetYamlConfig(id = "app", displayName = "App"),
      toolNameResolver = ToolNameResolver.fromBuiltInAndCustomTools(),
      trailmapDirs = mapOf("app" to null),
    )

    val proceed = CliCallerContext.withServingPort(port) {
      CliCallerContext.withServedTargets({ setOf(bundledApp) }) {
        CliCallerContext.withCallerCwd(callerDir.toPath()) {
          checkDaemonBeforeConnect(
            port,
            targetAppId = "app",
            fetchStatus = { error("no status request expected") },
            thisDaemon = { DaemonWorkspace(callerAnchor, contentHash = null) },
            warn = { warnings += it },
            refuse = { refusals += it },
          )
        }
      }
    }

    assertFalse(proceed)
    assertEquals(1, refusals.size)
    assertTrue("app" in refusals.single() && "bundled" in refusals.single(), refusals.single())
    assertEquals(emptyList(), warnings)
  }

  @Test
  fun `a separate process refuses a daemon serving another checkout's copy of the caller's trailmap`() {
    val (callerDir, _) = workspace("caller")
    val (otherDir, otherAnchor) = workspace("other")
    trailmap(callerDir, "app")
    val otherApp = trailmap(otherDir, "app")
    val refusals = mutableListOf<String>()

    val proceed = CliCallerContext.withCallerCwd(callerDir.toPath()) {
      checkDaemonBeforeConnect(
        port,
        targetAppId = "app",
        fetchStatus = { status(otherAnchor, servedTrailmaps = mapOf("app" to mapOf("app" to otherApp))) },
        thisDaemon = { error("this process is not a daemon") },
        warn = {},
        refuse = { refusals += it },
      )
    }

    assertFalse(proceed)
    assertTrue(otherApp in refusals.single(), refusals.single())
  }

  @Test
  fun `a daemon serving the caller's own trailmap is connected to`() {
    val (callerDir, callerAnchor) = workspace("caller")
    val callerApp = trailmap(callerDir, "app")

    val proceed = CliCallerContext.withCallerCwd(callerDir.toPath()) {
      checkDaemonBeforeConnect(
        port,
        targetAppId = "app",
        fetchStatus = { status(callerAnchor, servedTrailmaps = mapOf("app" to mapOf("app" to callerApp))) },
        thisDaemon = { error("this process is not a daemon") },
        warn = {},
        refuse = { error("nothing to refuse: $it") },
      )
    }

    assertTrue(proceed)
  }

  /** `device list` and the like run no trailmap code, so a shadowed trailmap must not stop them. */
  @Test
  fun `a command that names no target is not refused`() {
    val (callerDir, _) = workspace("caller")
    val (_, otherAnchor) = workspace("other")
    trailmap(callerDir, "app")

    val proceed = CliCallerContext.withCallerCwd(callerDir.toPath()) {
      checkDaemonBeforeConnect(
        port,
        targetAppId = null,
        fetchStatus = { status(otherAnchor, servedTrailmaps = mapOf("app" to mapOf("app" to null))) },
        thisDaemon = { error("this process is not a daemon") },
        warn = {},
        refuse = { error("nothing to refuse: $it") },
      )
    }

    assertTrue(proceed)
  }

  /**
   * A daemon from before the check reports no origins, and one with runs in flight is kept on its
   * old version, so it would run whatever copy it loaded.
   */
  @Test
  fun `a daemon too old to report trailmap origins is refused for a target the workspace has`() {
    val (callerDir, callerAnchor) = workspace("caller")
    trailmap(callerDir, "app")
    val refusals = mutableListOf<String>()

    val proceed = CliCallerContext.withCallerCwd(callerDir.toPath()) {
      checkDaemonBeforeConnect(
        port,
        targetAppId = "app",
        fetchStatus = { status(callerAnchor, capabilities = emptySet()) },
        thisDaemon = { error("this process is not a daemon") },
        warn = {},
        refuse = { refusals += it },
      )
    }

    assertFalse(proceed)
    assertTrue("too old" in refusals.single() && "`app`" in refusals.single(), refusals.single())
  }

  @Test
  fun `a daemon too old to report trailmap origins is used for a target the workspace does not have`() {
    val (callerDir, callerAnchor) = workspace("caller")
    trailmap(callerDir, "app")

    val proceed = CliCallerContext.withCallerCwd(callerDir.toPath()) {
      checkDaemonBeforeConnect(
        port,
        targetAppId = "other",
        fetchStatus = { status(callerAnchor, capabilities = emptySet()) },
        thisDaemon = { error("this process is not a daemon") },
        warn = {},
        refuse = { error("nothing to refuse: $it") },
      )
    }

    assertTrue(proceed)
  }

  /**
   * A daemon the command just started from its own directory can still serve a bundled copy: the
   * workspace trailmap failed to load and the bundled one filled in.
   */
  @Test
  fun `a daemon this command just started is refused when it serves another copy of the target`() {
    val (callerDir, callerAnchor) = workspace("caller")
    trailmap(callerDir, "app")
    val refusals = mutableListOf<String>()

    val refused = CliCallerContext.withCallerCwd(callerDir.toPath()) {
      refusesStartedDaemon(
        port,
        targetAppId = "app",
        fetchStatus = { status(callerAnchor, servedTrailmaps = mapOf("app" to mapOf("app" to null))) },
        refuse = { refusals += it },
      )
    }

    assertTrue(refused)
    assertTrue("app" in refusals.single() && "trailblaze check" in refusals.single(), refusals.single())
  }

  @Test
  fun `a daemon this command just started that serves the caller's own target is used`() {
    val (callerDir, callerAnchor) = workspace("caller")
    val callerApp = trailmap(callerDir, "app")

    val refused = CliCallerContext.withCallerCwd(callerDir.toPath()) {
      refusesStartedDaemon(
        port,
        targetAppId = "app",
        fetchStatus = { status(callerAnchor, servedTrailmaps = mapOf("app" to mapOf("app" to callerApp))) },
        refuse = { error("nothing to refuse: $it") },
      )
    }

    assertFalse(refused)
  }
}
