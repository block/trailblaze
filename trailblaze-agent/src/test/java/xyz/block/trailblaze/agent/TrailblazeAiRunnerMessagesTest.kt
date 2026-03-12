package xyz.block.trailblaze.agent

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import org.junit.Test
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.VerificationStep

class TrailblazeAiRunnerMessagesTest {

  // -- Prior objective context --

  @Test
  fun `no prior objective section when no completed objectives`() {
    val message = getReminderForStep("Tap on Settings")
    assertThat(message).doesNotContain("PRIOR OBJECTIVE")
  }

  @Test
  fun `shows only last completed objective`() {
    val message = getReminderForStep(
      prompt = "Tap on Settings",
      completedObjectiveDescriptions = listOf("Open app", "Sign in", "Navigate to menu"),
    )
    assertThat(message).contains("PRIOR OBJECTIVE")
    assertThat(message).contains("Navigate to menu")
    assertThat(message).doesNotContain("Open app")
    assertThat(message).doesNotContain("Sign in")
  }

  @Test
  fun `shows earlier count when multiple completed objectives`() {
    val message = getReminderForStep(
      prompt = "Tap on Settings",
      completedObjectiveDescriptions = listOf("Open app", "Sign in", "Navigate to menu"),
    )
    assertThat(message).contains("2 earlier objective")
  }

  @Test
  fun `no earlier count when only one completed objective`() {
    val message = getReminderForStep(
      prompt = "Tap on Settings",
      completedObjectiveDescriptions = listOf("Open app"),
    )
    assertThat(message).contains("Open app")
    assertThat(message).doesNotContain("earlier objective")
  }

  // -- Current objective --

  @Test
  fun `shows current objective with Task prefix for direction step`() {
    val message = getReminderForStep("Tap on Settings")
    assertThat(message).contains("CURRENT OBJECTIVE")
    assertThat(message).contains("Task Tap on Settings")
  }

  @Test
  fun `shows current objective with Verify prefix for verification step`() {
    val message = TrailblazeAiRunnerMessages.getReminderMessage(
      promptStep = VerificationStep(verify = "Total is $10.00"),
    )
    assertThat(message).contains("Verify Total is $10.00")
  }

  // -- Verification objectives --

  @Test
  fun `verification step includes verification-specific instructions`() {
    val message = TrailblazeAiRunnerMessages.getReminderMessage(
      promptStep = VerificationStep(verify = "Total is $10.00"),
    )
    assertThat(message).contains("VERIFICATION")
    assertThat(message).contains("DO NOT attempt to alter the screen state")
  }

  @Test
  fun `direction step does not include verification instructions`() {
    val message = getReminderForStep("Tap on Settings")
    assertThat(message).doesNotContain("VERIFICATION")
  }

  // -- Conditional objective detection --

  @Test
  fun `conditional objective includes conditional guidance`() {
    val message = getReminderForStep("if there is Cake item on the screen, tap on it once")
    assertThat(message).contains("CONDITIONAL")
  }

  @Test
  fun `conditional objective does not include element-not-found failure guidance`() {
    val message = getReminderForStep("if there is Cake item on the screen, tap on it once")
    assertThat(message).doesNotContain("required element or target cannot be found")
  }

  @Test
  fun `non-conditional objective includes element-not-found failure guidance`() {
    val message = getReminderForStep("Tap on the Pizza button")
    assertThat(message).contains("required element or target cannot be found")
  }

  @Test
  fun `non-conditional objective does not include conditional guidance`() {
    val message = getReminderForStep("Tap on Settings")
    assertThat(message).doesNotContain("CONDITIONAL")
  }

  // -- Multi-step objective detection --

  @Test
  fun `multi-step objective includes multi-step guidance`() {
    val message = getReminderForStep("Tap on Charge and then tap on Card on File")
    assertThat(message).contains("MULTI-STEP")
  }

  @Test
  fun `multi-step objective does not include single-action guidance`() {
    val message = getReminderForStep("Tap on Charge and then tap on Card on File")
    assertThat(message).doesNotContain("single-action")
  }

  // -- Single-action objective --

  @Test
  fun `single-action objective includes single-action guidance`() {
    val message = getReminderForStep("Tap on Settings")
    assertThat(message).contains("single-action")
  }

  @Test
  fun `single-action objective does not include multi-step guidance`() {
    val message = getReminderForStep("Tap on Settings")
    assertThat(message).doesNotContain("MULTI-STEP")
  }

  // -- objectiveStatus always present --

  @Test
  fun `always includes objectiveStatus instructions`() {
    val message = getReminderForStep("Tap on Settings")
    assertThat(message).contains("objectiveStatus")
    assertThat(message).contains("IN_PROGRESS")
    assertThat(message).contains("COMPLETED")
    assertThat(message).contains("FAILED")
  }

  // -- Last status update --

  @Test
  fun `shows last status update when in progress`() {
    val message = getReminderForStep(
      prompt = "Tap on Settings",
      latestObjectiveStatus = "IN_PROGRESS",
    )
    assertThat(message).contains("LAST STATUS UPDATE")
    assertThat(message).contains("Your last status: IN_PROGRESS")
  }

  @Test
  fun `hides last status update when completed`() {
    val message = getReminderForStep(
      prompt = "Tap on Settings",
      latestObjectiveStatus = "COMPLETED",
    )
    assertThat(message).doesNotContain("LAST STATUS UPDATE")
  }

  // -- Helper --

  private fun getReminderForStep(
    prompt: String,
    completedObjectiveDescriptions: List<String> = emptyList(),
    latestObjectiveStatus: String? = null,
  ): String = TrailblazeAiRunnerMessages.getReminderMessage(
    promptStep = DirectionStep(step = prompt),
    completedObjectiveDescriptions = completedObjectiveDescriptions,
    latestObjectiveStatus = latestObjectiveStatus,
  )
}
