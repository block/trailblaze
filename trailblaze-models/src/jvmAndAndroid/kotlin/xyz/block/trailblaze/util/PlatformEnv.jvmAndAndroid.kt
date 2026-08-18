package xyz.block.trailblaze.util

actual fun readPlatformEnvVar(name: String): String? = System.getenv(name)
