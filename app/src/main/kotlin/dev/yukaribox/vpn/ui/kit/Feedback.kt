package dev.yukaribox.vpn.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.yukaribox.vpn.ui.theme.ControlShape
import dev.yukaribox.vpn.ui.theme.MeterShape
import dev.yukaribox.vpn.ui.theme.MicroLabel
import dev.yukaribox.vpn.ui.theme.yukari

/**
 * Empty states, notices, search and the one confirm dialog.
 *
 * An empty screen is an instruction, not a shrug, so [EmptyState] takes a body
 * sentence that says what to do next and an optional action that does it.
 */

/** Empty list placeholder: a bare glyph, a headline, one sentence, one action. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            // Bare, not plated: there is exactly one big circle per screen and it is
            // always the tunnel. On Servers and Routes a plated glyph here would be a
            // second circle sitting beside a 58 dp FAB.
            Icon(
                icon,
                null,
                Modifier.size(EMPTY_GLYPH),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) Box(Modifier.padding(top = 6.dp)) { action() }
    }
}

/**
 * Notice strip for a condition the user has to act on — above all the three
 * `loadFailed` flags: a store whose file could not be parsed has silently reset, and
 * an empty list looks exactly like a fresh install unless the app says otherwise.
 *
 * Severity escalates by **outline and weight**, never by hue: [emphasis] borders the
 * card and sets its sentence Bold, which is the same move a badge makes when it needs
 * to outrank the badge beside it.
 *
 * The 4 dp rule is geometry rather than severity, so it is unconditionally ink — the
 * transitional `accent` that could tint it is gone with its last caller.
 */
@Composable
fun Notice(
    text: String,
    modifier: Modifier = Modifier,
    emphasis: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    PaperCard(
        modifier = modifier.fillMaxWidth(),
        borderColor = if (emphasis) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        contentPadding = PaddingValues(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(30.dp)
                    .clip(MeterShape)
                    .background(MaterialTheme.yukari.ink),
            )
            Text(
                text,
                style = MaterialTheme.typography.bodySmall.let {
                    if (emphasis) it.copy(fontWeight = FontWeight.Bold) else it
                },
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                Text(
                    actionLabel.uppercase(),
                    style = MicroLabel,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clip(ControlShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(role = Role.Button, onClick = onAction)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * Search field: a soft-rounded rectangle, matching every other control.
 *
 * Outlined, and that is not decoration — the field is `paper` on a page that is the
 * same near-white, so without the hairline it has no edge at all.
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: ImageVector,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(ControlShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, ControlShape)
            .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            leadingIcon,
            null,
            Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Box(Modifier.weight(1f).padding(vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = LocalTextStyle.current
                    .merge(MaterialTheme.typography.bodyMedium)
                    .copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (trailing != null) trailing()
    }
}

/**
 * The app's one confirm dialog. Every destructive action routes through it so the
 * wording pattern — a question as the title, the consequence as the body, the verb as
 * the button — is enforced in one place rather than re-decided per call site.
 *
 * The destructive verb is carried by **weight**, not by a red: bold against a cancel
 * set in the tertiary grey at the default weight. There is no error colour left to
 * spend, and a bold verb beside a recessive one is the clearer pair anyway.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    cancelLabel: String,
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        containerColor = MaterialTheme.colorScheme.surface,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = if (destructive) FontWeight.Bold else FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(cancelLabel, color = MaterialTheme.yukari.textTertiary)
            }
        },
    )
}

/** The bare glyph an empty state leads with. */
private val EMPTY_GLYPH = 40.dp
