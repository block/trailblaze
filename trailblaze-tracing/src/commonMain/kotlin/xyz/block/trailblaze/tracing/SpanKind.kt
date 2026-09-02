package xyz.block.trailblaze.tracing

import kotlinx.serialization.Serializable

/**
 * What role a span plays in a call, using OpenTelemetry's kinds.
 *
 * The one that earns its keep is the [CLIENT]/[SERVER] pair: once a trace crosses a process
 * boundary, "the caller's view of the request" and "the callee's view of the same request" are two
 * spans, and only their kinds say which is which. Without that a merged trace reads as one span
 * mysteriously containing another of the same name.
 *
 * [INTERNAL] is the default and is left out of the recorded JSON — the overwhelming majority of
 * `trace { }` blocks are plain in-process work, and writing "INTERNAL" on every one of them would
 * be noise in a file we hand to Perfetto.
 *
 * Only [CLIENT] has producers today, at the three HTTP call sites. [SERVER] arrives with the
 * propagation that gives it a caller to pair with; [PRODUCER]/[CONSUMER] are here because a kind
 * this enum cannot express would have to be added as a breaking change to a serialized format.
 */
@Serializable
enum class SpanKind {
  /** In-process work with no remote counterpart. The default. */
  INTERNAL,

  /** An outgoing request, measured by the caller. Pairs with the callee's [SERVER] span. */
  CLIENT,

  /** Handling an incoming request, measured by the callee. Pairs with the caller's [CLIENT] span. Not recorded yet. */
  SERVER,

  /** Handing work to a queue or another process without waiting for it. Not recorded yet. */
  PRODUCER,

  /** Picking up work a [PRODUCER] handed off. Not recorded yet. */
  CONSUMER,
}
