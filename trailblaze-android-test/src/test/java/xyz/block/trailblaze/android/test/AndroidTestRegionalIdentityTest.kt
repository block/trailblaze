package xyz.block.trailblaze.android.test

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import xyz.block.trailblaze.devices.*
import xyz.block.trailblaze.yaml.*

class AndroidTestRegionalIdentityTest {
  @Test
  fun `direct driver selects a regional-only recording and context from declaration`() {
    val original = listOf("android", "phone").map(::TrailblazeDeviceClassifier)
    val logging =
      AndroidTestLoggingRule(
        {
          TrailblazeDeviceInfo(
            TrailblazeDeviceId("test", TrailblazeDevicePlatform.ANDROID),
            TrailblazeDriverType.ANDROID_TEST,
            100,
            200,
            classifiers = original,
          )
        },
        null,
        true,
        "https://localhost:1",
      )
    val rule =
      AndroidTestTrailblazeRule(
        targetProvider = { error("no device in decode") },
        loggingRule = logging,
      )
    val items =
      rule.decodeTrailForRun(
        """
        config:
          devices:
            android-phone: {driver: ANDROID_TEST, locale: es-MX}
            android-phone-es-mx: {driver: ANDROID_TEST, locale: es-US}
        trail:
          - step: Open app
            recording:
              android-phone-es-mx: [openApp: {appId: com.example.app}]
        """
          .trimIndent()
      )
    val config = createTrailblazeYaml().extractTrailConfig(items)!!
    assertEquals("es-MX", config.locale)
    val step =
      items.filterIsInstance<TrailYamlItem.PromptsTrailItem>().single().promptSteps.single()
        as DirectionStep
    assertNotNull(step.recording)
    assertEquals(
      listOf("android", "phone", "es", "mx"),
      logging.trailblazeDeviceInfoProvider().classifiers.map { it.classifier },
    )
    assertEquals(
      "android-phone",
      logging.trailblazeDeviceInfoProvider().metadata["executionDeviceClass"],
    )
  }

  @Test
  fun `RPC runtime override reaches current context without replacing original lane`() {
    val original = listOf("android", "phone").map(::TrailblazeDeviceClassifier)
    val logging =
      AndroidTestLoggingRule(
        {
          TrailblazeDeviceInfo(
            TrailblazeDeviceId("test", TrailblazeDevicePlatform.ANDROID),
            TrailblazeDriverType.ANDROID_TEST,
            100,
            200,
            classifiers = original,
          )
        },
        null,
        true,
        "https://localhost:1",
      )
    val request =
      xyz.block.trailblaze.llm.RunYamlRequest(
        testName = "rpc",
        yaml = "- openApp: {appId: com.example.app}",
        trailFilePath = null,
        targetAppName = null,
        useRecordedSteps = true,
        trailblazeDeviceId = TrailblazeDeviceId("test", TrailblazeDevicePlatform.ANDROID),
        trailblazeLlmModel =
          xyz.block.trailblaze.llm.TrailblazeLlmModel(
            trailblazeLlmProvider = xyz.block.trailblaze.llm.TrailblazeLlmProvider.OPENAI,
            modelId = "test",
            inputCostPerOneMillionTokens = 0.0,
            outputCostPerOneMillionTokens = 0.0,
            contextLength = 1000,
            maxOutputTokens = 1000,
            capabilityIds = emptyList(),
          ),
        config = xyz.block.trailblaze.model.TrailblazeConfig(),
        referrer = xyz.block.trailblaze.llm.TrailblazeReferrer(id = "test", display = "test"),
        deviceClassifierOverride = listOf("android", "phone", "es", "mx"),
      )
    logging.prepareRpcIdentity(request, false)
    assertEquals(
      request.deviceClassifierOverride,
      logging.trailblazeDeviceInfoProvider().classifiers.map { it.classifier },
    )
    assertEquals(
      "android-phone",
      logging.trailblazeDeviceInfoProvider().metadata["executionDeviceClass"],
    )
    val full =
      """
      config:
        devices:
          android-phone-es: {driver: ANDROID_TEST, locale: es-MX}
      trail:
        - step: Open app
      """
        .trimIndent()
    logging.prepareRpcIdentity(
      request.copy(yaml = full, deviceClassifierOverride = listOf("android", "phone", "es")),
      false,
    )
    val rule =
      AndroidTestTrailblazeRule(targetProvider = { error("no device") }, loggingRule = logging)
    rule.decodeTrailForRun(full)
    assertEquals(
      "android-phone-es",
      logging.trailblazeDeviceInfoProvider().metadata["executionDeviceClass"],
    )
    logging.prepareRpcIdentity(request.copy(deviceClassifierOverride = emptyList()), true)
    assertEquals(
      listOf("android", "phone", "es", "mx"),
      logging.trailblazeDeviceInfoProvider().classifiers.map { it.classifier },
    )
    assertEquals(
      "android-phone-es",
      logging.trailblazeDeviceInfoProvider().metadata["executionDeviceClass"],
    )
    val nextSession =
      request.copy(
        deviceClassifierOverride = listOf("android", "phone", "es", "us"),
        config = request.config.copy(sendSessionStartLog = false),
      )
    logging.prepareRpcIdentity(nextSession, false)
    assertEquals(
      nextSession.deviceClassifierOverride,
      logging.trailblazeDeviceInfoProvider().classifiers.map { it.classifier },
    )
    assertEquals(
      "android-phone",
      logging.trailblazeDeviceInfoProvider().metadata["executionDeviceClass"],
    )
  }

  @Test
  fun `registration reuse replaces changed runtime region and retains unchanged identity`() =
    kotlinx.coroutines.runBlocking {
      val original = listOf("android", "phone").map(::TrailblazeDeviceClassifier)
      val logging =
        AndroidTestLoggingRule(
          {
            TrailblazeDeviceInfo(
              TrailblazeDeviceId("cache-fixture", TrailblazeDevicePlatform.ANDROID),
              TrailblazeDriverType.ANDROID_TEST,
              100,
              200,
              classifiers = original,
            )
          },
          null,
          true,
          "https://localhost:1",
        )
      val repo = xyz.block.trailblaze.toolcalls.TrailblazeToolRepo.withDynamicToolSets()
      val rule =
        AndroidTestTrailblazeRule(
          targetProvider = { error("no device") },
          loggingRule = logging,
          trailblazeToolRepoOverride = repo,
        )
      val sessionId = xyz.block.trailblaze.logs.model.SessionId("regional-cache-fixture")
      var registered = emptyList<String>()
      try {
        for (region in listOf("mx", "us")) {
          rule.decodeTrailForRun(
            """
          config:
            devices:
              android-phone: {driver: ANDROID_TEST, locale: es-$region}
          trail:
            - step: Open app
        """
              .trimIndent()
          )
          val reused =
            rule.claimOrLaunchScriptedTools(sessionId) { info ->
              assertEquals(logging.trailblazeDeviceInfoProvider().classifiers, info.classifiers)
              registered =
                listOf("mx", "us").filter { candidate ->
                  RequiredDeviceClassifiers.Segments(listOf("es", candidate))
                    .isSatisfiedBy(info.classifiers)
                }
              null
            }
          kotlin.test.assertFalse(reused)
          assertEquals(listOf(region), registered)
          assertEquals(region, logging.trailblazeDeviceInfoProvider().classifiers.last().classifier)
          kotlin.test.assertTrue(
            rule.claimOrLaunchScriptedTools(sessionId) {
              error("unchanged registration must be reused")
            }
          )
        }
      } finally {
        xyz.block.trailblaze.android.ScriptedToolBundleReuse.release(sessionId)
      }
    }

  @Test
  fun `RPC run refuses a hardware key the device doesn't report`() {
    fun loggingOn(deviceKey: String) =
      AndroidTestLoggingRule(
        {
          TrailblazeDeviceInfo(
            TrailblazeDeviceId("test", TrailblazeDevicePlatform.ANDROID),
            TrailblazeDriverType.ANDROID_TEST,
            100,
            200,
            classifiers = deviceKey.split("-").map(::TrailblazeDeviceClassifier),
          )
        },
        null,
        true,
        "https://localhost:1",
      )
    val request =
      xyz.block.trailblaze.llm.RunYamlRequest(
        testName = "rpc",
        yaml = "- tools:\n    - openApp:\n        appId: com.example.app",
        trailFilePath = null,
        targetAppName = null,
        useRecordedSteps = true,
        trailblazeDeviceId = TrailblazeDeviceId("test", TrailblazeDevicePlatform.ANDROID),
        trailblazeLlmModel =
          xyz.block.trailblaze.llm.TrailblazeLlmModel(
            trailblazeLlmProvider = xyz.block.trailblaze.llm.TrailblazeLlmProvider.OPENAI,
            modelId = "test",
            inputCostPerOneMillionTokens = 0.0,
            outputCostPerOneMillionTokens = 0.0,
            contextLength = 1000,
            maxOutputTokens = 1000,
            capabilityIds = emptyList(),
          ),
        config = xyz.block.trailblaze.model.TrailblazeConfig(),
        referrer = xyz.block.trailblaze.llm.TrailblazeReferrer(id = "test", display = "test"),
        deviceClassifierOverride = listOf("acme", "k2", "es"),
      )
    // An acme-k2 run that lands on another device fails, rather than replay and save under its key.
    assertFailsWith<IllegalArgumentException> { loggingOn("acme-k3").prepareRpcIdentity(request, false) }
    assertFailsWith<IllegalArgumentException> { loggingOn("android-phone").prepareRpcIdentity(request, false) }
    // On an acme-k2 it runs under the requested key.
    val k2 = loggingOn("acme-k2")
    k2.prepareRpcIdentity(request, false)
    assertEquals(request.deviceClassifierOverride, k2.selectionDeviceInfo().classifiers.map { it.classifier })
  }
}
