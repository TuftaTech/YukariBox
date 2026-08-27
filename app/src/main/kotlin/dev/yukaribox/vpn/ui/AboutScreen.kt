package dev.yukaribox.vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.ui.kit.IconCircle
import dev.yukaribox.vpn.ui.kit.PaperCard
import dev.yukaribox.vpn.ui.kit.ScreenMargin
import dev.yukaribox.vpn.ui.kit.SectionCaption
import dev.yukaribox.vpn.ui.kit.TitleTopBar
import dev.yukaribox.vpn.ui.theme.YukariIcons

/**
 * Help & Support — the drawer's last real destination.
 *
 * It is genuinely help, not a version dump with an "about" heading. This app has no
 * servers of its own, which is the single thing new users do not expect, so the first
 * thing on the screen explains that and the two that follow explain the two behaviours
 * that surprise people: a subscription refresh replacing a group wholesale, and the kill
 * switch dropping every packet when a session collapses.
 *
 * The build information is last, because it only matters when something is wrong.
 */
@Composable
fun AboutScreen(onOpenDrawer: () -> Unit) {
    val context = LocalContext.current
    val version = remember(context) { appVersion(context) }
    Column(Modifier.fillMaxSize()) {
        TitleTopBar(
            title = stringResource(R.string.title_help),
            onNav = onOpenDrawer,
            navContentDescription = stringResource(R.string.cd_menu),
            navIcon = Icons.Default.Menu,
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenMargin),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionCaption(stringResource(R.string.help_section_start))
            HelpCard(
                icon = YukariIcons.Globe,
                title = stringResource(R.string.help_servers_title),
                body = stringResource(R.string.help_servers_body),
            )
            HelpCard(
                icon = YukariIcons.Folder,
                title = stringResource(R.string.help_groups_title),
                body = stringResource(R.string.help_groups_body),
            )
            HelpCard(
                icon = YukariIcons.Shield,
                title = stringResource(R.string.help_killswitch_title),
                body = stringResource(R.string.help_killswitch_body),
            )
            HelpCard(
                icon = YukariIcons.Backup,
                title = stringResource(R.string.help_backup_title),
                body = stringResource(R.string.help_backup_body),
            )
            SectionCaption(stringResource(R.string.help_section_build))
            BuildCard(version)
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** One help topic: a plated glyph, a question-shaped heading, and one paragraph. */
@Composable
private fun HelpCard(
    icon: ImageVector,
    title: String,
    body: String,
) {
    PaperCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconCircle(size = 36.dp) { Icon(icon, null, Modifier.size(17.dp)) }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Version, core build and licence — what a bug report needs. */
@Composable
private fun BuildCard(version: String) {
    PaperCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconCircle(size = 36.dp) { Icon(YukariIcons.Tank, null, Modifier.size(18.dp)) }
            Text(
                "${stringResource(R.string.app_name)} $version",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.about_core),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.about_ui),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.about_licence),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
