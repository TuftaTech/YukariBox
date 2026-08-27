package dev.yukaribox.vpn.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.core.Logs
import dev.yukaribox.vpn.data.NodeRepository
import dev.yukaribox.vpn.proxy.ProxyNode
import dev.yukaribox.vpn.proxy.ProxyType
import dev.yukaribox.vpn.proxy.ShadowsocksMethods
import dev.yukaribox.vpn.proxy.TlsSettings
import dev.yukaribox.vpn.proxy.TransportSettings
import dev.yukaribox.vpn.ui.kit.ScreenMargin
import dev.yukaribox.vpn.ui.kit.SelectChip
import dev.yukaribox.vpn.ui.kit.TitleTopBar
import dev.yukaribox.vpn.ui.theme.ControlShape
import dev.yukaribox.vpn.ui.theme.LabelWide
import dev.yukaribox.vpn.ui.theme.segmentShape
import dev.yukaribox.vpn.ui.theme.yukari

/**
 * Manual node editor. Creates a new node ([editId] == null) or edits an
 * existing one in the active group. Field sections show/hide per protocol.
 */
@Composable
fun NodeEditScreen(editId: Int?, onClose: () -> Unit) {
    val existing = editId?.let { id -> NodeRepository.nodes.firstOrNull { it.id == id }?.node }

    var type by remember { mutableStateOf(existing?.type ?: ProxyType.VLESS) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var server by remember { mutableStateOf(existing?.server ?: "") }
    var port by remember { mutableStateOf(existing?.port?.toString() ?: "443") }
    var uuid by remember { mutableStateOf(existing?.uuid ?: "") }
    var password by remember { mutableStateOf(existing?.password ?: "") }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var encryption by remember { mutableStateOf(existing?.encryption ?: "") }
    var flow by remember { mutableStateOf(existing?.flow ?: "") }
    // tls
    var security by remember { mutableStateOf(existing?.tls?.security ?: "none") }
    var sni by remember { mutableStateOf(existing?.tls?.sni ?: "") }
    var alpn by remember { mutableStateOf(existing?.tls?.alpn?.joinToString(",") ?: "") }
    var fingerprint by remember { mutableStateOf(existing?.tls?.fingerprint ?: "") }
    var allowInsecure by remember { mutableStateOf(existing?.tls?.allowInsecure ?: false) }
    var realityPbk by remember { mutableStateOf(existing?.tls?.realityPublicKey ?: "") }
    var realitySid by remember { mutableStateOf(existing?.tls?.realityShortId ?: "") }
    // transport
    var network by remember { mutableStateOf(existing?.transport?.network ?: "tcp") }
    var trHost by remember { mutableStateOf(existing?.transport?.host ?: "") }
    var trPath by remember { mutableStateOf(existing?.transport?.path ?: "") }
    var grpcService by remember { mutableStateOf(existing?.transport?.grpcServiceName ?: "") }
    // hysteria2
    var obfs by remember { mutableStateOf(existing?.obfs ?: "") }
    var obfsPassword by remember { mutableStateOf(existing?.obfsPassword ?: "") }
    // wireguard
    var wgLocal by remember { mutableStateOf(existing?.wgLocalAddress?.joinToString(",") ?: "") }
    var wgPrivate by remember { mutableStateOf(existing?.wgPrivateKey ?: "") }
    var wgPeerPub by remember { mutableStateOf(existing?.wgPeerPublicKey ?: "") }
    var wgPsk by remember { mutableStateOf(existing?.wgPreSharedKey ?: "") }
    var wgMtu by remember { mutableStateOf(existing?.wgMtu?.toString() ?: "1420") }
    var wgReserved by remember { mutableStateOf(existing?.wgReserved ?: "") }
    // tuic
    var congestion by remember { mutableStateOf(existing?.congestionControl ?: "bbr") }
    var udpRelay by remember { mutableStateOf(existing?.udpRelayMode ?: "native") }

    var error by remember { mutableStateOf<String?>(null) }

    val messages = ValidationMessages(
        portNotNumber = stringResource(R.string.node_edit_err_port),
        serverRequired = stringResource(R.string.node_edit_err_server),
        uuidRequired = stringResource(R.string.node_edit_err_uuid),
        passwordRequired = stringResource(R.string.node_edit_err_password),
        methodRequired = stringResource(R.string.node_edit_err_method),
        privateKeyRequired = stringResource(R.string.node_edit_err_wg_private),
        peerKeyRequired = stringResource(R.string.node_edit_err_wg_peer),
        invalidInput = stringResource(R.string.node_edit_err_invalid),
    )

    val tlsCapable = type in setOf(ProxyType.VLESS, ProxyType.VMESS, ProxyType.TROJAN, ProxyType.HTTP)
    val tlsForced = type in setOf(ProxyType.HYSTERIA2, ProxyType.TUIC)
    val transportCapable = type in setOf(ProxyType.VLESS, ProxyType.VMESS, ProxyType.TROJAN)

    Column(Modifier.fillMaxSize()) {
        TitleTopBar(
            title = stringResource(R.string.title_node),
            onNav = onClose,
            navContentDescription = stringResource(R.string.action_close),
            navIcon = Icons.Default.Close,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenMargin),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Protocol picker
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ProxyType.entries.forEach { candidate ->
                    // A protocol is a label, not a category with a colour: nine hues on
                    // one row was the loudest thing in the palette this replaces.
                    SelectChip(
                        text = protocolLabel(candidate),
                        selected = type == candidate,
                        onClick = { if (existing == null) type = candidate },
                    )
                }
            }

            SectionHeading(stringResource(R.string.node_edit_section_basic))
            Field(stringResource(R.string.node_edit_name), name) { name = it }
            Field(stringResource(R.string.node_edit_server), server) { server = it }
            Field(stringResource(R.string.node_edit_port), port) { port = it.filter { c -> c.isDigit() }.take(5) }

            SectionHeading(protocolLabel(type))
            when (type) {
                ProxyType.VLESS -> {
                    Field(stringResource(R.string.node_edit_uuid), uuid) { uuid = it }
                    Field(stringResource(R.string.node_edit_flow), flow) { flow = it }
                }
                ProxyType.VMESS -> {
                    Field(stringResource(R.string.node_edit_uuid), uuid) { uuid = it }
                    Field(stringResource(R.string.node_edit_vmess_security), encryption) { encryption = it }
                }
                ProxyType.TROJAN -> {
                    Field(stringResource(R.string.node_edit_password), password) { password = it }
                }
                ProxyType.SHADOWSOCKS -> {
                    DropdownField(
                        label = stringResource(R.string.node_edit_method),
                        options = ShadowsocksMethods.SUPPORTED,
                        // A pre-existing node may carry a legacy method; show it so the
                        // user sees what they're replacing, but only allowlisted values
                        // can be picked and buildNode() rejects legacy on save.
                        value = encryption.ifBlank { ShadowsocksMethods.DEFAULT },
                        onSelect = { encryption = it },
                    )
                    Field(stringResource(R.string.node_edit_password), password) { password = it }
                }
                ProxyType.HYSTERIA2 -> {
                    Field(stringResource(R.string.node_edit_password), password) { password = it }
                    Field(stringResource(R.string.node_edit_obfs), obfs) { obfs = it }
                    if (obfs.isNotBlank()) {
                        Field(stringResource(R.string.node_edit_obfs_password), obfsPassword) { obfsPassword = it }
                    }
                }
                ProxyType.SOCKS, ProxyType.HTTP -> {
                    Field(stringResource(R.string.node_edit_username), username) { username = it }
                    Field(stringResource(R.string.node_edit_password_optional), password) { password = it }
                }
                ProxyType.WIREGUARD -> {
                    Field(stringResource(R.string.node_edit_wg_local), wgLocal) { wgLocal = it }
                    Field(stringResource(R.string.node_edit_wg_private), wgPrivate) { wgPrivate = it }
                    Field(stringResource(R.string.node_edit_wg_peer_pub), wgPeerPub) { wgPeerPub = it }
                    Field(stringResource(R.string.node_edit_wg_psk), wgPsk) { wgPsk = it }
                    Field(stringResource(R.string.node_edit_wg_mtu), wgMtu) { wgMtu = it.filter { c -> c.isDigit() }.take(4) }
                    Field(stringResource(R.string.node_edit_wg_reserved), wgReserved) { wgReserved = it }
                }
                ProxyType.TUIC -> {
                    Field(stringResource(R.string.node_edit_uuid), uuid) { uuid = it }
                    Field(stringResource(R.string.node_edit_password), password) { password = it }
                    FieldLabel(stringResource(R.string.node_edit_congestion))
                    SegmentedChoiceRow(
                        options = listOf("bbr", "cubic", "new_reno"),
                        selected = congestion,
                        onSelect = { congestion = it },
                    )
                    FieldLabel(stringResource(R.string.node_edit_udp_relay))
                    SegmentedChoiceRow(
                        options = listOf("native", "quic"),
                        selected = udpRelay,
                        onSelect = { udpRelay = it },
                    )
                }
            }

            if (transportCapable) {
                SectionHeading(stringResource(R.string.node_edit_section_transport))
                SegmentedChoiceRow(
                    options = listOf("tcp", "ws", "grpc", "http", "httpupgrade"),
                    selected = network,
                    onSelect = { network = it },
                )
                if (network in setOf("ws", "http", "httpupgrade")) {
                    Field(stringResource(R.string.node_edit_host_header), trHost) { trHost = it }
                    Field(stringResource(R.string.node_edit_path), trPath) { trPath = it }
                }
                if (network == "grpc") {
                    Field(stringResource(R.string.node_edit_grpc_service), grpcService) { grpcService = it }
                }
            }

            if (tlsCapable || tlsForced) {
                SectionHeading(stringResource(R.string.node_edit_section_tls))
                if (!tlsForced) {
                    SegmentedChoiceRow(
                        options = listOf("none", "tls", "reality"),
                        selected = security,
                        onSelect = { security = it },
                    )
                }
                if (tlsForced || security != "none") {
                    Field(stringResource(R.string.node_edit_sni), sni) { sni = it }
                    Field(stringResource(R.string.node_edit_alpn), alpn) { alpn = it }
                    if (!tlsForced) {
                        Field(stringResource(R.string.node_edit_fingerprint), fingerprint) { fingerprint = it }
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.node_edit_allow_insecure)) },
                        trailingContent = {
                            Switch(checked = allowInsecure, onCheckedChange = { allowInsecure = it })
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent,
                            headlineColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier.clickable { allowInsecure = !allowInsecure },
                    )
                    if (security == "reality") {
                        Field(stringResource(R.string.node_edit_reality_pbk), realityPbk) { realityPbk = it }
                        Field(stringResource(R.string.node_edit_reality_sid), realitySid) { realitySid = it }
                    }
                }
            }

            error?.let {
                // The warning glyph carries the severity; the sentence is plain ink.
                // There is no error hue in the palette, and a tinted line beside a glyph
                // that already says "warning" was saying it twice.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Button(
                onClick = {
                    try {
                        val node = buildNode(
                            type, name, server, port, uuid, password, username, encryption, flow,
                            security, sni, alpn, fingerprint, allowInsecure, realityPbk, realitySid,
                            network, trHost, trPath, grpcService, obfs, obfsPassword,
                            wgLocal, wgPrivate, wgPeerPub, wgPsk, wgMtu, wgReserved,
                            congestion, udpRelay, messages,
                        )
                        if (existing == null) {
                            NodeRepository.addNode(node)
                            Logs.i("UI", "manual node added: ${node.displayName}")
                        } else {
                            NodeRepository.updateNode(editId!!, node)
                            Logs.i("UI", "node updated: ${node.displayName}")
                        }
                        onClose()
                    } catch (e: Exception) {
                        error = e.message ?: messages.invalidInput
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(54.dp),
                shape = ControlShape,
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(if (existing == null) R.string.node_edit_btn_add else R.string.node_edit_btn_save),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Localized validation messages, resolved once in the composable scope. */
private class ValidationMessages(
    val portNotNumber: String,
    val serverRequired: String,
    val uuidRequired: String,
    val passwordRequired: String,
    val methodRequired: String,
    val privateKeyRequired: String,
    val peerKeyRequired: String,
    val invalidInput: String,
)

private fun buildNode(
    type: ProxyType,
    name: String,
    server: String,
    port: String,
    uuid: String,
    password: String,
    username: String,
    encryption: String,
    flow: String,
    security: String,
    sni: String,
    alpn: String,
    fingerprint: String,
    allowInsecure: Boolean,
    realityPbk: String,
    realitySid: String,
    network: String,
    trHost: String,
    trPath: String,
    grpcService: String,
    obfs: String,
    obfsPassword: String,
    wgLocal: String,
    wgPrivate: String,
    wgPeerPub: String,
    wgPsk: String,
    wgMtu: String,
    wgReserved: String,
    congestion: String,
    udpRelay: String,
    messages: ValidationMessages,
): ProxyNode {
    val portInt = port.toIntOrNull() ?: throw IllegalArgumentException(messages.portNotNumber)
    require(server.isNotBlank()) { messages.serverRequired }
    when (type) {
        ProxyType.VLESS, ProxyType.VMESS, ProxyType.TUIC ->
            require(uuid.isNotBlank()) { messages.uuidRequired }
        ProxyType.TROJAN, ProxyType.HYSTERIA2 ->
            require(password.isNotBlank()) { messages.passwordRequired }
        ProxyType.SHADOWSOCKS -> {
            require(ShadowsocksMethods.isSupported(encryption.ifBlank { ShadowsocksMethods.DEFAULT })) { messages.methodRequired }
            require(password.isNotBlank()) { messages.passwordRequired }
        }
        ProxyType.WIREGUARD -> {
            require(wgPrivate.isNotBlank()) { messages.privateKeyRequired }
            require(wgPeerPub.isNotBlank()) { messages.peerKeyRequired }
        }
        ProxyType.SOCKS, ProxyType.HTTP -> Unit
    }
    val effectiveSecurity = when {
        type == ProxyType.HYSTERIA2 || type == ProxyType.TUIC -> "tls"
        else -> security
    }
    val tls = TlsSettings(
        security = effectiveSecurity,
        sni = sni.trim(),
        alpn = alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        fingerprint = fingerprint.trim(),
        allowInsecure = allowInsecure,
        realityPublicKey = realityPbk.trim(),
        realityShortId = realitySid.trim(),
    )
    val transport = TransportSettings(
        network = network,
        host = trHost.trim(),
        path = trPath.trim(),
        grpcServiceName = grpcService.trim(),
    )
    return ProxyNode(
        type = type,
        name = name.trim(),
        server = server.trim(),
        port = portInt,
        uuid = uuid.trim(),
        password = password,
        username = username.trim(),
        encryption = when (type) {
            ProxyType.SHADOWSOCKS -> ShadowsocksMethods.normalize(encryption.ifBlank { ShadowsocksMethods.DEFAULT })
            ProxyType.VMESS -> encryption.trim().ifBlank { "auto" }
            else -> encryption.trim()
        },
        flow = flow.trim(),
        obfs = obfs.trim(),
        obfsPassword = obfsPassword,
        wgLocalAddress = wgLocal.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        wgPrivateKey = wgPrivate.trim(),
        wgPeerPublicKey = wgPeerPub.trim(),
        wgPreSharedKey = wgPsk.trim(),
        wgMtu = wgMtu.toIntOrNull() ?: 1420,
        wgReserved = wgReserved.trim(),
        congestionControl = congestion,
        udpRelayMode = udpRelay,
        tls = tls,
        transport = transport,
    )
}

/** Quiet caption above a chip row (e.g. congestion control values). */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * M3 single-choice segmented control over a small fixed option set (2–5 values).
 *
 * Stock apart from the cell shape, which comes from [segmentShape] rather than
 * `SegmentedButtonDefaults.itemShape`: the default resolves to M3's 50%-corner cell, and
 * a pill is the one silhouette this design does not have anywhere (§4).
 */
@Composable
private fun SegmentedChoiceRow(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = selected == option,
                onClick = { onSelect(option) },
                shape = segmentShape(index = index, count = options.size),
                label = { Text(option, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Read-only field that opens a dropdown over a fixed option set (too many for segments). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    label: String,
    options: List<String>,
    value: String,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        open = false
                    },
                )
            }
        }
    }
}

/**
 * Section caption inside the editor. Matches the one the settings screen uses, so a
 * user moving between the two reads the same rhythm rather than two dialects of it.
 */
@Composable
private fun SectionHeading(text: String) {
    Text(
        text.uppercase(),
        style = LabelWide,
        color = MaterialTheme.yukari.textTertiary,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    )
}
