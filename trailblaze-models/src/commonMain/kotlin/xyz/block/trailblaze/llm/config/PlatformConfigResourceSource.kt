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

/**
 * Returns the **bundled** (non-workspace-layered) [ConfigResourceSource] for the current
 * platform. Use this when a caller specifically needs the framework's bundled YAMLs
 * without any workspace overlay — e.g., a classpath snapshot cache that the workspace
 * overlay is layered on top of via a separate registration path.
 *
 * - JVM: [ClasspathConfigResourceSource] directly — bypasses the workspace-aware default
 *   that [platformConfigResourceSource] now returns.
 * - Android: same as [platformConfigResourceSource] — Android has no workspace concept
 *   (no walk-up CWD on device), so the platform default is already the bundled view.
 *
 * Callers that need a true classpath-only source for JVM-only code (docs generators, etc.)
 * should reference [ClasspathConfigResourceSource] directly rather than going through this
 * abstraction.
 */
expect fun bundledConfigResourceSource(): ConfigResourceSource
