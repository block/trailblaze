package xyz.block.trailblaze.toolcalls

import kotlin.jvm.JvmInline

/**
 * A marker interface for all Trailblaze commands.
 *
 * All Trailblaze commands should implement this interface and be @[kotlinx.serialization.Serializable].
 */
interface TrailblazeTool

@JvmInline
value class ToolName(val toolName: String)
