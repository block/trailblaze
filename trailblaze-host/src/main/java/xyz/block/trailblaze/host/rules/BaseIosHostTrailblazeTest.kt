package xyz.block.trailblaze.host.rules

import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import kotlin.reflect.KClass

abstract class BaseIosHostTrailblazeTest(
  setOfMarkEnabled: Boolean = true,
  systemPromptTemplate: String? = null,
  trailblazeToolSet: TrailblazeToolSet? = null,
  customToolClasses: Set<KClass<out TrailblazeTool>> = setOf(),
) : BaseHostTrailblazeTest(
  trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
  setOfMarkEnabled = setOfMarkEnabled,
  systemPromptTemplate = systemPromptTemplate,
  trailblazeToolSet = trailblazeToolSet,
  customToolClasses = customToolClasses,
)
