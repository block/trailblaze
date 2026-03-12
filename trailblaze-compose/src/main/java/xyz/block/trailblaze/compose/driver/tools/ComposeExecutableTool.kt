package xyz.block.trailblaze.compose.driver.tools

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import xyz.block.trailblaze.compose.driver.ComposeScreenState
import xyz.block.trailblaze.compose.driver.ComposeSemanticTreeMapper
import xyz.block.trailblaze.compose.driver.ComposeSemanticTreeMapper.ComposeRole
import xyz.block.trailblaze.compose.driver.rpc.ComposeRpcScreenState
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Interface for tools that execute directly against a Compose [ComposeUiTest].
 *
 * This is the Compose equivalent of PlaywrightExecutableTool. Tools implementing
 * this interface are executed by [ComposeTrailblazeAgent] which provides the
 * current ComposeUiTest instance.
 *
 * The default [execute] implementation throws an error directing callers to use
 * [ComposeTrailblazeAgent], which calls [executeWithCompose] directly.
 */
interface ComposeExecutableTool : ExecutableTrailblazeTool {

  /**
   * Executes this tool against the given Compose UI test instance.
   *
   * @param composeUiTest The ComposeUiTest instance to execute actions against.
   * @param context The tool execution context with session, logging, and memory.
   * @return The result of tool execution.
   */
  suspend fun executeWithCompose(
    composeUiTest: ComposeUiTest,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    error("ComposeExecutableTool must be executed via ComposeTrailblazeAgent")
  }

  companion object {

    /**
     * Resolves an element reference to a Compose [SemanticsMatcher].
     *
     * Priority: elementId > testTag > text.
     *
     * @return A [SemanticsMatcher], or `null` if no identifier was provided.
     */
    fun resolveElement(
      elementId: String?,
      testTag: String?,
      text: String?,
      context: TrailblazeToolExecutionContext,
    ): SemanticsMatcher? {
      if (elementId != null) {
        val elementRef = resolveElementRef(elementId, context)
        if (elementRef != null) {
          return buildMatcherFromRef(elementRef)
        }
      }
      return buildMatcher(testTag, text)
    }

    private fun resolveElementRef(
      elementId: String,
      context: TrailblazeToolExecutionContext,
    ): ComposeSemanticTreeMapper.ComposeElementRef? {
      val screenState = context.screenState ?: return null
      return when (screenState) {
        is ComposeScreenState -> screenState.resolveElementId(elementId)
        is ComposeRpcScreenState -> screenState.resolveElementId(elementId)
        else -> null
      }
    }

    internal fun buildMatcherFromRef(
      ref: ComposeSemanticTreeMapper.ComposeElementRef,
    ): SemanticsMatcher {
      if (ref.testTag != null) {
        return hasTestTag(ref.testTag)
      }
      return buildMatcherFromDescriptor(ref.descriptor)
    }

    internal fun buildMatcherFromDescriptor(descriptor: String): SemanticsMatcher {
      val textMatch = DESCRIPTOR_TEXT_REGEX.find(descriptor)
      val text =
        textMatch?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\")
      val role = descriptor.substringBefore(" ").substringBefore("\"").trim()

      val roleMatcher = buildRoleMatcher(role)

      return when {
        roleMatcher != null && text != null -> roleMatcher and hasText(text, substring = false)
        roleMatcher != null -> roleMatcher
        text != null -> hasText(text, substring = false)
        else -> hasText(descriptor, substring = false)
      }
    }

    /** Extracts the quoted text portion from an element descriptor like `button "Save"`. */
    private val DESCRIPTOR_TEXT_REGEX = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")

    internal fun buildRoleMatcher(role: String): SemanticsMatcher? =
      when (role) {
        ComposeRole.BUTTON -> SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)
        ComposeRole.TEXTBOX -> SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)
        ComposeRole.CHECKBOX ->
          SemanticsMatcher.expectValue(
            SemanticsProperties.Role,
            androidx.compose.ui.semantics.Role.Checkbox,
          )
        ComposeRole.SWITCH ->
          SemanticsMatcher.expectValue(
            SemanticsProperties.Role,
            androidx.compose.ui.semantics.Role.Switch,
          )
        ComposeRole.RADIO ->
          SemanticsMatcher.expectValue(
            SemanticsProperties.Role,
            androidx.compose.ui.semantics.Role.RadioButton,
          )
        ComposeRole.TAB ->
          SemanticsMatcher.expectValue(
            SemanticsProperties.Role,
            androidx.compose.ui.semantics.Role.Tab,
          )
        ComposeRole.IMAGE ->
          SemanticsMatcher.expectValue(
            SemanticsProperties.Role,
            androidx.compose.ui.semantics.Role.Image,
          )
        ComposeRole.COMBOBOX ->
          SemanticsMatcher.expectValue(
            SemanticsProperties.Role,
            androidx.compose.ui.semantics.Role.DropdownList,
          )
        else -> null
      }

    /** Returns the nth-index from the ref, for use with `onAllNodes(matcher)[nthIndex]`. */
    fun getNthIndex(
      elementId: String?,
      context: TrailblazeToolExecutionContext,
    ): Int {
      if (elementId == null) return 0
      val ref = resolveElementRef(elementId, context) ?: return 0
      return ref.nthIndex
    }
  }
}

internal fun buildMatcher(testTag: String?, text: String?) =
  when {
    testTag != null && text != null -> hasTestTag(testTag) and hasText(text, substring = false)
    testTag != null -> hasTestTag(testTag)
    text != null -> hasText(text, substring = false)
    else -> null
  }
