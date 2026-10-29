package xyz.block.trailblaze.quickjs.tools

/**
 * What `ctx.host.os` and `ctx.host.arch` read: the host JVM's OS and architecture, or null when
 * the tool runs on an Android device, which has no separate host computer.
 */
internal expect fun hostPlatformOrNull(): QuickJsHostPlatform?
