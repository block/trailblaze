package xyz.block.trailblaze.scripting.subprocess

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport.StderrSeverity
import kotlin.test.Test

class McpSubprocessSessionTest {

  @Test fun `default stderr classifier maps error lines to WARNING`() {
    val classify = McpSubprocessSession.DEFAULT_STDERR_CLASSIFIER

    assertThat(classify("Error: could not fetch user")).isEqualTo(StderrSeverity.WARNING)
    assertThat(classify("ERROR pre-flight check failed")).isEqualTo(StderrSeverity.WARNING)
    assertThat(classify("Handler errored out")).isEqualTo(StderrSeverity.WARNING)
  }

  @Test fun `default stderr classifier routes non-error output to DEBUG`() {
    val classify = McpSubprocessSession.DEFAULT_STDERR_CLASSIFIER

    assertThat(classify("tool 'myapp_login' registered")).isEqualTo(StderrSeverity.DEBUG)
    assertThat(classify("listening on stdio")).isEqualTo(StderrSeverity.DEBUG)
  }

  @Test fun `default client info identifies trailblaze`() {
    assertThat(McpSubprocessSession.DEFAULT_CLIENT_INFO.name).isEqualTo("trailblaze")
  }

  @Test fun `routeStderrLine fires the fail-fast callback only for FATAL`() {
    // FATAL is the contract the session-owned stderr pump must preserve from the old transport:
    // a fatal-classified line tears the subprocess down. No default classifier returns FATAL, so
    // this routing is the only coverage of that path.
    var fatalCount = 0
    routeStderrLine("boom", "fixture.js", StderrSeverity.FATAL) { fatalCount++ }
    assertThat(fatalCount).isEqualTo(1)

    for (nonFatal in listOf(StderrSeverity.WARNING, StderrSeverity.INFO, StderrSeverity.DEBUG, StderrSeverity.IGNORE)) {
      var called = false
      routeStderrLine("line", "fixture.js", nonFatal) { called = true }
      assertThat(called).isEqualTo(false)
    }
  }
}
