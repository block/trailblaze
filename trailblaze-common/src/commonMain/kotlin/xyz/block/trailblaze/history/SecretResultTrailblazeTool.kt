package xyz.block.trailblaze.history

/**
 * A tool whose success message is a credential, such as a session token or a password it resolved.
 * `ctx.history` keeps the call and whether it succeeded, but masks that message. Its caller still
 * gets the full result; only the history's copy is masked.
 */
interface SecretResultTrailblazeTool
