package xyz.block.trailblaze.exception

/**
 * Exception thrown when a Trailblaze session is cancelled by the user.
 */
class TrailblazeSessionCancelledException : TrailblazeException("Session was cancelled by user")
