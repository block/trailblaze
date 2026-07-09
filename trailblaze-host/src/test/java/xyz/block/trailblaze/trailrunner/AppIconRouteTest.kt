package xyz.block.trailblaze.trailrunner

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.report.utils.LogsRepo

/**
 * HTTP-level coverage for `/trailrunner/api/app-icon/{target}` — the Ktor wiring (Cache-Control
 * header, 404s, bundled-classpath fallback) that sits above the pure resolvers already covered by
 * [WorkspaceIconResolutionTest]. `deviceManager` is null here (no workspace target list), so these
 * cases exercise the bundled-icon branch and the no-resolution 404 path; the workspace-icon-hit
 * branch (which needs a live [xyz.block.trailblaze.ui.TrailblazeDeviceManager] +
 * [xyz.block.trailblaze.config.YamlBackedHostAppTarget]) stays covered at the pure-function level.
 */
class AppIconRouteTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private fun deps(): TrailRunnerDeps =
    TrailRunnerDeps(
      trailsRootProvider = { tmp.newFolder("trails") },
      logsRepo = LogsRepo(logsDir = tmp.newFolder("logs"), watchFileSystem = false),
      settingsRepo = null,
      deviceManager = null,
      integrationsProvider = null,
      integrationActionHandler = null,
      analyticsProvider = null,
      analyticsCaptureStarter = null,
      eventCaptureController = null,
    )

  private fun withAppIconRoute(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
    testApplication {
      application {
        routing {
          appIconRoutes(deps())
        }
      }
      block()
    }

  @Test
  fun `rejects a target id with path-breaking characters`() = withAppIconRoute {
    val response = client.get("/trailrunner/api/app-icon/bad!name")
    assertEquals(HttpStatusCode.NotFound, response.status)
  }

  @Test
  fun `404s when neither a workspace nor bundled icon resolves`() = withAppIconRoute {
    val response = client.get("/trailrunner/api/app-icon/unknown_target")
    assertEquals(HttpStatusCode.NotFound, response.status)
  }

  @Test
  fun `serves a bundled classpath icon with a cache header when present`() = withAppIconRoute {
    // app_icon_testfixture.png ships as a test resource purely to exercise this branch end-to-end.
    val response = client.get("/trailrunner/api/app-icon/testfixture")
    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(ContentType.Image.PNG, response.contentType())
    assertEquals("max-age=86400", response.headers["Cache-Control"])
    assertTrue(response.bodyAsBytes().isNotEmpty())
  }

  @Test
  fun `platform query param still falls through to the bundled icon when there is no workspace target`() = withAppIconRoute {
    // deviceManager is null, so the platform param has nothing to scope — this just guards that
    // adding the param didn't regress the existing no-workspace fallback path.
    val response = client.get("/trailrunner/api/app-icon/testfixture?platform=android")
    assertEquals(HttpStatusCode.OK, response.status)
    assertTrue(response.bodyAsBytes().isNotEmpty())
  }

  @Test
  fun `appId query param still falls through to the bundled icon when there is no workspace target`() = withAppIconRoute {
    // deviceManager is null, so the appId param has nothing to scope — this just guards that
    // adding the param didn't regress the existing no-workspace fallback path. The appId-override
    // resolution itself is covered at the pure-function level (WorkspaceIconResolutionTest).
    val response = client.get("/trailrunner/api/app-icon/testfixture?platform=android&appId=com.example.app.internal")
    assertEquals(HttpStatusCode.OK, response.status)
    assertTrue(response.bodyAsBytes().isNotEmpty())
  }

  @Test
  fun `an appId with path-breaking characters is ignored rather than used`() = withAppIconRoute {
    // Malformed appIds (e.g. a crafted `../` traversal attempt) are filtered out before reaching
    // resolution; this exercises that the request still degrades to the bundled-icon fallback
    // instead of erroring.
    val response = client.get("/trailrunner/api/app-icon/testfixture?appId=" + java.net.URLEncoder.encode("../../etc/passwd", "UTF-8"))
    assertEquals(HttpStatusCode.OK, response.status)
    assertTrue(response.bodyAsBytes().isNotEmpty())
  }
}
