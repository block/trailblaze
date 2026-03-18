package xyz.block.trailblaze.examples.sampleapp.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SwipeLeft
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import xyz.block.trailblaze.examples.sampleapp.ui.screens.catalog.CatalogScreen
import xyz.block.trailblaze.examples.sampleapp.ui.screens.forms.FormsScreen
import xyz.block.trailblaze.examples.sampleapp.ui.screens.lists.ListDetailScreen
import xyz.block.trailblaze.examples.sampleapp.ui.screens.lists.ListsScreen
import xyz.block.trailblaze.examples.sampleapp.ui.screens.settings.SettingsScreen
import xyz.block.trailblaze.examples.sampleapp.ui.screens.swipe.SwipeScreen
import xyz.block.trailblaze.examples.sampleapp.ui.screens.taps.TapsScreen

enum class Tab(val route: String, val label: String, val icon: ImageVector) {
  TAPS("taps", "Taps", Icons.Default.TouchApp),
  FORMS("forms", "Forms", Icons.Default.TextFields),
  LISTS("lists", "Lists", Icons.AutoMirrored.Filled.List),
  SWIPE("swipe", "Swipe", Icons.Default.SwipeLeft),
  CATALOG("catalog", "Catalog", Icons.Default.ShoppingCart),
  SETTINGS("settings", "Settings", Icons.Default.Settings),
}

@Composable
fun SampleAppNavigation() {
  val navController = rememberNavController()
  Scaffold(
    bottomBar = {
      NavigationBar {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination
        Tab.entries.forEach { tab ->
          NavigationBarItem(
            icon = { Icon(tab.icon, contentDescription = tab.label) },
            label = { Text(tab.label) },
            selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true,
            onClick = {
              navController.navigate(tab.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
              }
            },
          )
        }
      }
    }
  ) { innerPadding ->
    NavHost(
      navController = navController,
      startDestination = Tab.TAPS.route,
      modifier = Modifier.padding(innerPadding),
    ) {
      composable(Tab.TAPS.route) { TapsScreen() }
      composable(Tab.FORMS.route) { FormsScreen() }
      composable(Tab.LISTS.route) {
        ListsScreen(onItemClick = { index -> navController.navigate("list_detail/$index") })
      }
      composable("list_detail/{index}") { backStackEntry ->
        val index = backStackEntry.arguments?.getString("index")?.toIntOrNull() ?: 0
        ListDetailScreen(index = index, onBack = { navController.popBackStack() })
      }
      composable(Tab.SWIPE.route) { SwipeScreen() }
      composable(Tab.CATALOG.route) { CatalogScreen() }
      composable(Tab.SETTINGS.route) { SettingsScreen() }
    }
  }
}
