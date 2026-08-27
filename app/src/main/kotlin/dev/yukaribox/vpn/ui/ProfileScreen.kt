package dev.yukaribox.vpn.ui

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.core.Logs
import dev.yukaribox.vpn.core.SettingsStore
import dev.yukaribox.vpn.core.ThemeMode
import dev.yukaribox.vpn.data.AvatarStore
import dev.yukaribox.vpn.data.NodeRepository
import dev.yukaribox.vpn.ui.kit.NavRow
import dev.yukaribox.vpn.ui.kit.Notice
import dev.yukaribox.vpn.ui.kit.PaperCard
import dev.yukaribox.vpn.ui.kit.PickerRow
import dev.yukaribox.vpn.ui.kit.ScreenMargin
import dev.yukaribox.vpn.ui.kit.SectionCaption
import dev.yukaribox.vpn.ui.kit.SwitchRow
import dev.yukaribox.vpn.ui.kit.TitleTopBar
import dev.yukaribox.vpn.ui.theme.YukariIcons
import dev.yukaribox.vpn.ui.theme.yukari
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Profile: the user's own card, then the two settings groups the mockup puts here.
 *
 * The mockup's account rows describe a commercial VPN with sign-in and a paid tier. This
 * client has neither — it dials servers the user supplies — so the *structure* is kept
 * exactly and each row is bound to the thing it actually corresponds to: the
 * subscription that supplies the servers is the account, its refresh is the renewal, and
 * the backup is the only way that account exists anywhere but this device. `Free Plan`,
 * the crown, `Expires on` and `Sign Out` are **cut** rather than stubbed: there is no
 * auth or billing layer behind them, and a row that promises one is a lie the rest of
 * the app cannot honour.
 *
 * Rows are the reference's own form: a grey section caption, then single-line 42 dp rows
 * sitting directly on the page, with no card around the group and no second line under a
 * row. A value that belongs to a row rides its trailing slot, before the chevron.
 */
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onOpenGroups: () -> Unit,
    onNavigate: (Screen) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf(false) }
    var pickFailed by remember { mutableStateOf(false) }
    // The system photo picker rather than SAF: it needs no gallery permission, and because
    // the bytes are copied into `filesDir` at once there is no persistable URI grant to take
    // either. Where the picker is absent the contract falls back to ACTION_OPEN_DOCUMENT.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            pickFailed = false
            scope.launch { pickFailed = !withContext(Dispatchers.IO) { AvatarStore.set(uri) } }
        }
    }
    Column(Modifier.fillMaxSize()) {
        TitleTopBar(
            title = stringResource(R.string.title_profile),
            onNav = onBack,
            navContentDescription = stringResource(R.string.cd_back),
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenMargin),
        ) {
            ProfileCard(
                onPickAvatar = {
                    Logs.tap("profile:avatar")
                    pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onRename = { Logs.tap("profile:nickname"); renaming = true },
                onOpenGroups = onOpenGroups,
            )
            // A failed import has to say so. Both ways it can fail -- a file that is not an
            // image, and one over the store byte cap -- follow from what the user picked, so
            // silence would read as the tap having done nothing at all.
            if (pickFailed) {
                Notice(
                    text = stringResource(R.string.profile_avatar_failed),
                    modifier = Modifier.padding(top = 8.dp),
                    actionLabel = stringResource(R.string.action_close),
                    onAction = { pickFailed = false },
                )
            }
            SectionCaption(stringResource(R.string.profile_section_account))
            AccountRows(
                onOpenGroups = onOpenGroups,
                onNavigate = onNavigate,
                onRemoveAvatar = {
                    Logs.tap("profile:avatar-clear")
                    scope.launch { withContext(Dispatchers.IO) { AvatarStore.clear() } }
                },
            )
            SectionCaption(stringResource(R.string.profile_section_general))
            GeneralRows(onNavigate = onNavigate)
            Spacer(Modifier.height(28.dp))
        }
    }
    if (renaming) NicknameDialog(onDismiss = { renaming = false })
}

/**
 * The summary card: an 80 dp avatar, the brand signature, and what this install
 * actually holds.
 *
 * The second line is the mockup's `Free Plan` slot filled with real data — the number of
 * servers and groups the app has on disk — because that is the honest answer to "what is
 * this account". It is [libraryCounts], the same sentence the drawer header shows, and it
 * taps through to the groups list, which is where those two numbers come from.
 *
 * The avatar is the raster derivative, never tinted, and the ring is a plain hairline:
 * the reference lets her hair break out of the circle's top-right, which needs a second
 * unclipped layer over the ring and is deliberately not shipped yet.
 *
 * It is a different drawing from the drawer header's bust — the waving figure rather than
 * the winking one — on purpose. Two surfaces the user reaches one tap apart, showing the
 * same face at the same size, read as the same component rendered twice.
 */
@Composable
private fun ProfileCard(
    onPickAvatar: () -> Unit,
    onRename: () -> Unit,
    onOpenGroups: () -> Unit,
) {
    val counts = libraryCounts()
    PaperCard(
        contentPadding = PaddingValues(CARD_PADDING),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .height(CARD_HEIGHT),
    ) {
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PersonaAvatar(
                size = AVATAR,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.cd_avatar_change),
                        onClick = onPickAvatar,
                    )
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            )
            Column(Modifier.weight(1f)) {
                Box(
                    Modifier
                        .heightIn(min = NAME_TARGET)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = stringResource(R.string.cd_nickname_change),
                            onClick = onRename,
                        ),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        personaName(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    counts,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.yukari.textTertiary,
                    maxLines = 1,
                )
            }
            Box(
                Modifier
                    .size(CHEVRON_TARGET)
                    .clip(CircleShape)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.cd_groups_open),
                        onClick = onOpenGroups,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    null,
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/**
 * `ACCOUNT`: the three rows that manage where the servers come from and how they leave
 * this device.
 *
 * "Update now" carries the state of the fetch in its **trailing value** rather than in a
 * spinner or a second line — it is a background operation on a single-thread executor,
 * and the honest report is "when did this last succeed", which is also what tells the
 * user whether pressing it again would help. Without it a tap on this row would look
 * like nothing happening at all.
 */
@Composable
private fun AccountRows(
    onOpenGroups: () -> Unit,
    onNavigate: (Screen) -> Unit,
    onRemoveAvatar: () -> Unit,
) {
    val group = NodeRepository.activeSubscription()
    val canUpdate = group?.url?.isNotBlank() == true && !NodeRepository.importing
    // Only while there is something to remove. A row that is always present and inert four
    // times out of five reads worse than one that arrives with its subject, and the kit has
    // no disabled row to say "not now" with.
    if (AvatarStore.present) {
        NavRow(
            label = stringResource(R.string.profile_avatar_remove),
            icon = Icons.Default.Close,
            onClick = onRemoveAvatar,
        )
    }
    NavRow(
        label = stringResource(R.string.profile_subscriptions),
        icon = Icons.Default.Star,
        onClick = onOpenGroups,
    )
    NavRow(
        label = stringResource(R.string.profile_update_now),
        value = when {
            NodeRepository.importing -> stringResource(R.string.profile_updating)
            group == null -> stringResource(R.string.profile_no_group)
            group.url.isBlank() -> stringResource(R.string.profile_manual_group)
            else -> formatDate(group.updatedAt)
        },
        icon = Icons.Default.Refresh,
        onClick = {
            Logs.tap("profile:update")
            if (canUpdate) NodeRepository.updateActiveSubscription()
        },
    )
    NavRow(
        label = stringResource(R.string.profile_backup),
        icon = YukariIcons.Backup,
        onClick = { onNavigate(Screen.Backup) },
    )
}

/**
 * `GENERAL`: language, appearance, start-on-boot, and the two deeper screens.
 *
 * The language row only exists on Android 13 and later. Below that the platform has no
 * per-app locale API, and offering a picker that silently does nothing is worse than not
 * offering one — the app follows the system language there.
 */
@Composable
private fun GeneralRows(onNavigate: (Screen) -> Unit) {
    LanguageRow()
    AppearanceRow()
    SwitchRow(
        label = stringResource(R.string.profile_start_on_boot),
        icon = YukariIcons.Power,
        checked = SettingsStore.data.autoConnectOnBoot,
        onChange = { value -> SettingsStore.update { it.copy(autoConnectOnBoot = value) } },
    )
    NavRow(
        label = stringResource(R.string.profile_routing),
        icon = YukariIcons.Routes,
        onClick = { onNavigate(Screen.Routes) },
    )
    NavRow(
        label = stringResource(R.string.profile_advanced),
        icon = Icons.Default.Settings,
        onClick = { onNavigate(Screen.Settings) },
    )
}

/** Light / dark / follow the system. Applied instantly — no restart is involved. */
@Composable
private fun AppearanceRow() {
    val labels = ThemeMode.entries.associateWith { mode ->
        stringResource(
            when (mode) {
                ThemeMode.System -> R.string.theme_system
                ThemeMode.Light -> R.string.theme_light
                ThemeMode.Dark -> R.string.theme_dark
            },
        )
    }
    PickerRow(
        label = stringResource(R.string.profile_appearance),
        icon = YukariIcons.Appearance,
        current = labels.getValue(SettingsStore.themeMode),
        options = ThemeMode.entries.map { labels.getValue(it) },
        onPick = { picked ->
            val mode = labels.entries.first { it.value == picked }.key
            Logs.tap("profile:theme:${mode.name}")
            SettingsStore.update { it.copy(themeMode = mode) }
        },
    )
}

/**
 * Per-app language, through the platform's own `LocaleManager` so it shows up in Android
 * Settings alongside every other app. Language names are shown in their own language by
 * convention and are deliberately not translated.
 */
@Composable
private fun LanguageRow() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(LocaleManager::class.java) } ?: return
    val system = stringResource(R.string.language_system)
    val english = stringResource(R.string.language_en)
    val russian = stringResource(R.string.language_ru)
    val tags = manager.applicationLocales.toLanguageTags()
    val current = when {
        tags.startsWith("ru") -> russian
        tags.startsWith("en") -> english
        else -> system
    }
    PickerRow(
        label = stringResource(R.string.profile_language),
        icon = YukariIcons.Globe,
        current = current,
        options = listOf(system, english, russian),
        onPick = { picked ->
            Logs.tap("profile:language")
            manager.applicationLocales = when (picked) {
                english -> LocaleList.forLanguageTags("en")
                russian -> LocaleList.forLanguageTags("ru")
                else -> LocaleList.getEmptyLocaleList()
            }
        },
    )
}

/** Short, locale-aware date — the format the user's own system uses. */
private fun formatDate(millis: Long): String =
    DateFormat.getDateInstance(DateFormat.SHORT).format(Date(millis))

/** Summary-card height: the 80 dp avatar plus its 12 dp of padding, top and bottom. */
private val CARD_HEIGHT = 104.dp

/** The summary card's own padding — tighter than a content card's 16 dp. */
private val CARD_PADDING = 12.dp

/** The avatar circle. Measured on the reference's own profile card. */
private val AVATAR = 80.dp

/**
 * Touch targets for the two things in the card that are not the 80 dp avatar.
 *
 * 44 rather than the recommended 48 for the name, and the reason is the same one that makes
 * a settings row 42: the card is a `measured` 104 dp and its content 80, so a 48 dp name box
 * plus the counts line under it would push the pair out of the vertical centre the reference
 * puts it in. 44 keeps the name where it already sits to within 2 dp.
 *
 * The chevron gets a full 48 and the glyph stays 20 inside it, which lands its centre 24 dp
 * from the card edge -- the inset the drawer already uses for the same glyph.
 */
private val NAME_TARGET = 44.dp
private val CHEVRON_TARGET = 48.dp
