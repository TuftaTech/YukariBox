package dev.yukaribox.vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.core.ProxyAuth
import dev.yukaribox.vpn.core.SettingsStore
import dev.yukaribox.vpn.ui.kit.MetaBadge
import dev.yukaribox.vpn.ui.kit.Notice
import dev.yukaribox.vpn.ui.kit.PaperCard
import dev.yukaribox.vpn.ui.kit.QuietIconButton
import dev.yukaribox.vpn.ui.theme.LabelWide
import dev.yukaribox.vpn.ui.theme.YukariIcons
import dev.yukaribox.vpn.ui.theme.yukari

/**
 * Proxy-only mode: the listener address and the credentials a client needs.
 *
 * Shown next to the service-mode row and only in that mode, because in VPN mode there is
 * no inbound to point anything at. The password is masked until asked for and copied
 * rather than retyped — it is 24 URL-safe characters, and a user transcribing it by hand
 * into a browser's proxy dialog will get it wrong.
 *
 * When authentication is switched off the card says so in the loudest terms it has. That
 * opt-out leaves `127.0.0.1:2080` usable by every app on the device, which spends the
 * user's quota and attributes its destinations to them, so the warning belongs where the
 * address is rather than only next to the switch.
 */
@Composable
internal fun ProxyCard() {
    val context = LocalContext.current
    var revealed by remember { mutableStateOf(false) }
    val settings = SettingsStore.data
    val password = settings.proxyPassword

    PaperCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.proxy_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            MetaBadge(stringResource(R.string.proxy_badge))
        }
        Spacer(Modifier.height(10.dp))
        ProxyField(
            label = stringResource(R.string.proxy_address),
            value = PROXY_ENDPOINT,
            onCopy = { copyText(context, PROXY_ENDPOINT) },
        )
        if (settings.proxyAuthDisabled) {
            Spacer(Modifier.height(12.dp))
            Notice(
                text = stringResource(R.string.proxy_auth_off),
                emphasis = true,
            )
        } else {
            Spacer(Modifier.height(8.dp))
            ProxyField(
                label = stringResource(R.string.proxy_user),
                value = ProxyAuth.USER,
                onCopy = { copyText(context, ProxyAuth.USER) },
            )
            Spacer(Modifier.height(8.dp))
            ProxyField(
                // Blank until proxy-only mode has actually run once: the password is
                // generated lazily, so promising one before it exists would be a lie.
                label = stringResource(R.string.proxy_password),
                value = when {
                    password.isBlank() -> stringResource(R.string.proxy_pending)
                    revealed -> password
                    else -> MASK
                },
                onCopy = { copySecret(context, password) }.takeIf { password.isNotBlank() },
                onToggleReveal = { revealed = !revealed }.takeIf { password.isNotBlank() },
                revealed = revealed,
            )
        }
    }
}

/** One label + value row of the proxy card, with copy and an optional reveal. */
@Composable
private fun ProxyField(
    label: String,
    value: String,
    onCopy: (() -> Unit)? = null,
    onToggleReveal: (() -> Unit)? = null,
    revealed: Boolean = false,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label.uppercase(),
                style = LabelWide,
                color = MaterialTheme.yukari.textTertiary,
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onToggleReveal != null) {
            QuietIconButton(
                icon = if (revealed) YukariIcons.EyeOff else YukariIcons.Eye,
                contentDescription = stringResource(
                    if (revealed) R.string.proxy_hide else R.string.proxy_show,
                ),
                onClick = onToggleReveal,
            )
        }
        if (onCopy != null) {
            QuietIconButton(
                icon = YukariIcons.Copy,
                contentDescription = stringResource(R.string.action_copy),
                onClick = onCopy,
            )
        }
    }
}

/** Loopback endpoint of the proxy-only mixed inbound. Matches `ConfigBuilder`. */
private const val PROXY_ENDPOINT = "127.0.0.1:2080"

/** Fixed-width mask; deliberately not derived from the password's length. */
private const val MASK = "••••••••••••"
