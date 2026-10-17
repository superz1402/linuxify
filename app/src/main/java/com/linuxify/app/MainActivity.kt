package com.linuxify.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.linuxify.app.ui.AppViewModel
import com.linuxify.app.ui.screens.FilesScreen
import com.linuxify.app.ui.screens.HomeScreen
import com.linuxify.app.ui.screens.PackagesScreen
import com.linuxify.app.ui.screens.ProjectsScreen
import com.linuxify.app.ui.screens.ServicesScreen
import com.linuxify.app.ui.screens.SettingsScreen
import com.linuxify.app.ui.screens.SetupScreen
import com.linuxify.app.ui.screens.TerminalScreen
import com.linuxify.app.ui.theme.LinuxifyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LinuxifyTheme {
                LinuxifyRoot()
            }
        }
    }
}

private data class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun LinuxifyRoot(vm: AppViewModel = viewModel()) {
    val widthClass = calculateWindowSizeClass(androidx.compose.ui.platform.LocalContext.current as android.app.Activity)
    val compact = widthClass.widthSizeClass == WindowWidthSizeClass.Compact

    val navController = rememberNavController()
    val destinations = listOf(
        Destination("home", "Home", Icons.Filled.Home),
        Destination("terminal", "Terminal", Icons.Filled.Terminal),
        Destination("projects", "Projects", Icons.Filled.Folder),
        Destination("services", "Services", Icons.Filled.Memory),
        Destination("settings", "Settings", Icons.Filled.Settings),
    )
    val primaryDestinations = setOf("home", "terminal", "projects", "services", "settings")

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: "home"

    // Installer gate: when runtime is not ready, show setup instead of main UI.
    val installState by vm.installer.state.collectAsStateWithLifecycle()
    val runtimeReady = installState is com.linuxify.app.runtime.RootfsInstaller.InstallState.Ready ||
        vm.installer.isReadyMarkerPresent()

    if (!runtimeReady) {
        SetupScreen(vm = vm, onInstalled = { vm.refreshHomeFacts() })
        return
    }

    Scaffold(
        bottomBar = {
            if (compact && currentRoute in primaryDestinations) {
                NavigationBar {
                    destinations.forEach { d ->
                        NavigationBarItem(
                            selected = currentRoute == d.route,
                            onClick = {
                                navController.navigate(d.route) {
                                    popUpTo("home") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(d.icon, contentDescription = d.label) },
                            label = { Text(d.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Row(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!compact) {
                NavigationRail {
                    destinations.forEach { d ->
                        NavigationRailItem(
                            selected = currentRoute == d.route,
                            onClick = {
                                navController.navigate(d.route) {
                                    popUpTo("home") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(d.icon, contentDescription = d.label) },
                            label = { Text(d.label) },
                        )
                    }
                }
            }
            LinuxifyNavHost(navController, vm)
        }
    }
}

@Composable
private fun LinuxifyNavHost(navController: NavHostController, vm: AppViewModel) {
    NavHost(navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                vm = vm,
                onOpenTerminal = { navController.navigate("terminal") { launchSingleTop = true } },
                onOpenProjects = { navController.navigate("projects") { launchSingleTop = true } },
                onOpenServices = { navController.navigate("services") { launchSingleTop = true } },
                onOpenPackages = { navController.navigate("packages") { launchSingleTop = true } },
            )
        }
        composable("terminal") { TerminalScreen(vm) }
        composable("projects") {
            ProjectsScreen(
                vm = vm,
                onOpenTerminalIn = { navController.navigate("terminal") { launchSingleTop = true } },
                onBrowseFiles = { navController.navigate("files") { launchSingleTop = true } },
            )
        }
        composable("files") { FilesScreen(vm) }
        composable("packages") { PackagesScreen(vm) }
        composable("services") { ServicesScreen(vm) }
        composable("settings") { SettingsScreen(vm) }
    }
}
