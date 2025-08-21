package xyz.block.trailblaze.logs.client

import xyz.block.trailblaze.toolcalls.TrailblazeToolSet

@Suppress("ktlint:standard:property-naming")
var TrailblazeJsonInstance = TrailblazeJson.createTrailblazeJsonInstance(
  TrailblazeToolSet.AllBuiltInTrailblazeToolsByKoogToolDescriptor,
)
