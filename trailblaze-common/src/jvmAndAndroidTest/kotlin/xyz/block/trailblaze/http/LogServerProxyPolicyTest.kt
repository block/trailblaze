package xyz.block.trailblaze.http

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Which log endpoints skip the device's HTTP proxy. The default ones must (a dead capture proxy
 * would otherwise eat every log upload); a remote `trailblaze.logsEndpoint` must not (the proxy may
 * be the only route to it).
 */
class LogServerProxyPolicyTest {

  @Test
  fun `the two default endpoints bypass the proxy`() {
    // `localhost` under `trailblaze.reverseProxy`, `10.0.2.2` on an emulator — the shapes
    // InstrumentationArgUtil.logsEndpoint and AndroidTestInstrumentation.logsEndpoint produce.
    assertTrue(shouldBypassProxyForLogServer("https://localhost:52526"))
    assertTrue(shouldBypassProxyForLogServer("https://10.0.2.2:52526"))
  }

  @Test
  fun `loopback in its other spellings bypasses the proxy too`() {
    assertTrue(shouldBypassProxyForLogServer("http://127.0.0.1:52526"))
    assertTrue(shouldBypassProxyForLogServer("http://127.0.0.2:52526/agentlog"))
    assertTrue(shouldBypassProxyForLogServer("http://[::1]:52526"))
    assertTrue(shouldBypassProxyForLogServer("HTTPS://LOCALHOST:52526"))
  }

  @Test
  fun `a remote log server keeps the proxy`() {
    // The supported remote-logs-server configuration: whatever reaches it, reaches it the way every
    // other request on the device does.
    assertFalse(shouldBypassProxyForLogServer("https://logs.example.test:52526"))
  }

  @Test
  fun `a host that merely starts with a local name is remote`() {
    // Whole-host equality, not a prefix or suffix test: these are someone else's hosts, and a
    // bypass would route them off the proxy that is supposed to carry them.
    assertFalse(shouldBypassProxyForLogServer("https://localhost.logs.example.test"))
    assertFalse(shouldBypassProxyForLogServer("https://10.0.2.20"))
  }

  @Test
  fun `an address with no parseable host keeps the proxy`() {
    // Can't be shown to be device-local, so it isn't treated as one.
    assertFalse(shouldBypassProxyForLogServer("localhost:52526"))
    assertFalse(shouldBypassProxyForLogServer("not a url"))
    assertFalse(shouldBypassProxyForLogServer(""))
  }
}
