package xyz.block.trailblaze.cli

import xyz.block.trailblaze.logs.server.endpoints.CliStatusResponse
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

  private fun status(anchor: String?, hash: String? = null) = CliStatusResponse(
    running = true,
    port = port,
    connectedDevices = 0,
    uptimeSeconds = 0,
    workspaceAnchor = anchor,
    workspaceContentHash = hash,
  )

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
}
