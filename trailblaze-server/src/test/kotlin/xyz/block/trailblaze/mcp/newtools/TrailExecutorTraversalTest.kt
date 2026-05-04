package xyz.block.trailblaze.mcp.newtools

import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * Security tests for [TrailExecutorImpl.resolveFilePath].
 *
 * MCP clients supply `filePath` strings directly into `trail(action=RUN, file=...)`
 * and similar tools. A gap in the path-traversal guard would let an attacker read or
 * execute an arbitrary file on the MCP host. These tests pin the guard at the
 * resolution layer — not through `executeFromFile`, so they don't couple to the
 * bridge's device-side setup — by calling the `internal`-visibility
 * [TrailExecutorImpl.resolveFilePath] directly.
 *
 * Covered:
 *  - `../` sequences in relative paths never escape the trails tree.
 *  - Absolute paths outside the trails tree are rejected outright.
 *  - The name-search fallback (which routes through [xyz.block.trailblaze.config.project.TrailDiscovery])
 *    cannot return a file whose canonical path is outside `trailsDirectory`, even via
 *    a file symlink pointing outside. This is belt-and-suspenders — `TrailDiscovery`
 *    already refuses to traverse symlinks, so the guard should never actually fire
 *    for this case; the test confirms the double defense.
 *  - Legitimate relative paths inside the tree resolve correctly (smoke check).
 */
class TrailExecutorTraversalTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private fun executor(): TrailExecutorImpl =
    TrailExecutorImpl(
      mcpBridge = NoopBridge,
      sessionContext = null,
      trailsDirectory = tempFolder.root.absolutePath,
    )

  private fun newTrail(relativePath: String): File {
    val file = File(tempFolder.root, relativePath)
    file.parentFile?.mkdirs()
    file.writeText("steps: []\n")
    return file
  }

  @Test
  fun `relative path with dot-dot is rejected via canonical prefix check`() {
    // `../../etc/passwd` would canonicalize outside trailsDirectory; the guard must
    // fire even though the path is technically "relative." Use File.separator so the
    // test is correct on both Unix and Windows CI runners.
    val traversal = listOf("..", "..", "etc", "passwd").joinToString(File.separator)

    assertFailsWith<IllegalArgumentException> {
      executor().resolveFilePath(traversal)
    }
  }

  @Test
  fun `absolute path outside the trails dir is rejected with a clear error`() {
    val outside = File(System.getProperty("java.io.tmpdir"), "outside-${System.nanoTime()}.trail.yaml")
    // Don't actually create the file — the guard must reject the path regardless of
    // whether the target exists, so a non-existent absolute path is still rejected.

    val error = assertFailsWith<IllegalArgumentException> {
      executor().resolveFilePath(outside.absolutePath)
    }
    assertTrue(
      "outside the trails directory" in error.message.orEmpty(),
      "expected error to mention containment violation; got: ${error.message}",
    )
  }

  @Test
  fun `absolute path inside the trails dir is accepted`() {
    val trail = newTrail("flows/login.trail.yaml")

    val resolved = executor().resolveFilePath(trail.absolutePath)

    assertEquals(trail.canonicalPath, resolved.canonicalPath)
  }

  @Test
  fun `relative path inside the trails dir resolves to the right file`() {
    val trail = newTrail("flows/login.trail.yaml")

    val resolved = executor().resolveFilePath("flows/login.trail.yaml")

    assertEquals(trail.canonicalPath, resolved.canonicalPath)
  }

  @Test
  fun `bare name triggers dot-trail-yaml auto-append`() {
    val trail = newTrail("login.trail.yaml")

    val resolved = executor().resolveFilePath("login")

    assertEquals(trail.canonicalPath, resolved.canonicalPath)
  }

  @get:Rule
  val outsideTemp: TemporaryFolder = TemporaryFolder()

  @Test
  fun `symlink inside trailsDir targeting outside is rejected by the traversal guard`() {
    // A symlink at `<trailsDir>/escape.trail.yaml` whose target canonicalizes outside
    // `trailsDir` must never be accepted. The guard fires via `validateWithinTrailsDir`:
    // `File("escape.trail.yaml").canonicalPath` follows the symlink, lands outside the
    // tree, and the prefix check in `validateWithinTrailsDir` rejects with
    // `IllegalArgumentException`. This is the one scenario where
    // `TrailDiscovery`'s no-follow policy alone is not sufficient — step 3 of
    // `resolveFilePath` (`.trail.yaml` auto-append + `File.exists()`) follows symlinks
    // before the name-search fallback even runs, so the belt-and-suspenders validator
    // is load-bearing.
    //
    // `outsideTemp` is a sibling TemporaryFolder — JUnit allocates both inside the
    // JVM tmp dir but gives each its own root, so `outsideTemp.root` is genuinely
    // outside `tempFolder.root`. Without a genuinely-outside target the test cannot
    // demonstrate an escape.
    Assume.assumeTrue("Symlink support required", supportsSymlinks())
    val outsideTarget = File(outsideTemp.root, "secret.trail.yaml").apply {
      writeText("steps: []\n")
    }
    Assume.assumeFalse(
      "outsideTemp and tempFolder overlap — the test cannot demonstrate an escape.",
      outsideTarget.canonicalPath.startsWith(tempFolder.root.canonicalPath + File.separator),
    )
    Files.createSymbolicLink(
      File(tempFolder.root, "escape.trail.yaml").toPath(),
      outsideTarget.toPath(),
    )

    val error = assertFailsWith<IllegalArgumentException> {
      executor().resolveFilePath("escape")
    }
    assertTrue(
      "outside the trails directory" in error.message.orEmpty(),
      "expected traversal-reject error; got: ${error.message}",
    )
  }

  private fun supportsSymlinks(): Boolean = try {
    val probeTarget = File(tempFolder.root, "_symlink-probe-target").apply { mkdirs() }
    val probeLink = File(tempFolder.root, "_symlink-probe-link").toPath()
    Files.createSymbolicLink(probeLink, probeTarget.toPath())
    Files.deleteIfExists(probeLink)
    probeTarget.delete()
    true
  } catch (_: Exception) {
    false
  }

  /** No-op bridge — `resolveFilePath` never touches device-side methods. */
  private object NoopBridge : TrailblazeMcpBridge {
    override suspend fun selectDevice(trailblazeDeviceId: TrailblazeDeviceId): TrailblazeConnectedDeviceSummary =
      throw NotImplementedError()

    override suspend fun getAvailableDevices(): Set<TrailblazeConnectedDeviceSummary> = emptySet()

    override suspend fun getInstalledAppIds(): Set<String> = emptySet()

    override fun getAvailableAppTargets(): Set<TrailblazeHostAppTarget> = emptySet()

    override suspend fun runYaml(
      yaml: String,
      startNewSession: Boolean,
      agentImplementation: AgentImplementation,
    ): String = throw NotImplementedError()

    override fun getCurrentlySelectedDeviceId(): TrailblazeDeviceId? = null

    override suspend fun getCurrentScreenState(): ScreenState? = null

    override suspend fun executeTrailblazeTool(
      tool: TrailblazeTool,
      blocking: Boolean,
      traceId: TraceId?,
    ): String =
      throw NotImplementedError()

    override suspend fun endSession(): Boolean = false

    override fun selectAppTarget(appTargetId: String): String? = null

    override fun getCurrentAppTargetId(): String? = null

    override fun getDriverType(): TrailblazeDriverType? = null

    override suspend fun getScreenStateViaRpc(
      includeScreenshot: Boolean,
      screenshotScalingConfig: ScreenshotScalingConfig,
      includeAnnotatedScreenshot: Boolean,
      includeAllElements: Boolean,
    ): GetScreenStateResponse? = null

    override fun getActiveSessionId(): SessionId? = null

    override suspend fun ensureSessionAndGetId(testName: String?): SessionId? = null
  }
}
