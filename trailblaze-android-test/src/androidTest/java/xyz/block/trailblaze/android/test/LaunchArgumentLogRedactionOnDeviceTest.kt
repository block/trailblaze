package xyz.block.trailblaze.android.test

import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json
import org.junit.Test
import xyz.block.trailblaze.toolcalls.commands.MaestroTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.MaestroTrailblazeToolSerializer
import xyz.block.trailblaze.toolcalls.toLogPayload

/** Android's regex parser can reject a matcher that passes JVM log-serialization tests. */
class LaunchArgumentLogRedactionOnDeviceTest {
  @Test
  fun launchLogsMaskCredentialsAndUnboundReferencesWithoutChangingExecutionBytes() {
    val secret = "fixture-session-secret"
    for (reference in listOf("{{oauth2Token}}", "\${oauth2Token}")) {
      val tool = MaestroTrailblazeTool(
        """- launchApp: {appId: com.example.app, arguments: {credential: "$secret", version: "2", reference: "$reference"}}""",
      )

      val logged = tool.toLogPayload().raw.toString()

      assertFalse(logged.contains(secret))
      // A bare tool payload has no binding provenance, so even a reference-shaped value
      // must be masked. The serialized executable command still carries the original bytes.
      assertFalse(logged.contains(reference))
      val executable = Json.encodeToString(MaestroTrailblazeToolSerializer, tool)
      assertContains(executable, secret)
      assertContains(executable, reference)
    }
    assertContains(MaestroTrailblazeTool("- eraseText: 1").toLogPayload().raw.toString(), "eraseText")
  }
}
