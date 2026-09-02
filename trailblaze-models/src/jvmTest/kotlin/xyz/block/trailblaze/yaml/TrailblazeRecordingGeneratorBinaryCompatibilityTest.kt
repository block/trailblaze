package xyz.block.trailblaze.yaml

import kotlin.test.Test
import kotlin.test.assertNotNull

class TrailblazeRecordingGeneratorBinaryCompatibilityTest {

  private val generator = Class.forName("xyz.block.trailblaze.yaml.TrailblazeRecordingGeneratorKt")

  @Test
  fun `released JVM recording generator descriptors remain available`() {
    assertNotNull(
      generator.getDeclaredMethod(
        "generateRecordedTrailItems",
        List::class.java,
        TrailblazeYaml::class.java,
        TrailConfig::class.java,
      ),
    )
    assertNotNull(
      generator.getDeclaredMethod(
        "generateUnifiedRecordedYaml",
        List::class.java,
        TrailblazeYaml::class.java,
        TrailConfig::class.java,
        String::class.java,
        String::class.java,
      ),
    )
  }

  @Test
  fun `successful-only variants retain distinct JVM descriptors`() {
    assertNotNull(
      generator.getDeclaredMethod(
        "generateRecordedTrailItems",
        List::class.java,
        TrailblazeYaml::class.java,
        TrailConfig::class.java,
        Boolean::class.javaPrimitiveType,
      ),
    )
    assertNotNull(
      generator.getDeclaredMethod(
        "generateUnifiedRecordedYaml",
        List::class.java,
        TrailblazeYaml::class.java,
        TrailConfig::class.java,
        String::class.java,
        String::class.java,
        Boolean::class.javaPrimitiveType,
      ),
    )
  }
}
