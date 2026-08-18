package xyz.block.trailblaze.llm.config

/**
 * iOS: no classpath to enumerate. The built-in provider YAML would ship as bundle resources
 * (`NSBundle`), which needs a real on-device consumer to define the layout — until then,
 * "nothing discovered", mirroring the wasmJs actuals.
 */
actual fun readBuiltInProviderYamlResources(): Map<String, String> = emptyMap()

actual fun readBuiltInProviderYaml(providerId: String): String? = null
