package xyz.block.trailblaze.yaml.unified

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeClassifierLineage
import xyz.block.trailblaze.yaml.TrailFormatYaml

/**
 * The device vectors for a label's `when` and `except`: which of a trail's devices each value
 * picks. Trails are decoded from YAML, so the devices come from the same `config.devices` and
 * recordings an author writes.
 */
class TrailLabelDevicesTest {

  private val trail =
    TrailFormatYaml()
      .decodeUnifiedTrail(
        """
        config:
          devices:
            android-phone: {}
            android-phone-es: {}
            ios-iphone: {}
            ios-ipad: {}
            lab-a: {}
            lab-a-rack: {}
            lab-pair:
              devices:
                seller:
                  classifier: lab-a
                buyer:
                  classifier: lab-ab
        trail:
          - step: Open the app
            recording:
              android:
                - pressBack: {}
              web:
                - pressBack: {}
              all:
                - pressBack: {}
              lab-pair:
                - pressBack: {}
        """
          .trimIndent()
      )

  private val devices = TrailLabelDevices.devicesOf(trail)

  private fun picked(
    whenDevices: List<String>? = null,
    except: List<String>? = null,
  ): List<String> {
    val resolution =
      TrailLabelDevices.resolve(
        TrailLabel(type = "t", name = "n", whenDevices = whenDevices, exceptDevices = except),
        devices,
      )
    assertEquals(emptyList(), resolution.problems)
    return resolution.devices
  }

  @Test
  fun `a trail's devices are its keys, its configuration members, and its other recorded classifiers`() {
    // `android` backs the declared android-phone, so it is not a device of its own; `web` is.
    assertEquals(
      listOf(
        "android-phone",
        "android-phone-es",
        "ios-iphone",
        "ios-ipad",
        "lab-a",
        "lab-a-rack",
        "lab-pair.seller",
        "lab-pair.buyer",
        "web",
      ),
      devices.map { it.id },
    )
  }

  private fun devicesOf(yaml: String): List<String> =
    TrailLabelDevices.devicesOf(TrailFormatYaml().decodeUnifiedTrail(yaml.trimIndent())).map {
      it.id
    }

  @Test
  fun `a configuration's name is not a classifier, so it backs no recording`() {
    // `lab` is a parent of the name `lab-pair`, but of neither member, so it is its own device.
    assertEquals(
      listOf("lab-pair.seller", "lab-pair.buyer", "lab"),
      devicesOf(
        """
        config:
          devices:
            lab-pair:
              devices:
                seller:
                  classifier: ios-iphone
                buyer:
                  classifier: ios-ipad
        trail:
          - step: Open the app
            recording:
              lab:
                - pressBack: {}
        """
      ),
    )
  }

  @Test
  fun `an all entry in config devices holds defaults and is not a device`() {
    assertEquals(
      listOf("android-phone"),
      devicesOf(
        """
        config:
          devices:
            all:
              requires: [android-host-execution]
            android-phone: {}
        trail:
          - step: Open the app
        """
      ),
    )
  }

  @Test
  fun `a recording on a registered parent backs the declared device`() {
    TrailblazeClassifierLineage.registerParentOverride("foldable", "android")
    try {
      assertEquals(
        listOf("foldable-x2"),
        devicesOf(
          """
          config:
            devices:
              foldable-x2: {}
          trail:
            - step: Open the app
              recording:
                android:
                  - pressBack: {}
          """
        ),
      )
    } finally {
      TrailblazeClassifierLineage.clearRegisteredOverridesForTest()
    }
  }

  @Test
  fun `no when and no except is every device`() {
    assertEquals(devices.map { it.id }, picked())
  }

  @Test
  fun `a value picks the devices whose key holds its words side by side`() {
    assertEquals(listOf("android-phone", "android-phone-es"), picked(listOf("android")))
    assertEquals(listOf("android-phone", "android-phone-es"), picked(listOf("phone")))
    assertEquals(listOf("android-phone-es"), picked(listOf("es")))
    assertEquals(listOf("android-phone-es"), picked(listOf("phone-es")))
    assertEquals(listOf("ios-iphone", "ios-ipad"), picked(listOf("ios")))
  }

  @Test
  fun `a word never matches part of another word`() {
    // lab-pair.buyer's classifier is lab-ab: `a` and `lab-a` do not pick it through that.
    assertEquals(listOf("lab-a", "lab-a-rack", "lab-pair.seller"), picked(listOf("lab-a")))
  }

  @Test
  fun `a configuration's name picks all its members and a member is picked by name`() {
    assertEquals(listOf("lab-pair.seller", "lab-pair.buyer"), picked(listOf("lab-pair")))
    assertEquals(listOf("lab-pair.buyer"), picked(listOf("lab-pair.buyer")))
    assertEquals(listOf("lab-pair.buyer"), picked(listOf("lab-ab")))
  }

  @Test
  fun `except takes devices out of when, or out of every device`() {
    assertEquals(
      listOf("lab-a", "lab-pair.seller"),
      picked(listOf("lab-a"), except = listOf("rack")),
    )
    assertEquals(listOf("ios-iphone"), picked(listOf("ios"), except = listOf("ios-ipad")))
    assertEquals(
      listOf(
        "ios-iphone",
        "ios-ipad",
        "lab-a",
        "lab-a-rack",
        "lab-pair.seller",
        "lab-pair.buyer",
        "web",
      ),
      picked(except = listOf("android")),
    )
  }

  @Test
  fun `values that pick nothing, or leave nothing, are problems`() {
    val unmatched =
      TrailLabelDevices.resolve(
        TrailLabel(type = "t", name = "n", whenDevices = listOf("tablet")),
        devices,
      )
    assertTrue(
      unmatched.problems.single().startsWith("`when` value `tablet` picks none"),
      unmatched.toString(),
    )
    val emptied =
      TrailLabelDevices.resolve(
        TrailLabel(
          type = "t",
          name = "n",
          whenDevices = listOf("ios"),
          exceptDevices = listOf("ios"),
        ),
        devices,
      )
    assertTrue(emptied.problems.single().contains("leave none"), emptied.toString())
    val idle =
      TrailLabelDevices.resolve(
        TrailLabel(
          type = "t",
          name = "n",
          whenDevices = listOf("ios"),
          exceptDevices = listOf("android"),
        ),
        devices,
      )
    assertEquals(
      listOf(
        "`except` value `android` takes out none of the devices `when` picks (ios-iphone, ios-ipad)"
      ),
      idle.problems,
    )
  }

  @Test
  fun `when and except round-trip on one line, after name`() {
    val label =
      TrailLabel(
        type = "feature",
        name = "second-screen",
        reasoning = "Only the pair has a buyer screen.",
        whenDevices = listOf("lab-pair", "lab-a-rack"),
        exceptDevices = listOf("lab-pair.buyer"),
      )
    assertEquals(
      "{ type: feature, name: second-screen, when: [lab-pair, lab-a-rack], except: [lab-pair.buyer], " +
        "reasoning: Only the pair has a buyer screen. }",
      TrailLabels.renderEntry(label),
    )
    val yaml = TrailFormatYaml()
    val withLabel = trail.copy(config = trail.config.copy(labels = listOf(label)))
    val emitted = yaml.encodeUnifiedTrailToString(withLabel)
    assertEquals(listOf(label), yaml.decodeUnifiedTrail(emitted).config.labels, emitted)
    assertEquals(
      listOf(label),
      TrailLabels.readLists(emitted)!!.getValue(TrailLabels.CURATED_KEY).getOrThrow(),
    )
  }

  @Test
  fun `device values that would misread as plain YAML are quoted`() {
    assertEquals(
      "{ type: t, name: label, when: [\"no\", \"2026\"] }",
      TrailLabels.renderEntry(
        TrailLabel(type = "t", name = "label", whenDevices = listOf("no", "2026"))
      ),
    )
  }
}
