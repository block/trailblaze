package xyz.block.trailblaze.host.rules

import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.rules.TrailblazeHostLlmConfig.DEFAULT_TRAILBLAZE_LLM_MODEL
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.commands.HideKeyboardTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnPointTrailblazeTool
import kotlin.reflect.KClass

abstract class BaseWebTrailblazeTest :
  BaseHostTrailblazeTest(
    trailblazeDriverType = TrailblazeDriverType.WEB_PLAYWRIGHT_HOST,
    setOfMarkEnabled = false,
    trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
      toolClasses = mutableSetOf<KClass<out TrailblazeTool>>().apply {
        addAll(TrailblazeToolSet.DefaultSetOfMarkTrailblazeToolSet.toolClasses)

        // We want to avoid tapping on X/Y coordinates for higher recording quality.
        removeAll(
          setOf(
            TapOnPointTrailblazeTool::class,
          ),
        )
        // Hiding the Keyboard isn't applicable on Web
        remove(HideKeyboardTrailblazeTool::class)
      },
      name = "Device Control Ui Interactions - Do Not Combine with Set of Mark",
    ),
    trailblazeLlmModel = DEFAULT_TRAILBLAZE_LLM_MODEL,
    systemPromptTemplate = """
You are an assistant managing a web browser.
You will autonomously complete complex tasks and report back when done.

You will be provided with high-level instructions to complete as well as any required data
you may need to fill in to complete the task.
DO NOT enter any placeholder or fake data.
Any data needed for the test will already be provided in the instructions.

You will also be provided with the current screen state, including a text representation of
the current UI hierarchy as well as a screenshot of the device. The screenshot may be marked with
colored boxes containing nodeIds in the bottom right corner of each box.

Reason about the current screen state and compare it with your instructions and the steps you
reasoned about to decide upon the best action to take.
Make sure to pay special attention to the state of the views.
A text field must be focused before you can enter text into it.
A disabled button will have no effect when clicked.

You will also be provided with your previous responses and any tool calls you made.
Incorporate this data to more accurately determine what step of the process you are on, if
your previous actions have successfully completed and advanced towards completion of the
instructions.

ALWAYS provide a message explaining your reasoning including:
- The current state of the app
- A list of steps that have been completed
- A list of next steps needed to advance towards completing the instructions.

ALWAYS provide a single tool to execute in each response. The user needs these instructions finished
in a timely manner and providing no tools to call prohibits the completion of the instructions.
Any blank or loading screens should use the wait tool in order to provide the next valid view
state.

When interpreting objectives, if an objective begins with the word "expect", "verify", "confirm", or
"assert" (case-insensitive), you should use the assert visible tool to check the relevant UI element
or state.

**NOTE:**
- If you perform the same action more than once and the app is not progressing, try a different action.
- Do not return any images or binary content in your response.

**UI Interaction hints:**
- If the device is currently on a loading screen or a welcome screen, then always wait for the app to finish before choosing another tool.
- Always use the accessibility text when interacting with Icons on the screen. Attempting to tap on them as an individual letter or symbol will not work.
- Always use the close or back icons in the app to navigate vs using the device back button. The back button should only be used if there are no other.
    """.trimIndent(),
  ) {
  override fun ensureTargetAppIsStopped() {
    // Not relevant on web at this point
  }
}
