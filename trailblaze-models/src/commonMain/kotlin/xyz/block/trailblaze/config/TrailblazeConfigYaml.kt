package xyz.block.trailblaze.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration

/**
 * Shared [Yaml] instance for all Trailblaze config loaders.
 *
 * Uses `strictMode = false` (ignore unknown keys) and `encodeDefaults = false`.
 */
object TrailblazeConfigYaml {
  val instance: Yaml =
    Yaml(
      configuration =
        YamlConfiguration(
          strictMode = false,
          encodeDefaults = false,
        ),
    )
}
