package dev.yukaribox.vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.core.LogReader
import dev.yukaribox.vpn.core.Logs
import dev.yukaribox.vpn.core.SettingsStore
import dev.yukaribox.vpn.ui.kit.BarIconButton
import dev.yukaribox.vpn.ui.kit.EmptyState
import dev.yukaribox.vpn.ui.kit.PaperCard
import dev.yukaribox.vpn.ui.kit.PrimaryButton
import dev.yukaribox.vpn.ui.kit.ScreenMargin
import dev.yukaribox.vpn.ui.kit.TitleTopBar
import dev.yukaribox.vpn.ui.theme.LogMono
import dev.yukaribox.vpn.ui.theme.MicroLabel
import dev.yukaribox.vpn.ui.theme.YukariIcons
import dev.yukaribox.vpn.ui.theme.yukari
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Live viewer over the core's `neko.log` ring buffer — the in-app counterpart of
 * `adb logcat -s YukariBox`. Shows sing-box's own lines and everything [Logs] records,
 * with follow / refresh / copy / clear so every action the app takes is observable on
 * the device itself.
 *
 * The one screen in the app set in monospace, because log lines are pre-aligned by their
 * producer and a proportional face would break their columns. Severity is carried by the
 * *weight* and the *ink* of the line — two signals, because the platform monospace has
 * only two weights (see [logLineStyle]) — and the level word is spelled out in every line
 * anyway.
 */
@Composable
fun LogScreen(onOpenDrawer: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf(emptyList<String>()) }
    var following by remember { mutableStateOf(true) }
    var reloadTick by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    // Poll while following; a manual refresh bumps the tick to force one read.
    LaunchedEffect(following, reloadTick) {
        // The stamp of the last read, so an idle second costs two `stat` calls instead of a
        // full re-read. Local to the effect rather than remembered, so a manual refresh --
        // which restarts the effect -- always reads, whatever the file's mtime says.
        var seen: Pair<Long, Long>? = null
        do {
            // Off the main dispatcher. A LaunchedEffect runs on AndroidUiDispatcher.Main,
            // and this reads up to 256 KiB out of the ring buffer, decodes it as UTF-8 and
            // splits it into one string per entry — once a second, for as long as the screen
            // is open. Both halves are produced in the same hop, so the raw text the copy
            // action hands over and the lines on screen always agree.
            val read = withContext(Dispatchers.IO) {
                val stamp = LogReader.stamp()
                if (stamp == seen) {
                    null
                } else {
                    val raw = LogReader.read()
                    Triple(stamp, raw, raw.split('\n').filter { it.isNotBlank() }.map(::withoutFlagEmoji))
                }
            }
            if (read != null) {
                seen = read.first
                text = read.second
                lines = read.third
            }
            if (following) delay(POLL_MS)
        } while (following)
    }

    // Was the tail on screen *before* these lines arrived? Read during the composition the
    // new size triggers, so `layoutInfo` still describes the previous frame — the same trick
    // `ServersScrollPin` uses. Following used to pin to the last line on every poll, so
    // reading anything further up meant racing the next second’s refresh, with the Pause
    // button as the only way to hold still.
    val wasAtEnd = remember(lines.size) {
        Snapshot.withoutReadObservation {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index
            info.totalItemsCount == 0 || last == null || last >= info.totalItemsCount - 1
        }
    }
    LaunchedEffect(lines.size) {
        if (following && wasAtEnd && lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }
    // Resume is an explicit intent, so it re-pins regardless of where the viewport is:
    // pressing Play while scrolled up otherwise left the header reading FOLLOWING with
    // nothing following, since `wasAtEnd` is false exactly because the tail is off screen.
    LaunchedEffect(following) {
        if (following && lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        TitleTopBar(
            title = stringResource(R.string.title_logs),
            onNav = { Logs.tap("logs:menu"); onOpenDrawer() },
            navContentDescription = stringResource(R.string.cd_menu),
            navIcon = Icons.Default.Menu,
            actions = {
                BarIconButton(
                    icon = if (following) YukariIcons.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(
                        if (following) R.string.logs_cd_pause else R.string.logs_cd_resume,
                    ),
                    onClick = { Logs.tap("logs:follow"); following = !following },
                )
                BarIconButton(
                    icon = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.logs_cd_refresh),
                    onClick = { Logs.tap("logs:refresh"); reloadTick++ },
                )
                BarIconButton(
                    icon = YukariIcons.Copy,
                    contentDescription = stringResource(R.string.action_copy),
                    onClick = { Logs.tap("logs:copy"); copyText(context, text) },
                )
                BarIconButton(
                    icon = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.logs_cd_clear),
                    onClick = {
                        Logs.tap("logs:clear")
                        LogReader.clear()
                        text = ""
                        lines = emptyList()
                        reloadTick++
                    },
                )
            },
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ScreenMargin, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(if (following) R.string.logs_following else R.string.logs_paused).uppercase(),
                style = MicroLabel,
                color = if (following) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.yukari.textTertiary
                },
            )
            Text(
                stringResource(R.string.logs_lines, lines.size),
                style = MicroLabel,
                color = MaterialTheme.yukari.textTertiary,
            )
        }
        PaperCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = ScreenMargin),
            contentPadding = PaddingValues(0.dp),
        ) {
            if (!SettingsStore.data.logging) {
                // Not the same as "empty". An empty journal that is recording will fill; this
                // one never will, and a screen that cannot say which of the two it is sends
                // the reader to look for a bug that is not there.
                EmptyState(
                    title = stringResource(R.string.logs_off),
                    body = stringResource(R.string.logs_off_hint),
                    icon = YukariIcons.Document,
                    action = {
                        PrimaryButton(
                            text = stringResource(R.string.logs_turn_on),
                            onClick = { Logs.setEnabled(true) },
                        )
                    },
                )
            } else if (lines.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.logs_empty),
                    body = stringResource(R.string.logs_empty_hint),
                    icon = YukariIcons.Document,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    items(lines) { line ->
                        Text(
                            line,
                            style = logLineStyle(line),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * Weight a log line by its level marker — handles both sing-box and [Logs] shapes.
 *
 * **Two weights and two inks, three tiers.** The platform monospace ships Regular and
 * Bold only, so `FontWeight.Medium` on it resolves to the Regular face: the four-tier
 * ramp this replaces asked for Bold / Medium / Regular / secondary and rendered a
 * `[Warn]` line pixel-identical to an `[Info]` one (mean ink 26.11 for both, measured on
 * the same timestamp run). The tiers that survive are the ones two independent signals
 * can carry:
 *
 * | Tier | Encoding |
 * |---|---|
 * | Error / fatal | **Bold**, `onSurface` |
 * | Warning | Regular, `onSurface` |
 * | Everything else — info, debug, trace, and the core's unmarked lines | Regular, `onSurfaceVariant` |
 *
 * A warning keeps the ink and loses the weight; info gives up its ink instead and joins
 * debug and trace in the secondary grey. That is the collapse worth making: a warning is
 * the line a user scrolls the log looking for, and info is the line there are three
 * thousand of. Nothing is lost either way — every line still prints its own level word
 * (`[Error]`, `WARN[`), so the marker is in the text and the weight only makes it
 * findable while scrolling.
 *
 * Measured on the device after the change, mean ink over the identical `2026/08/24` date
 * run of one line per tier (x 72–255 px of a 1220 px panel): **info 26.41, warn 37.34,
 * error 51.09** — three separated values where warn and info used to be equal to the
 * second decimal.
 *
 * `[Warn` rather than `[Warning]`: [Logs] writes the level verbatim, so its own warnings
 * arrive as `[Warn]` while the core's wrapper writes `[Warning]`. Matching only the long
 * form left every warning the app itself raised set at body weight.
 */
@Composable
private fun logLineStyle(line: String): TextStyle {
    val ink = MaterialTheme.colorScheme.onSurface
    return when {
        line.contains("ERROR[") || line.contains("[Error]") || line.contains("FATAL") ->
            LogMono.copy(fontWeight = FontWeight.Bold, color = ink)
        line.contains("WARN[") || line.contains("[Warn") -> LogMono.copy(color = ink)
        else -> LogMono.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A log line with any regional-indicator pair taken out, for drawing only.
 *
 * Node display names come from subscription feeds and are recorded verbatim — that is a
 * documented, accepted trade-off for the *file*, which is a diagnostic and has to match
 * what the app really saw. But a flag emoji is the one full-colour element a feed can
 * inject, the system paints it in colour whatever the text style says, and this screen
 * prints whole lines (`node=🇨🇦 CA | …` from `dumpState`), so the zero-hue rule would
 * break at runtime on a screen no amount of token work can reach. Stripped here, at the
 * point of drawing: [Logs] is untouched, the stored bytes are unchanged, and the copy
 * action still hands over the original text.
 *
 * Not `NodeGeo.plainName`: that one is built for a *name* — it collapses whitespace and
 * trims leading separators, which would quietly reflow a log line's columns.
 */
private fun withoutFlagEmoji(line: String): String {
    // Every regional indicator is U+1F1E6..U+1F1FF, i.e. a surrogate pair whose high
    // half is U+D83C. No high surrogate, nothing to do — which is every line but a few.
    if (line.indexOf(FLAG_HIGH_SURROGATE) < 0) return line
    val out = StringBuilder(line.length)
    var index = 0
    while (index < line.length) {
        val point = line.codePointAt(index)
        if (point !in FLAG_FIRST..FLAG_LAST) out.appendCodePoint(point)
        index += Character.charCount(point)
    }
    return out.toString()
}

/** How often the buffer is re-read while following. */
private const val POLL_MS = 1000L

/** The regional-indicator block, and the high surrogate every one of them starts with. */
private const val FLAG_FIRST = 0x1F1E6
private const val FLAG_LAST = 0x1F1FF
private const val FLAG_HIGH_SURROGATE = '\uD83C'
