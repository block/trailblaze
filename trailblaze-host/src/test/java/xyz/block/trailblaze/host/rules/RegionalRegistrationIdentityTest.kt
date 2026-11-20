package xyz.block.trailblaze.host.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.*
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.devices.*
import xyz.block.trailblaze.host.NoOpPageManager
import xyz.block.trailblaze.scripting.HostScriptedToolLauncher

class RegionalRegistrationIdentityTest {
  @Test
  fun `iOS and web registration use this declaration on first and successive runs`() {
    val ios =
      object :
        BaseHostTrailblazeTest(
          trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
          explicitDeviceId = TrailblazeDeviceId("fixture", TrailblazeDevicePlatform.IOS),
          deviceClassifierOverride =
            listOf("ios", "iphone", "es").map(::TrailblazeDeviceClassifier),
          noLogging = true,
        ) {
        override fun ensureTargetAppIsStopped() = Unit
      }
    val web =
      BasePlaywrightNativeTest(
        trailblazeDeviceId = TrailblazeDeviceId("fixture", TrailblazeDevicePlatform.WEB),
        deviceClassifierOverride = listOf("web", "browser", "es").map(::TrailblazeDeviceClassifier),
        existingBrowserManager = NoOpPageManager(),
        noLogging = true,
      )
    val tools =
      listOf("mx", "us").map { region ->
        InlineScriptToolConfig(
          script = "tools/$region.ts",
          name = region,
          description = region,
          meta =
            buildJsonObject {
              put(
                "trailblaze/requiredClassifiers",
                buildJsonArray {
                  add("es")
                  add(region)
                },
              )
            },
        )
      }
    for (region in listOf("MX", "US", "MX")) {
      for ((base, driver, prepare, current) in
        listOf(
          Case(
            "ios-iphone",
            TrailblazeDriverType.IOS_HOST,
            ios::prepareRecordingClassifiers,
            { ios.trailblazeDeviceClassifiers },
          ),
          Case(
            "web-browser",
            TrailblazeDriverType.PLAYWRIGHT_NATIVE,
            web::prepareRecordingClassifiers,
            web::currentRecordingClassifiers,
          ),
        )) {
        prepare(
          """
          config:
            devices:
              $base-es: {locale: es-$region}
              $base-es-mx: {locale: es-US}
          trail:
            - step: Open app
        """
            .trimIndent()
        )
        val applicable =
          HostScriptedToolLauncher.partitionSessionInlineTools(
              tools,
              listOf(driver),
              true,
              "[test]",
              current(),
            )
            .second
        assertEquals(listOf(region.lowercase()), applicable.map { it.name })
        assertEquals(region.lowercase(), current().last().classifier)
      }
    }
  }

  @Test
  fun `bare web tools preserve region in the same session and reset in a new session`() {
    val original = listOf("web", "browser", "es").map(::TrailblazeDeviceClassifier)
    val web =
      BasePlaywrightNativeTest(
        trailblazeDeviceId = TrailblazeDeviceId("session-fixture", TrailblazeDevicePlatform.WEB),
        deviceClassifierOverride = original,
        existingBrowserManager = NoOpPageManager(),
        noLogging = true,
      )
    val tools =
      listOf("mx", "us").map { region ->
        InlineScriptToolConfig(
          script = "tools/$region.ts",
          name = region,
          description = region,
          meta =
            buildJsonObject {
              put(
                "trailblaze/requiredClassifiers",
                buildJsonArray {
                  add("es")
                  add(region)
                },
              )
            },
        )
      }
    fun registered() =
      HostScriptedToolLauncher.partitionSessionInlineTools(
          tools,
          listOf(TrailblazeDriverType.PLAYWRIGHT_NATIVE),
          true,
          "[test]",
          web.currentRecordingClassifiers(),
        )
        .second
        .map { it.name }
    val sessionA =
      xyz.block.trailblaze.logs.client.TrailblazeSession(
        xyz.block.trailblaze.logs.model.SessionId("web-session-a"),
        kotlinx.datetime.Clock.System.now(),
      )
    web.loggingRule.setSession(sessionA)
    web.prepareRecordingClassifiers(
      """
      config:
        devices:
          web-browser-es: {locale: es-MX}
      trail:
        - step: Open page
      """
        .trimIndent()
    )
    assertEquals(listOf("mx"), registered())
    val bare = "- openApp: {appId: com.example.app}"
    web.prepareRecordingClassifiers(bare)
    assertEquals(
      listOf("web", "browser", "es", "mx"),
      web.currentRecordingClassifiers().map { it.classifier },
    )
    assertEquals(listOf("mx"), registered())
    web.loggingRule.setSession(
      xyz.block.trailblaze.logs.client.TrailblazeSession(
        xyz.block.trailblaze.logs.model.SessionId("web-session-b"),
        kotlinx.datetime.Clock.System.now(),
      )
    )
    web.prepareRecordingClassifiers(bare)
    assertEquals(original, web.currentRecordingClassifiers())
    assertEquals(emptyList(), registered())
  }

  @Test
  fun `bare host tools retain region only within their session`() {
    val original = listOf("ios", "iphone", "es").map(::TrailblazeDeviceClassifier)
    val host = object : BaseHostTrailblazeTest(
      trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
      explicitDeviceId = TrailblazeDeviceId("session-fixture", TrailblazeDevicePlatform.IOS),
      deviceClassifierOverride = original,
      noLogging = true,
    ) { override fun ensureTargetAppIsStopped() = Unit }
    fun session(id: String) = xyz.block.trailblaze.logs.client.TrailblazeSession(
      xyz.block.trailblaze.logs.model.SessionId(id), kotlinx.datetime.Clock.System.now())
    host.loggingRule.setSession(session("host-a"))
    host.prepareRecordingClassifiers("config: {devices: {ios-iphone-es: {locale: es-MX}}}\ntrail: []")
    val projected = original + TrailblazeDeviceClassifier("mx")
    assertEquals(projected, host.trailblazeDeviceClassifiers)
    val bare = "- openApp: {appId: com.example.app}"
    host.prepareRecordingClassifiers(bare)
    assertEquals(projected, host.trailblazeDeviceClassifiers)
    host.loggingRule.setSession(session("host-b"))
    host.prepareRecordingClassifiers(bare)
    assertEquals(original, host.trailblazeDeviceClassifiers)
  }

  private data class Case(
    val base: String,
    val driver: TrailblazeDriverType,
    val prepare: (String) -> Unit,
    val current: () -> List<TrailblazeDeviceClassifier>,
  )
}
