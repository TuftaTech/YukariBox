package dev.yukaribox.vpn.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.core.Logs
import dev.yukaribox.vpn.ui.kit.InkBottomBar
import dev.yukaribox.vpn.ui.kit.NavDestination
import dev.yukaribox.vpn.ui.theme.YukariIcons
import kotlinx.coroutines.launch

/**
 * The app shell: a navigation drawer over a three-tab bottom bar, with the current
 * screen between them. `MainActivity` calls this and nothing else.
 *
 * The structure is the mockup's, and it is a split by frequency rather than by
 * hierarchy. The bar carries the three things a user opens every session — the connect
 * screen, the traffic figures, their own settings. The drawer carries everything that
 * configures the app. Nothing is in both places.
 */
@Composable
fun YukariBoxApp(onToggleConnection: () -> Unit = {}) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val shareText = stringResource(R.string.share_text)

    var nav by rememberSaveable(stateSaver = NavStateSaver) { mutableStateOf(NavState()) }
    var editNodeId by rememberSaveable { mutableStateOf<Int?>(null) }

    val homeLabel = stringResource(R.string.nav_home)
    val statsLabel = stringResource(R.string.nav_stats)
    val profileLabel = stringResource(R.string.nav_profile)
    val destinations = remember(homeLabel, statsLabel, profileLabel) {
        listOf(
            NavDestination(Icons.Default.Home, homeLabel),
            NavDestination(YukariIcons.Stats, statsLabel),
            NavDestination(Icons.Default.Person, profileLabel),
        )
    }

    val go: (Screen) -> Unit = { target ->
        Logs.tap("nav:${target.name}")
        nav = nav.push(target)
    }
    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }

    // Back closes the drawer first, then unwinds the stack. Without the first case a
    // Back with the drawer open would navigate underneath it.
    BackHandler(enabled = drawerState.isOpen) { closeDrawer() }
    BackHandler(enabled = !drawerState.isOpen && nav.canPop) { nav = nav.pop() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Only while it is open: the servers list and the settings screen both scroll,
        // and an edge-swipe handler competing with them made the list feel sticky.
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(DRAWER_FRACTION)
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                DrawerContent(
                    active = activeDrawerItem(nav.current),
                    onItem = { item ->
                        closeDrawer()
                        when (item) {
                            // A reset, not a push: this is the way back to the connect
                            // screen from three screens deep, so it clears the stack and
                            // the tab together.
                            DrawerItem.Home -> { Logs.tap("nav:Home"); nav = NavState() }
                            DrawerItem.Servers -> go(Screen.Servers)
                            DrawerItem.Groups -> go(Screen.Groups)
                            DrawerItem.Settings -> go(Screen.Settings)
                            DrawerItem.Log -> go(Screen.Logs)
                            DrawerItem.Help -> go(Screen.About)
                            DrawerItem.Share -> shareApp(context, shareText)
                        }
                    },
                    onProfile = { closeDrawer(); nav = nav.select(Tab.Profile) },
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Box(Modifier.weight(1f)) {
                ScreenHost(
                    nav = nav,
                    editNodeId = editNodeId,
                    onNavigate = go,
                    onBack = { nav = nav.pop() },
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onEditNode = { id -> editNodeId = id; go(Screen.NodeEdit) },
                    onToggleConnection = onToggleConnection,
                )
            }
            if (nav.barVisible) {
                InkBottomBar(
                    // Remembered on the three labels. Built inline it was a new `List` on
                    // every shell recomposition, and a `List` parameter is unstable, so the
                    // bar re-composed its three tabs whenever anything above it changed.
                    destinations = destinations,
                    selectedIndex = nav.tab.ordinal,
                    onSelect = { index -> nav = nav.select(Tab.entries[index]) },
                )
            }
        }
    }
}

/** Which drawer row is lit, derived from where the shell actually is. */
private fun activeDrawerItem(current: Screen): DrawerItem? = when {
    current == Screen.Home -> DrawerItem.Home
    current == Screen.Groups -> DrawerItem.Groups
    current == Screen.Servers || current == Screen.NodeEdit -> DrawerItem.Servers
    current == Screen.Settings || current == Screen.PerApp || current == Screen.Routes -> DrawerItem.Settings
    current == Screen.Logs -> DrawerItem.Log
    current == Screen.About -> DrawerItem.Help
    else -> null
}

/** Hand the app's own name and a short line to whatever the user picked. */
private fun shareApp(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(Intent.createChooser(send, null)) }
}

/**
 * Survives process death: the tab and its stack are restored, so a user killed by the
 * system while reading the log comes back to the log rather than to Home.
 */
private val NavStateSaver = listSaver<NavState, String>(
    save = { state -> listOf(state.tab.name) + state.stack.map { it.name } },
    restore = { saved ->
        // Unknown names are dropped rather than throwing: a rename between versions
        // must not crash the app on its first launch after an update.
        val tab = saved.firstOrNull()?.let { name -> Tab.entries.firstOrNull { it.name == name } }
        NavState(
            tab = tab ?: Tab.Home,
            stack = saved.drop(1).mapNotNull { name -> Screen.entries.firstOrNull { it.name == name } },
        )
    },
)

/** Drawer width as a fraction of the screen: the mockup's 334 dp sheet on a 402 dp panel. */
private const val DRAWER_FRACTION = 0.83f

