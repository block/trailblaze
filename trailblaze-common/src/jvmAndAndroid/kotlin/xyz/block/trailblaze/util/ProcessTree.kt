package xyz.block.trailblaze.util

/**
 * Forcibly ends this process and, where the platform can list them, every process it started. A
 * wrapper's children otherwise outlive it: a `.bat` file runs its tools under cmd.exe, and on
 * Windows a surviving child keeps the output pipe open, so a reader waiting on it never returns.
 */
internal expect fun Process.destroyTreeForcibly()
