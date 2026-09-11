package xyz.block.trailblaze.cli

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import picocli.CommandLine
import xyz.block.trailblaze.trailrunner.CompanionDisconnectRequest

class CompanionCommandTest {
  @Test
  fun `daemon startup claim elects one owner and safely reclaims a dead owner`() {
    val dir = createTempDirectory("companion-daemon-claim").toFile()
    val pidFile = dir.resolve("daemon-52525.pid.starting")
    try {
      val owner = assertIs<DaemonStartupClaim.Owner>(claimDaemonStartup(pidFile))
      assertEquals(
        DaemonStartupClaim.Existing(ProcessHandle.current().pid()),
        claimDaemonStartup(pidFile),
      )

      assertTrue(replaceDaemonStartupPid(owner.ownerFile, ProcessHandle.current().pid()))
      assertEquals(ProcessHandle.current().pid().toString(), owner.ownerFile.readLines().first())

      owner.ownerFile.writeText("${ProcessHandle.current().pid()}\nstale-process\n")
      val orphanedHandoff = pidFile.resolve("owner-handoff.new")
      orphanedHandoff.writeText("interrupted replacement")
      val replacement = assertIs<DaemonStartupClaim.Owner>(claimDaemonStartup(pidFile))
      assertFalse(orphanedHandoff.exists(), "a reclaimed generation must remove orphaned handoffs")
      releaseDaemonStartupClaim(owner.ownerFile)
      assertTrue(replacement.ownerFile.exists(), "a delayed cleanup must not delete a new generation")
      assertEquals(
        DaemonStartupClaim.Existing(ProcessHandle.current().pid()),
        claimDaemonStartup(pidFile),
      )
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `unpublished daemon startup claim is never reclaimed by a competing starter`() {
    val dir = createTempDirectory("companion-unpublished-claim").toFile()
    val pidFile = dir.resolve("daemon-52525.pid.starting")
    try {
      assertTrue(pidFile.mkdir())

      // mkdir is the atomic election; a second process must not delete this directory while the
      // first process is between election and publishing its owner metadata.
      assertEquals(DaemonStartupClaim.Unavailable, claimDaemonStartup(pidFile))
      assertTrue(pidFile.isDirectory)
      assertTrue(pidFile.listFiles().isNullOrEmpty())
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `delayed owner publication lets a waiting starter join`() {
    val dir = createTempDirectory("companion-delayed-claim").toFile()
    val pidFile = dir.resolve("daemon-52525.pid.starting")
    val publishedClaimFile = dir.resolve("published-claim")
    var publisher: Thread? = null
    try {
      val publishedOwner = assertIs<DaemonStartupClaim.Owner>(claimDaemonStartup(publishedClaimFile))
      assertTrue(pidFile.mkdir())
      publisher = thread {
        Thread.sleep(20)
        pidFile.resolve("owner-published").writeText(publishedOwner.ownerFile.readText())
      }

      assertTrue(
        waitForStartupClaimPublication(
          pidFile,
          daemonIsReady = { false },
          maxAttempts = 100,
          retryDelayMillis = 10,
        ),
      )
      requireNotNull(publisher).join()
      assertEquals(
        DaemonStartupClaim.Existing(ProcessHandle.current().pid()),
        claimDaemonStartup(pidFile),
      )
    } finally {
      publisher?.join()
      dir.deleteRecursively()
    }
  }

  @Test
  fun `released unpublished startup claim lets a waiting starter retry`() {
    val dir = createTempDirectory("companion-released-claim").toFile()
    val pidFile = dir.resolve("daemon-52525.pid.starting")
    try {
      assertTrue(pidFile.mkdir())
      var readinessChecks = 0

      assertTrue(
        waitForStartupClaimPublication(
          pidFile,
          daemonIsReady = {
            readinessChecks += 1
            if (readinessChecks == 2) assertTrue(pidFile.delete())
            false
          },
          maxAttempts = 3,
          retryDelayMillis = 1,
        ),
      )
      assertFalse(pidFile.exists())
      assertIs<DaemonStartupClaim.Owner>(claimDaemonStartup(pidFile))
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `daemon startup reaper removes the exact exited child claim`() {
    val dir = createTempDirectory("companion-daemon-reaper").toFile()
    val pidFile = dir.resolve("daemon-52525.pid.starting")
    val child = ProcessBuilder("sh", "-c", "read line").start()
    try {
      val owner = assertIs<DaemonStartupClaim.Owner>(claimDaemonStartup(pidFile))
      assertTrue(replaceDaemonStartupPid(owner.ownerFile, child.pid()))
      val reaper = assertNotNull(scheduleDaemonStartupClaimReaper(pidFile, owner.ownerFile, child.pid()))
      assertTrue(pidFile.exists(), "the reaper must retain a live child's claim")
      child.outputStream.bufferedWriter().use { it.newLine() }
      child.waitFor()
      assertEquals(0, reaper.waitFor())
      assertFalse(pidFile.exists())
    } finally {
      child.destroyForcibly()
      dir.deleteRecursively()
    }
  }

  @Test
  fun `shared command tree registers the complete companion surface`() {
    val root = CommandLine(TrailblazeCliCommand({ error("unused") }, { error("unused") }))
    val companion = root.subcommands["companion"]

    assertTrue(companion != null)
    assertEquals(
      setOf("start", "event", "send", "listen", "disconnect", "respond", "agent-help"),
      companion.subcommands.keys,
    )
  }

  @Test
  fun `companion parse failures preserve the one JSON object stdout contract`() {
    val commandLine = CommandLine(CompanionCommand())
    installTrailblazeExceptionHandlers(commandLine)
    var exit = 0

    val output = captureStdout { exit = commandLine.execute("event") }.trim()

    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
    val error = Json.parseToJsonElement(output).jsonObject
    assertEquals("false", error.getValue("ok").jsonPrimitive.content)
    assertContains(error.getValue("error").jsonPrimitive.content, "runId")
  }

  @Test
  fun `agent help is bundled with the OSS command`() {
    var exit = 0
    val output = captureStdout {
      exit = CommandLine(CompanionCommand()).execute("--agent-help")
    }

    assertEquals(TrailblazeExitCode.SUCCESS.code, exit)
    assertContains(output, "trailblaze companion start")
    assertContains(output, "trailblaze companion listen")

    val childOutput = captureStdout {
      exit = CommandLine(CompanionCommand()).execute("agent-help")
    }
    assertEquals(TrailblazeExitCode.SUCCESS.code, exit)
    assertEquals(output, childOutput)
  }

  @Test
  fun `client posts JSON to the companion route and mirrors the daemon verdict`() {
    val server = HttpServer.create(InetSocketAddress(0), 0)
    var requestBody = ""
    server.createContext("/trailrunner/api/companion/run-123/disconnect") { exchange ->
      requestBody = exchange.requestBody.bufferedReader().readText()
      val response = "{\"ok\":true}"
      exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
      exchange.responseBody.use { it.write(response.toByteArray()) }
    }
    server.start()
    try {
      val result = CompanionCliClient(server.address.port).post(
        "/run-123/disconnect",
        CompanionDisconnectRequest(note = "finished"),
        CompanionDisconnectRequest.serializer(),
      )

      assertTrue(result.ok)
      assertContains(requestBody, "\"note\":\"finished\"")
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `standalone MCP server is not reused for Companion`() {
    val server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext("/cli/status") { exchange ->
      val response = "not found"
      exchange.sendResponseHeaders(404, response.toByteArray().size.toLong())
      exchange.responseBody.use { it.write(response.toByteArray()) }
    }
    server.start()
    try {
      assertFalse(daemonSupportsCompanion(server.address.port))
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `daemon validation rejection exits as misuse`() {
    val server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext("/trailrunner/api/companion/run-123/event") { exchange ->
      val response = "{\"ok\":false,\"error\":\"text is required\"}"
      exchange.sendResponseHeaders(400, response.toByteArray().size.toLong())
      exchange.responseBody.use { it.write(response.toByteArray()) }
    }
    server.start()
    try {
      val result = CompanionCliClient(server.address.port).post(
        "/run-123/event",
        CompanionDisconnectRequest(),
        CompanionDisconnectRequest.serializer(),
      )
      var exit = 0
      val output = captureStdout { exit = result.print() }

      assertEquals(TrailblazeExitCode.MISUSE.code, exit)
      assertContains(output, "text is required")
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `listen converts agent events to JSONL and stops on done`() {
    val server = HttpServer.create(InetSocketAddress(0), 0)
    var query = ""
    server.createContext("/trailrunner/api/external-agent/run-123/stream") { exchange ->
      query = exchange.requestURI.rawQuery
      val response = """
        event: agent-event
        data: {"seq":8,"kind":"human_action"}

        event: done
        data: {}

      """.trimIndent()
      exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
      exchange.responseBody.use { it.write(response.toByteArray()) }
    }
    server.start()
    try {
      var exit = 0
      val output = captureStdout { exit = CompanionCliClient(server.address.port).listen("run-123", 7) }

      assertEquals(TrailblazeExitCode.SUCCESS.code, exit)
      assertEquals("{\"seq\":8,\"kind\":\"human_action\"}", output.trim())
      assertContains(query, "consumer=agent")
      assertContains(query, "afterSeq=7")
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `listen returns misuse when the requested run does not exist`() {
    val server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext("/trailrunner/api/external-agent/missing-run/stream") { exchange ->
      val response = """
        event: error
        data: {"error":"external agent run not found: missing-run"}

      """.trimIndent()
      exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
      exchange.responseBody.use { it.write(response.toByteArray()) }
    }
    server.start()
    try {
      var exit = 0
      val output = captureStdout {
        exit = CompanionCliClient(server.address.port).listen("missing-run", null)
      }

      assertEquals(TrailblazeExitCode.MISUSE.code, exit)
      assertContains(output, "run not found")
    } finally {
      server.stop(0)
    }
  }
}
