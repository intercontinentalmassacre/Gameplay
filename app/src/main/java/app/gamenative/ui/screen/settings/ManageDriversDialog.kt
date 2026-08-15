package app.gamenative.ui.screen.settings

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.service.SteamService
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.component.IconButton
import app.gamenative.ui.component.dialog.AlertDialog
import app.gamenative.ui.component.dialog.TextButton
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.BrightnessManager
import com.winlator.contents.AdrenotoolsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manage installed graphics drivers: see what's installed, which one is
 * active in the container, and remove unwanted ones. Companion to
 * [GetDriverDialog] — the "Add driver" button routes there.
 */
@Composable
fun ManageDriversDialog(
    open: Boolean,
    onDismiss: () -> Unit,
    onAddDriver: () -> Unit,
    activeDriverId: String? = null,
) {
    if (!open) return
    val ctx = LocalContext.current
    val activity = BrightnessManager.findActivity(ctx)
    // Only bail when a real Activity is mid-teardown. A null Activity is a
    // legitimate second-screen (Presentation) window context — never block it.
    if (activity != null && (activity.isFinishing || activity.isDestroyed)) return

    // Read installed drivers synchronously so the active card renders on
    // first frame. LaunchedEffect below refreshes the list when dialog re-opens.
    val installedDrivers = remember {
        mutableStateListOf<String>().apply { addAll(loadInstalledIds(ctx)) }
    }
    val driverMeta = remember {
        val map = mutableStateMapOf<String, Pair<String, String>>()
        installedDrivers.forEach { id ->
            map[id] = driverNameVersion(ctx, id)
        }
        map
    }
    var driverToDelete by remember { mutableStateOf<String?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            scope.launch {
                isImporting = true
                SteamService.isImporting = true
                val res = withContext(Dispatchers.IO) { handlePickedUri(ctx, it) }
                if (res.installed) {
                    refreshInstalledDrivers(ctx, installedDrivers, driverMeta)
                }
                SnackbarManager.show(res.userMessage)
                SteamService.isImporting = false
                isImporting = false
            }
        }
    }

    LaunchedEffect(open) {
        refreshInstalledDrivers(ctx, installedDrivers, driverMeta)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.settings_manage_drivers_title),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        SteamService.isImporting = true
                        launcher.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                    },
                    enabled = !isImporting,
                ) {
                    Icon(
                        imageVector = Icons.Filled.FileUpload,
                        contentDescription = stringResource(R.string.import_zip_from_device),
                    )
                }
            }
        },
        text = {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val isWide = maxWidth >= 600.dp
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    // Active driver card. On wide screens the label and content
                    // sit side-by-side so the card stays compact.
                    val activeMeta = activeDriverId?.let { driverMeta[it] }
                    if (activeDriverId != null && activeMeta != null) {
                        if (isWide) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.manage_active_driver_card),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = activeMeta.first,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    if (activeMeta.second.isNotEmpty()) {
                                        Text(
                                            text = stringResource(R.string.driver_installed_version, activeMeta.second),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        } else {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.manage_active_driver_card),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = activeMeta.first,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                                if (activeMeta.second.isNotEmpty()) {
                                    Text(
                                        text = stringResource(R.string.driver_installed_version, activeMeta.second),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))
                    }

                    if (installedDrivers.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(R.string.manage_no_drivers),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                onClick = onAddDriver,
                                modifier = Modifier.padding(top = 8.dp),
                            ) {
                                Text(stringResource(R.string.manage_no_drivers_action))
                            }
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.driver_installed_title),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        Column(modifier = Modifier.fillMaxWidth()) {
                            installedDrivers.forEach { id ->
                                val displayName = driverMeta[id]?.first?.takeIf { it.isNotEmpty() } ?: id
                                val version = driverMeta[id]?.second?.takeIf { it.isNotEmpty() }
                                val isActive = id == activeDriverId
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Text(
                                                text = displayName,
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                            if (isActive) {
                                                Text(
                                                    text = stringResource(R.string.manage_active_driver_card),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        }
                                        if (version != null) {
                                            Text(
                                                text = stringResource(R.string.driver_installed_version, version),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { driverToDelete = id },
                                        modifier = Modifier.padding(start = 8.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = stringResource(R.string.driver_action_delete),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // No bottom actions in body — Import lives in the title-bar overflow
                    // and Get a driver lives in the confirmButton footer. This keeps a single
                    // visual primary CTA per dialog and avoids three competing actions.

                    // Delete confirm — nested inside text lambda so it overlays the dialog content.
                    driverToDelete?.let { id ->
                        val pendingName = driverMeta[id]?.first?.takeIf { it.isNotEmpty() } ?: id
                        AlertDialog(
                            onDismissRequest = { driverToDelete = null },
                            title = { Text(text = stringResource(R.string.confirm_delete)) },
                            text = { Text(text = stringResource(R.string.remove_driver_confirmation, pendingName)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    try {
                                        AdrenotoolsManager(ctx).removeDriver(id)
                                        installedDrivers.remove(id)
                                        driverMeta.remove(id)
                                        SnackbarManager.show(ctx.getString(R.string.driver_snackbar_removed, pendingName))
                                    } catch (e: Exception) {
                                        SnackbarManager.show(
                                            ctx.getString(
                                                R.string.driver_snackbar_remove_error,
                                                pendingName,
                                                e.message ?: "",
                                            )
                                        )
                                    }
                                    driverToDelete = null
                                }) {
                                    Text(
                                        text = stringResource(R.string.driver_action_delete),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { driverToDelete = null }) {
                                    Text(stringResource(R.string.cancel))
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAddDriver) {
                Text(stringResource(R.string.manage_action_add_driver))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_ManageDriversDialog() {
    PluviaTheme {
        ManageDriversDialog(open = true, onDismiss = {}, onAddDriver = {})
    }
}

private data class DriverInstallResult(val userMessage: String, val installed: Boolean)

/**
 * Reload installed drivers from adrenotools and refresh both the list and
 * the metadata map. Synchronous; safe to call from any Composable context.
 */
private fun refreshInstalledDrivers(
    context: Context,
    list: androidx.compose.runtime.snapshots.SnapshotStateList<String>,
    meta: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Pair<String, String>>,
) {
    list.clear()
    meta.clear()
    runCatching {
        val mgr = AdrenotoolsManager(context)
        val ids = mgr.enumarateInstalledDrivers()
        list.addAll(ids)
        ids.forEach { id ->
            val name = runCatching { mgr.getDriverName(id) }.getOrNull().orEmpty()
            val version = runCatching { mgr.getDriverVersion(id) }.getOrNull().orEmpty()
            if (name.isNotEmpty() || version.isNotEmpty()) {
                meta[id] = name to version
            }
        }
    }
}

private fun loadInstalledIds(context: Context): List<String> =
    runCatching { AdrenotoolsManager(context).enumarateInstalledDrivers() }
        .getOrDefault(emptyList())

private fun driverNameVersion(context: Context, id: String): Pair<String, String> {
    val mgr = AdrenotoolsManager(context)
    val name = runCatching { mgr.getDriverName(id) }.getOrNull().orEmpty()
    val version = runCatching { mgr.getDriverVersion(id) }.getOrNull().orEmpty()
    return name to version
}

private fun handlePickedUri(context: Context, uri: Uri): DriverInstallResult {
    return try {
        val name = AdrenotoolsManager(context).installDriver(uri)
        if (name.isNotEmpty()) {
            DriverInstallResult(
                userMessage = context.getString(R.string.driver_snackbar_installed, name),
                installed = true,
            )
        } else {
            DriverInstallResult(
                userMessage = context.getString(R.string.driver_snackbar_install_failed),
                installed = false,
            )
        }
    } catch (e: Exception) {
        DriverInstallResult(
            userMessage = context.getString(R.string.driver_snackbar_import_error, e.message ?: ""),
            installed = false,
        )
    }
}
