package xyz.block.trailblaze.quickjs.tools

import xyz.block.trailblaze.devices.TrailblazeDeviceId

/**
 * The `ctx.device.trailblazePort` value for [trailblazeDeviceId], or null when it
 * can't be trusted.
 *
 * Only the host can compute it: the port hashes in the host's `HostPortNamespace` (its
 * `ANDROID_ADB_SERVER_PORT`), which a process on the device doesn't have, so the Android actual
 * returns null rather than a port that is wrong whenever the host's ADB server isn't on the
 * default. Web devices have no on-device Trailblaze server, so they get null too.
 */
internal expect fun trailblazePortOrNull(trailblazeDeviceId: TrailblazeDeviceId): Int?
