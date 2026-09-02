package xyz.block.trailblaze.tracing

import kotlin.coroutines.CoroutineContext

// No thread-context element on this target, so a suspending span carries its identity for its
// suspending children only and never touches TraceSpanLocal — see traceSpanCoroutineContext's
// PLATFORM GAP note for what that costs.
actual fun traceSpanCoroutineContext(spanId: String): CoroutineContext = TraceSpanContextElement(spanId)
