package xyz.block.trailblaze.maestro

import maestro.Maestro
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.MaestroCommand
import org.slf4j.LoggerFactory
import xyz.block.trailblaze.api.MaestroDriverActionType
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.viewmatcher.TapSelectorV2

/**
 * Handles logging of assertion commands for visualization purposes.
 * This class extracts the assertion logging logic that was previously embedded in Orchestra.
 * Works for both Android on-device and host-based (iOS/RPC) execution environments.
 */
class AssertionLogger(
  private val maestro: Maestro,
  private val screenStateProvider: (() -> ScreenState)?,
  private val trailblazeLogger: TrailblazeLogger,
) {

  private val logger = LoggerFactory.getLogger(AssertionLogger::class.java)

  /**
   * Handles logging for successful assertion commands. Should be called after the command succeeds.
   */
  fun logSuccessfulAssertionCommand(command: MaestroCommand) {
    logAssertionCommandInternal(command, succeeded = true)
  }

  /**
   * Handles logging for failed assertion commands. Should be called after the command fails.
   */
  fun logFailedAssertionCommand(command: MaestroCommand) {
    logAssertionCommandInternal(command, succeeded = false)
  }

  /**
   * Internal method to log assertion commands with success/failure status.
   */
  private fun logAssertionCommandInternal(
    command: MaestroCommand,
    succeeded: Boolean,
  ) {
    val assertCommand = command.asCommand() as? AssertConditionCommand ?: return

    try {
      if (screenStateProvider != null) {
        val screenState = screenStateProvider.invoke()
        val assertionFilterDescription = assertCommand.condition?.description() ?: "(unknown)"

        // Determine if this is a visible or notVisible assertion
        val isVisibleAssertion = assertCommand.condition?.visible != null
        val isNotVisibleAssertion = assertCommand.condition?.notVisible != null

        // Try to find the element being asserted to get its coordinates
        var elementCenterX = screenState.deviceWidth / 2 // Default to screen center
        var elementCenterY = screenState.deviceHeight / 2
        var textToDisplay: String? = null

        try {
          if (isVisibleAssertion) {
            // For visible assertions, try to find the element using our selector-based approach
            assertCommand.condition?.visible?.let { maestroSelector ->
              val trailblazeSelector = convertMaestroSelectorToTrailblaze(maestroSelector)

              // Use the official selector-based coordinate finding
              val coordinates = TapSelectorV2.findNodeCenterUsingSelector(
                root = screenState.viewHierarchy,
                selector = trailblazeSelector,
                trailblazeDevicePlatform = screenState.trailblazeDevicePlatform,
                widthPixels = screenState.deviceWidth,
                heightPixels = screenState.deviceHeight,
              )

              coordinates?.let { (x, y) ->
                elementCenterX = x
                elementCenterY = y
              }
            }
          } else if (isNotVisibleAssertion) {
            // For notVisible assertions, use screen center and extract the text we're confirming is NOT there
            assertCommand.condition?.notVisible?.let { selector ->
              // Extract text pattern from selector
              textToDisplay = selector.textRegex ?: selector.idRegex?.let { "id: $it" } ?: "element"
            }
          }
        } catch (e: Exception) {
          // If we can't find the element, use screen center
          logger.debug("Could not find element for assertion visualization: ${e.message}")
        }

        // For failed assertions, extract the text that was expected to be visible
        if (!succeeded && isVisibleAssertion) {
          assertCommand.condition?.visible?.let { selector ->
            textToDisplay = selector.textRegex ?: selector.idRegex?.let { "id: $it" } ?: "element"
          }
        }

        val screenshotFilename = trailblazeLogger.logScreenState(screenState)
        trailblazeLogger.log(
          TrailblazeLog.MaestroDriverLog(
            viewHierarchy = screenState.viewHierarchy,
            screenshotFile = screenshotFilename,
            action = MaestroDriverActionType.AssertCondition(
              conditionDescription = assertionFilterDescription,
              x = elementCenterX,
              y = elementCenterY,
              isVisible = isVisibleAssertion,
              textToDisplay = textToDisplay,
              succeeded = succeeded,
            ),
            durationMs = 0,
            timestamp = kotlinx.datetime.Clock.System.now(),
            session = trailblazeLogger.getCurrentSessionId(),
            deviceWidth = screenState.deviceWidth,
            deviceHeight = screenState.deviceHeight,
          ),
        )
      }
    } catch (t: Throwable) {
      logger.error("[TrailblazeLog] Failed to log assertion during execution.", t)
    }
  }

  /**
   * Converts a Maestro ElementSelector to a TrailblazeElementSelector.
   * This is a simplified conversion that handles the most common selector properties.
   */
  private fun convertMaestroSelectorToTrailblaze(maestroSelector: maestro.orchestra.ElementSelector): TrailblazeElementSelector = TrailblazeElementSelector(
    textRegex = maestroSelector.textRegex,
    idRegex = maestroSelector.idRegex,
    index = maestroSelector.index,
    enabled = maestroSelector.enabled,
    selected = maestroSelector.selected,
    checked = maestroSelector.checked,
    focused = maestroSelector.focused,
    below = maestroSelector.below?.let { convertMaestroSelectorToTrailblaze(it) },
    above = maestroSelector.above?.let { convertMaestroSelectorToTrailblaze(it) },
    leftOf = maestroSelector.leftOf?.let { convertMaestroSelectorToTrailblaze(it) },
    rightOf = maestroSelector.rightOf?.let { convertMaestroSelectorToTrailblaze(it) },
    containsChild = maestroSelector.containsChild?.let { convertMaestroSelectorToTrailblaze(it) },
    containsDescendants = maestroSelector.containsDescendants?.map {
      convertMaestroSelectorToTrailblaze(
        it,
      )
    },
    childOf = maestroSelector.childOf?.let { convertMaestroSelectorToTrailblaze(it) },
  )
}
