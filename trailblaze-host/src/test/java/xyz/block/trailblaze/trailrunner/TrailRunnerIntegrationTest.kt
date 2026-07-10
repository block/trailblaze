package xyz.block.trailblaze.trailrunner

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.llm.config.WorkspaceConfigDirHolder
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrailRunnerIntegrationTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private lateinit var trailsDir: File
  private lateinit var logsDir: File
  private lateinit var logsRepo: LogsRepo

  private val addedExtraRoots = mutableListOf<String>()

  @Before
  fun setUp() {
    trailsDir = tmp.newFolder("trails")
    logsDir = tmp.newFolder("logs")
    logsRepo = LogsRepo(logsDir = logsDir, watchFileSystem = false)
  }

  @After
  fun restoreExtraRoots() {
    for (path in addedExtraRoots) {
      ExtraTrailRoots.remove(path)
    }
  }

  private fun writeTrail(relativePath: String, title: String = "Test trail"): File {
    val file = File(trailsDir, relativePath)
    file.parentFile?.mkdirs()
    file.writeText(
      """
      - config:
          id: test/trail
          title: "$title"
          target: myapp
          platform: ios
          tags: [smoke]
      - prompts:
        - step: Launch the app signed in
          recording:
            tools:
            - myapp_ios_signInViaUI: { email: x@y.z, password: secret }
        - verify: Money tab is visible
          recording:
            tools:
            - assertVisibleBySelector: { reason: "", nodeSelector: { iosMaestro: { resourceIdRegex: balance_tab_button } } }
      """.trimIndent(),
    )
    return file
  }

  private fun zipBytes(entries: Map<String, String>): ByteArray =
    ByteArrayOutputStream().use { baos ->
      ZipOutputStream(baos).use { zip ->
        entries.forEach { (name, body) ->
          zip.putNextEntry(ZipEntry(name))
          zip.write(body.toByteArray())
          zip.closeEntry()
        }
      }
      baos.toByteArray()
    }

  private fun zipEntryNames(bytes: ByteArray): Set<String> =
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
      buildSet {
        var entry = zip.nextEntry
        while (entry != null) {
          add(entry.name)
          entry = zip.nextEntry
        }
      }
    }

  private fun withTrailRunner(
    settingsRepo: TrailblazeSettingsRepo? = null,
    integrationActionHandler: (suspend (integrationId: String, actionId: String) -> Unit)? = null,
    // Lets a test drive the full extension seam (e.g. a throwing integrationsProvider). When null,
    // an extension carrying just [integrationActionHandler] is used, matching the default wiring.
    extension: TrailRunnerExtension? = null,
    block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit,
  ) =
    testApplication {
      application {
        install(ContentNegotiation) { json(TrailblazeJsonInstance) }
        // The production daemon (TrailblazeMcpServer) installs SSE; the test app must too, since
        // TrailRunnerEndpoint.register wires sessionStreamRoutes (an `sse {}` route).
        install(SSE)
        // Likewise WebSockets — register wires lspRoutes (a `webSocket {}` route), and the route
        // builder requires the plugin at registration time. The daemon installs it for the
        // `/rpc-ws` device endpoint; the TypeScript language-server bridge rides the same plugin.
        install(WebSockets)
        routing {
          TrailRunnerEndpoint.register(
            this,
            trailsRootProvider = { trailsDir },
            logsRepo = logsRepo,
            settingsRepo = settingsRepo,
            extension = extension
              ?: object : TrailRunnerExtension {
                override val integrationActionHandler = integrationActionHandler
              },
          )
        }
      }
      block()
    }

  /** A settings repo backed by a temp file, so PUT /settings + the SettingsPatchRequest RPC can be
   *  exercised end-to-end. Mirrors the known-good construction in TestTrailblazeAppDependencies. */
  private fun newSettingsRepo(): TrailblazeSettingsRepo =
    TrailblazeSettingsRepo(
      settingsFile = File(tmp.newFolder("settings"), "settings.json"),
      initialConfig = SavedTrailblazeAppConfig(
        selectedTrailblazeDriverTypes = mapOf(
          TrailblazeDevicePlatform.ANDROID to TrailblazeDriverType.DEFAULT_ANDROID,
          TrailblazeDevicePlatform.IOS to TrailblazeDriverType.IOS_HOST,
        ),
        testingEnvironment = TrailblazeServerState.TestingEnvironment.MOBILE,
      ),
      defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
      allTargetApps = { emptySet() },
      supportedDriverTypes = setOf(
        TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        TrailblazeDriverType.IOS_HOST,
      ),
    )

  @Test
  fun `LSP tool-schema route serves the tool-definition JSON schema`() = withTrailRunner {
    // The YAML editor points yaml-language-server at this URL via `yaml.schemas`, so the route must
    // serve valid JSON describing the `.tool.yaml` structure. Guards that the schema resource ships in
    // the JAR and is well-formed (a malformed/missing schema silently disables YAML validation).
    val response = client.get("/trailrunner/api/lsp/tool-schema.json")
    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.bodyAsText()
    // Parse as JSON (throws on malformed) and assert it declares the key tool-definition properties.
    val parsed = TrailblazeJsonInstance.parseToJsonElement(body)
    assertTrue(body.contains("\"id\""), "schema should describe the required `id` property")
    assertTrue(body.contains("\"parameters\"") && body.contains("\"shortcut\""), "schema should describe tool keys")
    assertTrue(parsed is kotlinx.serialization.json.JsonObject, "schema root should be a JSON object")
  }

  @Test
  fun `LSP trail-schema route serves the trail-definition JSON schema`() = withTrailRunner {
    // The trail editor points yaml-language-server at this URL, scoped by `?target=`. The route must
    // serve valid JSON describing the `.trail.yaml` structure even with no resolvable target (it falls
    // back to the whole catalog). Guards that the endpoint is wired and well-formed.
    val response = client.get("/trailrunner/api/lsp/trail-schema.json?target=nonexistent&platform=android")
    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.bodyAsText()
    val parsed = TrailblazeJsonInstance.parseToJsonElement(body)
    assertTrue(parsed is kotlinx.serialization.json.JsonObject, "schema root should be a JSON object")
    // The trail schema is a oneOf over the v1-list and unified-mapping shapes, and models `recording`.
    assertTrue(body.contains("\"oneOf\""), "schema should be a oneOf over the two trail shapes")
    assertTrue(body.contains("\"recording\""), "schema should describe the recording block")
  }

  @Test
  fun `LSP file-uri with no path returns base URLs and a null fileUri`() = withTrailRunner {
    // A path-less request is how the trail editor (in-memory .trail.yaml, no on-disk source) asks for
    // just the daemon-local schema base — 200 with a null fileUri, not a 404.
    val response = client.get("/trailrunner/api/lsp/file-uri")
    assertEquals(HttpStatusCode.OK, response.status)
    val parsed = TrailblazeJsonInstance.parseToJsonElement(response.bodyAsText()) as kotlinx.serialization.json.JsonObject
    assertTrue(parsed["fileUri"] == null || parsed["fileUri"] is kotlinx.serialization.json.JsonNull, "fileUri should be null for a path-less request")
  }

  @Test
  fun `LSP file-uri returns 404 for an unresolvable path`() = withTrailRunner {
    val response = client.get("/trailrunner/api/lsp/file-uri?path=not/a/real/tool.ts")
    assertEquals(HttpStatusCode.NotFound, response.status)
  }

  @Test
  fun `LSP file-uri returns the canonical file URI for a resolvable scripted tool`() {
    // Pin a fixture workspace so ToolSourceFiles resolves the tool under <config>/trailmaps — the
    // documented WorkspaceConfigDirHolder test pattern (swap the resolver, restore it after).
    val configDir = File(trailsDir, "config")
    val toolRel = "demo/tools/sample.ts"
    val toolFile = File(configDir, "trailmaps/$toolRel").apply {
      parentFile.mkdirs()
      writeText("import { trailblaze } from \"@trailblaze/scripting\";\n")
    }
    val previousResolver = WorkspaceConfigDirHolder.resolver
    WorkspaceConfigDirHolder.resolver = { configDir }
    try {
      withTrailRunner {
        val sourcePath = "${TrailblazeConfigPaths.TRAILMAPS_DIR}/$toolRel"
        val response = client.get("/trailrunner/api/lsp/file-uri?path=$sourcePath")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = TrailblazeJsonInstance.decodeFromString(LspFileUriResponse.serializer(), response.bodyAsText())
        // The editor opens the document at this URI; it must be the tool's REAL on-disk file:// URI so
        // vtsls walks up to the per-trailmap tsconfig. Canonical form is `file:///…` (nio Path.toUri).
        assertTrue(body.fileUri?.startsWith("file:") == true, "expected a file: URI, got ${body.fileUri}")
        assertTrue(body.fileUri?.endsWith("/tools/sample.ts") == true, "expected the tool path, got ${body.fileUri}")
        assertTrue(body.workspaceUri?.startsWith("file:") == true, "expected a workspace file: URI, got ${body.workspaceUri}")
      }
    } finally {
      WorkspaceConfigDirHolder.resolver = previousResolver
      toolFile.delete()
    }
  }

  @Test
  fun `GET Trail Runner slash returns 200 with html content`() = withTrailRunner {
    val response = client.get("/trailrunner/")
    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.bodyAsText()
    assertTrue(
      body.contains("<!DOCTYPE", ignoreCase = true) || body.contains("<html", ignoreCase = true),
      "expected HTML response from /trailrunner/, got: ${body.take(200)}",
    )
  }

  @Test
  fun `GET Trail Runner without trailing slash ends up at index html`() = withTrailRunner {
    val response = client.get("/trailrunner")
    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.bodyAsText()
    assertTrue(
      body.contains("<!DOCTYPE", ignoreCase = true) || body.contains("<html", ignoreCase = true),
      "expected HTML after following redirect from /trailrunner",
    )
  }

  // The open-source build supplies no integrationsProvider (DefaultTrailRunnerExtension), so the
  // endpoint must degrade to an empty list rather than error.
  @Test
  fun `GET integrations returns empty list when no provider is wired`() = withTrailRunner {
    val response = client.get("/trailrunner/api/integrations")
    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals("""{"integrations":[]}""", response.bodyAsText())
  }

  // A downstream integrationsProvider that throws must not crash GET /api/integrations — the seam
  // call is guarded, so the endpoint degrades to an empty list (observable contract of the guard).
  @Test
  fun `GET integrations degrades to empty when the provider throws`() {
    val throwingExtension = object : TrailRunnerExtension {
      override val integrationsProvider: (() -> List<IntegrationDto>)? = { error("boom") }
    }
    withTrailRunner(extension = throwingExtension) {
      val response = client.get("/trailrunner/api/integrations")
      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals("""{"integrations":[]}""", response.bodyAsText())
    }
  }

  @Test
  fun `GET Trail Runner index injects a base href so relative assets resolve under the prefix`() = withTrailRunner {
    // The SPA references its assets with RELATIVE paths (./app, ./styles). Reached at
    // /trailrunner (no trailing slash) — which is what happens behind a cloud-workstation
    // preview proxy — the browser would resolve those against the host ROOT (/app/..., 404).
    // The served index must carry an explicit <base href="/trailrunner/"> so relative assets
    // resolve under the prefix regardless of the trailing slash.
    val body = client.get("/trailrunner/").bodyAsText()
    assertTrue(
      body.contains("<base href=\"/trailrunner/\""),
      "index.html must carry an injected <base href=\"/trailrunner/\">; got head: ${body.take(300)}",
    )
    // The base tag must appear before the first relative asset reference, otherwise the parser
    // resolves that asset before it has seen the base.
    val baseIdx = body.indexOf("<base ")
    val firstRelativeAssetIdx = body.indexOf("\"./")
    assertTrue(
      baseIdx in 0 until firstRelativeAssetIdx,
      "the <base> tag must precede the first relative asset (base=$baseIdx, firstAsset=$firstRelativeAssetIdx)",
    )
  }

  @Test
  fun `GET Trail Runner api trails returns 200 JSON`() = withTrailRunner {
    writeTrail("smoke/login.trail.yaml")

    val response = client.get("/trailrunner/api/trails")

    assertEquals(HttpStatusCode.OK, response.status)
    assertTrue(
      response.contentType()?.match(ContentType.Application.Json) == true,
      "expected JSON content type",
    )
  }

  // ── POST /api/folder/migrate-unified — status-code mapping the modal relies on ──

  @Test
  fun `migrate-unified without id returns 400`() = withTrailRunner {
    val response = client.post("/trailrunner/api/folder/migrate-unified")
    assertEquals(HttpStatusCode.BadRequest, response.status)
  }

  @Test
  fun `migrate-unified for an unknown folder returns 404`() = withTrailRunner {
    val response = client.post("/trailrunner/api/folder/migrate-unified?id=0/nope/missing")
    assertEquals(HttpStatusCode.NotFound, response.status)
  }

  @Test
  fun `migrate-unified maps a migrator refusal to 400 without touching files`() = withTrailRunner {
    // A folder of already-unified trails is the refusal case the folder back-arrow makes reachable.
    val file = File(trailsDir, "flows/login.trail.yaml")
    file.parentFile?.mkdirs()
    file.writeText("config: {id: login}\ntrail:\n  - step: Log in\n")

    val response = client.post("/trailrunner/api/folder/migrate-unified?id=0/flows")

    assertEquals(HttpStatusCode.BadRequest, response.status)
    assertTrue(file.isFile, "refusal must not delete or rewrite inputs")
  }

  @Test
  fun `migrate-unified succeeds for a v1 bundle and reports the written file`() = withTrailRunner {
    writeTrail("case_1/android-phone.trail.yaml")

    val response = client.post("/trailrunner/api/folder/migrate-unified?id=0/case_1")

    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.bodyAsText()
    assertTrue(body.contains("case_1.trail.yaml"), "expected outputName in body: ${body.take(300)}")
    assertTrue(File(trailsDir, "case_1/case_1.trail.yaml").isFile)
    assertTrue(!File(trailsDir, "case_1/android-phone.trail.yaml").exists(), "input should be consumed")
  }

  @Test
  fun `GET Trail Runner api trails body contains fixture trail`() = withTrailRunner {
    writeTrail("smoke/login.trail.yaml", title = "Login smoke")

    val body = client.get("/trailrunner/api/trails").bodyAsText()

    assertTrue(body.contains("Login smoke"), "expected title in response body")
    assertTrue(body.contains("myapp"), "expected target in response body")
    assertTrue(body.contains("ios"), "expected platform in response body")
  }

  @Test
  fun `GET Trail Runner api trails trail id starts with 0 slash`() = withTrailRunner {
    writeTrail("login.trail.yaml")

    val body = client.get("/trailrunner/api/trails").bodyAsText()

    assertTrue(body.contains("\"0/login\""), "expected id '0/login' in body: ${body.take(500)}")
  }

  @Test
  fun `GET Trail Runner api trails returns empty trails list when directory is empty`() = withTrailRunner {
    val body = client.get("/trailrunner/api/trails").bodyAsText()

    assertTrue(body.contains("\"trails\""), "response should have 'trails' key")
    assertTrue(body.contains("[]"), "trails list should be empty")
  }

  @Test
  fun `GET Trail Runner api trails lists an empty directory under folders`() = withTrailRunner {
    writeTrail("myapp/login/ios-iphone.trail.yaml")
    File(trailsDir, "myapp/new-section").mkdirs()

    val body = client.get("/trailrunner/api/trails").bodyAsText()

    assertTrue(body.contains("\"folders\""), "response should have 'folders' key")
    val foldersJson = body.substringAfter("\"folders\"")
    assertTrue(foldersJson.contains("trails/myapp/new-section"), "empty directory should be listed with the root label")
    assertTrue(!foldersJson.contains("trails/myapp/login"), "directories holding trails come from trail paths, not folders")
  }

  @Test
  fun `POST Trail Runner api trails mkdir creates an empty directory`() = withTrailRunner {
    // Anchor the temp workspace: resolvePrimaryRoot only honors a configured root
    // that already contains trail files, else it falls back to $cwd/trails.
    writeTrail("anchor/anchor.trail.yaml")

    val response = client.post("/trailrunner/api/trails/mkdir") {
      contentType(ContentType.Application.Json)
      setBody("""{"path":"myapp/new-section"}""")
    }

    assertEquals(HttpStatusCode.OK, response.status)
    assertTrue(File(trailsDir, "myapp/new-section").isDirectory, "directory should exist on disk")
  }

  @Test
  fun `POST Trail Runner api trails mkdir rejects path traversal`() = withTrailRunner {
    val response = client.post("/trailrunner/api/trails/mkdir") {
      contentType(ContentType.Application.Json)
      setBody("""{"path":"../escape"}""")
    }

    assertEquals(HttpStatusCode.BadRequest, response.status)
    assertTrue(!File(trailsDir.parentFile, "escape").exists(), "traversal target must not be created")
  }

  @Test
  fun `POST Trail Runner api trails mkdir rejects an existing directory`() = withTrailRunner {
    writeTrail("anchor/anchor.trail.yaml")
    File(trailsDir, "myapp").mkdirs()

    val response = client.post("/trailrunner/api/trails/mkdir") {
      contentType(ContentType.Application.Json)
      setBody("""{"path":"myapp"}""")
    }

    assertTrue(response.bodyAsText().contains("already exists"), "existing directory should be rejected")
  }

  @Test
  fun `GET Trail Runner api trail returns 200 with steps for existing trail`() = withTrailRunner {
    writeTrail("login.trail.yaml")

    val response = client.get("/trailrunner/api/trail/0/login")

    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.bodyAsText()
    assertTrue(body.contains("steps"), "expected 'steps' in trail detail response")
    assertTrue(body.contains("myapp_ios_signInViaUI"), "expected tool name in steps")
  }

  @Test
  fun `GET Trail Runner api trail returns 404 for unknown id`() = withTrailRunner {
    val response = client.get("/trailrunner/api/trail/0/does-not-exist")

    assertEquals(HttpStatusCode.NotFound, response.status)
  }

  @Test
  fun `GET Trail Runner api trail step and verify kinds are present`() = withTrailRunner {
    writeTrail("login.trail.yaml")

    val body = client.get("/trailrunner/api/trail/0/login").bodyAsText()

    assertTrue(body.contains("\"step\""), "expected step kind")
    assertTrue(body.contains("\"verify\""), "expected verify kind")
  }

  @Test
  fun `GET Trail Runner api trail for nested path returns 200`() = withTrailRunner {
    writeTrail("myapp/cold-boot/my-trail.trail.yaml", title = "Cold boot")

    val response = client.get("/trailrunner/api/trail/0/myapp/cold-boot/my-trail")

    assertEquals(HttpStatusCode.OK, response.status)
    assertTrue(response.bodyAsText().contains("Cold boot"))
  }

  @Test
  fun `bare unified trail_yaml is listed by api trails and resolvable by api trail id`() {
    // The migrated unified shape is a BARE `trail.yaml` (no `<device>` prefix), so it does NOT end
    // in `.trail.yaml`. Drive the two routes the browser hits: the index must list it with the
    // directory-derived id `.../trail`, and the detail route must resolve that id back to the file —
    // the full walk → build → resolveTrailFile round-trip over HTTP, not just the unit-level resolver.
    val caseDir = File(trailsDir, "regression/case_5374124").also { it.mkdirs() }
    File(caseDir, "trail.yaml").writeText(
      """
      config:
        id: regression/case_5374124
        title: Cold boot flow
        target: myapp
      trail:
        - step: Open the app
      """.trimIndent(),
    )

    withTrailRunner {
      val index = client.get("/trailrunner/api/trails").bodyAsText()
      assertTrue(
        index.contains("\"0/regression/case_5374124/trail\""),
        "index should list the bare trail id: ${index.take(500)}",
      )
      assertTrue(index.contains("Cold boot flow"), "index should carry the config title: ${index.take(500)}")

      val detail = client.get("/trailrunner/api/trail/0/regression/case_5374124/trail")
      assertEquals(HttpStatusCode.OK, detail.status)
      assertTrue(
        detail.bodyAsText().contains("Open the app"),
        "detail route should resolve the bare file and render its step",
      )
    }
  }

  @Test
  fun `api trails edited surfaces a modified bare trail_yaml`() {
    // `buildEditedTrailsResponse` runs `git status --porcelain` under the workspace and keeps only
    // trail-shaped basenames. Guards that a migrated bare `trail.yaml` shows up under edited-only
    // filtering — the previous `endsWith(".trail.yaml")` filter dropped it (bare != `.trail.yaml`).
    val caseDir = File(trailsDir, "regression/case_5374124").also { it.mkdirs() }
    val bare = File(caseDir, "trail.yaml")
    bare.writeText("config:\n  id: regression/case_5374124\ntrail:\n  - step: Open the app\n")

    fun git(vararg args: String) {
      val p = ProcessBuilder(listOf("git", "-C", trailsDir.absolutePath) + args)
        .redirectErrorStream(true)
        .start()
      val output = p.inputStream.bufferedReader().readText()
      check(p.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
    }
    git("init", "-q")
    git("add", "-A")
    git("-c", "user.email=t@t.t", "-c", "user.name=t", "-c", "commit.gpgsign=false", "commit", "-q", "-m", "seed")
    // Modify the now-committed bare trail so `git status --porcelain` reports it as ` M`.
    bare.appendText("  - step: And another\n")

    withTrailRunner {
      val response = client.get("/trailrunner/api/trails/edited")
      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.bodyAsText()
      assertTrue(
        body.contains("regression/case_5374124/trail.yaml"),
        "edited list should include the modified bare trail: $body",
      )
    }
  }

  @Test
  fun `GET Trail Runner api trails roots returns 200 with primary`() = withTrailRunner {
    val response = client.get("/trailrunner/api/trails/roots")

    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.bodyAsText()
    assertTrue(body.contains("primary"), "expected 'primary' key in roots response")
  }

  @Test
  fun `PUT Trail Runner api tool-source with no resolvable target returns 404`() = withTrailRunner {
    val response = client.put("/trailrunner/api/tool-source") {
      contentType(ContentType.Application.Json)
      setBody("""{"className":"no.such.ClassXyz","source":"object X"}""")
    }
    assertEquals(HttpStatusCode.NotFound, response.status)
  }

  @Test
  fun `PUT Trail Runner api tool-source with blank source returns 400`() = withTrailRunner {
    val response = client.put("/trailrunner/api/tool-source") {
      contentType(ContentType.Application.Json)
      setBody("""{"className":"some.Class","source":""}""")
    }
    assertEquals(HttpStatusCode.BadRequest, response.status)
  }

  @Test
  fun `POST session cancel returns 503 when no deviceManager is wired`() = withTrailRunner {
    // No deviceManager is wired in this harness, so cancel can't reach a device. That must stay a
    // 503 (service unavailable), distinct from a 404 for an unknown session id.
    val response = client.post("/trailrunner/api/session/sess_x/cancel")
    assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
  }

  @Test
  fun `POST session delete returns 404 for an unknown session id`() = withTrailRunner {
    val response = client.post("/trailrunner/api/session/no-such-session/delete")
    assertEquals(HttpStatusCode.NotFound, response.status)
  }

  @Test
  fun `POST trailmap component rejects an unknown kind with 400`() = withTrailRunner {
    val response = client.post("/trailrunner/api/trailmap/component") {
      contentType(ContentType.Application.Json)
      setBody("""{"trailmap":"sample","kind":"bogus","name":"x"}""")
    }
    assertEquals(HttpStatusCode.BadRequest, response.status)
  }

  @Test
  fun `POST trailmap component returns 409 for an unknown trailmap`() = withTrailRunner {
    // A well-formed request (known kind, valid name) whose trailmap doesn't exist is a conflict,
    // not a bad request — 409, distinct from the 400 validation rejections above.
    val response = client.post("/trailrunner/api/trailmap/component") {
      contentType(ContentType.Application.Json)
      setBody("""{"trailmap":"no-such-trailmap","kind":"trailheads","name":"home"}""")
    }
    assertEquals(HttpStatusCode.Conflict, response.status)
  }

  @Test
  fun `POST Trail Runner api trail validate accepts a well-formed trail`() = withTrailRunner {
    val response = client.post("/trailrunner/api/trail/validate") {
      contentType(ContentType.Application.Json)
      setBody("""{"yaml":"- config:\n    title: ok\n- prompts:\n  - step: do the thing"}""")
    }
    assertEquals(HttpStatusCode.OK, response.status)
    assertTrue(response.bodyAsText().replace(" ", "").contains("\"valid\":true"))
  }

  @Test
  fun `POST Trail Runner api trail validate flags malformed yaml with an error`() = withTrailRunner {
    val response = client.post("/trailrunner/api/trail/validate") {
      contentType(ContentType.Application.Json)
      setBody("""{"yaml":"- config:\n  title: [unclosed"}""")
    }
    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.bodyAsText().replace(" ", "")
    assertTrue(body.contains("\"valid\":false"), "expected invalid: $body")
    assertTrue(body.contains("errors"), "expected errors list: $body")
  }

  @Test
  fun `POST Trail Runner api trail validate with empty yaml is invalid`() = withTrailRunner {
    val response = client.post("/trailrunner/api/trail/validate") {
      contentType(ContentType.Application.Json)
      setBody("""{"yaml":""}""")
    }
    assertTrue(response.bodyAsText().replace(" ", "").contains("\"valid\":false"))
  }

  @Test
  fun `PUT Trail Runner api trail overwrites an existing trail in place`() = withTrailRunner {
    val file = writeTrail("editable.trail.yaml", title = "Before edit")
    val newYaml = "- config:\n    title: After edit\n- prompts:\n  - step: do the thing"
    val response = client.put("/trailrunner/api/trail/0/editable") {
      contentType(ContentType.Application.Json)
      setBody("""{"yaml":"$newYaml"}""")
    }
    assertEquals(HttpStatusCode.OK, response.status)
    assertTrue(response.bodyAsText().replace(" ", "").contains("\"success\":true"))
    assertTrue(file.readText().contains("After edit"), "file should be overwritten in place")
  }

  @Test
  fun `PUT Trail Runner api trail returns 404 for unknown id`() = withTrailRunner {
    val response = client.put("/trailrunner/api/trail/0/no-such-trail") {
      contentType(ContentType.Application.Json)
      setBody("""{"yaml":"- config:\n    title: x"}""")
    }
    assertEquals(HttpStatusCode.NotFound, response.status)
  }

  @Test
  fun `PUT Trail Runner api trail rejects path traversal`() = withTrailRunner {
    val response = client.put("/trailrunner/api/trail/0/..%2F..%2Fescape") {
      contentType(ContentType.Application.Json)
      setBody("""{"yaml":"- config:\n    title: x"}""")
    }
    assertTrue(response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.BadRequest,
      "traversal id must not resolve (got ${'$'}{response.status})")
  }

  @Test
  fun `PUT Trail Runner api trail with empty yaml returns 400`() = withTrailRunner {
    writeTrail("empty-body.trail.yaml")
    val response = client.put("/trailrunner/api/trail/0/empty-body") {
      contentType(ContentType.Application.Json)
      setBody("""{"yaml":""}""")
    }
    assertEquals(HttpStatusCode.BadRequest, response.status)
  }

  @Test
  fun `POST Trail Runner api trails roots adds a valid directory`() = withTrailRunner {
    val extraDir = tmp.newFolder("extra-trails")
    addedExtraRoots += extraDir.canonicalPath

    val response = client.post("/trailrunner/api/trails/roots") {
      contentType(ContentType.Application.Json)
      setBody("""{"path":"${extraDir.absolutePath.replace("\\", "\\\\")}"}""")
    }

    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.bodyAsText()
    assertTrue(body.contains("extras"), "response should list extras")
  }

  @Test
  fun `POST Trail Runner api trails roots rejects non-existent path with 400`() = withTrailRunner {
    val response = client.post("/trailrunner/api/trails/roots") {
      contentType(ContentType.Application.Json)
      setBody("""{"path":"/tmp/no-such-dir-xyz-trailblaze-test"}""")
    }

    assertEquals(HttpStatusCode.BadRequest, response.status)
  }

  @Test
  fun `POST Trail Runner api trails roots with empty path returns 400`() = withTrailRunner {
    val response = client.post("/trailrunner/api/trails/roots") {
      contentType(ContentType.Application.Json)
      setBody("""{"path":""}""")
    }

    assertEquals(HttpStatusCode.BadRequest, response.status)
  }

  @Test
  fun `DELETE Trail Runner api trails roots removes a previously added root`() = withTrailRunner {
    val extraDir = tmp.newFolder("to-delete-trails")
    val canonical = extraDir.canonicalPath

    client.post("/trailrunner/api/trails/roots") {
      contentType(ContentType.Application.Json)
      setBody("""{"path":"${extraDir.absolutePath.replace("\\", "\\\\")}"}""")
    }

    val deleteResponse = client.delete("/trailrunner/api/trails/roots") {
      contentType(ContentType.Application.Json)
      setBody("""{"path":"${canonical.replace("\\", "\\\\")}"}""")
    }

    assertEquals(HttpStatusCode.OK, deleteResponse.status)
  }

  @Test
  fun `GET Trail Runner api sessions returns 200 JSON with sessions key`() = withTrailRunner {
    val response = client.get("/trailrunner/api/sessions")

    assertEquals(HttpStatusCode.OK, response.status)
    assertTrue(response.bodyAsText().contains("\"sessions\""))
  }

  @Test
  fun `GET session archive exports the session folder as zip`() = withTrailRunner {
    File(logsDir, "sess_zip/events").mkdirs()
    File(logsDir, "sess_zip/0001.json").writeText("""{"ok":true}""")
    File(logsDir, "sess_zip/events/analytics.json.ndjson").writeText("""{"timeMs":1,"data":{"event":"x"}}""")

    val response = client.get("/trailrunner/api/session/sess_zip/export.zip")

    assertEquals(HttpStatusCode.OK, response.status)
    assertTrue(
      response.contentType()?.match(ContentType.parse("application/zip")) == true,
      "expected zip content type, got ${response.contentType()}",
    )
    val names = zipEntryNames(response.bodyAsBytes())
    assertTrue("sess_zip/0001.json" in names, "expected root log file in archive: $names")
    assertTrue("sess_zip/events/analytics.json.ndjson" in names, "expected nested event file in archive: $names")
  }

  @Test
  fun `POST session import accepts a session zip archive`() = withTrailRunner {
    val bytes = zipBytes(mapOf("import_sess/0001.json" to """{"ok":true}"""))

    val response = client.post("/trailrunner/api/session/import") {
      contentType(ContentType.parse("application/zip"))
      setBody(bytes)
    }

    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.bodyAsText()
    assertTrue(body.contains("\"ok\":true"), "expected successful import: $body")
    assertTrue(body.contains("\"sessionId\":\"import_sess\""), "expected imported session id: $body")
    assertTrue(File(logsDir, "import_sess/0001.json").isFile, "import should write archive contents into logs")
  }

  @Test
  fun `POST sessions clear deletes every session folder`() = withTrailRunner {
    File(logsDir, "sess_one").mkdirs()
    File(logsDir, "sess_two").mkdirs()

    val missingConfirmation = client.post("/trailrunner/api/sessions/clear")
    assertEquals(HttpStatusCode.BadRequest, missingConfirmation.status)
    assertTrue(File(logsDir, "sess_one").exists(), "first session should remain without confirmation")
    assertTrue(File(logsDir, "sess_two").exists(), "second session should remain without confirmation")

    val response = client.post("/trailrunner/api/sessions/clear") {
      contentType(ContentType.Application.Json)
      setBody("""{"confirm":true}""")
    }

    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.bodyAsText()
    assertTrue(body.contains("\"deleted\":2"), "expected deleted count, got: $body")
    assertTrue(!File(logsDir, "sess_one").exists(), "first session should be deleted")
    assertTrue(!File(logsDir, "sess_two").exists(), "second session should be deleted")
  }

  @Test
  fun `GET analytics returns 404 for unknown session`() = withTrailRunner {
    val response = client.get("/trailrunner/api/session/no_such_session/analytics")
    assertEquals(HttpStatusCode.NotFound, response.status)
  }

  @Test
  fun `GET analytics returns available false when no provider is wired`() = withTrailRunner {
    File(logsDir, "sess_x").mkdirs()
    val response = client.get("/trailrunner/api/session/sess_x/analytics")
    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.bodyAsText()
    assertTrue(body.contains("\"available\":false"), "expected available:false, got: $body")
  }

  @Test
  fun `GET events returns 404 for unknown session`() = withTrailRunner {
    val response = client.get("/trailrunner/api/session/no_such_session/events")
    assertEquals(HttpStatusCode.NotFound, response.status)
  }

  @Test
  fun `GET events returns available false when no events dir`() = withTrailRunner {
    File(logsDir, "sess_e0").mkdirs()
    val response = client.get("/trailrunner/api/session/sess_e0/events")
    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.bodyAsText()
    assertTrue(body.contains("\"available\":false"), "expected available:false, got: $body")
  }

  @Test
  fun `GET events parses per-stream ndjson into labeled streams with decoded data`() = withTrailRunner {
    // Streams live under events/<name>.<style>.ndjson; each line is a { timeMs, data } envelope.
    val eventsDir = File(logsDir, "sess_e1/events").apply { mkdirs() }
    // Two events for the network stream, one for feature flags. Labels derive from the stream name.
    File(eventsDir, "com.example.network.json.ndjson").writeText(
      """
      {"timeMs":1750154400000,"data":{"url":"https://example.com/a","status":200}}
      {"timeMs":1750154402000,"data":{"url":"https://example.com/b","status":404}}
      """.trimIndent() + "\n",
    )
    File(eventsDir, "feature_flags.json.ndjson").writeText(
      """{"timeMs":1750154401000,"data":{"flag":"new_ui","enabled":true}}""" + "\n",
    )

    val response = client.get("/trailrunner/api/session/sess_e1/events")

    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.bodyAsText()
    assertTrue(body.contains("\"available\":true"), "expected available:true, got: $body")
    assertTrue(body.contains("\"streamId\":\"com.example.network\""), "expected network stream name: $body")
    assertTrue(body.contains("\"label\":\"network\""), "network label should derive from the name: $body")
    assertTrue(body.contains("\"label\":\"feature_flags\""), "feature_flags label should pass through: $body")
    assertTrue(body.contains("\"count\":2"), "network stream should have 2 events: $body")
    // Decoded inner payload is preserved as JSON (not a base64/string blob).
    assertTrue(body.contains("https://example.com/a"), "expected decoded network payload: $body")
    assertTrue(body.contains("\"flag\":\"new_ui\""), "expected decoded feature-flag payload: $body")
    // timeMs is carried through for the timeline interleave.
    assertTrue(!body.contains("\"timeMs\":0"), "timeMs should be the non-zero envelope value: $body")
    // The per-file style is surfaced on the wire.
    assertTrue(body.contains("\"style\":\"json\""), "stream style should be carried: $body")
  }

  @Test
  fun `GET events ignores blank and malformed lines`() = withTrailRunner {
    val eventsDir = File(logsDir, "sess_e2/events").apply { mkdirs() }
    File(eventsDir, "analytics.json.ndjson").writeText(
      "\n" +
        """{"timeMs":1750154400000,"data":{"event":"tap"}}""" + "\n" +
        "this is not json\n" +
        "\n",
    )

    val response = client.get("/trailrunner/api/session/sess_e2/events")

    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.bodyAsText()
    assertTrue(body.contains("\"available\":true"), "expected available:true, got: $body")
    assertTrue(body.contains("\"count\":1"), "only the one valid line should be counted: $body")
  }

  @Test
  fun `GET events caps a huge stream file and flags it truncated`() = withTrailRunner {
    val eventsDir = File(logsDir, "sess_e3/events").apply { mkdirs() }
    // More events than the per-stream cap; the endpoint must stop early, not read all of them.
    val line = """{"timeMs":1750154400000,"data":{"event":"e"}}"""
    File(eventsDir, "analytics.json.ndjson").writeText((0 until EVENTS_MAX_EVENTS_PER_STREAM + 50).joinToString("\n") { line } + "\n")

    val response = client.get("/trailrunner/api/session/sess_e3/events")

    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.bodyAsText()
    assertTrue(body.contains("\"truncated\":true"), "over-cap file should be flagged truncated: ${body.take(200)}")
    assertTrue(body.contains("\"count\":$EVENTS_MAX_EVENTS_PER_STREAM"), "should return exactly the cap, got: ${body.take(200)}")
  }

  @Test
  fun `POST run returns 503 when no deviceManager is wired`() = withTrailRunner {
    val response = client.post("/trailrunner/api/run") {
      contentType(ContentType.Application.Json)
      setBody("""{"trailblazeDeviceId":{"instanceId":"x","trailblazeDevicePlatform":"ANDROID"},"yaml":"- prompts:\n  - step: noop"}""")
    }
    assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
  }

  @Test
  fun `GET Trail Runner unknown path returns 404`() = withTrailRunner {
    val response = client.get("/trailrunner/this-file-does-not-exist.html")

    assertEquals(HttpStatusCode.NotFound, response.status)
  }

  // ─── Typed /rpc/<Name> endpoints ────────────────────────────────────────────
  // The TS daemon.test.ts exercises the client against a fake fetch; these hit the real Kotlin RPC
  // dispatch (registerRpcHandler → handler → RpcResult envelope) so the wire contract + the
  // HTTP_ERROR failure convention are verified server-side too. RPC routes live at root /rpc/<Name>.

  @Test
  fun `POST rpc CreateTrailRequest writes the file and returns success in-band`() = withTrailRunner {
    writeTrail("anchor/anchor.trail.yaml") // anchor the workspace root
    val response = client.post("/rpc/CreateTrailRequest") {
      contentType(ContentType.Application.Json)
      setBody("""{"path":"myapp/new","yaml":"- config:\n    id: x\n    title: X"}""")
    }
    assertEquals(HttpStatusCode.OK, response.status)
    val flat = response.bodyAsText().replace(Regex("\\s"), "")
    assertTrue(flat.contains("\"success\":true"), "expected success=true: ${response.bodyAsText().take(200)}")
    assertTrue(File(trailsDir, "myapp/new.trail.yaml").isFile, "trail file should exist on disk")
  }

  @Test
  fun `POST rpc CancelSessionRequest with no deviceManager is an HTTP_ERROR failure envelope`() = withTrailRunner {
    val response = client.post("/rpc/CancelSessionRequest") {
      contentType(ContentType.Application.Json)
      setBody("""{"id":"sess_x"}""")
    }
    // A non-2xx RpcResult.Failure: the daemon serializes a flat RpcErrorResponse.
    assertEquals(HttpStatusCode.InternalServerError, response.status)
    val body = response.bodyAsText()
    assertTrue(body.replace(Regex("\\s"), "").contains("\"errorType\":\"HTTP_ERROR\""), "expected HTTP_ERROR (not UNKNOWN_ERROR): $body")
    assertTrue(body.contains("deviceManager not available"), "expected the no-deviceManager message: $body")
  }

  @Test
  fun `POST rpc AddTrailRootRequest with a non-directory is an HTTP_ERROR failure envelope`() = withTrailRunner {
    // A real file (exists, but is not a directory) deterministically trips the "not a directory"
    // guard and is portable (no hard-coded Unix path). JSON-escape backslashes for Windows paths.
    val notADir = tmp.newFile("not-a-directory.txt")
    val jsonPath = notADir.absolutePath.replace("\\", "\\\\")
    val response = client.post("/rpc/AddTrailRootRequest") {
      contentType(ContentType.Application.Json)
      setBody("""{"path":"$jsonPath"}""")
    }
    assertEquals(HttpStatusCode.InternalServerError, response.status)
    val body = response.bodyAsText()
    assertTrue(body.replace(Regex("\\s"), "").contains("\"errorType\":\"HTTP_ERROR\""), "expected HTTP_ERROR: $body")
    assertTrue(body.contains("not a directory"), "validation message should survive to the envelope: $body")
  }

  @Test
  fun `POST rpc SettingsPatchRequest with no settings repo is an HTTP_ERROR failure`() = withTrailRunner {
    val response = client.post("/rpc/SettingsPatchRequest") {
      contentType(ContentType.Application.Json)
      setBody("""{"selfHealEnabled":true}""")
    }
    assertEquals(HttpStatusCode.InternalServerError, response.status)
    assertTrue(response.bodyAsText().contains("settings not available"), "expected the unavailable message")
  }

  @Test
  fun `POST rpc IntegrationActionRequest returns ok when a handler is wired`() {
    var launched = false
    withTrailRunner(
      integrationActionHandler = { integrationId, actionId ->
        launched = integrationId == "sample" && actionId == "open"
        OkResponse(ok = launched)
      },
    ) {
      val response = client.post("/rpc/IntegrationActionRequest") {
        contentType(ContentType.Application.Json)
        setBody("""{"id":"sample","action":"open"}""")
      }
      assertEquals(HttpStatusCode.OK, response.status)
      assertTrue(response.bodyAsText().replace(Regex("\\s"), "").contains("\"ok\":true"), "expected ok=true")
    }
    assertTrue(launched, "the wired integration action should have been invoked")
  }

  @Test
  fun `POST rpc IntegrationActionRequest reports unavailable when no handler is wired`() = withTrailRunner {
    val response = client.post("/rpc/IntegrationActionRequest") {
      contentType(ContentType.Application.Json)
      setBody("""{"id":"sample","action":"open"}""")
    }
    // No failure envelope here — "not available" rides in-band in the OkResponse.
    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.bodyAsText()
    assertTrue(body.replace(Regex("\\s"), "").contains("\"ok\":false"), "expected ok=false: $body")
    assertTrue(body.contains("Integration actions are not available"), "expected the unavailable message: $body")
  }

  @Test
  fun `POST integrations action with a blank segment is a 400`() = withTrailRunner {
    // A whitespace-only path segment trims to empty; it must be rejected up front rather than
    // reaching the extension handler as an empty id and surfacing as a generic handler failure.
    val response = client.post("/trailrunner/api/integrations/%20/actions/open")
    assertEquals(HttpStatusCode.BadRequest, response.status)
  }

  // ─── Settings patch apply logic (needs a wired settings repo) ────────────────

  @Test
  fun `PUT settings applies a single boolean field and leaves the rest unchanged`() {
    val repo = newSettingsRepo()
    val before = repo.serverStateFlow.value.appConfig.themeMode
    withTrailRunner(settingsRepo = repo) {
      val response = client.put("/trailrunner/api/settings") {
        contentType(ContentType.Application.Json)
        setBody("""{"selfHealEnabled":true}""")
      }
      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("\"selfHealEnabled\":true"), "selfHealEnabled should be applied: ${body.take(300)}")
    }
    assertEquals(true, repo.serverStateFlow.value.appConfig.selfHealEnabled)
    assertEquals(before, repo.serverStateFlow.value.appConfig.themeMode, "unrelated fields must be untouched")
  }

  @Test
  fun `PUT settings clears maxLlmCalls when given a non-positive value`() {
    val repo = newSettingsRepo()
    withTrailRunner(settingsRepo = repo) {
      // First set it to a real value, then clear it with 0.
      client.put("/trailrunner/api/settings") {
        contentType(ContentType.Application.Json)
        setBody("""{"maxLlmCalls":7}""")
      }
      assertEquals(7, repo.serverStateFlow.value.appConfig.maxLlmCalls)
      client.put("/trailrunner/api/settings") {
        contentType(ContentType.Application.Json)
        setBody("""{"maxLlmCalls":0}""")
      }
    }
    assertEquals(null, repo.serverStateFlow.value.appConfig.maxLlmCalls, "0 clears the cap (the old takeIf{>0} sentinel)")
  }

  @Test
  fun `PUT settings coerces screenshot compression quality into 0 to 1`() {
    val repo = newSettingsRepo()
    withTrailRunner(settingsRepo = repo) {
      client.put("/trailrunner/api/settings") {
        contentType(ContentType.Application.Json)
        setBody("""{"screenshotCompressionQuality":5.0}""")
      }
    }
    assertEquals(1.0f, repo.serverStateFlow.value.appConfig.screenshotCompressionQuality, "out-of-range quality is coerced to 1.0")
  }

  @Test
  fun `PUT settings persists next-start daemon ports and ignores invalid ports`() {
    val repo = newSettingsRepo()
    withTrailRunner(settingsRepo = repo) {
      val response = client.put("/trailrunner/api/settings") {
        contentType(ContentType.Application.Json)
        setBody("""{"serverPort":54123,"serverHttpsPort":54124}""")
      }
      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.bodyAsText().replace(Regex("\\s"), "")
      assertTrue(body.contains("\"serverPort\":54123"), "expected updated HTTP port in response: $body")
      assertTrue(body.contains("\"serverHttpsPort\":54124"), "expected updated HTTPS port in response: $body")

      client.put("/trailrunner/api/settings") {
        contentType(ContentType.Application.Json)
        setBody("""{"serverPort":0,"serverHttpsPort":70000}""")
      }
    }
    assertEquals(54123, repo.serverStateFlow.value.appConfig.serverPort)
    assertEquals("http://localhost:54123", repo.serverStateFlow.value.appConfig.serverUrl)
    assertEquals(54124, repo.serverStateFlow.value.appConfig.serverHttpsPort)
  }
}
