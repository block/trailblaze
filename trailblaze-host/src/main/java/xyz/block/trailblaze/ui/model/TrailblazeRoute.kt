package xyz.block.trailblaze.ui.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.navigation.NavHostController
import kotlinx.serialization.Serializable

/**
 * CompositionLocal for accessing the NavController anywhere in the Compose tree.
 * This enables custom tabs and other composables to navigate without prop drilling.
 */
val LocalNavController = compositionLocalOf<NavHostController> {
  error("No NavController provided. Make sure LocalNavController is provided at the top level.")
}

/**
 * Extension function for type-safe navigation to TrailblazeRoute destinations.
 * Handles both built-in routes (with type-safe navigation) and external custom routes.
 * 
 * @param route The TrailblazeRoute to navigate to
 * @param launchSingleTop Whether to launch the route as single top (defaults to true)
 */
fun NavHostController.navigateToRoute(route: TrailblazeRoute, launchSingleTop: Boolean = true) {
  when (route) {
    // Built-in framework routes use type-safe navigation
    is TrailblazeRoute.Sessions,
    is TrailblazeRoute.Devices,
    is TrailblazeRoute.YamlRoute,
    is TrailblazeRoute.Settings -> {
      navigate(route) {
        this.launchSingleTop = launchSingleTop
      }
    }
    // External routes use string-based navigation (can't use composable<T> for unknown types)
    else -> {
      val routeString = route::class.qualifiedName ?: route.toString()
      navigate(routeString) {
        this.launchSingleTop = launchSingleTop
      }
    }
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
}
