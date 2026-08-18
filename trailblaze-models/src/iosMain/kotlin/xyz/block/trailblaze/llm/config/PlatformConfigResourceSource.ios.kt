package xyz.block.trailblaze.llm.config

/**
 * iOS: no classpath and no Android `AssetManager`. An on-device agent will load config from
 * bundle resources or from the host over RPC; both want a real [ConfigResourceSource]
 * implementation, not a hoisted classloader. Empty results until then — consumers degrade to
 * "nothing discovered" rather than failing, same as wasmJs.
 */
private val emptyConfigResourceSource = ConfigResourceSource { _, _ -> emptyMap() }

actual fun platformConfigResourceSource(): ConfigResourceSource = emptyConfigResourceSource

actual fun bundledConfigResourceSource(): ConfigResourceSource = emptyConfigResourceSource
