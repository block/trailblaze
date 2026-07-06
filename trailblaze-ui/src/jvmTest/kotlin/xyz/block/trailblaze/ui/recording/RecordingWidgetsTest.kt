package xyz.block.trailblaze.ui.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.block.trailblaze.toolcalls.TrailblazeToolParameterVisibility
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolParameterDescriptor

class RecordingWidgetsTest {
  @Test
  fun `buildSingleToolYaml nests dotted parameter names`() {
    val descriptor = TrailblazeToolDescriptor(
      name = "loginAsUser",
      requiredParameters = listOf(
        TrailblazeToolParameterDescriptor(
          name = "account.type",
          type = "String",
          validValues = listOf("guest", "member", "premiumMember", "newMember"),
        ),
      ),
      optionalParameters = listOf(
        TrailblazeToolParameterDescriptor(name = "startingRoute", type = "String"),
        TrailblazeToolParameterDescriptor(name = "account.tierId", type = "String"),
      ),
    )

    assertEquals(
      """
      loginAsUser:
        account:
          type: guest
          tierId: tier-1
        startingRoute: /home
      """.trimIndent(),
      buildSingleToolYaml(
        descriptor,
        mapOf(
          "account.type" to "guest",
          "account.tierId" to "tier-1",
          "startingRoute" to "/home",
        ),
      ),
    )
  }

  @Test
  fun `visibleParameters filters associated fields by selected discriminator value`() {
    val parameters = listOf(
      TrailblazeToolParameterDescriptor(
        name = "account.tierId",
        type = "String",
      ).visibleWhen("account.type", "guest"),
      TrailblazeToolParameterDescriptor(
        name = "account.email",
        type = "String",
      ).visibleWhen("account.type", "member"),
      TrailblazeToolParameterDescriptor(name = "startingRoute", type = "String"),
    )

    assertEquals(
      listOf("account.tierId", "startingRoute"),
      visibleParameters(parameters, mapOf("account.type" to "guest")).map { it.name },
    )
  }

  @Test
  fun `buildSingleToolYaml skips stale values for hidden associated fields`() {
    val descriptor = TrailblazeToolDescriptor(
      name = "loginAsUser",
      requiredParameters = listOf(
        TrailblazeToolParameterDescriptor(
          name = "account.type",
          type = "String",
          validValues = listOf("guest", "member"),
        ),
      ),
      optionalParameters = listOf(
        TrailblazeToolParameterDescriptor(
          name = "account.tierId",
          type = "String",
        ).visibleWhen("account.type", "guest"),
        TrailblazeToolParameterDescriptor(
          name = "account.email",
          type = "String",
        ).visibleWhen("account.type", "member"),
      ),
    )

    assertEquals(
      """
      loginAsUser:
        account:
          type: guest
          tierId: tier-1
      """.trimIndent(),
      buildSingleToolYaml(
        descriptor,
        mapOf(
          "account.type" to "guest",
          "account.tierId" to "tier-1",
          "account.email" to "stale@example.com",
        ),
      ),
    )
  }
}

private fun TrailblazeToolParameterDescriptor.visibleWhen(
  parameterName: String,
  vararg values: String,
): TrailblazeToolParameterDescriptor = also {
  visibleWhen = TrailblazeToolParameterVisibility(
    parameterName = parameterName,
    values = values.toList(),
  )
}
