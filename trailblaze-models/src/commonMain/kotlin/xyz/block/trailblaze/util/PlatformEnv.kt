package xyz.block.trailblaze.util

/**
 * Reads an environment variable, or null when absent — or when the platform has no
 * environment at all (Wasm/JS in a browser). `System.getenv` behind an expect so
 * commonMain callers — [xyz.block.trailblaze.AgentMemory]'s unknown-token kill-switch here,
 * env-reading utilities in downstream KMP modules like trailblaze-common — stay
 * platform-clean. Public because Kotlin `internal` doesn't cross Gradle modules, and the
 * alternative — a copy of this expect/actual per module — risks a duplicate-class clash: a
 * downstream copy keeping these filenames emits the same file-facade class (for the
 * jvmAndAndroid actual that's `PlatformEnv_jvmAndAndroidKt`, as the `.api` baselines show)
 * in the same package, twice on one runtime classpath.
 */
expect fun readPlatformEnvVar(name: String): String?
