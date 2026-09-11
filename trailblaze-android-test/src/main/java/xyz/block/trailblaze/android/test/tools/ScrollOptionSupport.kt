package xyz.block.trailblaze.android.test.tools

import maestro.ScrollDirection
import maestro.orchestra.ScrollUntilVisibleCommand
import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * The one place that decides which scroll-until-visible options this driver can carry out.
 *
 * A recorded scroll reaches the in-process driver two ways — as the canonical
 * `scrollUntilTextIsVisible` tool, and as a raw Maestro `scrollUntilVisible` command — and both
 * land on the same [AndroidTestScrollUntilVisibleTool] loop. Answering the question at each entry
 * point is how the two answers drift, so both ask here, and the defaults are read from Maestro's
 * own command rather than copied as literals.
 *
 * Each function returns null when the option can be honored, or the reason it cannot. Refusing is
 * the point: the loop stops the moment the element is laid out on screen — the same stop condition
 * the accessibility driver's scrollUntilVisible uses — so a partial threshold and a "keep going
 * until it is centred" have no honest reading here, and silently dropping either would report a
 * scroll the recording never asked for.
 */
internal object ScrollOptionSupport {

  /**
   * Maestro's own default, and the only direction an in-process scroll can drive.
   *
   * There is no directional fling in-process: the scroll action drives the largest scrollable
   * container FORWARD.
   */
  val SUPPORTED_DIRECTION: ScrollDirection = ScrollDirection.DOWN

  fun unsupportedDirection(direction: ScrollDirection): String? =
    if (direction == SUPPORTED_DIRECTION) {
      null
    } else {
      "direction=$direction (in-process scrolling drives the container forward only, so refusing " +
        "beats scrolling the wrong way and reporting the element was never found)"
    }

  fun unsupportedVisibilityPercentage(visibilityPercentage: Int): String? =
    if (visibilityPercentage == ScrollUntilVisibleCommand.DEFAULT_ELEMENT_VISIBILITY_PERCENTAGE) {
      null
    } else {
      "visibilityPercentage=$visibilityPercentage (this driver stops as soon as the element is " +
        "laid out on screen and cannot measure a partial threshold)"
    }

  /**
   * How long a scroll-until-visible searches when the recording did not write a `timeout`.
   *
   * Read from Maestro's own command, like the other defaults here, because an unwritten option has
   * to mean the same thing on every driver. Left unset, the in-process loop would be bounded only
   * by its scroll cap while a Maestro-backed driver stopped at this deadline — the same recording
   * searching for two different lengths of time depending on where it ran. It bites hardest under
   * `optional: true`, where a target that is simply absent would burn the whole cap before the
   * step is skipped.
   */
  val DEFAULT_TIMEOUT_MS: Long = ScrollUntilVisibleCommand.DEFAULT_TIMEOUT_IN_MILLIS.toLong()

  /**
   * `null` means the recording left `centerElement` unwritten, which is not a request to centre:
   * it hands the decision to the driver, and this driver declares
   * [TrailblazeDriverType.ANDROID_TEST.centersScrollTargetByDefault] false. Resolving it here from
   * that same declaration — rather than accepting null outright — is what keeps the refusal honest
   * if the driver's declared default ever changes. The raw Maestro entry point reaches the same
   * answer by not asking at all when the key is absent.
   */
  fun unsupportedCenterElement(centerElement: Boolean?): String? {
    val resolved = centerElement ?: TrailblazeDriverType.ANDROID_TEST.centersScrollTargetByDefault
    return if (resolved == ScrollUntilVisibleCommand.DEFAULT_CENTER_ELEMENT) {
      null
    } else {
      "centerElement=$resolved (this driver stops as soon as the element is on screen and " +
        "cannot keep scrolling to centre it)"
    }
  }
}
