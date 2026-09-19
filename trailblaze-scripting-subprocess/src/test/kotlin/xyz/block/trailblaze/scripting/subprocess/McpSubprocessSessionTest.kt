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

  /**
   * The escalation ladder's last wait is the answer, not a courtesy pause. SIGKILL cannot be
   * refused, but the OS can take longer than the wait to reap the child, and a child blocked in an
   * uninterruptible system call is not gone until it is. A caller told "it exited" frees resources
   * — the transport's IO permit above all — that the child is still holding.
   */
  @Test fun `escalation reports a child that outlives SIGKILL as still alive`() {
    val process = FakeProcess(survivesSigkill = true)
    val hurried = McpSubprocessSession.Duration(afterCloseSeconds = 0, afterSigtermSeconds = 0, afterSigkillSeconds = 0)

    val exited = destroyWithEscalation(process, hurried)

    assertThat(exited).isEqualTo(false)
    assertThat(process.isAlive).isEqualTo(true)
    // Every rung was climbed before giving up: the answer is "still alive after SIGKILL", not
    // "gave up early".
    assertThat(process.destroyCalls).isEqualTo(1)
    assertThat(process.destroyForciblyCalls).isEqualTo(1)
  }

  @Test fun `escalation reports a child that SIGKILL ends as exited`() {
    val process = FakeProcess(survivesSigkill = false)
    val hurried = McpSubprocessSession.Duration(afterCloseSeconds = 0, afterSigtermSeconds = 0, afterSigkillSeconds = 0)

    assertThat(destroyWithEscalation(process, hurried)).isEqualTo(true)
    assertThat(process.isAlive).isEqualTo(false)
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
