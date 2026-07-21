package xyz.block.trailblaze.trailrunner

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.utils.io.readUTF8Line
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.report.utils.LogsRepo
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The companion HTTP surface, exercised the way the `trailblaze companion` launcher actually calls
 * it: form-urlencoded bodies. This parsing exists nowhere else in the daemon, so a regression here
 * breaks every CLI verb while the supervisor suite stays green. Also pins the route-level policy:
 * status-code mapping (404 unknown run / 400 validation) and the cross-origin (CSRF) guard.
 */
class CompanionRoutesTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private lateinit var trailsDir: File
  private lateinit var logsRepo: LogsRepo
  private lateinit var preexistingRunIds: Set<String>

  @Before
  fun setUp() {
    trailsDir = tmp.newFolder("trails")
    logsRepo = LogsRepo(logsDir = tmp.newFolder("logs"), watchFileSystem = false)
    preexistingRunIds = ExternalAgentSupervisor.runs.keys.toSet()
  }

  @After
  fun cleanup() {
    (ExternalAgentSupervisor.runs.keys.toSet() - preexistingRunIds).forEach {
      // Disconnect first so a run left RUNNING by a failed assertion doesn't keep its
      // idle-watchdog coroutine ticking; harmless failure Result for non-companion seeds.
      ExternalAgentSupervisor.disconnectCompanion(it, note = null)
      ExternalAgentSupervisor.runs.remove(it)
    }
  }

  private fun withCompanionRoutes(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
    testApplication {
      application {
        // The SAME converter the production daemon installs (TrailblazeMcpServer), so the JSON
        // body path is exercised with the config the SPA actually hits, not a test lookalike.
        install(ContentNegotiation) { json(McpJson) }
        install(SSE)
        install(WebSockets)
        routing { TrailRunnerEndpoint.register(this, trailsRootProvider = { trailsDir }, logsRepo = logsRepo) }
      }
      block()
    }

  /** Seeds a non-companion run, the same way the supervisor tests do. */
  private fun seedSpawnedRun(id: String) {
    ExternalAgentSupervisor.runs[id] = MutableExternalAgentRun(
      id = id,
      request = ExternalAgentRunRequest(agentType = ExternalAgentType.CLAUDE, prompt = "test run"),
      title = "Test run",
      prompt = "test run",
      cwd = File("."),
    )
  }

  @Test
  fun `form-urlencoded connect and event round-trip like the CLI`() = withCompanionRoutes {
    val connect = client.post("/trailrunner/api/companion/connect") {
      header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
      setBody("agentType=claude&title=ToS+trail&folder=myapp%2Ftos&agentLabel=Claude+Code")
    }
    assertEquals(HttpStatusCode.OK, connect.status)
    val body = connect.bodyAsText()
    assertTrue(body.startsWith("""{"ok":true"""), body)
    // The launcher greps the FLAT runId key - pin its presence, not just run.id.
    val runId = Regex(""""runId":"([^"]+)"""").find(body)!!.groupValues[1]
    assertTrue(runId.startsWith("agent-"), body)
    // Canonicalized (TemporaryFolder lives under a symlinked path on macOS), and pointing at THIS
    // test's root - if a leaked TRAILBLAZE_TRAILS_DIR ever overrode the provider, this fails loudly.
    val primaryRoot = Regex(""""primaryRoot":"([^"]+)"""").find(body)!!.groupValues[1]
    assertEquals(trailsDir.canonicalPath, primaryRoot)

    val event = client.post("/trailrunner/api/companion/$runId/event") {
      header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
      setBody("kind=lifecycle&title=Step+recorded&text=tapped+the+tab")
    }
    assertEquals(HttpStatusCode.OK, event.status)
    val recorded = ExternalAgentSupervisor.events(runId).orEmpty().first { it.title == "Step recorded" }
    assertEquals(ExternalAgentEventKind.LIFECYCLE, recorded.kind)

    val disconnect = client.post("/trailrunner/api/companion/$runId/disconnect") {
      header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
      setBody("note=all+done")
    }
    assertEquals(HttpStatusCode.OK, disconnect.status)
    assertEquals(ExternalAgentSessionStatus.COMPLETED, ExternalAgentSupervisor.run(runId)?.status)
  }

  @Test
  fun `form directive, user-action, and save-recording round-trip like the CLI`() = withCompanionRoutes {
    trailsDir.resolve("myapp/tos").mkdirs()
    val connect = client.post("/trailrunner/api/companion/connect") {
      header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
      setBody("agentType=claude&folder=myapp%2Ftos")
    }
    val runId = Regex(""""runId":"([^"]+)"""").find(connect.bodyAsText())!!.groupValues[1]

    // `companion send <runId> checklist --title Plan --item Scaffold --item "Record the flow"`:
    // repeated --item joins newline-separated; the route reassembles the items array.
    val directive = client.post("/trailrunner/api/companion/$runId/directive") {
      header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
      setBody("directive=checklist&title=Plan&items=Scaffold%0ARecord+the+flow")
    }
    assertEquals(HttpStatusCode.OK, directive.status)
    val checklist = ExternalAgentSupervisor.events(runId).orEmpty().first { it.title == "checklist" }
    assertEquals(ExternalAgentEventKind.UI_COMMAND, checklist.kind)
    assertTrue(checklist.input.orEmpty().contains("Record the flow"), checklist.input.orEmpty())

    val badDirective = client.post("/trailrunner/api/companion/$runId/directive") {
      header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
      setBody("directive=explode")
    }
    assertEquals(HttpStatusCode.BadRequest, badDirective.status)

    // `--item "   "` must fail like the JSON path, not decay into "no payload" - that would
    // RETRACT the standing checklist the agent just posted, with a 200 saying all is well.
    val blankItem = client.post("/trailrunner/api/companion/$runId/directive") {
      header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
      setBody("directive=checklist&items=+++")
    }
    assertEquals(HttpStatusCode.BadRequest, blankItem.status)
    assertTrue(ExternalAgentSupervisor.run(runId)?.companion?.directives.orEmpty().containsKey("checklist"))

    val action = client.post("/trailrunner/api/companion/$runId/user-action") {
      header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
      setBody("type=handback&note=over+to+you")
    }
    assertEquals(HttpStatusCode.OK, action.status)
    val handback = ExternalAgentSupervisor.events(runId).orEmpty().first { it.title == "handback" }
    assertEquals(ExternalAgentEventKind.HUMAN_ACTION, handback.kind)
    assertTrue(handback.input.orEmpty().contains("over to you"))

    val save = client.post("/trailrunner/api/companion/$runId/save-recording") {
      header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
      setBody("variant=ios&yaml=-+config%3A%0A")
    }
    assertEquals(HttpStatusCode.OK, save.status)
    assertTrue(save.bodyAsText().contains("savedPath"))
    assertEquals("- config:\n", trailsDir.resolve("myapp/tos/ios.trail.yaml").readText())
  }

  @Test
  fun `JSON bodies round-trip like the SPA, including an explicit null payload`() = withCompanionRoutes {
    trailsDir.resolve("myapp/tos").mkdirs()
    val connect = client.post("/trailrunner/api/companion/connect") {
      header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
      setBody("""{"agentType":"claude","folder":"myapp/tos"}""")
    }
    assertEquals(HttpStatusCode.OK, connect.status, connect.bodyAsText())
    val runId = Regex(""""runId":"([^"]+)"""").find(connect.bodyAsText())!!.groupValues[1]

    val directive = client.post("/trailrunner/api/companion/$runId/directive") {
      header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
      setBody("""{"directive":"banner","payload":{"text":"Reviewing…"}}""")
    }
    assertEquals(HttpStatusCode.OK, directive.status, directive.bodyAsText())

    // The SPA sends `payload: payload || null` - an explicit JSON null must decode as absent,
    // not trip the "payload must be a JSON object" guard.
    val action = client.post("/trailrunner/api/companion/$runId/user-action") {
      header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
      setBody("""{"type":"handback","payload":null}""")
    }
    assertEquals(HttpStatusCode.OK, action.status, action.bodyAsText())

    val save = client.post("/trailrunner/api/companion/$runId/save-recording") {
      header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
      setBody("""{"variant":"ios","yaml":"- config:\n"}""")
    }
    assertEquals(HttpStatusCode.OK, save.status, save.bodyAsText())
    assertEquals("- config:\n", trailsDir.resolve("myapp/tos/ios.trail.yaml").readText())
  }

  @Test
  fun `directive payload edge cases map to 400 and save-recording 404s an unknown run`() = withCompanionRoutes {
    val connect = client.post("/trailrunner/api/companion/connect") {
      header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
      setBody("agentType=claude")
    }
    val runId = Regex(""""runId":"([^"]+)"""").find(connect.bodyAsText())!!.groupValues[1]

    // The form path's raw --payload passthrough: malformed JSON and a non-object both 400.
    val malformed = client.post("/trailrunner/api/companion/$runId/directive") {
      header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
      setBody("directive=banner&payload=%7Bnope")
    }
    assertEquals(HttpStatusCode.BadRequest, malformed.status)
    val nonObject = client.post("/trailrunner/api/companion/$runId/directive") {
      header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
      setBody("directive=banner&payload=%5B1%2C2%5D")
    }
    assertEquals(HttpStatusCode.BadRequest, nonObject.status)

    // The save route duplicates respondCompanionResult's status mapping; pin its 404 branch too.
    val unknown = client.post("/trailrunner/api/companion/no-such-run/save-recording") {
      header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
      setBody("variant=ios&yaml=x")
    }
    assertEquals(HttpStatusCode.NotFound, unknown.status)
  }

  @Test
  fun `malformed JSON and unknown agentType are 400s`() = withCompanionRoutes {
    val malformed = client.post("/trailrunner/api/companion/connect") {
      header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
      setBody("{nope")
    }
    assertEquals(HttpStatusCode.BadRequest, malformed.status)
    assertTrue(malformed.bodyAsText().startsWith("""{"ok":false"""))

    // A typo'd agent must be rejected, not silently coerced to claude.
    val typo = client.post("/trailrunner/api/companion/connect") {
      header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
      setBody("agentType=codexx")
    }
    assertEquals(HttpStatusCode.BadRequest, typo.status)
  }

  @Test
  fun `unknown run is 404 and a non-companion run is 400`() = withCompanionRoutes {
    val unknown = client.post("/trailrunner/api/companion/no-such-run/event") {
      header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
      setBody("text=hello")
    }
    assertEquals(HttpStatusCode.NotFound, unknown.status)

    val id = "companion-route-guard-" + System.nanoTime()
    seedSpawnedRun(id)
    val nonCompanion = client.post("/trailrunner/api/companion/$id/disconnect") {
      header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
      setBody("note=x")
    }
    assertEquals(HttpStatusCode.BadRequest, nonCompanion.status)
    assertTrue(nonCompanion.bodyAsText().contains("not a companion session"))

    val folderContent = client.get("/trailrunner/api/companion/$id/folder-content")
    assertEquals(HttpStatusCode.NotFound, folderContent.status)
  }

  @Test
  fun `disconnect refuses a malformed body but a missing body stays a plain disconnect`() = withCompanionRoutes {
    val connect = client.post("/trailrunner/api/companion/connect") {
      header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
      setBody("agentType=claude")
    }
    val runId = Regex(""""runId":"([^"]+)"""").find(connect.bodyAsText())!!.groupValues[1]

    // A body that is present but unreadable is a caller mistake, not a disconnect: the note it
    // meant to attach would be dropped, and a garbled request would end the session. 400, live.
    val malformed = client.post("/trailrunner/api/companion/$runId/disconnect") {
      header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
      setBody("{nope")
    }
    assertEquals(HttpStatusCode.BadRequest, malformed.status)
    assertTrue(malformed.bodyAsText().startsWith("""{"ok":false"""))
    assertEquals(ExternalAgentSessionStatus.RUNNING, ExternalAgentSupervisor.run(runId)?.status)

    // No body at all is the bare `companion disconnect`: succeeds, with no note.
    val bare = client.post("/trailrunner/api/companion/$runId/disconnect")
    assertEquals(HttpStatusCode.OK, bare.status)
    assertEquals(ExternalAgentSessionStatus.COMPLETED, ExternalAgentSupervisor.run(runId)?.status)
  }

  @Test
  fun `folder-tree lists recursively and open-file guards fire before any editor launch`() = withCompanionRoutes {
    val connect = client.post("/trailrunner/api/companion/connect") {
      header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
      setBody("agentType=claude&folder=myapp%2Ftos")
    }
    val runId = Regex(""""runId":"([^"]+)"""").find(connect.bodyAsText())!!.groupValues[1]
    File(trailsDir, "myapp/tos/shots").mkdirs()
    File(trailsDir, "myapp/tos/blaze.yaml").writeText("- config:\n")
    File(trailsDir, "myapp/tos/shots/home.png").writeText("png")

    val tree = client.get("/trailrunner/api/companion/$runId/folder-tree")
    assertEquals(HttpStatusCode.OK, tree.status)
    val body = tree.bodyAsText()
    assertTrue(body.startsWith("""{"ok":true"""), body)
    assertTrue(body.contains(""""path":"blaze.yaml""""), body)
    assertTrue(body.contains(""""path":"shots/home.png""""), body)
    assertEquals(HttpStatusCode.NotFound, client.get("/trailrunner/api/companion/no-such-run/folder-tree").status)

    // Same DNS-rebinding guard as folder-content: a non-local Host is refused before any read.
    val rebound = client.get("/trailrunner/api/companion/$runId/folder-tree") {
      header(HttpHeaders.Host, "attacker.example")
    }
    assertEquals(HttpStatusCode.Forbidden, rebound.status)

    // open-file: only the guard paths are exercised here - the happy path would launch a real
    // editor on the test machine. The resolve/containment logic itself is supervisor-tested.
    val escape = client.post("/trailrunner/api/companion/$runId/open-file") {
      header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
      setBody("""{"path":"../../secret.txt"}""")
    }
    assertEquals(HttpStatusCode.BadRequest, escape.status)
    assertTrue(escape.bodyAsText().contains("no such file"), escape.bodyAsText())
    val missingPath = client.post("/trailrunner/api/companion/$runId/open-file") {
      header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
      setBody("""{"path":""}""")
    }
    assertEquals(HttpStatusCode.BadRequest, missingPath.status)
    val unknownRun = client.post("/trailrunner/api/companion/no-such-run/open-file") {
      header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
      setBody("""{"path":"blaze.yaml"}""")
    }
    assertEquals(HttpStatusCode.NotFound, unknownRun.status)
    val crossOrigin = client.post("/trailrunner/api/companion/$runId/open-file") {
      header(HttpHeaders.Origin, "http://evil.example")
      header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
      setBody("""{"path":"blaze.yaml"}""")
    }
    assertEquals(HttpStatusCode.Forbidden, crossOrigin.status)

    // reveal-folder guards, same reasoning (success would open a Finder window here).
    assertEquals(HttpStatusCode.NotFound, client.post("/trailrunner/api/companion/no-such-run/reveal-folder").status)
    val revealCrossOrigin = client.post("/trailrunner/api/companion/$runId/reveal-folder") {
      header(HttpHeaders.Origin, "http://evil.example")
    }
    assertEquals(HttpStatusCode.Forbidden, revealCrossOrigin.status)
  }

  @Test
  fun `respond settles a deferred request through the form path like the CLI`() = withCompanionRoutes {
    val connect = client.post("/trailrunner/api/companion/connect") {
      header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
      setBody("agentType=claude&folder=myapp%2Ftos")
    }
    val runId = Regex(""""runId":"([^"]+)"""").find(connect.bodyAsText())!!.groupValues[1]
    ExternalAgentSupervisor.addAgentConsumer(runId)
    try {
      val deferred = ExternalAgentSupervisor.deferToCompanion(
        "review-trail",
        "myapp/tos",
        buildJsonObject { put("sessionId", "s1") },
      ) as DeferOutcome.Deferred

      val respond = client.post("/trailrunner/api/companion/$runId/respond") {
        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
        setBody("requestId=${deferred.requestId}&status=done&note=looks+good")
      }
      assertEquals(HttpStatusCode.OK, respond.status, respond.bodyAsText())
      val entry = ExternalAgentSupervisor.run(runId)?.companion?.requests?.get(deferred.requestId)
      assertEquals("done", entry?.status)
      assertEquals("looks good", entry?.note)

      // Unknown request and unknown run map like every other companion verb: 400 / 404.
      val unknownRequest = client.post("/trailrunner/api/companion/$runId/respond") {
        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
        setBody("requestId=r_99&status=done")
      }
      assertEquals(HttpStatusCode.BadRequest, unknownRequest.status)
      val unknownRun = client.post("/trailrunner/api/companion/no-such-run/respond") {
        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
        setBody("requestId=r_1&status=done")
      }
      assertEquals(HttpStatusCode.NotFound, unknownRun.status)
      val crossOrigin = client.post("/trailrunner/api/companion/$runId/respond") {
        header(HttpHeaders.Origin, "http://evil.example")
        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
        setBody("requestId=${deferred.requestId}&status=done")
      }
      assertEquals(HttpStatusCode.Forbidden, crossOrigin.status)
    } finally {
      ExternalAgentSupervisor.removeAgentConsumer(runId)
    }
  }

  @Test
  fun `an agent-tagged stream flips the listening signal and a plain stream does not`() = withCompanionRoutes {
    val connect = client.post("/trailrunner/api/companion/connect") {
      header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
      setBody("agentType=claude")
    }
    val runId = Regex(""""runId":"([^"]+)"""").find(connect.bodyAsText())!!.groupValues[1]
    assertFalse(ExternalAgentSupervisor.hasAgentConsumer(runId))

    // A plain stream (the SPA's) must never read as a listening agent, even while open.
    client.prepareGet("/trailrunner/api/external-agent/$runId/stream").execute { response ->
      val channel = response.bodyAsChannel()
      channel.readUTF8Line() // first frame delivered - the handler is live
      assertFalse(ExternalAgentSupervisor.hasAgentConsumer(runId))
    }

    client.prepareGet("/trailrunner/api/external-agent/$runId/stream?consumer=agent").execute { response ->
      val channel = response.bodyAsChannel()
      channel.readUTF8Line()
      assertTrue(ExternalAgentSupervisor.hasAgentConsumer(runId))
      // Ending the run closes the stream server-side; the finally must drop the registration.
      ExternalAgentSupervisor.disconnectCompanion(runId, note = null)
      while (channel.readUTF8Line() != null) { /* drain to the server's done frame */ }
    }
    assertFalse(ExternalAgentSupervisor.hasAgentConsumer(runId))
  }

  @Test
  fun `review defers to the attached agent and degrades when it is not listening`() = withCompanionRoutes {
    val sessionId = "review-defer-${System.nanoTime()}"
    val sessionDir = File(logsRepo.logsDir, sessionId).apply { mkdirs() }
    File(sessionDir, ".trailrunner-trail-id").writeText("0/myapp/tos")

    // No companion anywhere: the defer is a no-op and the normal no-provider answer comes back.
    val none = client.post("/trailrunner/api/session/$sessionId/review")
    assertTrue(none.bodyAsText().contains("trail review is not available"), none.bodyAsText())

    val connect = client.post("/trailrunner/api/companion/connect") {
      header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
      setBody("agentType=claude&folder=myapp%2Ftos")
    }
    val runId = Regex(""""runId":"([^"]+)"""").find(connect.bodyAsText())!!.groupValues[1]

    // Attached but nobody listening on the stream: tell the human, don't queue.
    val degraded = client.post("/trailrunner/api/session/$sessionId/review")
    assertTrue(degraded.bodyAsText().contains(""""degraded":true"""), degraded.bodyAsText())
    assertTrue(degraded.bodyAsText().contains("agent not listening"), degraded.bodyAsText())

    ExternalAgentSupervisor.addAgentConsumer(runId)
    try {
      val deferred = client.post("/trailrunner/api/session/$sessionId/review")
      val body = deferred.bodyAsText()
      assertTrue(body.contains(""""deferred":true"""), body)
      val requestId = Regex(""""requestId":"([^"]+)"""").find(body)!!.groupValues[1]
      assertEquals("pending", ExternalAgentSupervisor.run(runId)?.companion?.requests?.get(requestId)?.status)
      val ask = ExternalAgentSupervisor.events(runId).orEmpty().first { it.title == "agent-request" }
      assertTrue(ask.input.orEmpty().contains(""""kind":"review-trail""""), ask.input.orEmpty())
      assertTrue(ask.input.orEmpty().contains(sessionId), ask.input.orEmpty())
    } finally {
      ExternalAgentSupervisor.removeAgentConsumer(runId)
    }
  }

  @Test
  fun `propose defers by folder and only 503s when nothing can answer`() = withCompanionRoutes {
    // Body validation now precedes the provider check: a bad ask is a 400 even with no proposer.
    val bad = client.post("/trailrunner/api/blaze/propose") {
      header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
      setBody("""{}""")
    }
    assertEquals(HttpStatusCode.BadRequest, bad.status)
    val unanswerable = client.post("/trailrunner/api/blaze/propose") {
      header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
      setBody("""{"objective":"log in"}""")
    }
    assertEquals(HttpStatusCode.ServiceUnavailable, unanswerable.status)

    val connect = client.post("/trailrunner/api/companion/connect") {
      header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
      setBody("agentType=claude&folder=myapp%2Ftos")
    }
    val runId = Regex(""""runId":"([^"]+)"""").find(connect.bodyAsText())!!.groupValues[1]
    ExternalAgentSupervisor.addAgentConsumer(runId)
    try {
      val byFolder = client.post("/trailrunner/api/blaze/propose") {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody("""{"objective":"log in","folder":"myapp/tos"}""")
      }
      assertEquals(HttpStatusCode.OK, byFolder.status)
      assertTrue(byFolder.bodyAsText().contains(""""deferred":true"""), byFolder.bodyAsText())
      // A folderless ask still finds the sole live companion.
      val folderless = client.post("/trailrunner/api/blaze/propose") {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody("""{"objective":"add assertions"}""")
      }
      assertTrue(folderless.bodyAsText().contains(""""deferred":true"""), folderless.bodyAsText())
      val ask = ExternalAgentSupervisor.events(runId).orEmpty().first { it.title == "agent-request" }
      assertTrue(ask.input.orEmpty().contains(""""kind":"propose-steps""""), ask.input.orEmpty())
      assertTrue(ask.input.orEmpty().contains("log in"), ask.input.orEmpty())
    } finally {
      ExternalAgentSupervisor.removeAgentConsumer(runId)
    }
  }

  @Test
  fun `cross-origin form posts are refused`() = withCompanionRoutes {
    val response = client.post("/trailrunner/api/companion/connect") {
      header(HttpHeaders.Origin, "http://evil.example")
      header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
      setBody("agentType=claude")
    }
    assertEquals(HttpStatusCode.Forbidden, response.status)
    assertTrue(response.bodyAsText().startsWith("""{"ok":false"""))

    // The guard covers every mutating verb, not just connect.
    val event = client.post("/trailrunner/api/companion/some-run/event") {
      header(HttpHeaders.Origin, "http://evil.example")
      header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
      setBody("text=spam")
    }
    assertEquals(HttpStatusCode.Forbidden, event.status)
    for (verb in listOf("directive", "user-action", "save-recording")) {
      val blocked = client.post("/trailrunner/api/companion/some-run/$verb") {
        header(HttpHeaders.Origin, "http://evil.example")
        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
        setBody("directive=banner")
      }
      assertEquals(HttpStatusCode.Forbidden, blocked.status, verb)
    }

    // The allowlist must be ANCHORED: a hostname that merely starts with "localhost" is an attack.
    val prefixed = client.post("/trailrunner/api/companion/connect") {
      header(HttpHeaders.Origin, "http://localhost.evil.com")
      header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
      setBody("agentType=claude")
    }
    assertEquals(HttpStatusCode.Forbidden, prefixed.status)

    // The literal `Origin: null` (sandboxed iframe) must fail closed, not parse to a default host.
    val nullOrigin = client.post("/trailrunner/api/companion/connect") {
      header(HttpHeaders.Origin, "null")
      header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
      setBody("agentType=claude")
    }
    assertEquals(HttpStatusCode.Forbidden, nullOrigin.status)

    // The SPA's own origin (any localhost host, whatever the port) stays allowed.
    val local = client.post("/trailrunner/api/companion/no-such-run/event") {
      header(HttpHeaders.Origin, "http://localhost:52525")
      header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
      setBody("text=hi")
    }
    assertEquals(HttpStatusCode.NotFound, local.status)
  }

  @Test
  fun `folder-content refuses a non-local Host (DNS-rebinding guard)`() = withCompanionRoutes {
    // Browsers omit Origin on same-origin GETs, so the read routes lean on the Host header: a page
    // rebound to 127.0.0.1 still sends its own hostname here and must be refused.
    val rebound = client.get("/trailrunner/api/companion/some-run/folder-content") {
      header(HttpHeaders.Host, "attacker.example")
    }
    assertEquals(HttpStatusCode.Forbidden, rebound.status)
    assertTrue(rebound.bodyAsText().startsWith("""{"ok":false"""))

    // A loopback Host is the SPA's own request and passes through to the normal 404.
    val local = client.get("/trailrunner/api/companion/some-run/folder-content") {
      header(HttpHeaders.Host, "localhost:52525")
    }
    assertEquals(HttpStatusCode.NotFound, local.status)
  }
}
