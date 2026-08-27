package dev.yukaribox.vpn.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.data.RouteRepository
import dev.yukaribox.vpn.data.RouteRule
import dev.yukaribox.vpn.data.RuleOutbound
import dev.yukaribox.vpn.ui.kit.AlertBadge
import dev.yukaribox.vpn.ui.kit.CircleButton
import dev.yukaribox.vpn.ui.kit.ConfirmDialog
import dev.yukaribox.vpn.ui.kit.EmptyState
import dev.yukaribox.vpn.ui.kit.GAP
import dev.yukaribox.vpn.ui.kit.ListMargin
import dev.yukaribox.vpn.ui.kit.OutlineBadge
import dev.yukaribox.vpn.ui.kit.PaperCard
import dev.yukaribox.vpn.ui.kit.SegmentedTabs
import dev.yukaribox.vpn.ui.kit.TitleTopBar
import dev.yukaribox.vpn.ui.kit.YukariSwitch
import dev.yukaribox.vpn.ui.kit.swapSpec
import dev.yukaribox.vpn.ui.theme.LabelWide
import dev.yukaribox.vpn.ui.theme.ListCardShape
import dev.yukaribox.vpn.ui.theme.YukariIcons
import dev.yukaribox.vpn.ui.theme.yukari
import java.util.UUID

/**
 * Custom routing rules: an ordered list of match → outbound, applied before bypass-LAN,
 * with the list's order as its priority.
 *
 * Every rule is sanitized on its way to the core (`RouteRuleValidation`), which is not a
 * formality: a rule whose only condition is malformed would reach sing-box as an object
 * with no conditions at all, and sing-box reads that as "match everything" — so a typo in
 * a `direct` rule's CIDR used to send the user's entire traffic outside the proxy.
 */
@Composable
fun RoutesScreen(onBack: () -> Unit) {
    var editing by remember { mutableStateOf<RouteRule?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<RouteRule?>(null) }

    Column(Modifier.fillMaxSize()) {
        TitleTopBar(
            title = stringResource(R.string.title_routes),
            onNav = onBack,
            navContentDescription = stringResource(R.string.cd_back),
        )
        Box(Modifier.weight(1f)) {
            if (RouteRepository.rules.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.routes_empty_title),
                    body = stringResource(R.string.routes_empty_hint),
                    icon = YukariIcons.Routes,
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(GAP),
                    contentPadding = PaddingValues(
                        start = ListMargin,
                        end = ListMargin,
                        top = 8.dp,
                        bottom = 96.dp,
                    ),
                ) {
                    item {
                        Text(
                            stringResource(R.string.routes_hint_priority),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    items(RouteRepository.rules, key = { it.id }) { rule ->
                        RuleCard(
                            rule = rule,
                            modifier = Modifier.animateItem(
                        fadeInSpec = swapSpec(),
                        placementSpec = swapSpec(),
                        fadeOutSpec = swapSpec(),
                    ),
                            onToggle = { RouteRepository.setEnabled(rule.id, it) },
                            onEdit = { editing = rule },
                            onDelete = { deleteTarget = rule },
                            onMoveUp = { RouteRepository.move(rule.id, up = true) },
                            onMoveDown = { RouteRepository.move(rule.id, up = false) },
                        )
                    }
                }
            }
            CircleButton(
                icon = Icons.Default.Add,
                contentDescription = stringResource(R.string.routes_new_rule),
                onClick = { creating = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = FAB_INSET_END, bottom = FAB_INSET_BOTTOM),
            )
        }
    }

    if (creating) {
        RuleEditorDialog(
            rule = RouteRule(id = UUID.randomUUID().toString()),
            onSave = { RouteRepository.add(it); creating = false },
            onDismiss = { creating = false },
        )
    }
    editing?.let { rule ->
        RuleEditorDialog(
            rule = rule,
            onSave = { RouteRepository.update(it); editing = null },
            onDismiss = { editing = null },
        )
    }
    deleteTarget?.let { rule ->
        ConfirmDialog(
            title = stringResource(R.string.routes_delete_title),
            body = stringResource(
                R.string.routes_delete_body,
                rule.name.ifBlank { stringResource(R.string.routes_rule_unnamed) },
            ),
            confirmLabel = stringResource(R.string.action_delete),
            cancelLabel = stringResource(R.string.action_cancel),
            onConfirm = { RouteRepository.delete(rule.id); deleteTarget = null },
            onDismiss = { deleteTarget = null },
        )
    }
}

// -------------------------------------------------------------- rule card ----

@Composable
private fun RuleCard(
    rule: RouteRule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val alpha = if (rule.enabled) 1f else DISABLED_ALPHA
    PaperCard(
        modifier = modifier.fillMaxWidth(),
        shape = ListCardShape,
        contentPadding = PaddingValues(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        onClick = onEdit,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutboundTag(rule.outbound)
            Column(Modifier.weight(1f)) {
                Text(
                    rule.name.ifBlank { stringResource(R.string.routes_rule_unnamed) },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    maxLines = 1,
                )
                Text(
                    ruleSummary(rule),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    maxLines = 2,
                )
            }
            Column {
                IconButton(onClick = onMoveUp, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.routes_cd_move_up),
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(onClick = onMoveDown, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.routes_cd_move_down),
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }
            YukariSwitch(checked = rule.enabled, onCheckedChange = onToggle)
        }
    }
}

/** Human summary of the rule's matchers, "no matchers" when it can't fire. */
@Composable
private fun ruleSummary(rule: RouteRule): String = buildList {
    if (rule.domains.isNotEmpty()) {
        add(pluralStringResource(R.plurals.routes_sum_domains, rule.domains.size, rule.domains.size))
    }
    if (rule.ipCidrs.isNotEmpty()) {
        add(pluralStringResource(R.plurals.routes_sum_cidrs, rule.ipCidrs.size, rule.ipCidrs.size))
    }
    if (rule.ports.isNotEmpty()) {
        add(stringResource(R.string.routes_sum_ports, rule.ports.joinToString(",")))
    }
    if (rule.packages.isNotEmpty()) {
        add(pluralStringResource(R.plurals.routes_sum_apps, rule.packages.size, rule.packages.size))
    }
}.joinToString(" · ").ifBlank { stringResource(R.string.routes_sum_no_matchers) }

/**
 * The outbound tag on a rule card.
 *
 * Three distinct *shapes* rather than three colours: a filled ink tag for proxy, an
 * outlined one for direct, and a filled tag with heavier wording for block. The three
 * decisions are not comparable on one scale, and there is no hue left to spend on
 * them — filled / outlined / filled-and-heavier is the escalation the design uses
 * everywhere a badge has to outrank the badge beside it.
 */
@Composable
private fun OutboundTag(outbound: RuleOutbound) {
    val label = stringResource(outboundLabelRes(outbound))
    when (outbound) {
        RuleOutbound.Proxy -> AlertBadge(label)
        RuleOutbound.Direct -> OutlineBadge(label)
        RuleOutbound.Block -> AlertBadge(label, heavy = true)
    }
}

@StringRes
private fun outboundLabelRes(outbound: RuleOutbound): Int = when (outbound) {
    RuleOutbound.Proxy -> R.string.routes_outbound_proxy
    RuleOutbound.Direct -> R.string.routes_outbound_direct
    RuleOutbound.Block -> R.string.routes_outbound_block
}

// ------------------------------------------------------------ rule editor ----

@Composable
private fun RuleEditorDialog(
    rule: RouteRule,
    onSave: (RouteRule) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(rule.name) }
    var domains by remember { mutableStateOf(rule.domains.joinToString("\n")) }
    var cidrs by remember { mutableStateOf(rule.ipCidrs.joinToString("\n")) }
    var ports by remember { mutableStateOf(rule.ports.joinToString(",")) }
    var packages by remember { mutableStateOf(rule.packages.joinToString("\n")) }
    var outbound by remember { mutableStateOf(rule.outbound) }
    val outboundLabels = RuleOutbound.entries.associateWith { stringResource(outboundLabelRes(it)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                stringResource(
                    if (rule.name.isBlank() && rule.isEmpty) R.string.routes_new_rule else R.string.routes_edit_rule,
                ),
            )
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.routes_field_name)) },
                    singleLine = true,
                )
                DialogCaption(stringResource(R.string.routes_section_match))
                OutlinedTextField(
                    value = domains,
                    onValueChange = { domains = it },
                    label = { Text(stringResource(R.string.routes_field_domains)) },
                    supportingText = { Text(stringResource(R.string.routes_field_domains_hint)) },
                    minLines = 2,
                )
                OutlinedTextField(
                    value = cidrs,
                    onValueChange = { cidrs = it },
                    label = { Text(stringResource(R.string.routes_field_cidrs)) },
                    minLines = 1,
                )
                OutlinedTextField(
                    value = ports,
                    onValueChange = { ports = it },
                    label = { Text(stringResource(R.string.routes_field_ports)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = packages,
                    onValueChange = { packages = it },
                    label = { Text(stringResource(R.string.routes_field_packages)) },
                    minLines = 1,
                )
                DialogCaption(stringResource(R.string.routes_section_outbound))
                SegmentedTabs(
                    options = RuleOutbound.entries.toList(),
                    selectedOption = outbound,
                    label = { outboundLabels.getValue(it) },
                    onSelect = { outbound = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                fun splitLines(s: String) = s.split('\n', ',', ';')
                    .map { it.trim() }.filter { it.isNotEmpty() }
                onSave(
                    rule.copy(
                        name = name.trim(),
                        domains = splitLines(domains),
                        ipCidrs = splitLines(cidrs),
                        ports = ports.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                        packages = splitLines(packages),
                        outbound = outbound,
                    ),
                )
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Section caption inside the rule editor, matching the screen-level one. */
@Composable
private fun DialogCaption(text: String) {
    Text(
        text.uppercase(),
        style = LabelWide,
        color = MaterialTheme.yukari.textTertiary,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

/** How much a disabled rule fades. Enough to read as off, not enough to be unreadable. */
private const val DISABLED_ALPHA = 0.45f

/** The FAB's insets — the same pair the servers screen uses, measured off the reference. */
private val FAB_INSET_END = 18.dp
private val FAB_INSET_BOTTOM = 24.dp
