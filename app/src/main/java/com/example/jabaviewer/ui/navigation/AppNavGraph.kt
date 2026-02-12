package com.example.jabaviewer.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.jabaviewer.core.DocumentFormat
import com.example.jabaviewer.ui.screens.details.ItemDetailsScreen
import com.example.jabaviewer.ui.screens.library.LibraryScreen
import com.example.jabaviewer.ui.screens.onboarding.OnboardingScreen
import com.example.jabaviewer.ui.screens.reader.DjvuViewerScreen
import com.example.jabaviewer.ui.screens.reader.ReaderScreen
import com.example.jabaviewer.ui.screens.settings.SettingsScreen

@Composable
fun AppNavGraph(
    navController: NavHostController,
    startDestination: String,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        onboardingRoute(navController)
        libraryRoute(navController)
        settingsRoute(navController)
        detailsRoute(navController)
        readerRoute(navController)
        djvuViewerRoute(navController)
    }
}

private fun NavGraphBuilder.onboardingRoute(navController: NavHostController) {
    composable(Routes.Onboarding) {
        OnboardingScreen(
            onContinue = {
                navController.navigate(Routes.Library) {
                    popUpTo(Routes.Onboarding) { inclusive = true }
                }
            }
        )
    }
}

private fun NavGraphBuilder.libraryRoute(navController: NavHostController) {
    composable(Routes.Library) {
        LibraryScreen(
            onOpenDetails = { itemId -> navController.navigate(Routes.details(itemId)) },
            onOpenDocument = { item ->
                val route = if (item.format == DocumentFormat.DJVU) {
                    Routes.djvuViewer(item.id)
                } else {
                    Routes.reader(item.id)
                }
                navController.navigate(route)
            },
            onOpenSettings = { navController.navigate(Routes.Settings) },
        )
    }
}

private fun NavGraphBuilder.settingsRoute(navController: NavHostController) {
    composable(Routes.Settings) {
        SettingsScreen(onBack = { navController.popBackStack() })
    }
}

private fun NavGraphBuilder.detailsRoute(navController: NavHostController) {
    composable(
        route = Routes.DetailsRoute,
        arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val itemId = backStackEntry.arguments?.getString("itemId").orEmpty()
        ItemDetailsScreen(
            onBack = { navController.popBackStack() },
            onOpenReader = { navController.navigate(Routes.reader(itemId)) },
            onOpenDjvu = { navController.navigate(Routes.djvuViewer(itemId)) },
        )
    }
}

private fun NavGraphBuilder.readerRoute(navController: NavHostController) {
    composable(
        route = Routes.ReaderRoute,
        arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val itemId = backStackEntry.arguments?.getString("itemId").orEmpty()
        ReaderScreen(
            itemId = itemId,
            onBack = { navController.popBackStack() },
        )
    }
}

private fun NavGraphBuilder.djvuViewerRoute(navController: NavHostController) {
    composable(
        route = Routes.DjvuViewerRoute,
        arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val itemId = backStackEntry.arguments?.getString("itemId").orEmpty()
        DjvuViewerScreen(
            itemId = itemId,
            onBack = { navController.popBackStack() },
        )
    }
}
