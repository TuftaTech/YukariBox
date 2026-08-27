package dev.yukaribox.vpn.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.ContentTransform
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import dev.yukaribox.vpn.core.SettingsStore
import dev.yukaribox.vpn.ui.theme.YukariMotion

/**
 * Renders the screen [nav] points at, and animates the change.
 *
 * The four screens the drawer opens — servers, groups, settings, the log and help — are
 * handed [onOpenDrawer] rather than [onBack]: they wear the hamburger, because that is
 * where a user goes to leave them. System Back still pops the stack.
 *
 * The transition encodes hierarchy: a tab switch is a crossfade (siblings, no
 * direction), a push slides in from the trailing edge and a pop back out. Depth comes
 * from the stack size, so the direction is right even when a push and a tab change land
 * in the same frame.
 *
 * Separate from the shell because that file owns the drawer, the bar and the back
 * handlers; keeping the twelve-branch `when` here leaves both under detekt's per-file
 * function budget.
 *
 * **Every destination is wrapped in a [rememberSaveableStateHolder] slot.**
 * `AnimatedContent` disposes the screen it animates away from, and a disposed composable's
 * `rememberSaveable` is gone with it — so a tab switch threw away the servers list's scroll
 * position and its search query, and coming back re-composed a ten-thousand-row list from
 * the top. The holder keeps each destination's saved state keyed by name, which is what
 * makes leaving and returning cheap as well as correct.
 *
 * State is deliberately **kept across a pop, not only across a tab switch**: telling the
 * two apart would put the nav model's semantics in here, and returning to a list where you
 * were is the behaviour a user expects from both. Nothing accumulates beyond one entry per
 * visited destination, and an entry is a scroll index and a query string.
 */
@Composable
fun ScreenHost(
    nav: NavState,
    editNodeId: Int?,
    onNavigate: (Screen) -> Unit,
    onBack: () -> Unit,
    onOpenDrawer: () -> Unit,
    onEditNode: (Int?) -> Unit,
    onToggleConnection: () -> Unit,
) {
    val holder = rememberSaveableStateHolder()
    // `fillMaxSize` and the `using null` below are one fix, not two decorations.
    //
    // `AnimatedContent` sizes itself to its content and, by default, *animates* that size
    // with a spring while pinning both children to `TopStart`. The shell's content box
    // changes height at exactly the same moment as the screen — the bottom bar shows at a
    // tab root and hides on a pushed screen — so returning Home from Servers animated the
    // box from 904 dp to 824, which drew the incoming screen growing out of the top-left
    // corner with the outgoing list visible around it. Filling the box makes both children
    // the same size, and a null size transform means the container never animates its own.
    AnimatedContent(
        targetState = nav,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            // The one thing the "animations" setting turns off. It exists for users who
            // find motion uncomfortable, so it has to remove the transition rather than
            // shorten it — a 160 ms crossfade is still a crossfade.
            if (!SettingsStore.animations) {
                return@AnimatedContent ContentTransform(
                    targetContentEnter = EnterTransition.None,
                    initialContentExit = ExitTransition.None,
                    sizeTransform = null,
                )
            }
            val depthChange = targetState.stack.size - initialState.stack.size
            val duration = if (depthChange == 0) YukariMotion.SWAP else YukariMotion.PUSH
            val spec = tween<Float>(duration, easing = YukariMotion.Standard)
            val slide = tween<IntOffset>(duration, easing = YukariMotion.Standard)
            // A sixth of the width, not a full-width slide: the screens share a
            // background, so a short offset reads as depth without the content
            // appearing to fly past.
            var enter = fadeIn(spec)
            var exit = fadeOut(tween(YukariMotion.SWAP, easing = YukariMotion.Standard))
            if (depthChange > 0) {
                enter += slideInHorizontally(slide) { it / 6 }
                exit += slideOutHorizontally(slide) { -it / 6 }
            } else if (depthChange < 0) {
                enter += slideInHorizontally(slide) { -it / 6 }
                exit += slideOutHorizontally(slide) { it / 6 }
            }
            ContentTransform(
                targetContentEnter = enter,
                initialContentExit = exit,
                sizeTransform = null,
            )
        },
        label = "screen",
    ) { state ->
        val openGroups = { onNavigate(Screen.Groups) }
        holder.SaveableStateProvider(state.current.name) {
            when (state.current) {
                Screen.Home -> HomeScreen(
                    onOpenDrawer = onOpenDrawer,
                    onOpenServers = { onNavigate(Screen.Servers) },
                    onToggleConnection = onToggleConnection,
                )
                Screen.Stats -> StatsScreen(onOpenDrawer = onOpenDrawer)
                Screen.Profile -> ProfileScreen(
                    onBack = onBack,
                    onOpenGroups = openGroups,
                    onNavigate = onNavigate,
                )
                Screen.Servers -> ServersScreen(
                    onOpenDrawer = onOpenDrawer,
                    onEditNode = onEditNode,
                    onToggleConnection = onToggleConnection,
                )
                // Selecting a group there is a switch, not a journey: pop the screen the
                // drawer pushed, then show the list it just changed. Two calls rather than a
                // `replace` on the nav model, because the model is four transitions and a
                // fifth one used by exactly one caller earns less than it costs.
                Screen.Groups -> GroupsScreen(
                    onOpenDrawer = onOpenDrawer,
                    onOpenServers = { onBack(); onNavigate(Screen.Servers) },
                )
                Screen.Settings -> SettingsScreen(
                    onOpenDrawer = onOpenDrawer,
                    onOpenPerApp = { onNavigate(Screen.PerApp) },
                    onOpenRoutes = { onNavigate(Screen.Routes) },
                    onOpenLogs = { onNavigate(Screen.Logs) },
                )
                Screen.PerApp -> PerAppScreen(onBack = onBack)
                Screen.Routes -> RoutesScreen(onBack = onBack)
                Screen.Logs -> LogScreen(onOpenDrawer = onOpenDrawer)
                Screen.Backup -> BackupScreen(onBack = onBack)
                Screen.NodeEdit -> NodeEditScreen(editId = editNodeId, onClose = onBack)
                Screen.About -> AboutScreen(onOpenDrawer = onOpenDrawer)
            }
        }
    }
}
