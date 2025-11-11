package xyz.block.trailblaze.maestro

import maestro.Filters
import maestro.Maestro
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.MaestroCommand
import maestro.utils.StringUtils.toRegexSafe
import org.slf4j.LoggerFactory
import xyz.block.trailblaze.api.MaestroDriverActionType
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger

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
   * Handles logging for assertion commands. Should be called before the command is executed.
   */
  fun logAssertionCommand(command: MaestroCommand) {
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
            // For visible assertions, try to find the element and get its bounds
            assertCommand.condition?.visible?.let { selector ->
              val element = findElementSafely(selector)?.element
              element?.bounds?.let { bounds ->
                // Calculate center point of the element
                elementCenterX = (bounds.x + bounds.width / 2)
                elementCenterY = (bounds.y + bounds.height / 2)
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

        val screenshotFilename =
          screenState.screenshotBytes?.let { trailblazeLogger.logScreenshot(it) }
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
   * Safely finds an element without throwing exceptions that would interrupt the flow.
   */
  private fun findElementSafely(selector: maestro.orchestra.ElementSelector): maestro.FindElementResult? = try {
    maestro.findElementWithTimeout(
      timeoutMs = 500,
      filter = buildFilterForSelector(selector),
    )
  } catch (e: Exception) {
    logger.debug("Element not found during assertion logging: ${e.message}")
    null
  }

  /**
   * Builds a filter from an ElementSelector - simplified version for logging purposes.
   */
  private fun buildFilterForSelector(selector: maestro.orchestra.ElementSelector): maestro.ElementFilter {
    val filters = mutableListOf<maestro.ElementFilter>()

    selector.textRegex?.let {
      filters += Filters.deepestMatchingElement(
        Filters.textMatches(it.toRegexSafe(REGEX_OPTIONS)),
      )
    }

    selector.idRegex?.let {
      filters += Filters.deepestMatchingElement(
        Filters.idMatches(it.toRegexSafe(REGEX_OPTIONS)),
      )
    }

    var resultFilter = Filters.intersect(filters)
    resultFilter = selector.index?.toDouble()?.toInt()?.let {
      Filters.compose(
        resultFilter,
        Filters.index(it),
      )
    } ?: Filters.compose(
      resultFilter,
      Filters.clickableFirst(),
    )

    return resultFilter
  }

  companion object {
    val REGEX_OPTIONS =
      setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
  }
}
