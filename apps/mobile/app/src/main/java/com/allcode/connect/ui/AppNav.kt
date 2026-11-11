package com.allcode.connect.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.allcode.connect.AppGraph
import com.allcode.connect.LaunchLink
import com.allcode.connect.ui.diff.DiffScreen
import com.allcode.connect.ui.home.MainScreen
import com.allcode.connect.ui.machines.MachineScreen
import com.allcode.connect.ui.newtask.NewTaskScreen
import com.allcode.connect.ui.pair.PairScreen
import com.allcode.connect.ui.preview.PreviewScreen
import com.allcode.connect.ui.prs.PullRequestScreen
import com.allcode.connect.ui.settings.SettingsScreen
import com.allcode.connect.ui.terminal.TerminalScreen
import com.allcode.connect.ui.theme.AllCode
import com.allcode.connect.ui.theme.Motion
import com.allcode.connect.ui.thread.ThreadScreen
import com.allcode.connect.ui.usage.UsageScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

object Routes {
    @Serializable data object Main
    @Serializable data class Pair(val link: String? = null)
    @Serializable data class Thread(val environmentId: String, val threadId: String)
    @Serializable data class NewTask(val environmentId: String? = null, val projectId: String? = null)
    @Serializable data class Diff(val environmentId: String, val threadId: String)
    @Serializable data class Terminal(val environmentId: String, val threadId: String? = null, val projectId: String? = null)
    @Serializable data class PullRequest(val environmentId: String, val projectId: String, val repository: String, val number: Int, val title: String)
    @Serializable data class Preview(val environmentId: String, val threadId: String? = null, val projectId: String? = null)
    @Serializable data class Machine(val environmentId: String)
    @Serializable data class Usage(val environmentId: String? = null)
    @Serializable data object Settings
}

/** What screens use to move around. */
class Navigator(private val controller: NavHostController) {
    fun back() { controller.popBackStack() }
    fun thread(environmentId: String, threadId: String, replace: Boolean = false) {
        controller.navigate(Routes.Thread(environmentId, threadId)) {
            if (replace) popUpTo<Routes.Main>()
            launchSingleTop = true
        }
    }
    fun newTask(environmentId: String? = null, projectId: String? = null) = controller.navigate(Routes.NewTask(environmentId, projectId))
    fun pair(link: String? = null) = controller.navigate(Routes.Pair(link)) { launchSingleTop = true }
    fun diff(environmentId: String, threadId: String) = controller.navigate(Routes.Diff(environmentId, threadId))
    fun terminal(environmentId: String, threadId: String? = null, projectId: String? = null) =
        controller.navigate(Routes.Terminal(environmentId, threadId, projectId))
    fun pullRequest(environmentId: String, projectId: String, repository: String, number: Int, title: String) =
        controller.navigate(Routes.PullRequest(environmentId, projectId, repository, number, title))
    fun preview(environmentId: String, threadId: String? = null, projectId: String? = null) =
        controller.navigate(Routes.Preview(environmentId, threadId, projectId))
    fun machine(environmentId: String) = controller.navigate(Routes.Machine(environmentId))
    fun usage(environmentId: String? = null) = controller.navigate(Routes.Usage(environmentId))
    fun settings() = controller.navigate(Routes.Settings)
    fun home() { controller.popBackStack(Routes.Main, inclusive = false) }
}

// A push slides in a short way and settles; the screen underneath just fades. Nothing bounces.
private fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(): EnterTransition =
    slideInHorizontally(tween(240, easing = Motion.EaseOutStrong)) { it / 10 } + fadeIn(tween(160, easing = Motion.EaseOutStrong))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(): ExitTransition =
    fadeOut(tween(120))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(): EnterTransition =
    fadeIn(tween(160, easing = Motion.EaseOutStrong))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(): ExitTransition =
    slideOutHorizontally(tween(200, easing = Motion.EaseOutStrong)) { it / 10 } + fadeOut(tween(160))

@Composable
fun AppNav(graph: AppGraph, links: MutableStateFlow<LaunchLink?>) {
    val controller = rememberNavController()
    val navigator = remember(controller) { Navigator(controller) }
    val link by links.collectAsStateWithLifecycle()
    val hasMachines = remember { graph.connections.connections.value.isNotEmpty() }

    LaunchedEffect(link) {
        when (val current = link) {
            is LaunchLink.Pair -> navigator.pair(current.raw)
            is LaunchLink.Thread -> navigator.thread(current.environmentId, current.threadId)
            null -> Unit
        }
        links.value = null
    }

    NavHost(
        navController = controller,
        startDestination = if (hasMachines) Routes.Main else Routes.Pair(),
        modifier = Modifier.fillMaxSize().background(AllCode.colors.paper),
        enterTransition = { enter() },
        exitTransition = { exit() },
        popEnterTransition = { popEnter() },
        popExitTransition = { popExit() },
    ) {
        composable<Routes.Main> { MainScreen(graph, navigator) }
        composable<Routes.Pair> { entry ->
            val route = entry.toRoute<Routes.Pair>()
            PairScreen(graph, route.link, canGoBack = controller.previousBackStackEntry != null, onBack = navigator::back) { environmentId ->
                controller.navigate(Routes.Main) {
                    popUpTo(0) { inclusive = true }
                }
                graph.prefs.update { it.copy(lastEnvironmentId = environmentId) }
            }
        }
        composable<Routes.Thread> { entry ->
            val route = entry.toRoute<Routes.Thread>()
            ThreadScreen(graph, navigator, route.environmentId, route.threadId)
        }
        composable<Routes.NewTask> { entry ->
            val route = entry.toRoute<Routes.NewTask>()
            NewTaskScreen(graph, navigator, route.environmentId, route.projectId)
        }
        composable<Routes.Diff> { entry ->
            val route = entry.toRoute<Routes.Diff>()
            DiffScreen(graph, navigator, route.environmentId, route.threadId)
        }
        composable<Routes.Terminal> { entry ->
            val route = entry.toRoute<Routes.Terminal>()
            TerminalScreen(graph, navigator, route.environmentId, route.threadId, route.projectId)
        }
        composable<Routes.PullRequest> { entry ->
            val route = entry.toRoute<Routes.PullRequest>()
            PullRequestScreen(graph, navigator, route.environmentId, route.projectId, route.repository, route.number, route.title)
        }
        composable<Routes.Preview> { entry ->
            val route = entry.toRoute<Routes.Preview>()
            PreviewScreen(graph, navigator, route.environmentId, route.threadId, route.projectId)
        }
        composable<Routes.Machine> { entry ->
            MachineScreen(graph, navigator, entry.toRoute<Routes.Machine>().environmentId)
        }
        composable<Routes.Usage> { entry ->
            UsageScreen(graph, navigator, entry.toRoute<Routes.Usage>().environmentId)
        }
        composable<Routes.Settings> { SettingsScreen(graph, navigator) }
    }
}
