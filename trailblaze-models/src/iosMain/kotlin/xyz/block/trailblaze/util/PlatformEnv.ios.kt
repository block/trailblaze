package xyz.block.trailblaze.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * iOS: the real POSIX `getenv`, so this behaves like the JVM actual rather than degrading to
 * `null` the way wasmJs must. A packaged app inherits almost no environment, but a simulator
 * process launched by `simctl` / a test runner does — which is exactly where a Trailblaze agent
 * would read `TRAILBLAZE_*` overrides from.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun readPlatformEnvVar(name: String): String? = getenv(name)?.toKString()
