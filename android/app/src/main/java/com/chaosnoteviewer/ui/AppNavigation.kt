package com.chaosnoteviewer.ui

import androidx.compose.runtime.Composable
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
fun AppNavigation() {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = Routes.LIST) {
        composable(Routes.LIST) {
            NotesListScreen(
                onOpenNote = { nav.navigate(Routes.detail(it)) },
                onNewNote = { nav.navigate(Routes.edit()) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.DETAIL, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            DetailScreen(
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
                noteId = entry.arguments?.getString("id"),
                onDone = { nav.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
