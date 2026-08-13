package xyz.block.trailblaze.scripting.fetch

/**
 * Host names that reach the machine running this device — populated on Android (`10.0.2.2`) and
 * empty on the host JVM. Part of [OkHttpFetchExtension.isDeviceLocalHost], which decides whose
 * certificates `fetch` accepts unvalidated.
 *
 * Split per target because the alias is only an alias *from inside an emulator*. The same extension
 * is installed by the host launchers, where `10.0.2.2` is an ordinary routable address, so treating
 * it as device-local there would silently drop certificate and hostname verification for a real
 * remote host.
 */
internal expect val EMULATOR_HOST_ALIASES: Set<String>
