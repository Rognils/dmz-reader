package se.c64.dmzreader.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import se.c64.dmzreader.AppContainer
import se.c64.dmzreader.ui.favorites.FavoritesScreen
import se.c64.dmzreader.ui.library.LibraryScreen
import se.c64.dmzreader.ui.library.LibraryViewModel
import se.c64.dmzreader.ui.search.SearchScreen
import se.c64.dmzreader.ui.settings.DiagnosticsScreen
import se.c64.dmzreader.ui.settings.DiagnosticsViewModel
import se.c64.dmzreader.ui.settings.SettingsScreen
import se.c64.dmzreader.ui.statistics.StatisticsScreen
import se.c64.dmzreader.ui.theme.DmzColors

private object Routes {
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val FAVORITES = "favorites"
    const val STATISTICS = "statistics"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "settings/diagnostics"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

/** De fyra flikarna i planens E1. */
private val tabs = listOf(
    Tab(Routes.LIBRARY, "Bibliotek", Icons.AutoMirrored.Filled.List),
    Tab(Routes.SEARCH, "Sök", Icons.Filled.Search),
    Tab(Routes.FAVORITES, "Favoriter", Icons.Filled.Favorite),
    Tab(Routes.STATISTICS, "Statistik", Icons.Filled.Info),
)

@Composable
fun DmzReaderRoot(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination

    Scaffold(
        containerColor = DmzColors.Background,
        bottomBar = {
            NavigationBar(containerColor = DmzColors.Surface) {
                tabs.forEach { tab ->
                    // Inställningar nås från Bibliotek och räknas dit.
                    val selected = destination?.hierarchy?.any { it.route == tab.route } == true ||
                        (tab.route == Routes.LIBRARY && destination?.route?.startsWith(Routes.SETTINGS) == true)
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = DmzColors.Background,
                            selectedTextColor = DmzColors.Accent,
                            indicatorColor = DmzColors.Accent,
                            unselectedIconColor = DmzColors.TextSecondary,
                            unselectedTextColor = DmzColors.TextSecondary,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.LIBRARY,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.LIBRARY) {
                val viewModel: LibraryViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { LibraryViewModel(container.libraryRepository, container.inventoryStatus) }
                    },
                )
                LibraryScreen(viewModel, onOpenSettings = { navController.navigate(Routes.SETTINGS) })
            }
            composable(Routes.SEARCH) { SearchScreen() }
            composable(Routes.FAVORITES) { FavoritesScreen() }
            composable(Routes.STATISTICS) { StatisticsScreen() }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                )
            }
            composable(Routes.DIAGNOSTICS) {
                val viewModel: DiagnosticsViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            DiagnosticsViewModel(
                                container.libraryRepository,
                                container.activeInventory,
                                container.inventoryStatus,
                                container.diagnosticsRepository,
                            )
                        }
                    },
                )
                DiagnosticsScreen(viewModel, onBack = { navController.popBackStack() })
            }
        }
    }
}

