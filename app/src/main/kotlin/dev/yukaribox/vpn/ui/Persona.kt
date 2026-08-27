package dev.yukaribox.vpn.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.core.SettingsGuard
import dev.yukaribox.vpn.core.SettingsStore
import dev.yukaribox.vpn.data.AvatarStore
import dev.yukaribox.vpn.ui.kit.YukariAvatar
import dev.yukaribox.vpn.ui.theme.yukari
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Who the user is, as the two surfaces that show a persona draw it: a name and a face.
 *
 * Both have a built-in default and a user override, and both overrides live on the Profile
 * tab. They are gathered here rather than left in the two screens because the drawer header
 * and the profile card show the *same* pair — reading the settings twice is how they drift,
 * which is the reason [libraryCounts] is shared between them already.
 *
 * This file is in `ui/` and not in `ui/kit/`. The kit is the design system and knows nothing
 * about `SettingsStore`; a component that reads settings is app code, so [YukariAvatar]
 * stays a dumb drawing and [PersonaAvatar] is the one that chooses.
 */

/** The nickname if the user set one, otherwise the built-in persona. */
@Composable
fun personaName(): String = SettingsStore.nickname.ifBlank { stringResource(R.string.brand_persona) }

/**
 * The circular avatar: the user's picture when one is stored, the mascot when not.
 *
 * While a stored picture is still being decoded the circle is drawn **empty** rather than
 * falling back to the mascot. `FlagArt` records why a placeholder must not be the wrong
 * content: a plate that drew the previous country's flag for a frame or two under the new
 * one's description was a real defect. Showing Yukari where the user's own face belongs is
 * the same mistake, and the window is one frame on the first draw of a process.
 */
@Composable
fun PersonaAvatar(modifier: Modifier = Modifier, size: Dp = 48.dp) {
    if (!AvatarStore.present) {
        YukariAvatar(modifier = modifier, size = size)
        return
    }
    val picture = rememberUserAvatar()
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (picture != null) {
            Image(
                bitmap = picture,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/**
 * The stored avatar at composition, remembered per [AvatarStore.revision].
 *
 * Never decodes inside composition, and mirrors `rememberFlag` down to the keyed holder:
 * `produceState` remembers its backing state *unkeyed*, so on a revision change it keeps
 * the previous value until the new producer assigns — which would draw the avatar the user
 * just replaced.
 */
@Composable
private fun rememberUserAvatar(): ImageBitmap? {
    val revision = AvatarStore.revision
    val hit = remember(revision) { AvatarStore.cached()?.asImageBitmap() }
    if (hit != null) return hit
    val loaded = remember(revision) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(revision) {
        loaded.value = withContext(Dispatchers.IO) { AvatarStore.bitmap()?.asImageBitmap() }
    }
    return loaded.value
}

/**
 * Rename yourself, or clear the name back to the built-in persona.
 *
 * The field is **not** sanitized per keystroke, for the reason the MTU field is not either
 * (`SettingsGuard`'s KDoc): trimming as you type makes a space impossible to enter, so
 * "Vasya the Great" cannot be typed at all. The bounds apply once, on save — which is also
 * where a hand-edited `settings.json` meets them.
 */
@Composable
internal fun NicknameDialog(onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(SettingsStore.nickname) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.profile_nickname)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.profile_nickname_field)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.profile_nickname_hint, SettingsGuard.NICKNAME_MAX),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.yukari.textTertiary,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    SettingsStore.update { it.copy(nickname = SettingsGuard.nickname(text)) }
                    onDismiss()
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
