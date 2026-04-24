package xyz.block.trailblaze.llm.config

/**
 * Returns the default [ConfigResourceSource] for the current platform.
 *
 * - JVM: [ClasspathConfigResourceSource] — scans classpath entries (directories and JARs).
 * - Android: an AssetManager-backed source — Android's classloader cannot enumerate
 *   resource directories, so we list assets directly. Resolved via
 *   `InstrumentationRegistry.getInstrumentation().context.assets`, which requires the
 *   process to be running under `androidx.test` instrumentation (Trailblaze's only
 *   supported Android runtime mode).
 */
expect fun platformConfigResourceSource(): ConfigResourceSource
