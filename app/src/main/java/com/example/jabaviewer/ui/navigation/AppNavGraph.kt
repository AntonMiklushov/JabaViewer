package com.example.jabaviewer.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.jabaviewer.ui.screens.details.ItemDetailsScreen
import com.example.jabaviewer.ui.screens.library.LibraryScreen
import com.example.jabaviewer.ui.screens.onboarding.OnboardingScreen
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
        composable(Routes.Onboarding) {
            OnboardingScreen(
                onContinue = {
                    navController.navigate(Routes.Library) {
                        popUpTo(Routes.Onboarding) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.Library) {
            LibraryScreen(
                onOpenDetails = { itemId -> navController.navigate(Routes.details(itemId)) },
                onOpenReader = { itemId -> navController.navigate(Routes.reader(itemId)) },
                onOpenSettings = { navController.navigate(Routes.Settings) },
            )
        }
        composable(Routes.Settings) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.DetailsRoute,
            arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val itemId = backStackEntry.arguments?.getString("itemId").orEmpty()
            ItemDetailsScreen(
                onBack = { navController.popBackStack() },
                onOpenReader = { navController.navigate(Routes.reader(itemId)) },
            )
        }
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
}
