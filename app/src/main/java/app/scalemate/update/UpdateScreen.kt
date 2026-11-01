package app.scalemate.update

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
        UpdateStatusContent(
            state = state,
            onCheck = vm::check,
            onDownload = vm::downloadUpdate,
            onInstall = vm::installVerified,
            onCancel = vm::cancelDownload,
            onOpenRelease = { entry ->
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.releaseUrl)))
                }
            }
        )

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
                UpdatePhase.PREPARING,
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
                                UpdatePhase.PREPARING, UpdatePhase.DOWNLOADING,
                                UpdatePhase.VERIFYING, UpdatePhase.INSTALLING)
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
internal fun UpdateStatusContent(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onOpenRelease: (ReleaseIndexEntry) -> Unit
) {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val dateFormatter = remember(locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale)
            .withZone(ZoneId.systemDefault())
    }
    val installed = state.installed
    val entry = state.entry?.takeIf { state.hasUpdate }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.update_current_version), style = MaterialTheme.typography.labelLarge)
                Text(installed?.versionName ?: stringResource(R.string.loading),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                installed?.let {
                    Text(stringResource(R.string.update_version_code, it.versionCode,
                        stringResource(if (it.isPreview) R.string.update_preview else R.string.update_stable)),
                        style = MaterialTheme.typography.bodySmall)
                }
                if (state.lastCheckAt > 0) Text(
                    stringResource(R.string.update_last_checked, dateFormatter.format(Instant.ofEpochMilli(state.lastCheckAt))),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .75f)
                )
            }
        }
        if (entry != null) {
            UpdateAvailableCard(entry, state, onCheck, onDownload, onInstall, onCancel) { onOpenRelease(entry) }
        } else {
            when (state.phase) {
                UpdatePhase.UP_TO_DATE -> InfoSurface(Icons.Default.Check,
                    stringResource(R.string.update_up_to_date), stringResource(R.string.update_up_to_date_hint))
                UpdatePhase.ERROR -> InfoSurface(Icons.Default.Warning,
                    stringResource(R.string.update_check_failed), state.message.ifBlank { stringResource(R.string.update_retry_hint) })
                else -> Unit
            }
            Button(
                onClick = onCheck,
                enabled = state.loaded && installed != null && state.phase != UpdatePhase.CHECKING,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
            ) {
                if (state.phase == UpdatePhase.CHECKING) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary)
                } else Icon(Icons.Default.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(when (state.phase) {
                    UpdatePhase.CHECKING -> R.string.update_checking
                    UpdatePhase.ERROR -> R.string.update_check_retry
                    else -> R.string.update_check
                }))
            }
            if (state.message.isNotBlank() && state.phase != UpdatePhase.ERROR) {
                Text(state.message, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun UpdateAvailableCard(
    entry: ReleaseIndexEntry,
    state: UpdateUiState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onOpenRelease: () -> Unit
) {
    var detailsExpanded by remember(entry.tag) { mutableStateOf(false) }
    val busy = state.phase in setOf(UpdatePhase.CHECKING, UpdatePhase.PREPARING,
        UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING, UpdatePhase.INSTALLING)
    val number = remember { NumberFormat.getIntegerInstance() }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (entry.isPreview) R.string.update_preview_available else R.string.update_stable_available),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.versionName, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
                Text("${number.format(entry.apkSizeBytes / 1024 / 1024)} MB", style = MaterialTheme.typography.labelMedium)
            }
            when (state.phase) {
                UpdatePhase.CHECKING, UpdatePhase.PREPARING, UpdatePhase.VERIFYING, UpdatePhase.INSTALLING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                UpdatePhase.DOWNLOADING -> {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.update_download_progress, state.downloadedBytes / 1024 / 1024,
                        state.totalBytes / 1024 / 1024), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.update_download_route, updateSourceSummary(state.activeSourceId)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.update_download_speed, state.downloadSpeedBytesPerSecond / 1024),
                            style = MaterialTheme.typography.bodySmall)
                        state.remainingSeconds?.let {
                            Text(stringResource(R.string.update_download_remaining, it), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                UpdatePhase.WAITING_INSTALL -> Text(stringResource(R.string.update_waiting_install),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                else -> Unit
            }
            if (state.message.isNotBlank()) Text(state.message, style = MaterialTheme.typography.bodySmall,
                color = if (state.phase == UpdatePhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = if (state.phase == UpdatePhase.WAITING_INSTALL) onInstall else onDownload,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
            ) {
                Text(stringResource(when (state.phase) {
                    UpdatePhase.CHECKING -> R.string.update_checking
                    UpdatePhase.PREPARING -> R.string.update_preparing
                    UpdatePhase.DOWNLOADING -> R.string.update_downloading
                    UpdatePhase.VERIFYING -> R.string.update_verifying
                    UpdatePhase.INSTALLING -> R.string.update_install_opening
                    UpdatePhase.WAITING_INSTALL -> R.string.update_install_verified
                    UpdatePhase.ERROR -> R.string.update_download_retry
                    else -> R.string.update_download_install
                }))
            }
            if (state.phase in setOf(UpdatePhase.PREPARING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)) {
                TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.update_cancel_download))
                }
            } else if (!busy && state.phase != UpdatePhase.WAITING_INSTALL) {
                TextButton(onClick = onCheck, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.update_recheck))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (!detailsExpanded) entry.highlights.firstOrNull()?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            TextButton(onClick = { detailsExpanded = !detailsExpanded }, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(if (detailsExpanded) R.string.update_changes_less else R.string.update_changes_more))
            }
            if (detailsExpanded) {
                entry.highlights.take(5).forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium) }
                entry.knownIssues.take(4).forEach {
                    Text(stringResource(R.string.update_known_issue, it), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onOpenRelease) { Text(stringResource(R.string.update_release_notes)) }
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
