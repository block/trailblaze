// AUTO-GENERATED — do not edit manually.
// Re-generate: ./gradlew :examples:android-sample-app-uitests:generateSampleAppTests

package xyz.block.trailblaze.examples.sampleapp.generated

import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.examples.sampleapp.SampleAppTrailblazeRule

class GeneratedSampleAppTests {

  @get:Rule val rule = SampleAppTrailblazeRule()

  @Test
  fun conditionalItem() =
    rule.runFromAsset(
      "android-ondevice-instrumentation/catalog/conditional-item/android-phone.trail.yaml"
    )

  @Test
  fun multipleItems() =
    rule.runFromAsset(
      "android-ondevice-instrumentation/catalog/multiple-items/android-phone.trail.yaml"
    )

  @Test
  fun overlayTap() =
    rule.runFromAsset(
      "android-ondevice-instrumentation/catalog/overlay-tap/android-phone.trail.yaml"
    )

  @Test
  fun scrollAndNavigate() =
    rule.runFromAsset(
      "android-ondevice-instrumentation/lists/scroll-and-navigate/android-phone.trail.yaml"
    )

  @Test
  fun simpleTap() =
    rule.runFromAsset("android-ondevice-instrumentation/taps/simple-tap/android-phone.trail.yaml")

  @Test
  fun swipeDirections() =
    rule.runFromAsset(
      "android-ondevice-instrumentation/swipe/swipe-directions/android-phone.trail.yaml"
    )

  @Test
  fun systemInteractions() =
    rule.runFromAsset(
      "android-ondevice-instrumentation/settings/system-interactions/android-phone.trail.yaml"
    )

  @Test
  fun tapInteractions() =
    rule.runFromAsset(
      "android-ondevice-instrumentation/taps/tap-interactions/android-phone.trail.yaml"
    )

  @Test
  fun textInput() =
    rule.runFromAsset("android-ondevice-instrumentation/forms/text-input/android-phone.trail.yaml")
}
