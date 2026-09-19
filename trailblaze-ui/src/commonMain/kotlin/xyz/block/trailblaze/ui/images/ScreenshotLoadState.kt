package xyz.block.trailblaze.ui.images

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import coil3.compose.AsyncImagePainter
import xyz.block.trailblaze.util.Console

/**
 * Whether a screenshot can be drawn, and what to say when it cannot.
 *
 * Every pane that shows a screenshot needs the same three things — the resolved model, a latch for
 * a load that failed after the model resolved, and one sentence covering both ways it can go wrong.
 * Four panes had grown their own copy (the two inspectors, the shared screenshot pane, the zoom
 * dialog), which is four places to fix when the wording or the retry policy changes.
 */
@Stable
internal class ScreenshotLoadState internal constructor(
  /** What to hand `AsyncImage`. Null when the loader produced nothing to load. */
  val model: Any?,
  /** What the pane must say instead of drawing. Null when [model] should be drawn. */
  val message: String?,
  private val failure: ScreenshotFailure,
) {
  val onError: (AsyncImagePainter.State.Error) -> Unit get() = failure.onError

  /**
   * Whether [retry] can change anything.
   *
   * Only a load that failed on a model is worth trying again — the model survives, and the fetch
   * behind it may not fail twice. With no model there is nothing to hand Coil, and clicking would
   * redraw the same sentence.
   */
  val canRetry: Boolean get() = model != null && message != null

  /** Drops the latch so the pane composes an `AsyncImage` again and Coil issues a fresh request. */
  fun retry() = failure.retry()
}

/**
 * The latch on its own, for a surface that holds a resolved model and never saw the reference it
 * came from — the zoom dialog is handed one by the pane that opened it.
 */
@Stable
internal class ScreenshotFailure internal constructor(
  private val state: MutableState<String?>,
  private val pane: String,
  private val screenshotRef: String?,
) {
  val cause: String? get() = state.value

  val onError: (AsyncImagePainter.State.Error) -> Unit = { errorState ->
    // The cause goes through the same bounding as the reference: an image pipeline failure quotes
    // back the model the loader built, which is the reference with a base path or URL in front.
    val cause = ScreenshotDiagnostics.cause(errorState.result.throwable)
    val named = screenshotRef?.let { "${ScreenshotDiagnostics.ref(it)}: " } ?: ""
    ScreenshotLoadLog.reportOnce("❌ $pane failed to load: $named$cause")
    state.value = cause
  }

  fun retry() {
    state.value = null
  }
}

/**
 * A latch for one screenshot, reset when [resetKey] changes.
 *
 * [resetKey] is deliberately not the resolved model: a loader mints a fresh string for the same
 * screenshot on every call, so keying on it would clear the latch on every recomposition and loop
 * against a load that keeps failing. Recovery is [ScreenshotFailure.retry] instead, which the pane
 * offers to the reader.
 */
@Composable
internal fun rememberScreenshotFailure(
  resetKey: Any?,
  pane: String,
  screenshotRef: String? = null,
): ScreenshotFailure {
  val state = remember(resetKey) { mutableStateOf<String?>(null) }
  return remember(state, pane, screenshotRef) { ScreenshotFailure(state, pane, screenshotRef) }
}

/**
 * [rememberScreenshotFailure] plus the model resolution and the wording, for a pane that knows
 * which screenshot it is showing.
 */
@Composable
internal fun rememberScreenshotLoadState(
  sessionId: String,
  screenshotFile: String,
  imageLoader: ImageLoader,
  pane: String,
): ScreenshotLoadState {
  // Remembered so the model is one stable object across recompositions: it is the key `AsyncImage`
  // compares to decide whether to re-issue a request, and the loaders build a fresh string on every
  // call.
  val model = remember(sessionId, screenshotFile, imageLoader) {
    imageLoader.getImageModel(sessionId, screenshotFile)
  }
  val failure = rememberScreenshotFailure(
    resetKey = sessionId to screenshotFile,
    pane = pane,
    screenshotRef = screenshotFile,
  )

  // In an effect rather than inline, so a composition that is thrown away does not report. Which
  // loader answered is the field that localizes this: the same screenshot renders through a
  // file-system loader in the desktop app and a network one in a published report, and only one of
  // them can return null for a screenshot that exists.
  LaunchedEffect(sessionId, screenshotFile, model == null) {
    if (model == null) {
      ScreenshotLoadLog.reportOnce(
        "❌ $pane has nothing to load for ${ScreenshotDiagnostics.ref(screenshotFile)}: " +
          "${imageLoader::class.simpleName} produced no image model",
      )
    }
  }

  val cause = failure.cause
  val message = remember(cause, screenshotFile, model == null) {
    if (model == null || cause != null) ScreenshotDiagnostics.message(cause, screenshotFile) else null
  }
  return remember(model, message, failure) { ScreenshotLoadState(model, message, failure) }
}

/**
 * Says each distinct screenshot failure once per run.
 *
 * Screenshot panes live in lazy lists, and Coil does not memory-cache an error result: scrolling a
 * row out and back re-fetches, re-fails and would log again. On a report where every load fails —
 * the shape of the ProGuard/Coil breakage (block/trailblaze#194) — that is a line per visible row
 * per scroll pass. Deduplicating on the finished line keeps a new cause for the same screenshot
 * audible while collapsing the repeats.
 *
 * Only touched from the Compose UI thread (composition and `AsyncImage` state callbacks).
 */
internal object ScreenshotLoadLog {

  /** Bounds the set for a session long enough to fail on thousands of distinct screenshots. */
  private const val MAX_REMEMBERED_LINES = 512

  private val reported = mutableSetOf<String>()

  fun reportOnce(line: String) {
    if (line in reported) return
    // Evicted before the add, not after: clearing afterwards drops the line that was just added, so
    // the very next repeat of it would be said again.
    if (reported.size >= MAX_REMEMBERED_LINES) reported.clear()
    reported.add(line)
    // Not `log`: that one is silenced by quiet mode and diverted by JSON mode, both of which the
    // daemon turns on inside the process that also hosts these panes.
    Console.error(line)
  }

  /** Test seam: a fresh process would say each line again. */
  internal fun resetForTest() = reported.clear()
}
