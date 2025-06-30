package xyz.block.trailblaze.exception

import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

class TrailblazeToolExecutionException(trailblazeToolResult: TrailblazeToolResult.Error) : TrailblazeException(trailblazeToolResult.toString())
