package app.scalemate.update

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scalemate.R
import app.scalemate.SettingsDivider
import app.scalemate.SettingsGroup
import app.scalemate.SettingsRow
import app.scalemate.SettingsToggle
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(
    vm: UpdateViewModel
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var sourceDialog by remember { mutableStateOf(false) }
    var channelWarning by remember { mutableStateOf(false) }
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val dateFormatter = remember(locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(ZoneId.systemDefault())
    }
    val number = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val installed = state.installed
    val currentVersion = installed?.let {
        if (it.packageName == "app.scalemate.fooddev") {
            stringResource(R.string.settings_dev_version, it.versionName)
        } else {
            "${it.versionName} · ${stringResource(if (it.isPreview) R.string.update_preview else R.string.update_stable)}"
        }
    } ?: stringResource(R.string.loading)
    val entry = state.entry

    LaunchedEffect(state.showChannelWarning) {
        if (state.showChannelWarning) {
            channelWarning = true
            vm.dismissChannelWarning()
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(R.string.update_current_version),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .75f)
                )
                Text(
                    currentVersion,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (installed != null) {
                    Text(
                        stringResource(
                            R.string.update_version_code,
                            installed.versionCode,
                            stringResource(
                                if (installed.isPreview) R.string.update_preview else R.string.update_stable
                            )
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .8f)
                    )
                }
                if (state.lastCheckAt > 0) {
                    Text(
                        stringResource(
                            R.string.update_last_checked,
                            dateFormatter.format(Instant.ofEpochMilli(state.lastCheckAt))
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .8f)
                    )
                }
            }
        }

        when (state.phase) {
            UpdatePhase.AVAILABLE,
            UpdatePhase.DOWNLOADING,
            UpdatePhase.VERIFYING,
            UpdatePhase.WAITING_INSTALL,
            UpdatePhase.INSTALLING -> entry?.let {
                UpdateAvailableCard(
                    entry = it,
                    state = state,
                    number = number,
                    onDownload = vm::downloadAndInstall,
                    onInstallVerified = vm::installVerified,
                    onCancel = vm::cancelDownload,
                    onOpenRelease = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.releaseUrl)))
                        }
                    }
                )
            }
            UpdatePhase.UP_TO_DATE -> {
                InfoSurface(
                    icon = Icons.Default.Check,
                    title = stringResource(R.string.update_up_to_date),
                    body = stringResource(R.string.update_up_to_date_hint)
                )
            }
            UpdatePhase.ERROR -> {
                InfoSurface(
                    icon = Icons.Default.Warning,
                    title = stringResource(R.string.update_check_failed),
                    body = state.message.ifBlank { stringResource(R.string.update_retry_hint) }
                )
            }
            else -> Unit
        }

        if (state.message.isNotBlank() && state.phase != UpdatePhase.ERROR) {
            Text(state.message, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = vm::check,
            enabled = state.phase !in listOf(
                UpdatePhase.CHECKING,
                UpdatePhase.DOWNLOADING,
                UpdatePhase.VERIFYING,
                UpdatePhase.INSTALLING
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
        ) {
            if (state.phase == UpdatePhase.CHECKING) {
                CircularProgressIndicator(
                    Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(10.dp))
            } else {
                Icon(Icons.Default.Refresh, null)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                stringResource(
                    if (state.phase == UpdatePhase.CHECKING) R.string.update_checking
                    else R.string.update_check
                )
            )
        }

        if (state.phase == UpdatePhase.ERROR && state.entry != null) {
            Button(onClick = vm::retry, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.update_download_retry))
            }
        }

        SettingsGroup(stringResource(R.string.update_channel)) {
            UpdateChoiceRow(
                title = stringResource(R.string.update_stable),
                summary = stringResource(R.string.update_stable_hint),
                selected = state.channel == UpdateChannel.STABLE,
                onClick = { vm.selectChannel(UpdateChannel.STABLE) }
            )
            SettingsDivider()
            UpdateChoiceRow(
                title = stringResource(R.string.update_preview),
                summary = stringResource(R.string.update_preview_hint),
                selected = state.channel == UpdateChannel.PREVIEW,
                badge = stringResource(R.string.update_preview_badge),
                onClick = { vm.selectChannel(UpdateChannel.PREVIEW) }
            )
        }

        SettingsGroup(stringResource(R.string.update_download_source)) {
            SettingsRow(
                stringResource(R.string.update_source),
                updateSourceSummary(state.sourceId)
            ) { sourceDialog = true }
            if (state.sourceId == UpdateSources.AUTO) {
                SettingsDivider()
                Text(
                    stringResource(R.string.update_source_auto_hint),
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SettingsGroup(stringResource(R.string.update_settings)) {
            SettingsToggle(
                stringResource(R.string.update_auto_check),
                stringResource(R.string.update_auto_check_hint),
                state.autoCheck,
                onChange = vm::setAutoCheck
            )
            SettingsDivider()
            SettingsToggle(
                stringResource(R.string.update_auto_download),
                stringResource(R.string.update_auto_download_hint),
                state.autoDownload,
                onChange = vm::setAutoDownload
            )
            SettingsDivider()
            SettingsToggle(
                stringResource(R.string.update_unmetered),
                stringResource(R.string.update_unmetered_hint),
                state.unmeteredOnly,
                onChange = vm::setUnmeteredOnly
            )
        }

        if (!state.canInstallPackages && state.phase in listOf(
                UpdatePhase.AVAILABLE,
                UpdatePhase.DOWNLOADING,
                UpdatePhase.WAITING_INSTALL
            )
        ) {
            AssistChip(
                onClick = vm::requestInstallPermission,
                label = { Text(stringResource(R.string.update_install_permission_needed)) },
                leadingIcon = { Icon(Icons.Default.Lock, null) }
            )
        }

        Text(
            stringResource(R.string.update_install_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stringResource(R.string.update_proxy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
    }

    if (sourceDialog) {
        AlertDialog(
            onDismissRequest = { vm.cancelRouteProbe(); sourceDialog = false },
            title = { Text(stringResource(R.string.update_download_source)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.update_probe_hint), style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            modifier = Modifier.weight(1f),
                            onClick = vm::probeRoutes,
                            enabled = !state.probingRoutes && state.phase !in listOf(
                                UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING, UpdatePhase.INSTALLING)
                        ) {
                            Text(stringResource(if (state.routeProbes.isEmpty())
                                R.string.update_probe_start else R.string.update_probe_again))
                        }
                        if (state.probingRoutes) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            TextButton(onClick = vm::cancelRouteProbe) {
                                Text(stringResource(R.string.update_probe_stop))
                            }
                        }
                    }
                    val latestProbe = state.routeProbes.values.maxOfOrNull { it.checkedAt }
                    if (latestProbe != null) {
                        Text(stringResource(R.string.update_probe_checked, state.probeTag,
                            dateFormatter.format(Instant.ofEpochMilli(latestProbe))),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    SourceChoice(
                        title = stringResource(R.string.update_source_auto),
                        summary = stringResource(R.string.update_source_auto_summary),
                        selected = state.sourceId == UpdateSources.AUTO
                    ) {
                        vm.selectSource(UpdateSources.AUTO)
                        sourceDialog = false
                    }
                    UpdateSources.all.forEach { source ->
                        SourceChoice(
                            title = if (source.kind == UpdateSourceKind.GITHUB) {
                                stringResource(R.string.update_source_github)
                            } else {
                                source.label
                            },
                            summary = when (source.kind) {
                                UpdateSourceKind.GITHUB -> stringResource(R.string.update_source_github_summary)
                                else -> stringResource(
                                    if (source.id in setOf("ghfast-top", "ghproxy-net"))
                                        R.string.update_source_slow_summary
                                    else R.string.update_source_proxy_summary
                                )
                            },
                            selected = state.sourceId == source.id,
                            warning = source.isThirdParty,
                            result = state.routeProbes[source.id],
                            probing = state.probingRoutes && source.id !in state.routeProbes,
                            fastest = !state.probingRoutes && fastestProbe(state.routeProbes.values) == source.id
                        ) {
                            vm.selectSource(source.id)
                            sourceDialog = false
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.cancelRouteProbe(); sourceDialog = false }) {
                    Text(stringResource(R.string.done))
                }
            }
        )
    }

    if (channelWarning) {
        AlertDialog(
            onDismissRequest = { channelWarning = false },
            title = { Text(stringResource(R.string.update_preview_confirm_title)) },
            text = { Text(stringResource(R.string.update_preview_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        channelWarning = false
                        vm.confirmPreviewChannel()
                    }
                ) {
                    Text(stringResource(R.string.update_preview_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { channelWarning = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun UpdateAvailableCard(
    entry: ReleaseIndexEntry,
    state: UpdateUiState,
    number: NumberFormat,
    onDownload: () -> Unit,
    onInstallVerified: () -> Unit,
    onCancel: () -> Unit,
    onOpenRelease: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp)
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Icon(
                        Icons.Default.Refresh,
                        null,
                        Modifier.padding(10.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.versionName, style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(
                            if (entry.isPreview) R.string.update_preview_available
                            else R.string.update_stable_available
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "${number.format(entry.apkSizeBytes / 1024 / 1024)} MB",
                    style = MaterialTheme.typography.labelMedium
                )
            }
            if (entry.highlights.isNotEmpty()) {
                entry.highlights.take(5).forEach {
                    Text("· $it", style = MaterialTheme.typography.bodyMedium)
                }
            }
            entry.knownIssues.take(4).forEach {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        stringResource(R.string.update_known_issue, it),
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            if (state.phase == UpdatePhase.VERIFYING) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.update_verifying))
            } else if (state.phase == UpdatePhase.DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(
                        R.string.update_download_progress,
                        state.downloadedBytes / 1024 / 1024,
                        state.totalBytes / 1024 / 1024
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cancel))
                }
            } else if (state.phase in listOf(UpdatePhase.WAITING_INSTALL, UpdatePhase.INSTALLING)) {
                Text(
                    stringResource(R.string.update_waiting_install),
                    color = MaterialTheme.colorScheme.primary
                )
                Button(onClick = onInstallVerified, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.update_install_verified))
                }
            } else {
                Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.update_download_install))
                }
            }
            TextButton(onClick = onOpenRelease, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.update_release_notes))
            }
        }
    }
}

@Composable
private fun InfoSurface(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun UpdateChoiceRow(
    title: String,
    summary: String,
    selected: Boolean,
    onClick: () -> Unit,
    badge: String? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Medium)
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        RadioButton(selected = selected, onClick = onClick)
    }
}

@Composable
private fun SourceChoice(
    title: String,
    summary: String,
    selected: Boolean,
    warning: Boolean = false,
    result: RouteProbeResult? = null,
    probing: Boolean = false,
    fastest: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontWeight = FontWeight.Medium)
            if (selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
        }
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall,
            color = if (warning) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (probing) {
            Text(stringResource(R.string.update_probe_pending), style = MaterialTheme.typography.labelSmall)
        } else if (result != null) {
            val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
            val metric = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }
            Text(
                if (result.failed) stringResource(R.string.update_probe_failed)
                else stringResource(R.string.update_probe_result,
                    metric.format(result.kibPerSecond), metric.format(result.elapsedMillis / 1000.0)),
                style = MaterialTheme.typography.labelMedium,
                color = if (result.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
            if (fastest) Text(stringResource(R.string.update_probe_fastest),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun updateSourceSummary(sourceId: String): String = when {
    sourceId == UpdateSources.AUTO -> stringResource(R.string.update_source_auto)
    UpdateSources.find(sourceId)?.kind == UpdateSourceKind.GITHUB ->
        stringResource(R.string.update_source_github)
    else -> UpdateSources.find(sourceId)?.label ?: sourceId
}
