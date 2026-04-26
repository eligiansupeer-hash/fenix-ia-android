package com.fenix.ia.presentation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.fenix.ia.presentation.chat.ChatScreen
import com.fenix.ia.presentation.projects.ProjectDetailScreen
import com.fenix.ia.presentation.projects.ProjectListScreen
import com.fenix.ia.presentation.settings.SettingsScreen

object Routes {
    const val PROJECTS = "projects"
    const val PROJECT_DETAIL = "project/{projectId}"
    const val CHAT = "chat/{projectId}/{chatId}"
    const val SETTINGS = "settings"

    fun projectDetail(projectId: String) = "project/$projectId"
    fun chat(projectId: String, chatId: String) = "chat/$projectId/$chatId"
}

@Composable
fun FenixNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.PROJECTS) {

        composable(Routes.PROJECTS) {
            ProjectListScreen(
                onNavigateToProject = { projectId ->
                    navController.navigate(Routes.projectDetail(projectId))
                },
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS)
                }
            )
        }

        composable(
            route = Routes.PROJECT_DETAIL,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId") ?: return@composable
            ProjectDetailScreen(
                projectId = projectId,
                onNavigateToChat = { chatId ->
                    navController.navigate(Routes.chat(projectId, chatId))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument("projectId") { type = NavType.StringType },
                navArgument("chatId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId") ?: return@composable
            val chatId = backStackEntry.arguments?.getString("chatId") ?: return@composable
            ChatScreen(
                chatId = chatId,
                projectId = projectId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
