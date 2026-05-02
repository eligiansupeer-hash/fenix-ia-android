package com.fenix.ia.presentation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.fenix.ia.presentation.artifacts.ArtifactsScreen
import com.fenix.ia.presentation.chat.ChatScreen
import com.fenix.ia.presentation.chat.GeneralChatListScreen
import com.fenix.ia.presentation.projects.ProjectDetailScreen
import com.fenix.ia.presentation.projects.ProjectListScreen
import com.fenix.ia.presentation.research.ResearchScreen
import com.fenix.ia.presentation.settings.SettingsScreen
import com.fenix.ia.presentation.tools.ToolsScreen
import com.fenix.ia.presentation.workflow.WorkflowScreen

object Routes {
    const val PROJECTS            = "projects"
    const val PROJECT_DETAIL      = "project/{projectId}"
    const val CHAT                = "chat/{projectId}/{chatId}"
    const val SETTINGS            = "settings"
    const val WORKFLOW            = "workflow/{projectId}"
    const val RESEARCH            = "research/{projectId}"
    const val TOOLS               = "tools/{projectId}"
    const val ARTIFACTS           = "artifacts/{projectId}"

    // P4: rutas para chats generales (sin proyecto)
    const val GENERAL_CHATS_LIST  = "general_chats"
    const val CHAT_GENERAL        = "chat_general/{chatId}"

    fun projectDetail(projectId: String) = "project/$projectId"
    fun chat(projectId: String, chatId: String) = "chat/$projectId/$chatId"
    fun workflow(projectId: String) = "workflow/$projectId"
    fun research(projectId: String) = "research/$projectId"
    fun tools(projectId: String) = "tools/$projectId"
    fun artifacts(projectId: String) = "artifacts/$projectId"

    // P4: helper para chat general
    fun chatGeneral(chatId: String) = "chat_general/$chatId"
}

@Composable
fun FenixNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.PROJECTS) {

        composable(Routes.PROJECTS) {
            ProjectListScreen(
                onNavigateToProject     = { navController.navigate(Routes.projectDetail(it)) },
                onNavigateToSettings    = { navController.navigate(Routes.SETTINGS) },
                onNavigateToGeneralChats = { navController.navigate(Routes.GENERAL_CHATS_LIST) }
            )
        }

        composable(
            route     = Routes.PROJECT_DETAIL,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) { back ->
            val projectId = back.arguments?.getString("projectId") ?: return@composable
            ProjectDetailScreen(
                projectId             = projectId,
                onNavigateToChat      = { navController.navigate(Routes.chat(projectId, it)) },
                onNavigateToWorkflow  = { navController.navigate(Routes.workflow(projectId)) },
                onNavigateToResearch  = { navController.navigate(Routes.research(projectId)) },
                onNavigateToTools     = { navController.navigate(Routes.tools(projectId)) },
                onNavigateToArtifacts = { navController.navigate(Routes.artifacts(projectId)) },
                onBack                = { navController.popBackStack() }
            )
        }

        composable(
            route     = Routes.CHAT,
            arguments = listOf(
                navArgument("projectId") { type = NavType.StringType },
                navArgument("chatId")    { type = NavType.StringType }
            )
        ) { back ->
            val projectId = back.arguments?.getString("projectId") ?: return@composable
            val chatId    = back.arguments?.getString("chatId")    ?: return@composable
            ChatScreen(
                chatId    = chatId,
                projectId = projectId,
                onBack    = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route     = Routes.WORKFLOW,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) { back ->
            val projectId = back.arguments?.getString("projectId") ?: return@composable
            WorkflowScreen(
                projectId = projectId,
                onBack    = { navController.popBackStack() }
            )
        }

        composable(
            route     = Routes.RESEARCH,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) { back ->
            val projectId = back.arguments?.getString("projectId") ?: return@composable
            ResearchScreen(
                projectId = projectId,
                onBack    = { navController.popBackStack() }
            )
        }

        composable(
            route     = Routes.TOOLS,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) { back ->
            val projectId = back.arguments?.getString("projectId") ?: return@composable
            ToolsScreen(
                projectId = projectId,
                onBack    = { navController.popBackStack() }
            )
        }

        composable(
            route     = Routes.ARTIFACTS,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) { back ->
            val projectId = back.arguments?.getString("projectId") ?: return@composable
            ArtifactsScreen(
                projectId = projectId,
                onBack    = { navController.popBackStack() }
            )
        }

        // P4: Lista de conversaciones globales
        composable(Routes.GENERAL_CHATS_LIST) {
            GeneralChatListScreen(
                onNavigateToChat = { chatId -> navController.navigate(Routes.chatGeneral(chatId)) },
                onBack           = { navController.popBackStack() }
            )
        }

        // P4: Chat general (projectId vacío, sin documentos de proyecto)
        composable(
            route     = Routes.CHAT_GENERAL,
            arguments = listOf(navArgument("chatId") { type = NavType.StringType })
        ) { back ->
            val chatId = back.arguments?.getString("chatId") ?: return@composable
            ChatScreen(
                chatId    = chatId,
                projectId = "",    // sin proyecto
                onBack    = { navController.popBackStack() }
            )
        }
    }
}
