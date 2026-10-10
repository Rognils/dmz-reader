package se.c64.dmzreader.ui

import se.c64.dmzreader.ui.statistics.StatisticsViewModel
import android.net.Uri
import se.c64.dmzreader.ui.search.IssueSearchSection
import se.c64.dmzreader.ui.search.IssueSearchViewModel
import se.c64.dmzreader.ui.settings.SearchIndexViewModel
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import se.c64.dmzreader.AppContainer
import se.c64.dmzreader.BuildConfig
import se.c64.dmzreader.ui.about.AboutScreen
import se.c64.dmzreader.ui.components.BarChartIcon
import se.c64.dmzreader.ui.favorites.FavoritesScreen
import se.c64.dmzreader.ui.favorites.FavoritesViewModel
import se.c64.dmzreader.ui.issue.IssueScreen
import se.c64.dmzreader.ui.issue.IssueViewModel
import se.c64.dmzreader.ui.library.LibraryScreen
import se.c64.dmzreader.ui.library.LibraryViewModel
import se.c64.dmzreader.ui.reader.ReaderRoute
import se.c64.dmzreader.ui.reader.ReaderViewModel
import se.c64.dmzreader.ui.search.SearchScreen
import se.c64.dmzreader.ui.search.SearchViewModel
import se.c64.dmzreader.ui.settings.ArchiveIndexViewModel
import se.c64.dmzreader.ui.settings.DiagnosticsScreen
import se.c64.dmzreader.ui.settings.DiagnosticsViewModel
import se.c64.dmzreader.ui.settings.SettingsScreen
import se.c64.dmzreader.ui.settings.SettingsViewModel
import se.c64.dmzreader.ui.statistics.StatisticsScreen
import se.c64.dmzreader.ui.theme.DmzColors
import se.c64.dmzreader.ui.year.YearScreen
import se.c64.dmzreader.ui.year.YearViewModel

/**
 * Navigationens uppbyggnad:
 *
 * - Fyra flikar med bottenfält. Fliken Bibliotek är ett eget delträd:
 *   Bibliotek → Årgång → Nummer.
 * - Inställningar och Diagnostik är undersidor utan bottenfält.
 */
private object Routes {
    const val LIBRARY_TAB = "library_tab"
    const val LIBRARY = "library"
    const val YEAR = "year/{publicationId}/{year}"
    const val ISSUE = "issue/{issueId}"
    const val SEARCH = "search"
    const val FAVORITES = "favorites"
    const val STATISTICS = "statistics"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "settings/diagnostics"
    const val ABOUT = "about"
    const val READER = "reader/{issueId}?page={page}&q={q}"

    /**
     * Läsaren för ett nummer. Med [page] öppnas den på den sidan, annars på läspositionen.
     * Med [query] visas träffraden för den sökningen.
     */
    fun reader(issueId: String, page: Int? = null, query: String? = null): String {
        val arguments = listOfNotNull(page?.let { "page=$it" }, query?.takeIf { it.isNotBlank() }?.let { "q=${Uri.encode(it)}" })
        return "reader/$issueId" + if (arguments.isEmpty()) "" else arguments.joinToString("&", prefix = "?")
    }
    fun year(publicationId: String, year: Int) = "year/$publicationId/$year"
    fun issue(issueId: String) = "issue/$issueId"

    /** Vyer som visas utan bottenfält. */
    val withoutBottomBar = setOf(SETTINGS, DIAGNOSTICS, READER)
}

/** Värdet på sidargumentet när ingen sida är begärd. */
private const val NO_PAGE = -1

private data class Tab(val route: String, val label: String, val icon: ImageVector)

/** De fyra flikarna i planens E1. */
private val tabs = listOf(
    Tab(Routes.LIBRARY_TAB, "Bibliotek", Icons.AutoMirrored.Filled.List),
    Tab(Routes.SEARCH, "Sök", Icons.Filled.Search),
    Tab(Routes.FAVORITES, "Favoriter", Icons.Filled.Favorite),
    Tab(Routes.STATISTICS, "Statistik", BarChartIcon),
)

@Composable
fun DmzReaderRoot(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val showBottomBar = destination?.route !in Routes.withoutBottomBar

    Scaffold(
        containerColor = DmzColors.Background,
        // Läsaren ritar under systemfälten. Övriga vyer håller sig innanför dem.
        contentWindowInsets = if (destination?.route == Routes.READER) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = DmzColors.Surface) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = destination?.hierarchy?.any { it.route == tab.route } == true,
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
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.LIBRARY_TAB,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            navigation(startDestination = Routes.LIBRARY, route = Routes.LIBRARY_TAB) {
                composable(Routes.LIBRARY) {
                    val viewModel: LibraryViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                LibraryViewModel(
                                    container.libraryRepository,
                                    container.libraryPreferences,
                                    container.inventoryStatus,
                                )
                            }
                        },
                    )
                    LibraryScreen(
                        viewModel = viewModel,
                        imageLoader = container.imageLoader,
                        onOpenYear = { publicationId, year -> navController.navigate(Routes.year(publicationId, year)) },
                        onOpenIssue = { navController.navigate(Routes.issue(it)) },
                        onContinueReading = { navController.navigate(Routes.reader(it)) },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                        onOpenAbout = { navController.navigate(Routes.ABOUT) },
                    )
                }
                composable(
                    route = Routes.YEAR,
                    arguments = listOf(
                        navArgument("publicationId") { type = NavType.StringType },
                        navArgument("year") { type = NavType.IntType },
                    ),
                ) { entry ->
                    val publicationId = requireNotNull(entry.arguments?.getString("publicationId"))
                    val year = requireNotNull(entry.arguments).getInt("year")
                    val viewModel: YearViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { YearViewModel(container.libraryRepository, publicationId, year) }
                        },
                    )
                    YearScreen(
                        viewModel = viewModel,
                        imageLoader = container.imageLoader,
                        onOpenIssue = { navController.navigate(Routes.issue(it)) },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(
                    route = Routes.ISSUE,
                    arguments = listOf(navArgument("issueId") { type = NavType.StringType }),
                ) { entry ->
                    val issueId = requireNotNull(entry.arguments?.getString("issueId"))
                    val viewModel: IssueViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                IssueViewModel(
                                    container.libraryRepository,
                                    container.readingRepository,
                                    container.offlineRepository,
                                    container.downloadScheduler,
                                    container.downloadPreferences,
                                    container.bookmarkRepository,
                                    issueId,
                                )
                            }
                        },
                    )
                    IssueScreen(
                        viewModel = viewModel,
                        imageLoader = container.imageLoader,
                        onOpenIssue = { navController.navigate(Routes.issue(it)) },
                        onRead = { navController.navigate(Routes.reader(it)) },
                        onReadPage = { id, pageNumber -> navController.navigate(Routes.reader(id, pageNumber)) },
                        onBack = { navController.popBackStack() },
                        search = {
                            val issueSearch: IssueSearchViewModel = viewModel(
                                key = "issue-search-$issueId",
                                factory = viewModelFactory {
                                    initializer { IssueSearchViewModel(issueId, container.searchIndex, container.libraryRepository) }
                                },
                            )
                            IssueSearchSection(issueSearch) { pageNumber, query ->
                                navController.navigate(Routes.reader(issueId, pageNumber, query))
                            }
                        },
                    )
                }
            }
            composable(
                route = Routes.READER,
                arguments = listOf(
                    navArgument("issueId") { type = NavType.StringType },
                    navArgument("page") {
                        type = NavType.IntType
                        defaultValue = NO_PAGE
                    },
                    navArgument("q") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                val issueId = requireNotNull(entry.arguments?.getString("issueId"))
                val page = entry.arguments?.getInt("page")?.takeIf { it != NO_PAGE }
                val query = entry.arguments?.getString("q")
                val issueSearch: IssueSearchViewModel = viewModel(
                    key = "reader-search",
                    factory = viewModelFactory {
                        initializer {
                            IssueSearchViewModel(issueId, container.searchIndex, container.libraryRepository, query, page)
                        }
                    },
                )
                val viewModel: ReaderViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            ReaderViewModel(
                                issueId,
                                container.libraryRepository,
                                container.pdfRepository,
                                container.readingRepository,
                                container.offlineRepository,
                                container.bookmarkRepository,
                                container.hqRepository,
                                container.thumbnailRoot,
                                container.applicationScope,
                                requestedPage = page,
                                sessionIdleLimitMillis = container.sessionIdleLimitMillis,
                                sessionSaveIntervalMillis = container.sessionSaveIntervalMillis,
                            )
                        }
                    },
                )
                ReaderRoute(viewModel, issueSearch, container.imageLoader, onClose = { navController.popBackStack() })
            }
            composable(Routes.SEARCH) {
                val viewModel: SearchViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            SearchViewModel(
                                container.libraryRepository,
                                container.bookmarkRepository,
                                container.searchIndex,
                                container.activeInventory,
                            )
                        }
                    },
                )
                SearchScreen(
                    viewModel = viewModel,
                    imageLoader = container.imageLoader,
                    onOpenIssue = { navController.navigate(Routes.issue(it)) },
                    onReadPage = { id, pageNumber -> navController.navigate(Routes.reader(id, pageNumber)) },
                    onReadHit = { id, pageNumber, query -> navController.navigate(Routes.reader(id, pageNumber, query)) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.FAVORITES) {
                val viewModel: FavoritesViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            FavoritesViewModel(
                                container.libraryRepository,
                                container.bookmarkRepository,
                                container.readingRepository,
                            )
                        }
                    },
                )
                FavoritesScreen(
                    viewModel = viewModel,
                    imageLoader = container.imageLoader,
                    onOpenIssue = { navController.navigate(Routes.issue(it)) },
                    onReadPage = { id, pageNumber -> navController.navigate(Routes.reader(id, pageNumber)) },
                )
            }
            composable(Routes.STATISTICS) {
                val viewModel: StatisticsViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            StatisticsViewModel(
                                container.libraryRepository,
                                container.bookmarkRepository,
                                container.readingRepository,
                                container.libraryPreferences,
                            )
                        }
                    },
                )
                StatisticsScreen(
                    viewModel = viewModel,
                    onOpenYear = { publicationId, year -> navController.navigate(Routes.year(publicationId, year)) },
                )
            }
            composable(Routes.SETTINGS) {
                val viewModel: SettingsViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            SettingsViewModel(
                                container.offlineRepository,
                                container.pdfRepository,
                                container.libraryRepository,
                                container.downloadPreferences,
                                container.downloadScheduler,
                                container.thumbnailRoot,
                                container.hqCache,
                                container.backupRepository,
                                container.backupPreferences,
                                readDocument = container::readDocument,
                                writeDocument = container::writeDocument,
                            )
                        }
                    },
                )
                val archiveIndexViewModel: ArchiveIndexViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            ArchiveIndexViewModel(container.inventoryManager, container.activeInventory, container::openDocument)
                        }
                    },
                )
                val searchIndexViewModel: SearchIndexViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            SearchIndexViewModel(
                                container.searchIndex,
                                container.activeInventory,
                                container::openDocument,
                                container::documentSize,
                                container.applicationScope,
                            )
                        }
                    },
                )
                SettingsScreen(
                    viewModel = viewModel,
                    archiveIndexViewModel = archiveIndexViewModel,
                    searchIndexViewModel = searchIndexViewModel,
                    onBack = { navController.popBackStack() },
                    onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                    onOpenAbout = { navController.navigate(Routes.ABOUT) },
                )
            }
            composable(Routes.ABOUT) {
                AboutScreen(
                    readText = container::readAboutText,
                    version = BuildConfig.VERSION_NAME,
                    onBack = { navController.popBackStack() },
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
