package com.fenix.ia.presentation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fenix.ia.presentation.projects.ProjectListScreen
import com.fenix.ia.presentation.settings.SettingsScreen

object Routes {
    const val PROJECTS = "projects"
    const val CHAT = "chat/{chatId}"
    const val SETTINGS = "settings"

    fun chat(chatId: String) = "chat/$chatId"
}

@Composable
fun FenixNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.PROJECTS) {
        composable(Routes.PROJECTS) {
            ProjectListScreen(
                onNavigateToChat = { chatId ->
                    navController.navigate(Routes.chat(chatId))
                },
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS)
                }
            )
        }
        composable(Routes.CHAT) { backStackEntry ->
            val chatId = backStackEntry.arguments?.getString("chatId") ?: return@composable
            // ChatScreen(chatId = chatId, onBack = { navController.popBackStack() })
            // TODO: implementar en siguiente sesión
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
