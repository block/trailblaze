package xyz.block.trailblaze.scripting.fetch

/**
 * Empty on the host: `10.0.2.2` is an ordinary LAN address to a daemon JVM, not a loopback alias, so
 * relaxing TLS for it here would drop certificate and hostname checks for a real remote host.
 */
internal actual val EMULATOR_HOST_ALIASES: Set<String> = emptySet()
