package xyz.block.trailblaze.ui.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.navigation.NavHostController
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.ui.icons.McpLogo
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig

/**
 * Factory for building [RunYamlRequest] pre-populated with desktop app config defaults.
 *
 * Obtain via [LocalRunYamlRequestFactory]. Call sites only need to supply the
 * per-run fields — device, yaml, testName, and referrer.
 */
class RunYamlRequestFactory(
  private val appConfig: SavedTrailblazeAppConfig,
  private val llmModel: TrailblazeLlmModel,
) {
  fun create(
    device: TrailblazeConnectedDeviceSummary,
    yaml: String,
    testName: String,
    referrer: TrailblazeReferrer,
    trailFilePath: String? = null,
    useRecordedSteps: Boolean = true,
    agentImplementation: AgentImplementation = appConfig.agentImplementation,
  ): RunYamlRequest = RunYamlRequest(
    testName = testName,
    yaml = yaml,
    trailblazeLlmModel = llmModel,
    useRecordedSteps = useRecordedSteps,
    targetAppName = appConfig.selectedTargetAppId,
    config = TrailblazeConfig(
      setOfMarkEnabled = appConfig.setOfMarkEnabled,
      aiFallback = appConfig.aiFallbackEnabled,
      overrideSessionId = null,
    ),
    trailFilePath = trailFilePath,
    trailblazeDeviceId = device.trailblazeDeviceId,
    driverType = device.trailblazeDriverType,
    referrer = referrer,
    agentImplementation = agentImplementation,
  )
}

/**
 * CompositionLocal for accessing the NavController anywhere in the Compose tree.
 * This enables custom tabs and other composables to navigate without prop drilling.
 */
val LocalNavController = compositionLocalOf<NavHostController> {
  error("No NavController provided. Make sure LocalNavController is provided at the top level.")
}

/**
 * CompositionLocal for accessing [RunYamlRequestFactory] anywhere in the Compose tree.
 * Provided by each tab that runs YAML tests, pre-configured with the current app defaults
 * (LLM model, target app, set-of-mark, etc.) so call sites only specify per-run fields.
 */
val LocalRunYamlRequestFactory = compositionLocalOf<RunYamlRequestFactory> {
  error("No RunYamlRequestFactory provided.")
}

/**
 * Extension function for navigation to TrailblazeRoute destinations.
 * All routes (built-in and external) use string-based navigation via qualified class name.
 * 
 * @param route The TrailblazeRoute to navigate to
 * @param launchSingleTop Whether to launch the route as single top (defaults to true)
 */
fun NavHostController.navigateToRoute(route: TrailblazeRoute, launchSingleTop: Boolean = true) {
  // All routes use string-based navigation via qualified class name
  // This matches how routes are registered in NavHost via composable(routeString)
  val routeString = route::class.qualifiedName ?: route.toString()
  navigate(routeString) {
    this.launchSingleTop = launchSingleTop
  }
}

/**
 * Base interface for all navigation routes in the Trailblaze app.
 * Built-in routes use type-safe navigation with Kotlin serialization.
 * 
 * External modules can create their own routes by implementing this interface
 * and annotating them with @Serializable for full type-safe navigation support.
 * 
 * Example:
 * ```
 * @Serializable
 * data object MyCustomRoute : TrailblazeRoute {
 *   override val displayName = "My Custom Tab"
 *   override val icon: @Composable () -> Unit = {
 *     Icon(Icons.Default.Star, contentDescription = "My Tab")
 *   }
 * }
 * ```
 */
interface TrailblazeRoute {
  val displayName: String
  val isEnabled: Boolean
    get() = true
  val icon: @Composable () -> Unit

  @Serializable
  data object Home : TrailblazeRoute {
    override val displayName = "Home"
    override val icon: @Composable () -> Unit = {
      Icon(Icons.Filled.Home, contentDescription = "Home")
    }
  }

  @Serializable
  data object Sessions : TrailblazeRoute {
    override val displayName = "Sessions"
    override val icon: @Composable () -> Unit = {
      Icon(Icons.Filled.List, contentDescription = "Sessions")
    }
  }

  @Serializable
  data object YamlRoute : TrailblazeRoute {
    override val displayName = "Yaml"
    override val icon: @Composable () -> Unit = {
      Icon(Icons.Filled.Code, contentDescription = "Yaml")
    }
  }

  @Serializable
  data object Devices : TrailblazeRoute {
    override val displayName = "Devices"
    override val icon: @Composable () -> Unit = {
      Icon(Icons.Filled.Smartphone, contentDescription = "Devices")
    }
  }

  @Serializable
  data object Settings : TrailblazeRoute {
    override val displayName = "Settings"
    override val icon: @Composable () -> Unit = {
      Icon(Icons.Filled.Settings, contentDescription = "Settings")
    }
  }

  @Serializable
  data object Trails : TrailblazeRoute {
    override val displayName = "Trails"
    override val icon: @Composable () -> Unit = {
      Icon(Icons.Filled.Hiking, contentDescription = "Trails")
    }
  }

  @Serializable
  data object Record : TrailblazeRoute {
    override val displayName = "Record"
    override val icon: @Composable () -> Unit = {
      Icon(Icons.Filled.FiberManualRecord, contentDescription = "Record")
    }
  }

  @Serializable
  data object Mcp : TrailblazeRoute {
    override val displayName = "MCP"
    override val icon: @Composable () -> Unit = {
      Icon(McpLogo, contentDescription = "MCP")
    }
  }
}
