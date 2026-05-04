package xyz.block.trailblaze.cli

import xyz.block.trailblaze.ui.TrailblazeSettingsRepo

/**
 * Bridge that lets [CliConfigHelper] route reads/writes through the running
 * daemon's [TrailblazeSettingsRepo] instead of the on-disk settings file.
 *
 * Why: the daemon owns an in-memory `appConfig` and auto-persists it on every
 * mutation. If a CLI invocation writes the file directly, any subsequent daemon
 * mutation (selected device, current session, etc.) clobbers the CLI's change
 * with the daemon's stale in-memory copy. Routing CLI config reads/writes
 * through this bridge — which mutates the daemon's state flow — keeps the
 * daemon and the file in agreement.
 *
 * Set once at daemon startup (see `TrailblazeDesktopApp.installRunHandler`).
 * When null, [CliConfigHelper] falls back to direct file IO — that's the
 * standalone-CLI path used when no daemon is running.
 *
 * ## Concurrency / lifecycle
 *
 * [settingsRepo] is module-level mutable state on a singleton object. The contract is
 * **one daemon JVM, one bridge installation, one repo reference**:
 *
 *  - **Production:** the daemon JVM sets [settingsRepo] exactly once during
 *    `TrailblazeDesktopApp.installRunHandler` before any CLI traffic is forwarded
 *    in-process. The `@Volatile` ensures CLI request threads see the assigned value.
 *  - **Standalone CLI / unit tests:** [settingsRepo] stays null. [CliConfigHelper]
 *    falls back to file IO. Tests that need to exercise the daemon-routed path should
 *    set this to a fixture repo and clear it in tear-down.
 *  - **Multi-daemon-per-process:** unsupported. A second daemon installing into the
 *    same JVM would overwrite the prior reference, leaving stale CLI requests pointed
 *    at a dead repo. Same shape as `WorkspaceContentHasher.lastCapturedHash` — flagged
 *    here so the next person reaching for "two daemons in one process" sees the
 *    constraint.
 *
 * Reads from [settingsRepo] are wrapped in `runCatching` at the call site (see
 * [CliConfigHelper.readConfigRaw]) so a partially-initialized or torn-down repo
 * falls through to file IO rather than propagating the exception out of CLI helpers.
 */
object DaemonSettingsBridge {
  @Volatile
  var settingsRepo: TrailblazeSettingsRepo? = null
}
