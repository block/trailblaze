package xyz.block.trailblaze.ui.images

/**
 * What an inspector says when it cannot show a screenshot.
 *
 * Everything here is bounded, because none of these strings is as small as it looks. A screenshot
 * reference is usually a filename, but [NetworkImageLoader] also accepts a `data:` URI with the
 * whole image inlined — and the loaders fold whatever they are given into the model they return, so
 * a failure from the image pipeline quotes it back in its own message. Untruncated, one bad
 * screenshot puts megabytes into stdout and into a text pane that has no scroll container and no
 * line limit.
 */
internal object ScreenshotDiagnostics {

  /** Enough of a `data:` URI to carry its scheme, media type and encoding, and no payload. */
  private const val DATA_URI_PREFIX_CHARS = 64

  /** A cap for everything else: a long filesystem path, a long URL, a verbose decoder message. */
  private const val MAX_CHARS = 240

  /** [screenshotFile] in a form that is safe to log or lay out. */
  fun ref(screenshotFile: String): String = when {
    // Not a `data:` prefix the loader would recognize, so it is a name or a URL. Still capped: a
    // filesystem path is unbounded, and it is the loader's base path plus a session id.
    !screenshotFile.startsWith("data:") -> bounded(screenshotFile)
    // Short enough to say in full — summarizing it would claim an elision that never happened.
    screenshotFile.length <= DATA_URI_PREFIX_CHARS -> screenshotFile
    // The prefix is the useful part: it says the screenshot was embedded rather than named, and
    // gives the media type. The length is what distinguishes a big blob from a missing file.
    else -> "${screenshotFile.take(DATA_URI_PREFIX_CHARS)}… " +
      "(embedded ${screenshotFile.length}-char data URI)"
  }

  /**
   * [throwable] as a bounded one-line cause.
   *
   * The type is kept even when there is a message, because the type is what says whether to go
   * look at the file, the server, or the decoder. A message that is present but blank is worse
   * than none — it renders as a sentence that stops at the colon.
   */
  fun cause(throwable: Throwable): String {
    val type = throwable::class.simpleName ?: "error"
    val message = throwable.message?.takeIf { it.isNotBlank() } ?: return type
    return bounded("$type: $message")
  }

  /**
   * What the empty pane says.
   *
   * Two unrelated failures land in the same branch and need different words. A non-null [loadError]
   * means the image pipeline rejected bytes it had fetched. A null one means the loader never
   * produced anything to fetch — a different thing to go look at, and in neither case "this node
   * has no screenshot": [screenshotFile] is non-null, so something was always named.
   */
  fun message(loadError: String?, screenshotFile: String): String = if (loadError != null) {
    "Failed to load screenshot: $loadError"
  } else {
    "Failed to load screenshot: nothing to load for ${ref(screenshotFile)}"
  }

  private fun bounded(text: String): String = if (text.length <= MAX_CHARS) {
    text
  } else {
    "${text.take(MAX_CHARS)}… (${text.length} chars)"
  }
}
