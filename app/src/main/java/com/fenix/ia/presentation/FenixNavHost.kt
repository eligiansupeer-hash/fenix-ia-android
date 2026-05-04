package com.fenix.ia.presentation

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.hilt.navigation.compose.hiltViewModel
import com.fenix.ia.audit.AuditLogger
import com.fenix.ia.presentation.artifacts.ArtifactsScreen
import com.fenix.ia.presentation.chat.ChatScreen
import com.fenix.ia.presentation.chat.GeneralChatListViewModel
import com.fenix.ia.presentation.chat.GeneralChatListScreen
import com.fenix.ia.presentation.drawer.FenixDrawerContent
import com.fenix.ia.presentation.drawer.FenixDrawerViewModel
import com.fenix.ia.presentation.files.FilePreviewScreen
import com.fenix.ia.presentation.projects.ProjectDetailScreen
import com.fenix.ia.presentation.projects.ProjectListScreen
import com.fenix.ia.presentation.research.ResearchScreen
import com.fenix.ia.presentation.settings.SettingsScreen
import com.fenix.ia.presentation.sistema.SistemaFenixScreen
import com.fenix.ia.presentation.tools.ToolsScreen
import com.fenix.ia.presentation.workflow.WorkflowScreen
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

object Routes {
    const val PROJECTS            = "projects"
    const val PROJECTS_CREATE     = "projects_create"
    const val PROJECT_DETAIL      = "project/{projectId}"
    const val CHAT                = "chat/{projectId}/{chatId}"
    const val SETTINGS            = "settings"
    const val WORKFLOW            = "workflow/{projectId}"
    const val RESEARCH            = "research/{projectId}"
    const val TOOLS               = "tools/{projectId}"
    const val ARTIFACTS           = "artifacts/{projectId}"
    const val CHAT_BOOT           = "chat_boot"
    const val FILE_PREVIEW        = "file_preview?target={target}&title={title}&kind={kind}"
    const val SISTEMA_FENIX       = "sistema_fenix"

    // P4: rutas para chats generales (sin proyecto)
    const val GENERAL_CHATS_LIST  = "general_chats"
    const val CHAT_GENERAL        = "chat_general/{chatId}"

    fun projectDetail(projectId: String) = "project/$projectId"
    fun chat(projectId: String, chatId: String) = "chat/$projectId/$chatId"
    fun workflow(projectId: String) = "workflow/$projectId"
    fun research(projectId: String) = "research/$projectId"
    fun tools(projectId: String) = "tools/$projectId"
    fun artifacts(projectId: String) = "artifacts/$projectId"
    fun filePreview(target: String, title: String, kind: String) =
        "file_preview?target=${Uri.encode(target)}&title=${Uri.encode(title)}&kind=${Uri.encode(kind)}"

    // P4: helper para chat general
    fun chatGeneral(chatId: String) = "chat_general/$chatId"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FenixNavHost(onRouteChanged: (String?) -> Unit = {}) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val drawerViewModel: FenixDrawerViewModel = hiltViewModel()
    val generalChatViewModel: GeneralChatListViewModel = hiltViewModel()
    val drawerUiState by drawerViewModel.uiState.collectAsState()
    val backStackEntry = navController.currentBackStackEntryAsState().value
    val currentRoute = backStackEntry?.destination?.route
    LaunchedEffect(currentRoute) {
        onRouteChanged(currentRoute)
        AuditLogger.screen(currentRoute)
    }

    val openDrawer = {
        AuditLogger.action("drawer_open", mapOf("route" to (currentRoute ?: "")))
        drawerViewModel.refreshCreatedFiles()
        scope.launch { drawerState.open() }
        Unit
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            FenixDrawerContent(
                state = drawerUiState,
                onProjectSelected = { project ->
                    AuditLogger.action("drawer_select_project", mapOf("projectId" to project.id))
                    scope.launch { drawerState.close() }
                    navController.navigate(Routes.projectDetail(project.id))
                },
                onChatSelected = { chat ->
                    AuditLogger.action("drawer_select_chat", mapOf("chatId" to chat.id, "projectId" to chat.projectId))
                    scope.launch { drawerState.close() }
                    if (chat.projectId.isBlank()) {
                        navController.navigate(Routes.chatGeneral(chat.id))
                    } else {
                        navController.navigate(Routes.chat(chat.projectId, chat.id))
                    }
                },
                onNewChatSelected = {
                    AuditLogger.action("drawer_new_general_chat")
                    generalChatViewModel.createNewChat { chatId ->
                        scope.launch { drawerState.close() }
                        navController.navigate(Routes.chatGeneral(chatId))
                    }
                },
                onCreateProjectSelected = {
                    AuditLogger.action("drawer_create_project")
                    scope.launch { drawerState.close() }
                    navController.navigate(Routes.PROJECTS_CREATE)
                },
                onCreatedFileSelected = { file ->
                    AuditLogger.action("drawer_select_created_file", mapOf("path" to file.path, "name" to file.name))
                    scope.launch { drawerState.close() }
                    navController.navigate(Routes.filePreview(file.path, file.name, "created"))
                },
                onUploadedDocumentSelected = { doc ->
                    AuditLogger.action("drawer_select_uploaded_document", mapOf("documentId" to doc.id, "name" to doc.name))
                    scope.launch { drawerState.close() }
                    navController.navigate(Routes.filePreview(doc.uri, doc.name, "uploaded"))
                },
                onSistemaFenixSelected = {
                    AuditLogger.action("drawer_select_sistema_fenix")
                    scope.launch { drawerState.close() }
                    navController.navigate(Routes.SISTEMA_FENIX)
                },
                onSettingsSelected = {
                    AuditLogger.action("drawer_select_settings")
                    scope.launch { drawerState.close() }
                    navController.navigate(Routes.SETTINGS)
                }
            )
        }
    ) {
    NavHost(navController = navController, startDestination = Routes.CHAT_BOOT) {

        composable(Routes.CHAT_BOOT) {
            GeneralChatBootScreen(
                onChatReady = { chatId ->
                    AuditLogger.action("boot_general_chat_created", mapOf("chatId" to chatId))
                    navController.navigate(Routes.chatGeneral(chatId)) {
                        popUpTo(Routes.CHAT_BOOT) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.PROJECTS) {
            ProjectListScreen(
                onNavigateToProject     = {
                    AuditLogger.action("navigate_project_detail", mapOf("projectId" to it))
                    navController.navigate(Routes.projectDetail(it))
                },
                onNavigateToSettings    = {
                    AuditLogger.action("navigate_settings")
                    navController.navigate(Routes.SETTINGS)
                },
                onNavigateToGeneralChats = {
                    AuditLogger.action("navigate_general_chats")
                    navController.navigate(Routes.GENERAL_CHATS_LIST)
                },
                onOpenMenu = openDrawer
            )
        }

        composable(Routes.PROJECTS_CREATE) {
            ProjectListScreen(
                onNavigateToProject     = {
                    AuditLogger.action("navigate_project_detail", mapOf("projectId" to it))
                    navController.navigate(Routes.projectDetail(it))
                },
                onNavigateToSettings    = {
                    AuditLogger.action("navigate_settings")
                    navController.navigate(Routes.SETTINGS)
                },
                onNavigateToGeneralChats = {
                    AuditLogger.action("navigate_general_chats")
                    navController.navigate(Routes.GENERAL_CHATS_LIST)
                },
                onOpenMenu = openDrawer,
                openCreateDialogOnStart = true
            )
        }

        composable(
            route     = Routes.PROJECT_DETAIL,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) { back ->
            val projectId = back.arguments?.getString("projectId") ?: return@composable
            ProjectDetailScreen(
                projectId             = projectId,
                onNavigateToChat      = {
                    AuditLogger.action("navigate_chat", mapOf("projectId" to projectId, "chatId" to it))
                    navController.navigate(Routes.chat(projectId, it))
                },
                onNavigateToWorkflow  = {
                    AuditLogger.action("navigate_workflow", mapOf("projectId" to projectId))
                    navController.navigate(Routes.workflow(projectId))
                },
                onNavigateToResearch  = {
                    AuditLogger.action("navigate_research", mapOf("projectId" to projectId))
                    navController.navigate(Routes.research(projectId))
                },
                onNavigateToTools     = {
                    AuditLogger.action("navigate_tools", mapOf("projectId" to projectId))
                    navController.navigate(Routes.tools(projectId))
                },
                onNavigateToArtifacts = {
                    AuditLogger.action("navigate_artifacts", mapOf("projectId" to projectId))
                    navController.navigate(Routes.artifacts(projectId))
                },
                onBack                = {
                    AuditLogger.action("back_project_detail", mapOf("projectId" to projectId))
                    navController.popBackStack()
                },
                onOpenMenu = openDrawer
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
                onOpenMenu = openDrawer,
                onBack    = {
                    AuditLogger.action("back_chat", mapOf("projectId" to projectId, "chatId" to chatId))
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = {
                AuditLogger.action("back_settings")
                navController.popBackStack()
            })
        }

        composable(Routes.SISTEMA_FENIX) {
            SistemaFenixScreen(
                onBack = {
                    AuditLogger.action("back_sistema_fenix")
                    navController.popBackStack()
                },
                onOpenMenu = openDrawer
            )
        }

        composable(
            route     = Routes.WORKFLOW,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) { back ->
            val projectId = back.arguments?.getString("projectId") ?: return@composable
            WorkflowScreen(
                projectId = projectId,
                onBack    = {
                    AuditLogger.action("back_workflow", mapOf("projectId" to projectId))
                    navController.popBackStack()
                }
            )
        }

        composable(
            route     = Routes.RESEARCH,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) { back ->
            val projectId = back.arguments?.getString("projectId") ?: return@composable
            ResearchScreen(
                projectId = projectId,
                onBack    = {
                    AuditLogger.action("back_research", mapOf("projectId" to projectId))
                    navController.popBackStack()
                }
            )
        }

        composable(
            route     = Routes.TOOLS,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) { back ->
            val projectId = back.arguments?.getString("projectId") ?: return@composable
            ToolsScreen(
                projectId = projectId,
                onBack    = {
                    AuditLogger.action("back_tools", mapOf("projectId" to projectId))
                    navController.popBackStack()
                }
            )
        }

        composable(
            route     = Routes.ARTIFACTS,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) { back ->
            val projectId = back.arguments?.getString("projectId") ?: return@composable
            ArtifactsScreen(
                projectId = projectId,
                onBack    = {
                    AuditLogger.action("back_artifacts", mapOf("projectId" to projectId))
                    navController.popBackStack()
                }
            )
        }

        // P4: Lista de conversaciones globales
        composable(Routes.GENERAL_CHATS_LIST) {
            GeneralChatListScreen(
                onNavigateToChat = { chatId ->
                    AuditLogger.action("navigate_general_chat", mapOf("chatId" to chatId))
                    navController.navigate(Routes.chatGeneral(chatId))
                },
                onBack           = {
                    AuditLogger.action("back_general_chats")
                    navController.popBackStack()
                }
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
                onOpenMenu = openDrawer,
                onBack    = {
                    AuditLogger.action("back_general_chat", mapOf("chatId" to chatId))
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Routes.FILE_PREVIEW,
            arguments = listOf(
                navArgument("target") { type = NavType.StringType; defaultValue = "" },
                navArgument("title") { type = NavType.StringType; defaultValue = "Archivo" },
                navArgument("kind") { type = NavType.StringType; defaultValue = "created" }
            )
        ) { back ->
            FilePreviewScreen(
                title = back.arguments?.getString("title").orEmpty(),
                target = back.arguments?.getString("target").orEmpty(),
                kind = back.arguments?.getString("kind") ?: "created",
                onBack = {
                    AuditLogger.action("back_file_preview")
                    navController.popBackStack()
                }
            )
        }
    }
    }
}

@Composable
private fun GeneralChatBootScreen(
    onChatReady: (String) -> Unit,
    viewModel: GeneralChatListViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) {
        viewModel.createNewChat(onChatReady)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
