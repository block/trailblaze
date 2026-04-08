package xyz.block.trailblaze.llm.config

/**
 * Discovers and reads all built-in provider YAML files from classpath resources.
 * Returns a map of provider_id to YAML content.
 *
 * Used by [BuiltInLlmModelRegistry.allModelLists] to populate the full catalog
 * (desktop UI, model pickers). Uses classpath directory scanning on JVM;
 * falls back to a core provider list on Android.
 *
 * On wasmJs: returns an empty map.
 */
expect fun readBuiltInProviderYamlResources(): Map<String, String>

/**
 * Reads a single provider's YAML from classpath resources by provider_id.
 * Returns the YAML content, or null if not found.
 *
 * Used by [BuiltInLlmModelRegistry.find] for on-demand loading — no discovery needed.
 * Works reliably on JVM, Android, and any runtime that supports [ClassLoader.getResource].
 *
 * On wasmJs: returns null.
 */
expect fun readBuiltInProviderYaml(providerId: String): String?
