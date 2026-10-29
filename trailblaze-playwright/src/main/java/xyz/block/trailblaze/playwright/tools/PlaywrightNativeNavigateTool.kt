package xyz.block.trailblaze.playwright.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.microsoft.playwright.Page
import java.io.File
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.serializers.CaseInsensitiveEnumSerializer

@Serializable
@TrailblazeToolClass("web_navigate")
@LLMDescription("Go to a URL (GOTO), or move BACK/FORWARD in browser history.")
data class PlaywrightNativeNavigateTool(
  @param:LLMDescription("GOTO (default), BACK, or FORWARD.")
  val action: NavigationAction = NavigationAction.GOTO,
  @param:LLMDescription(
    "URL for GOTO: a full URL (https://, file://) or a file path relative to the trail file.",
  )
  val url: String = "",
  override val reasoning: String? = null,
) : PlaywrightExecutableTool, ReasoningTrailblazeTool {

  @Serializable(with = NavigationAction.Serializer::class)
  enum class NavigationAction {
    GOTO,
    BACK,
    FORWARD,
    ;

    object Serializer : CaseInsensitiveEnumSerializer<NavigationAction>(NavigationAction::class, NavigationAction.entries)
  }

  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    reasoning?.let { Console.log("### Reasoning: $it") }
    return when (action) {
      NavigationAction.GOTO -> navigateToUrl(page, context)
      NavigationAction.BACK -> navigateBack(page)
      NavigationAction.FORWARD -> navigateForward(page)
    }
  }

  private fun navigateToUrl(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    if (url.isBlank()) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        "URL is required when action is 'goto'.",
      )
    }
    val resolvedUrl = resolveUrl(url, context.workingDirectory)
    Console.log("### Navigating to: $resolvedUrl")
    return try {
      page.navigate(resolvedUrl)
      val finalUrl = page.url()
      val message =
        if (finalUrl != resolvedUrl) {
          "Navigated to $resolvedUrl (redirected to $finalUrl)"
        } else {
          "Navigated to $finalUrl"
        }
      TrailblazeToolResult.Success(message = message)
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("Navigation failed: ${e.message}")
    }
  }

  companion object {
    private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    /**
     * Resolves a URL, converting relative file paths to absolute file:// URLs.
     *
     * When a [baseDir] is provided (the trail file's parent directory),
     * relative paths are resolved from there first. If the file exists, that path
     * is used. Otherwise falls back to resolving from the JVM's working directory.
     */
    internal fun resolveUrl(url: String, baseDir: File? = null): String {
      if (SCHEME_REGEX.containsMatchIn(url)) return url

      // Try resolving from the trail file's parent directory first
      if (baseDir != null) {
        val fromBase = File(baseDir, url)
        if (fromBase.exists()) return fromBase.toURI().toString()
      }

      // Fall back to JVM working directory
      val absolute = File(url).absoluteFile
      return absolute.toURI().toString()
    }
  }

  private fun navigateBack(page: Page): TrailblazeToolResult {
    Console.log("### Navigating back")
    return try {
      val response = page.goBack()
      if (response == null) {
        TrailblazeToolResult.Error.ExceptionThrown(
          "No browser history to navigate back to. The current page is: ${page.url()}",
        )
      } else {
        TrailblazeToolResult.Success(message = "Navigated back to ${page.url()}.")
      }
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("Navigate back failed: ${e.message}")
    }
  }

  private fun navigateForward(page: Page): TrailblazeToolResult {
    Console.log("### Navigating forward")
    return try {
      val response = page.goForward()
      if (response == null) {
        TrailblazeToolResult.Error.ExceptionThrown(
          "No forward browser history to navigate to. The current page is: ${page.url()}",
        )
      } else {
        TrailblazeToolResult.Success(message = "Navigated forward to ${page.url()}.")
      }
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("Navigate forward failed: ${e.message}")
    }
  }
}
