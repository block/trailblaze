package xyz.block.trailblaze.logs.client

import kotlinx.serialization.json.Json

/**
 * Shared [Json] instance pre-configured with polymorphic serializers for every tool class
 * classpath-discoverable via YAML, plus any imperatively-registered classes (see
 * [TrailblazeSerializationInitializer.registerImperativeToolClasses]).
 *
 * First access triggers [TrailblazeSerializationInitializer.buildAllTools], which seals
 * the tool set — late registration calls throw. All imperative registrations (Android
 * rule companion `init` blocks, JVM host base-test `init` blocks) must complete before
 * any code reads this value.
 */
@Suppress("ktlint:standard:property-naming")
val TrailblazeJsonInstance: Json by lazy {
  TrailblazeJson.createTrailblazeJsonInstance(
    TrailblazeSerializationInitializer.buildAllTools(),
    TrailblazeSerializationInitializer.buildYamlDefinedToolSerializers(),
  )
}

/**
 * [TrailblazeJsonInstance] without the indentation, for the high-volume session logs on disk.
 *
 * Same serializers, same wire shape — only whitespace differs, so anything that reads a session
 * log reads both forms unchanged.
 *
 * Session logs are deep view-hierarchy trees with one short value per line, which is the worst
 * case for a 4-space indent: whitespace was 92% of a real session's 140 MB of JSON, and 97% of
 * its largest single log. Pretty-printing is kept on [TrailblazeJsonInstance] itself because the
 * same instance persists human-edited files like `~/.trailblaze` config, where readability is the
 * point and the volume is nil.
 */
@Suppress("ktlint:standard:property-naming")
val TrailblazeCompactJsonInstance: Json by lazy {
  Json(TrailblazeJsonInstance) { prettyPrint = false }
}
