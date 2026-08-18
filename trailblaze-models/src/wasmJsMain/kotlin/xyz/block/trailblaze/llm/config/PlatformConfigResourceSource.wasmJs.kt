package xyz.block.trailblaze.llm.config

/**
 * Wasm/JS: no classpath, no filesystem, no assets — the browser UI never discovers YAML
 * config resources. Empty results mirror [readBuiltInProviderYamlResources]'s wasm actual:
 * consumers degrade to "nothing discovered" rather than failing.
 */
private val emptyConfigResourceSource = ConfigResourceSource { _, _ -> emptyMap() }

actual fun platformConfigResourceSource(): ConfigResourceSource = emptyConfigResourceSource

actual fun bundledConfigResourceSource(): ConfigResourceSource = emptyConfigResourceSource
