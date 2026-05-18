package xyz.block.trailblaze.ui.recording

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import xyz.block.trailblaze.recording.RecordedInteraction
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.toolName
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Renders [ActionYamlCard] in both of its modes — desktop's reflection-backed descriptor
 * resolver (non-null) and wasm's no-op stub (null) — and pins the contract that gates the
 * rich-form editor on the resolver returning a non-null descriptor for the tool.
 *
 * **Why this test exists.** `ActionYamlCard` is the shared composable between the desktop
 * recording tab and the wasm `/devices` viewer. The desktop passes
 * `RichEditorSupport.resolveDescriptorAndValues` (JVM-only, reads tool params via
 * `kotlinx.serialization` reflection). The web passes `{ _ -> null }` — wasmJs doesn't have
 * the JVM reflection pipeline so it gracefully degrades to YAML-only editing. A regression
 * that breaks the null path (e.g. an inner code path forgetting to handle null `richEditState`
 * and crashing during composition) would silently break the web recording panel. This is the
 * fence around that.
 *
 * The form↔YAML toggle button — which exists ONLY when a descriptor resolves — is the visible
 * proxy for the null/non-null branch. When it's absent, the user is on YAML-only; when it's
 * present, they can flip between modes. Asserting on its presence/absence is more durable than
 * asserting on intermediate `richEditState` because state is a private impl detail.
 */
@OptIn(ExperimentalTestApi::class)
class ActionYamlCardTest {

  private val tool = InputTextTrailblazeTool(text = "hello")
  private val interaction = RecordedInteraction(
    tool = tool,
    toolName = InputTextTrailblazeTool::class.toolName().toolName,
    screenshotBytes = null,
    viewHierarchyText = null,
    timestamp = 0L,
  )

  /** Probes the toggle by content-description — the icon's accessibility label flips per mode. */
  private val toggleToYamlDesc = "Switch to YAML editor"
  private val toggleToFormDesc = "Switch to form editor"

  @Test
  fun `null descriptorResolver hides form-YAML toggle - the wasm code path`() =
    runComposeUiTest {
      setContent {
        ActionYamlCard(
          index = 0,
          interaction = interaction,
          isRecording = false,
          isReplaying = false,
          isReplayingFromHere = false,
          onDelete = {},
          onReplay = {},
          onReplayFromHere = {},
          onSelectorChosen = {},
          onEditYaml = { Result.success(Unit) },
          // The contract under test — web passes this exact lambda.
          descriptorResolver = { _ -> null },
        )
      }
      waitForIdle()
      // Enter edit mode — only then does the toggle button render (in the icon row beside
      // Save / Cancel). Before clicking Edit we see only Replay / Delete / Edit / etc.
      onNodeWithContentDescription("Edit").performClick()
      waitForIdle()
      // With a null resolver the editor must start in YAML mode AND the toggle button must
      // not render at all — flipping to a non-existent form would be confusing to the user.
      onNodeWithContentDescription(toggleToYamlDesc).assertDoesNotExist()
      onNodeWithContentDescription(toggleToFormDesc).assertDoesNotExist()
      // Save / Cancel must still render — those are mode-independent.
      onNodeWithContentDescription("Save").assertIsDisplayed()
      onNodeWithContentDescription("Cancel edit").assertIsDisplayed()
    }

  @Test
  fun `non-null descriptorResolver shows form-YAML toggle - the desktop code path`() =
    runComposeUiTest {
      // Resolve to ANY non-null descriptor — the contents don't matter for this test, only
      // that the lambda returns non-null. Building a real descriptor requires JVM reflection
      // we deliberately avoid wiring in here (would re-couple this test to the very thing
      // wasm can't do). A stub descriptor with no params is sufficient — the card just needs
      // to see a non-null value to render the toggle.
      val stubDescriptor =
        xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor(
          name = "inputText",
          description = "stub",
        )
      setContent {
        ActionYamlCard(
          index = 0,
          interaction = interaction,
          isRecording = false,
          isReplaying = false,
          isReplayingFromHere = false,
          onDelete = {},
          onReplay = {},
          onReplayFromHere = {},
          onSelectorChosen = {},
          onEditYaml = { Result.success(Unit) },
          descriptorResolver = { _ -> stubDescriptor to mapOf("text" to "hello") },
        )
      }
      waitForIdle()
      onNodeWithContentDescription("Edit").performClick()
      waitForIdle()
      // Card starts in FORM mode (form widget shows). The toggle's icon points at the
      // *other* mode — so the visible icon's content-description is "Switch to YAML editor".
      onNodeWithContentDescription(toggleToYamlDesc).assertIsDisplayed()
    }

  /**
   * Sanity check that nothing else moved while we were verifying the toggle. The cluster of
   * non-edit-mode buttons (Edit / Replay this step / Replay from here / Delete) should be
   * present on both resolver paths — they don't depend on the descriptor at all. If a future
   * change accidentally gates one of them on `richEditState != null`, this catches it.
   *
   * Run twice — once with the null resolver (wasm path), once with a non-null stub (desktop
   * path) — because a gating regression could land on either side. The original single-
   * variant test claimed to cover "both resolver paths" in its name but only rendered the
   * null path (Copilot caught this on PR #3038).
   */
  @Test
  fun `non-edit-mode controls render under null descriptorResolver - wasm path`() {
    assertNonEditControlsRender(resolver = { _ -> null })
  }

  @Test
  fun `non-edit-mode controls render under non-null descriptorResolver - desktop path`() {
    val stubDescriptor =
      xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor(name = "inputText", description = "stub")
    assertNonEditControlsRender(resolver = { _ -> stubDescriptor to mapOf("text" to "hello") })
  }

  private fun assertNonEditControlsRender(
    resolver: (xyz.block.trailblaze.toolcalls.TrailblazeTool) ->
    Pair<xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor, Map<String, String>>?,
  ) = runComposeUiTest {
    var deleteClicks = 0
    setContent {
      ActionYamlCard(
        index = 0,
        interaction = interaction,
        isRecording = false,
        isReplaying = false,
        isReplayingFromHere = false,
        onDelete = { deleteClicks++ },
        onReplay = {},
        onReplayFromHere = {},
        onSelectorChosen = {},
        onEditYaml = { Result.success(Unit) },
        descriptorResolver = resolver,
      )
    }
    waitForIdle()
    onNodeWithContentDescription("Edit").assertIsDisplayed()
    onNodeWithContentDescription("Replay this step").assertIsDisplayed()
    onNodeWithContentDescription("Replay from here").assertIsDisplayed()
    onNodeWithContentDescription("Delete").assertIsDisplayed()
    // Click Delete to verify the callback contract didn't get accidentally rewired during
    // the lift to commonMain.
    onNodeWithContentDescription("Delete").performClick()
    assertEquals(1, deleteClicks, "Delete should fire its callback exactly once")
  }
}
