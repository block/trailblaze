package xyz.block.trailblaze.cli

import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Ktor's "bind whatever the OS gives you" request, and the sentinel a test holds before that bind
 * resolves. Pass this as `embeddedServer(..., port = EPHEMERAL_PORT)`, then take the real port from
 * [startOnEphemeralPort].
 */
internal const val EPHEMERAL_PORT = 0

/** Hang containment, not a performance budget — see [startOnEphemeralPort]. */
private const val RESOLVE_PORT_TIMEOUT_MS = 10_000L

/**
 * Starts a server bound to [EPHEMERAL_PORT] and returns the port Ktor actually bound.
 *
 * Use this instead of the `ServerSocket(0).use { it.localPort }` idiom. That form asks the OS for a
 * port, **closes the socket**, and hands the bare number to `embeddedServer` — so between the close
 * and the bind the port belongs to nobody, and with ~3k tests running alongside something else
 * eventually takes it. The loser dies with a `java.net.BindException` that carries no information
 * about the product, on whatever unrelated commit happened to be under test. Binding port 0 removes
 * that window rather than narrowing it: the only acquisition is Ktor's own bind, and the port is
 * never released and re-taken, so there is no interval in which a competitor can win it.
 *
 * Ordering is load-bearing. `start(wait = false)` propagates a failed bind to the caller rather than
 * returning, so reaching the resolve step means the bind already landed — which keeps
 * `resolvedConnectors()` off the path `MockRpcServer` warns about, where it never completes on a
 * failed bind (measured: no answer in 5s). The timeout is the backstop for that documented mode: it
 * turns a would-be park into an attributable failure instead of a build-blocking hang.
 */
internal fun EmbeddedServer<*, *>.startOnEphemeralPort(): Int {
  start(wait = false)
  return runBlocking { withTimeout(RESOLVE_PORT_TIMEOUT_MS) { engine.resolvedConnectors() } }
    .first()
    .port
}
