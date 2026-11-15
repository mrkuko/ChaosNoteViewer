package com.chaosnoteviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.chaosnoteviewer.LaunchRequest
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.chaosnoteviewer.ui.screens.DetailScreen
import com.chaosnoteviewer.ui.screens.EditScreen
import com.chaosnoteviewer.ui.screens.NotesListScreen
import com.chaosnoteviewer.ui.screens.SettingsScreen

// Routes of the app. A note is identified by its UUID string (notes.id).
object Routes {
    const val LIST = "list"
    const val DETAIL = "detail/{id}"
    const val EDIT = "edit?id={id}"         // no id = new note
    const val SETTINGS = "settings"

    fun detail(id: String) = "detail/$id"
    fun edit(id: String? = null) = if (id == null) "edit" else "edit?id=$id"
}

@Composable
fun AppNavigation(launch: LaunchRequest = LaunchRequest(), onLaunchHandled: () -> Unit = {}) {
    val nav = rememberNavController()
    // one NotesViewModel for the whole app, shared by all screens
    val vm: NotesViewModel = viewModel(factory = NotesViewModel.Factory)
    // created here, so the remembered sign-in is picked up as soon as the app starts
    val accountVm: AccountViewModel = viewModel(factory = AccountViewModel.Factory)
    val syncVm: SyncViewModel = viewModel(factory = SyncViewModel.Factory)

    // opened from the widget or a notification: go to the note, the new note form or the list
    LaunchedEffect(launch) {
        if (launch.isEmpty) return@LaunchedEffect
        launch.filter?.let(vm::setFilter)
        nav.popBackStack(Routes.LIST, inclusive = false)
        when {
            launch.noteId != null -> nav.navigate(Routes.detail(launch.noteId))
            launch.newNote -> nav.navigate(Routes.edit())
        }
        onLaunchHandled()
    }

    NavHost(navController = nav, startDestination = Routes.LIST) {
        composable(Routes.LIST) {
            NotesListScreen(
                vm = vm,
                syncVm = syncVm,
                onOpenNote = { nav.navigate(Routes.detail(it)) },
                onNewNote = { nav.navigate(Routes.edit()) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.DETAIL, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            DetailScreen(
                vm = vm,
                syncVm = syncVm,
                noteId = id,
                onBack = { nav.popBackStack() },
                onEdit = { nav.navigate(Routes.edit(id)) },
            )
        }
        composable(
            Routes.EDIT,
            arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            EditScreen(
                vm = vm,
                noteId = entry.arguments?.getString("id"),
                onDone = { nav.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(vm = accountVm, syncVm = syncVm, onBack = { nav.popBackStack() })
        }
    }
}
