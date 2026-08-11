package app.gamenative.ui.screen.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import app.gamenative.ui.component.dialog.AlertDialog
import app.gamenative.ui.component.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import app.gamenative.ui.component.IconButton
import app.gamenative.ui.component.Button
import app.gamenative.ui.component.NoExtractOutlinedTextField
import androidx.compose.material3.Text
import app.gamenative.ui.component.dialog.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import app.gamenative.R
import app.gamenative.ui.theme.settingsTileColors
import com.alorma.compose.settings.ui.SettingsGroup
import app.gamenative.ui.component.settings.SettingsMenuLink
import com.winlator.contents.AdrenotoolsManager
import java.io.File
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ArrowDropDown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.material3.Surface
import app.gamenative.ui.theme.PluviaTheme
import android.content.res.Configuration
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.service.SteamService
import app.gamenative.drivers.DriverRepositoryCatalog
import app.gamenative.drivers.RemoteDriverPackage
import app.gamenative.ui.component.dialog.LoadingDialog
import java.io.IOException
import timber.log.Timber
import java.net.SocketTimeoutException


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverManagerDialog(open: Boolean, onDismiss: () -> Unit) {
    if (!open) return
    val ctx = LocalContext.current
    var lastMessage by remember { mutableStateOf<String?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var isInstalling by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadBytes by remember { mutableStateOf(0L) }
    var totalBytes by remember { mutableStateOf(-1L) }
    val scope = rememberCoroutineScope()

    val repositories = DriverRepositoryCatalog.trustedRepositories
    var selectedRepository by remember { mutableStateOf(repositories.first()) }
    var remotePackages by remember { mutableStateOf<List<RemoteDriverPackage>>(emptyList()) }
    var isLoadingManifest by remember { mutableStateOf(true) }
    var manifestError by remember { mutableStateOf<String?>(null) }

    // Repository and driver dropdown state
    var isRepositoryExpanded by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }
    var selectedDriverId by remember { mutableStateOf<String?>(null) }

    // Gather installed custom drivers via AdrenotoolsManager and allow refreshing
    val installedDrivers = remember { mutableStateListOf<String>() }
    val driverMeta = remember { mutableStateMapOf<String, Pair<String, String>>() }
    var driverToDelete by remember { mutableStateOf<String?>(null) }

    val refreshDriverList: () -> Unit = {
        installedDrivers.clear()
        driverMeta.clear()
        try {
            val list = AdrenotoolsManager(ctx).enumarateInstalledDrivers()
            installedDrivers.addAll(list)
            val mgr = AdrenotoolsManager(ctx)
            list.forEach { id ->
                val name = mgr.getDriverName(id)
                val version = mgr.getDriverVersion(id)
                driverMeta[id] = name to version
            }
        } catch (_: Exception) {}
    }

    // Fetch only the selected source. This keeps GitHub API use bounded and
    // makes changing sources explicit to the user.
    LaunchedEffect(selectedRepository.id) {
        refreshDriverList()
        selectedDriverId = null
        remotePackages = emptyList()
        manifestError = null
        isLoadingManifest = true
        try {
            remotePackages = DriverRepositoryCatalog.fetchPackages(selectedRepository)
            if (remotePackages.isEmpty()) {
                manifestError = ctx.getString(R.string.driver_repository_empty)
            }
            Timber.d(
                "DriverManagerDialog: Loaded %d packages from %s",
                remotePackages.size,
                selectedRepository.name,
            )
        } catch (e: Exception) {
            manifestError = ctx.getString(R.string.driver_error_loading, e.message ?: "")
            Timber.e(e, "DriverManagerDialog: Error loading %s", selectedRepository.name)
        } finally {
            isLoadingManifest = false
        }
    }

    LoadingDialog(
        visible = isDownloading,
        progress = downloadProgress,
        message = stringResource(R.string.downloading),
    )

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            scope.launch {
                isImporting = true
                val res = withContext(Dispatchers.IO) { handlePickedUri(ctx, it) }
                lastMessage = res
                if (res.startsWith("Installed driver:")) refreshDriverList()
                SnackbarManager.show(res)
                SteamService.isImporting = false
                isImporting = false
            }
        }
    }

    // Function to download and install a driver from URL
    val downloadAndInstallDriver = { driverPackage: RemoteDriverPackage ->
        scope.launch {
            val overallStart = System.currentTimeMillis()
            isDownloading = true
            downloadProgress = 0f
            downloadBytes = 0L
            totalBytes = -1L
            try {
                Timber.d("DriverManagerDialog: Starting download %s", driverPackage.downloadUrl)
                val destFile = File(ctx.cacheDir, driverPackage.fileName)
                var lastUpdate = 0L
                DriverRepositoryCatalog.downloadPackage(driverPackage, destFile) { downloaded, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 300) {
                        lastUpdate = now
                        scope.launch(Dispatchers.Main) {
                            downloadBytes = downloaded
                            totalBytes = total
                            downloadProgress = if (total > 0) {
                                (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                        }
                    }
                }
                // Mark download complete before installing
                val downloadDurationMs = System.currentTimeMillis() - overallStart
                val downloadedSize = destFile.length()
                Timber.d("DriverManagerDialog: Download complete in ${downloadDurationMs}ms (${formatBytes(downloadedSize)})")
                withContext(Dispatchers.Main) { isDownloading = false; downloadProgress = 1f; downloadBytes = downloadedSize }

                // Install the driver from the temporary file
                withContext(Dispatchers.Main) { isInstalling = true }
                Timber.d("DriverManagerDialog: Starting install")
                val uri = Uri.fromFile(destFile)
                val installStart = System.currentTimeMillis()
                val res = withContext(Dispatchers.IO) { handlePickedUri(ctx, uri) }
                val installDurationMs = System.currentTimeMillis() - installStart
                withContext(Dispatchers.Main) {
                    lastMessage = res
                    if (res.startsWith("Installed driver:")) refreshDriverList()
                    SnackbarManager.show(res)
                }
                Timber.d("DriverManagerDialog: Install complete in ${installDurationMs}ms")
                Timber.d("DriverManagerDialog: Download+Install total ${(System.currentTimeMillis() - overallStart)}ms")

                // Delete the temporary file
                withContext(Dispatchers.IO) {
                    destFile.delete()
                }
            } catch (e: SocketTimeoutException) {
                val errorMessage = ctx.getString(R.string.driver_timeout)
                lastMessage = errorMessage
                SnackbarManager.show(errorMessage)
                Timber.e(e, "DriverManagerDialog: Download timeout")
            } catch (e: IOException) {
                val errorMessage = if (e.message?.contains("timeout", ignoreCase = true) == true) {
                    ctx.getString(R.string.driver_timeout)
                } else {
                    ctx.getString(R.string.driver_network_error, e.message ?: "")
                }
                lastMessage = errorMessage
                SnackbarManager.show(errorMessage)
                Timber.e(e, "DriverManagerDialog: Download failed with IO error")
            } catch (e: Exception) {
                val errorMessage = "Error downloading driver: ${e.message}"
                lastMessage = errorMessage
                SnackbarManager.show(errorMessage)
                Timber.e(e, "DriverManagerDialog: Download failed")
            } finally {
                isDownloading = false
                isInstalling = false
                downloadProgress = 0f
                downloadBytes = 0L
                totalBytes = -1L
            }
        }
    }

    ConsoleManagerDialog(
        open = open,
        title = stringResource(R.string.driver_manager),
        onDismiss = onDismiss,
    ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.driver_manager_description),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                Text(
                    text = stringResource(R.string.driver_repository_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                ExposedDropdownMenuBox(
                    expanded = isRepositoryExpanded,
                    onExpandedChange = { isRepositoryExpanded = !isRepositoryExpanded },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    NoExtractOutlinedTextField(
                        value = selectedRepository.name,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = isRepositoryExpanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = isRepositoryExpanded,
                        onDismissRequest = { isRepositoryExpanded = false },
                    ) {
                        repositories.forEach { repository ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(repository.name)
                                        Text(
                                            text = repository.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    selectedRepository = repository
                                    isRepositoryExpanded = false
                                },
                            )
                        }
                    }
                }
                Text(
                    text = selectedRepository.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
                )

                // Online packages from the selected repository.
                if (isLoadingManifest) {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.driver_repository_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                } else if (manifestError != null) {
                    Text(
                        text = manifestError ?: "Unknown error",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else if (remotePackages.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.driver_repository_available),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )

                    ExposedDropdownMenuBox(
                        expanded = isExpanded,
                        onExpandedChange = { isExpanded = !isExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        NoExtractOutlinedTextField(
                            value = remotePackages
                                .firstOrNull { it.id == selectedDriverId }
                                ?.displayName
                                .orEmpty(),
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            placeholder = { Text(stringResource(R.string.select_a_driver)) }
                        )

                        ExposedDropdownMenu(
                            expanded = isExpanded,
                            onDismissRequest = { isExpanded = false }
                        ) {
                            remotePackages.forEach { driverPackage ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(driverPackage.displayName)
                                            if (driverPackage.sizeBytes > 0) {
                                                Text(
                                                    text = formatBytes(driverPackage.sizeBytes),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        selectedDriverId = driverPackage.id
                                        isExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    remotePackages.firstOrNull { it.id == selectedDriverId }?.let { selectedPackage ->
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 16.dp)
                        ) {
                            Button(
                                onClick = { downloadAndInstallDriver(selectedPackage) },
                                enabled = !isDownloading && !isImporting
                            ) {
                                Text(stringResource(R.string.download))
                            }

                            if (isDownloading) {
                                if (totalBytes > 0) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        LinearProgressIndicator(progress = downloadProgress)
                                        Row(
                                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 4.dp)
                                        ) {
                                            Text(
                                                text = "${formatBytes(downloadBytes)} / ${formatBytes(totalBytes)}"
                                            )
                                        }
                                    }
                                } else {
                                    Column(modifier = Modifier.weight(1f)) {
                                        LinearProgressIndicator() // indeterminate when total unknown
                                        Row(
                                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 4.dp)
                                        ) {
                                            Text(text = stringResource(R.string.downloading))
                                        }
                                    }
                                }
                            }
                            if (isInstalling) {
                                Row(
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.height(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Text(text = stringResource(R.string.installing))
                                }
                            }
                        }
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 16.dp))

                // Local driver import section
                Text(
                    text = stringResource(R.string.driver_import_local_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Button(
                    onClick = {
                        SteamService.isImporting = true
                        launcher.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                    },
                    enabled = !isImporting && !isDownloading,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Text(stringResource(R.string.import_zip_from_device))
                }

                if (isImporting) {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.driver_importing),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }

                if (installedDrivers.isNotEmpty()) {
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        text = stringResource(R.string.driver_installed_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        for (id in installedDrivers) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                val meta = driverMeta[id]
                                val display = buildString {
                                    if (!meta?.first.isNullOrEmpty()) append(meta?.first) else append(id)
                                }
                                Text(
                                    text = display,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                IconButton(
                                    onClick = { driverToDelete = id },
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                    // Confirmation dialog for deletion
                    driverToDelete?.let { id ->
                        AlertDialog(
                            onDismissRequest = { driverToDelete = null },
                            title = { Text(text = stringResource(R.string.confirm_delete)) },
                            text = { Text(text = stringResource(R.string.remove_driver_confirmation, id)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    try {
                                        AdrenotoolsManager(ctx).removeDriver(id)
                                        lastMessage = "Removed driver: $id"
                                        SnackbarManager.show("Removed driver: $id")
                                        refreshDriverList()
                                    } catch (e: Exception) {
                                        lastMessage = "Error removing $id: ${e.message}"
                                        SnackbarManager.show("Error removing $id: ${e.message}")
                                    }
                                    driverToDelete = null
                                }) {
                                    Text(
                                        text = "Delete",
                                        color = MaterialTheme.colorScheme.error
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
    }
}

private fun handlePickedUri(context: Context, uri: Uri): String {
    return try {
        val name = AdrenotoolsManager(context).installDriver(uri)
        if (name.isNotEmpty()) {
            "Installed driver: $name"
        } else {
            "Failed to install driver: driver already installed or .zip corrupted"
        }
    } catch (e: Exception) {
        "Error importing driver: ${e.message}"
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes} B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format("%.2f GB", gb)
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview
@Composable
private fun Preview_DriverManagerDialog() {
    PluviaTheme {
        Surface {
            DriverManagerDialog(open = true, onDismiss = { })
        }
    }
}
