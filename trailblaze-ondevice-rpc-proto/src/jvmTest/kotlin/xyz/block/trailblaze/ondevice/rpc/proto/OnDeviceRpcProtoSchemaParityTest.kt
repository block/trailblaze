package xyz.block.trailblaze.ondevice.rpc.proto

import com.squareup.wire.WireField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.ScreenshotScalingConfig as ModelScreenshotScalingConfig
import xyz.block.trailblaze.api.TrailblazeNode as ModelTrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode as ModelViewHierarchyNode
import xyz.block.trailblaze.agent.ExecutionStatus as ModelExecutionStatus
import xyz.block.trailblaze.agent.TrailblazeProgressEvent as ModelProgressEvent
import xyz.block.trailblaze.devices.TrailblazeDeviceId as ModelDeviceId
import xyz.block.trailblaze.llm.RunYamlRequest as ModelRunYamlRequest
import xyz.block.trailblaze.llm.RunYamlResponse as ModelRunYamlResponse
import xyz.block.trailblaze.llm.TrailblazeLlmModel as ModelLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider as ModelLlmProvider
import xyz.block.trailblaze.llm.TrailblazeReferrer as ModelReferrer
import xyz.block.trailblaze.mcp.android.ondevice.rpc.DrainSessionRequest as ModelDrainSessionRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.DrainSessionResponse as ModelDrainSessionResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetExecutionStatusRequest as ModelGetExecutionStatusRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetExecutionStatusResponse as ModelGetExecutionStatusResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateRequest as ModelGetScreenStateRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse as ModelGetScreenStateResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.ListActiveSessionsRequest as ModelListActiveSessionsRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.ListActiveSessionsResponse as ModelListActiveSessionsResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.mcp.android.ondevice.rpc.SubscribeToProgressRequest as ModelSubscribeToProgressRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.SubscribeToProgressResponse as ModelSubscribeToProgressResponse
import xyz.block.trailblaze.model.TrailblazeConfig as ModelTrailblazeConfig

/**
 * Fails when a field is added to either side of a model/protobuf transform without updating the
 * other side. Intentional representation changes are documented as aliases or ignored fields at
 * the comparison site.
 */
class OnDeviceRpcProtoSchemaParityTest {

  @Test
  fun `RPC model fields have matching protobuf fields`() {
    assertParity(
      ModelGetScreenStateRequest.serializer(),
      GetScreenStateRequest::class.java,
      aliases = mapOf(
        "screenshotMaxDimension1" to setOf("screenshot_max_dimension_1"),
        "screenshotMaxDimension2" to setOf("screenshot_max_dimension_2"),
      ),
    )
    assertParity(
      ModelGetScreenStateResponse.serializer(),
      GetScreenStateResponse::class.java,
      aliases = mapOf(
        "screenshotBase64" to setOf("screenshot"),
        "annotatedScreenshotBase64" to setOf("annotated_screenshot"),
      ),
    )
    assertParity(
      ModelRunYamlRequest.serializer(),
      RunYamlRequest::class.java,
      aliases = mapOf(
        "trailblazeDeviceId" to setOf("device_id"),
        "trailblazeLlmModel" to setOf("llm_model"),
      ),
    )
    assertParity(
      ModelRunYamlResponse.serializer(),
      RunYamlResponse::class.java,
      aliases = mapOf("toolStructuredContent" to setOf("tool_structured_content_json")),
    )
    assertParity(ModelDrainSessionRequest.serializer(), DrainSessionRequest::class.java)
    assertParity(ModelDrainSessionResponse.serializer(), DrainSessionResponse::class.java)
    assertParity(
      ModelSubscribeToProgressRequest.serializer(),
      SubscribeToProgressRequest::class.java,
    )
    assertParity(
      ModelSubscribeToProgressResponse.serializer(),
      SubscribeToProgressResponse::class.java,
    )
    assertParity(
      ModelGetExecutionStatusRequest.serializer(),
      GetExecutionStatusRequest::class.java,
    )
    assertParity(
      ModelGetExecutionStatusResponse.serializer(),
      GetExecutionStatusResponse::class.java,
    )
    assertParity(
      ModelListActiveSessionsRequest.serializer(),
      ListActiveSessionsRequest::class.java,
    )
    assertParity(
      ModelListActiveSessionsResponse.serializer(),
      ListActiveSessionsResponse::class.java,
    )
  }

  @Test
  fun `nested RPC model fields have matching protobuf fields`() {
    assertParity(
      ModelDeviceId.serializer(),
      DeviceId::class.java,
      aliases = mapOf("trailblazeDevicePlatform" to setOf("platform")),
    )
    assertParity(
      ModelLlmModel.serializer(),
      LlmModel::class.java,
      aliases = mapOf("trailblazeLlmProvider" to setOf("provider")),
      // Derived locally from capabilityIds rather than transported independently.
      ignoredModelFields = setOf("capabilities"),
    )
    assertParity(ModelLlmProvider.serializer(), LlmProvider::class.java)
    assertParity(
      ModelScreenshotScalingConfig.serializer(),
      ScreenshotScalingConfig::class.java,
      aliases = mapOf(
        "maxDimension1" to setOf("max_dimension_1"),
        "maxDimension2" to setOf("max_dimension_2"),
      ),
    )
    assertParity(ModelTrailblazeConfig.serializer(), TrailblazeConfig::class.java)
    assertParity(ModelReferrer.serializer(), Referrer::class.java)
    assertParity(ModelExecutionStatus.serializer(), ExecutionStatus::class.java)
    assertParity(
      RpcResult.Failure.serializer(),
      RpcFailure::class.java,
      // These are host-side call context, not response payload fields.
      ignoredModelFields = setOf("method", "url"),
    )
  }

  @Test
  fun `screen tree model fields have matching protobuf fields`() {
    assertParity(ModelViewHierarchyNode.serializer(), ViewHierarchyNode::class.java)
    assertParity(
      ModelTrailblazeNode.serializer(),
      TrailblazeNode::class.java,
      aliases = mapOf(
        "driverDetail" to setOf("android_accessibility", "android_maestro"),
      ),
    )
    assertParity(ModelTrailblazeNode.Bounds.serializer(), Bounds::class.java)
    assertParity(
      DriverNodeDetail.AndroidAccessibility.serializer(),
      AndroidAccessibilityDetail::class.java,
    )
    assertParity(
      DriverNodeDetail.AndroidAccessibility.CollectionInfo.serializer(),
      CollectionInfo::class.java,
    )
    assertParity(
      DriverNodeDetail.AndroidAccessibility.CollectionItemInfo.serializer(),
      CollectionItemInfo::class.java,
    )
    assertParity(
      DriverNodeDetail.AndroidAccessibility.RangeInfo.serializer(),
      RangeInfo::class.java,
    )
    assertParity(
      DriverNodeDetail.AndroidMaestro.serializer(),
      AndroidMaestroDetail::class.java,
    )
  }

  @Test
  fun `progress event variants and fields have matching protobuf fields`() {
    val modelVariants = ModelProgressEvent::class.java.declaredClasses
      .filter(ModelProgressEvent::class.java::isAssignableFrom)
      .map { it.simpleName.toSnakeCase() }
      .toSet()
    val protoVariants = wireFields(ProgressEvent::class.java) - PROGRESS_COMMON_FIELDS
    assertEquals(
      modelVariants,
      protoVariants,
      "Progress-event variants differ. Add the new variant to the protobuf envelope and parity cases.",
    )

    assertProgressParity(ModelProgressEvent.StepStarted.serializer(), StepStarted::class.java)
    assertProgressParity(ModelProgressEvent.StepCompleted.serializer(), StepCompleted::class.java)
    assertProgressParity(ModelProgressEvent.SubtaskProgress.serializer(), SubtaskProgress::class.java)
    assertProgressParity(ModelProgressEvent.SubtaskCompleted.serializer(), SubtaskCompleted::class.java)
    assertProgressParity(ModelProgressEvent.TaskReplanned.serializer(), TaskReplanned::class.java)
    assertProgressParity(
      ModelProgressEvent.ReflectionTriggered.serializer(),
      ReflectionTriggered::class.java,
    )
    assertProgressParity(
      ModelProgressEvent.BacktrackPerformed.serializer(),
      BacktrackPerformed::class.java,
    )
    assertProgressParity(ModelProgressEvent.ExceptionHandled.serializer(), ExceptionHandled::class.java)
    assertProgressParity(ModelProgressEvent.FactStored.serializer(), FactStored::class.java)
    assertProgressParity(ModelProgressEvent.FactRecalled.serializer(), FactRecalled::class.java)
    assertProgressParity(ModelProgressEvent.ExecutionStarted.serializer(), ExecutionStarted::class.java)
    assertProgressParity(
      ModelProgressEvent.ExecutionCompleted.serializer(),
      ExecutionCompleted::class.java,
    )
  }

  private fun assertProgressParity(
    modelSerializer: KSerializer<*>,
    payloadClass: Class<*>,
  ) {
    assertParity(
      modelSerializer,
      protoFields = PROGRESS_COMMON_FIELDS + wireFields(payloadClass),
      label = "${modelSerializer.descriptor.serialName} <-> ${payloadClass.simpleName}",
    )
  }

  private fun assertParity(
    modelSerializer: KSerializer<*>,
    protoClass: Class<*>,
    aliases: Map<String, Set<String>> = emptyMap(),
    ignoredModelFields: Set<String> = emptySet(),
  ) {
    assertParity(
      modelSerializer = modelSerializer,
      protoFields = wireFields(protoClass),
      label = "${modelSerializer.descriptor.serialName} <-> ${protoClass.simpleName}",
      aliases = aliases,
      ignoredModelFields = ignoredModelFields,
    )
  }

  private fun assertParity(
    modelSerializer: KSerializer<*>,
    protoFields: Set<String>,
    label: String,
    aliases: Map<String, Set<String>> = emptyMap(),
    ignoredModelFields: Set<String> = emptySet(),
  ) {
    val modelFields = modelSerializer.descriptor.fieldNames()
    val mappedModelFields = modelFields
      .filterNot(ignoredModelFields::contains)
      .flatMap { aliases[it] ?: setOf(it.toSnakeCase()) }
      .toSet()

    assertEquals(
      mappedModelFields,
      protoFields,
      buildString {
        appendLine("Model/protobuf field mismatch for $label.")
        appendLine("Every model field must map to protobuf or be an explicit transport exception.")
        append("Model fields: $modelFields")
      },
    )
  }

  private fun SerialDescriptor.fieldNames(): Set<String> =
    (0 until elementsCount).map(::getElementName).toSet()

  private fun wireFields(protoClass: Class<*>): Set<String> =
    protoClass.declaredFields
      .filter { it.getAnnotation(WireField::class.java) != null }
      .map { it.name }
      .toSet()

  private fun String.toSnakeCase(): String =
    replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()

  private companion object {
    val PROGRESS_COMMON_FIELDS = setOf("timestamp", "session_id", "device_id")
  }
}
