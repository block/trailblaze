package xyz.block.trailblaze.scripting.fetch

/**
 * `10.0.2.2` is the Android emulator's alias for the machine hosting it — where the daemon's
 * self-signed HTTPS server listens when a run isn't using `adb reverse`. It only names that machine
 * when the caller is the emulator, which is why it's an Android-side actual rather than part of the
 * shared set.
 */
internal actual val EMULATOR_HOST_ALIASES: Set<String> = setOf("10.0.2.2")
