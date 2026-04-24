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
