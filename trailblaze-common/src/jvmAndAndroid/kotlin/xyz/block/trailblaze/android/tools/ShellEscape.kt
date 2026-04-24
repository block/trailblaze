package xyz.block.trailblaze.android.tools

/**
 * Wraps a string in single quotes with proper escaping for safe use in shell commands.
 *
 * Replaces any embedded single quotes with the sequence `'\''` (end quote, escaped literal
 * quote, restart quote) so the value is treated as a single shell argument. This prevents
 * shell injection via special characters like `;`, `|`, `$`, backticks, etc.
 */
fun String.shellEscape(): String = "'${replace("'", "'\\''")}'"
