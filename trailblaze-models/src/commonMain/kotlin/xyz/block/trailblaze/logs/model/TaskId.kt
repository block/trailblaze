package xyz.block.trailblaze.logs.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
@JvmInline
value class TaskId @PublishedApi internal constructor(
  val taskId: String,
) {
  companion object {
    @OptIn(ExperimentalUuidApi::class)
    fun generate(): TaskId = TaskId(taskId = Uuid.random().toString())
  }
}
