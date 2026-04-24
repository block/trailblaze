package xyz.block.trailblaze.yaml

/**
 * Platform builder for [TrailblazeYaml.Default].
 *
 * - JVM / Android: builds from `TrailblazeSerializationInitializer.buildAllTools()`
 *   (classpath / AssetManager YAML discovery).
 * - wasmJs: returns an empty [TrailblazeYaml]. Web consumers decode unknown tools via the
 *   `OtherTrailblazeTool` fallback and do not need typed tool serializers.
 */
internal expect fun buildTrailblazeYamlDefault(): TrailblazeYaml
