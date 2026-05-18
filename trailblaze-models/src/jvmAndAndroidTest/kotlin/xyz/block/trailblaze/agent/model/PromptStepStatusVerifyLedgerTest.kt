package xyz.block.trailblaze.agent.model

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.VerificationStep

class PromptStepStatusVerifyLedgerTest {

  private val mockScreenState =
    object : ScreenState {
      override val screenshotBytes: ByteArray? = null
      override val deviceWidth: Int = 1080
      override val deviceHeight: Int = 1920
      override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
      override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
      override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
    }

  @Test
  fun `verifyAssertionLedger is null by default`() {
    val status = PromptStepStatus(
      promptStep = VerificationStep(
        verify = """
          - "Gift Cards" is visible
          - "Loyalty" is visible
        """.trimIndent(),
      ),
      screenStateProvider = { mockScreenState },
    )
    assertNull(status.verifyAssertionLedger, "default constructor must not attach a ledger")
  }

  @Test
  fun `attached ledger satisfies and markAsComplete flips isFinished`() {
    val status = PromptStepStatus(
      promptStep = VerificationStep(
        verify = """
          - "Gift Cards" is visible
          - "Loyalty" is visible
        """.trimIndent(),
      ),
      screenStateProvider = { mockScreenState },
    )
    val ledger = VerifyAssertionLedger(verifyText = (status.promptStep as VerificationStep).verify)
    status.attachVerifyAssertionLedger(ledger)

    assertNotNull(status.verifyAssertionLedger)
    assertFalse(status.isFinished(), "step starts in-progress")

    ledger.recordSuccessfulAssertion(
      "assertVisibleBySelector",
      buildJsonObject { put("selector", buildJsonObject { put("textRegex", "Gift Cards") }) },
      isSuccess = true,
    )
    ledger.recordSuccessfulAssertion(
      "assertVisibleBySelector",
      buildJsonObject { put("selector", buildJsonObject { put("textRegex", "Loyalty") }) },
      isSuccess = true,
    )

    assertTrue(ledger.allSatisfied())
    // Simulate the helper's auto-termination path:
    status.markAsComplete(llmExplanation = "test")
    assertTrue(status.isFinished(), "markAsComplete must flip isFinished to true")
  }

  @Test
  fun `direction step does not benefit from ledger but accepts attach (caller responsibility)`() {
    val status = PromptStepStatus(
      promptStep = DirectionStep(step = "Tap the Sign In button"),
      screenStateProvider = { mockScreenState },
    )
    assertNull(status.verifyAssertionLedger)
    // Attaching to a DirectionStep is a caller bug, but the model should not crash —
    // the runner only attaches when prompt is VerificationStep.
    status.attachVerifyAssertionLedger(VerifyAssertionLedger("- \"A\"\n- \"B\""))
    assertNotNull(status.verifyAssertionLedger)
  }
}
