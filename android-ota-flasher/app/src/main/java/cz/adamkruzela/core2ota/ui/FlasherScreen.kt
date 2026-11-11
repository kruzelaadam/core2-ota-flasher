package cz.adamkruzela.core2ota.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.adamkruzela.core2ota.ble.Core2Device
import cz.adamkruzela.core2ota.catalog.CatalogEntry
import cz.adamkruzela.core2ota.catalog.ModifiedPackage
import cz.adamkruzela.core2ota.data.FlashMode
import cz.adamkruzela.core2ota.mds.MdsEngine

private val Ink = Color(0xFF0B1C24)
private val Sea = Color(0xFF1A4A5C)
private val Sand = Color(0xFFE8DCC8)
private val Alert = Color(0xFF8B2E1F)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlasherScreen(
    state: UiState,
    onSelectDevice: (String) -> Unit,
    onMode: (FlashMode) -> Unit,
    onRefreshCatalog: () -> Unit,
    onSelectFirmware: (String) -> Unit,
    onSelectModified: (String) -> Unit,
    onFlash: () -> Unit,
    onSecretMenu: () -> Unit,
    onExitSecretMenu: () -> Unit,
    onCancel: () -> Unit,
    onCopyCommLog: () -> Unit = {},
    onEngine: (MdsEngine.Choice) -> Unit = {},
    onRestartApp: () -> Unit = {},
    onOpenSuuntoAppInfo: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Suunto Core 2 OTA Flasher",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "By Adam Krůžela - adam.kruzela@youklid.cz",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Ink,
                        titleContentColor = Sand,
                    ),
                expandedHeight = 56.dp,
            )
        },
        containerColor = Color(0xFFF3EEE4),
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WarningBanner()

            SectionLabel("Device")
            when {
                !state.hasBtPermission ->
                    Hint("Bluetooth permission required.")
                !state.btEnabled ->
                    Hint("Turn Bluetooth on, then reopen the app.")
                state.devices.isEmpty() ->
                    Hint("No bonded Suunto Core 2 — pair in system settings.")
            }
            DeviceDropdown(
                devices = state.devices,
                selectedMac = state.selectedMac,
                enabled = !state.busy && state.devices.isNotEmpty(),
                onSelect = onSelectDevice,
            )

            EngineSection(
                engine = state.engine,
                enabled = !state.busy,
                onEngine = onEngine,
                onRestartApp = onRestartApp,
                onOpenSuuntoAppInfo = onOpenSuuntoAppInfo,
            )

            SectionLabel("Mode")
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = state.mode == FlashMode.ORIGINAL,
                    onClick = { onMode(FlashMode.ORIGINAL) },
                    enabled = !state.busy,
                    label = { Text("Original") },
                )
                FilterChip(
                    selected = state.mode == FlashMode.MODIFIED,
                    onClick = { onMode(FlashMode.MODIFIED) },
                    enabled = !state.busy,
                    label = { Text("Modified") },
                )
                FilterChip(
                    selected = false,
                    onClick = onRefreshCatalog,
                    enabled = !state.busy && !state.catalogLoading,
                    label = { Text(if (state.catalogLoading) "…" else "Refresh") },
                )
            }

            SectionLabel("Firmware")
            if (state.catalogLoading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Hint(
                        if (state.mode == FlashMode.ORIGINAL) {
                            "Loading Suunto catalog…"
                        } else {
                            "Loading Drive mirror…"
                        },
                    )
                }
            }
            state.catalogError?.let { Text(it, color = Alert, style = MaterialTheme.typography.bodySmall) }
            when (state.mode) {
                FlashMode.MODIFIED -> {
                    ModifiedDropdown(
                        packages = state.modifiedPackages,
                        selectedName = state.selectedModifiedName,
                        enabled = !state.busy && state.modifiedPackages.isNotEmpty(),
                        onSelect = onSelectModified,
                    )
                    state.versionNotes?.let { notes ->
                        Text(
                            notes,
                            style = MaterialTheme.typography.labelSmall,
                            color = Ink,
                            softWrap = true,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFFFFBF5), RoundedCornerShape(6.dp))
                                    .padding(8.dp),
                        )
                    }
                }
                FlashMode.ORIGINAL -> {
                    FirmwareDropdown(
                        entries = state.firmwares,
                        selectedUri = state.selectedFirmwareUri,
                        enabled = !state.busy && state.firmwares.isNotEmpty(),
                        onSelect = onSelectFirmware,
                    )
                }
            }

            SectionLabel("Flash")
            Text(
                state.status,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.waitingForWatch) {
                Text("Loading — keep this app open", style = MaterialTheme.typography.labelSmall)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            state.downloadPercent?.let { pct ->
                Text("Download $pct%", style = MaterialTheme.typography.labelSmall)
                LinearProgressIndicator(
                    progress = { pct / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.transferPercent?.let { pct ->
                ProgressRow("Watch reading ${state.transferPart.orEmpty()}", pct)
            }
            state.dfuPercent?.let { pct ->
                ProgressRow("Bluetooth radio DFU (${state.dfuStage ?: "…"})", pct)
            }
            state.watchPercent?.let { pct ->
                ProgressRow("Update progress on the watch", pct)
            }
            state.fatalError?.let {
                Text(it, color = Alert, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            }
            state.success?.let {
                Text(it, color = Sea, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            }

            val flashEnabled =
                !state.busy &&
                    !state.selectedMac.isNullOrBlank() &&
                    when (state.mode) {
                        FlashMode.ORIGINAL -> !state.selectedFirmwareUri.isNullOrBlank()
                        FlashMode.MODIFIED -> !state.selectedModifiedName.isNullOrBlank()
                    }
            val secretEnabled = !state.busy && !state.selectedMac.isNullOrBlank()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onFlash, enabled = flashEnabled) {
                    Text(if (state.flashing) "Flashing…" else "Flash")
                }
                OutlinedButton(onClick = onSecretMenu, enabled = secretEnabled) {
                    Text(if (state.secretBusy) "Opening…" else "Secret menu")
                }
                OutlinedButton(onClick = onExitSecretMenu, enabled = secretEnabled) {
                    Text("Exit secret")
                }
                if (state.busy) {
                    OutlinedButton(onClick = onCancel) { Text("Cancel / stop") }
                }
            }
            OutlinedButton(
                onClick = onCopyCommLog,
                enabled = !state.commLogPath.isNullOrBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Copy log")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun WarningBanner() {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.horizontalGradient(listOf(Alert, Color(0xFFB85A2A))),
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "⚠️  Core 2 only",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleSmall,
        )
        val lines =
            listOf(
                "Sideload tool by Adam Krůžela",
                "Do not flash other Suunto models",
                "Force-stop the official Suunto app before flashing (keep it installed)",
                "Battery ≥ ~25%",
            )
        lines.forEach { line ->
            Text(
                "• $line",
                color = Color.White.copy(alpha = 0.95f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ProgressRow(label: String, pct: Int) {
    Text("$label $pct%", style = MaterialTheme.typography.labelSmall)
    LinearProgressIndicator(
        progress = { pct / 100f },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EngineSection(
    engine: EngineUi,
    enabled: Boolean,
    onEngine: (MdsEngine.Choice) -> Unit,
    onRestartApp: () -> Unit,
    onOpenSuuntoAppInfo: () -> Unit,
) {
    SectionLabel("Engine")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            MdsEngine.Choice.AUTO to "Auto",
            MdsEngine.Choice.SUUNTO to "Suunto",
            MdsEngine.Choice.MOVESENSE to "Movesense",
        ).forEach { (choice, label) ->
            FilterChip(
                selected = engine.choice == choice,
                onClick = { onEngine(choice) },
                enabled = enabled,
                label = { Text(label) },
            )
        }
    }
    val activeColor = if (engine.activeKind == MdsEngine.Kind.SUUNTO) Sea else Alert
    Text(
        "Active: ${engine.activeDescription}",
        style = MaterialTheme.typography.labelSmall,
        color = activeColor,
    )
    engine.note?.let {
        Text(it, style = MaterialTheme.typography.labelSmall, color = Alert)
    }
    if (engine.activeKind == MdsEngine.Kind.MOVESENSE) {
        Hint(
            "The bundled Movesense engine cannot receive the watch's update requests. " +
                "Install the official Suunto app; the flasher only borrows its engine.",
        )
    }
    if (engine.restartNeeded) {
        Button(onClick = onRestartApp, modifier = Modifier.fillMaxWidth()) {
            Text("Restart app to switch engine")
        }
    }
    if (engine.suuntoApp != null) {
        OutlinedButton(onClick = onOpenSuuntoAppInfo, modifier = Modifier.fillMaxWidth()) {
            Text("Suunto app ${engine.suuntoApp.substringBefore(' ')}: Force stop…")
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = Ink,
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Ink.copy(alpha = 0.75f))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceDropdown(
    devices: List<Core2Device>,
    selectedMac: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = devices.firstOrNull { it.address == selectedMac }
    val label =
        selected?.let { deviceLabel(it) } ?: "Select device"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text("Bonded Core 2") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier =
                Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            devices.forEach { device ->
                DropdownMenuItem(
                    text = { Text(deviceLabel(device)) },
                    onClick = {
                        onSelect(device.address)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun deviceLabel(device: Core2Device): String {
    // BLE name is typically "Suunto Core 2 {serial}" — show name + serial in parentheses, never MAC.
    val nameOnly =
        when {
            device.name.equals("Suunto Core 2 ${device.serial}", ignoreCase = true) ->
                "Suunto Core 2"
            device.name.startsWith("Suunto Core 2") ->
                device.name.substringBeforeLast(device.serial).trim().ifBlank { "Suunto Core 2" }
            else -> device.name
        }
    return "$nameOnly (${device.serial})"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModifiedDropdown(
    packages: List<ModifiedPackage>,
    selectedName: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = selectedName ?: if (packages.isEmpty()) "No packages" else "Select Corium package"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text("Versions") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier =
                Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            packages.forEach { pkg ->
                DropdownMenuItem(
                    text = { Text(pkg.name) },
                    onClick = {
                        onSelect(pkg.name)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FirmwareDropdown(
    entries: List<CatalogEntry>,
    selectedUri: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = entries.firstOrNull { it.uri == selectedUri }
    val label =
        selected?.let { catalogLabel(it) }
            ?: if (entries.isEmpty()) "No builds" else "Select firmware"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text("Explorer catalog") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier =
                Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            entries.forEach { e ->
                DropdownMenuItem(
                    text = { Text(catalogLabel(e)) },
                    onClick = {
                        onSelect(e.uri)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Catalog row: version + date only, e.g. `1.13.44 - 2026-08-31`. */
private fun catalogLabel(entry: CatalogEntry): String =
    "${entry.version} - ${catalogDateOnly(entry.uploadDate)}"

private fun catalogDateOnly(raw: String): String {
    val t = raw.trim()
    if (t.isEmpty()) return "—"
    val cut = t.indexOf('T').takeIf { it > 0 } ?: t.indexOf(' ').takeIf { it > 0 }
    return if (cut != null) t.substring(0, cut) else t.take(10)
}
