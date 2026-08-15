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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.drivers.AdrenoTarget
import app.gamenative.drivers.DriverCategory
import app.gamenative.drivers.DriverRepositoryCatalog
import app.gamenative.drivers.RemoteDriverPackage
import app.gamenative.drivers.detectAdrenoTarget
import app.gamenative.drivers.preferredCategoryFor
import app.gamenative.service.SteamService
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.component.dialog.AlertDialog
import app.gamenative.ui.component.dialog.ConsoleSettingsPage
import app.gamenative.ui.component.DropdownMenuItem
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.BrightnessManager
import androidx.compose.material3.Button
import com.winlator.contents.AdrenotoolsManager
import com.winlator.core.GPUInformation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale

/**
 * Browse online graphics-driver sources and install one for your device.
 *
 * Single primary action: pick the source (auto-selected by GPU detection),
 * download the recommended package, install. Advanced controls collapse into
 * a "Change source" bottom sheet so the happy path stays one tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GetDriverDialog(
    open: Boolean,
    onDismiss: () -> Unit,
) {
    if (!open) return
    val ctx = LocalContext.current
    val activity = BrightnessManager.findActivity(ctx)
    // Only bail when a real Activity is mid-teardown. A null Activity is a
    // legitimate second-screen (Presentation) window context — never block it.
    if (activity != null && (activity.isFinishing || activity.isDestroyed)) return

    val detectedTarget by remember { mutableStateOf(detectAdrenoTarget(ctx)) }
    val detectedRenderer by remember { mutableStateOf(GPUInformation.getRenderer(ctx)) }
    val visibleCategories = remember {
        DriverRepositoryCatalog.categories.filter { category ->
            DriverRepositoryCatalog.repositoriesByCategory(category).isNotEmpty()
        }
    }
    var selectedCategory by remember(detectedTarget) {
        mutableStateOf(
            run {
                val preferred = preferredCategoryFor(detectedTarget)
                if (preferred in visibleCategories) preferred
                else visibleCategories.firstOrNull() ?: DriverCategory.TURNIP_STABLE
            }
        )
    }
    val filteredRepositories = remember(selectedCategory) {
        DriverRepositoryCatalog.repositoriesByCategory(selectedCategory)
    }
    var selectedRepository by remember(filteredRepositories) {
        mutableStateOf(filteredRepositories.firstOrNull()!!)
    }
    var remotePackages by remember { mutableStateOf<List<RemoteDriverPackage>>(emptyList()) }
    var isLoadingManifest by remember { mutableStateOf(true) }
    var manifestError by remember { mutableStateOf<String?>(null) }
    var selectedDriverId by remember { mutableStateOf<String?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    var isInstalling by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadBytes by remember { mutableStateOf(0L) }
    var totalBytes by remember { mutableStateOf(-1L) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var lastFailedPackage by remember { mutableStateOf<RemoteDriverPackage?>(null) }
    var retryTrigger by remember { mutableStateOf(0) }
    val installedThisSession = remember { mutableStateListOf<String>() }
    // Disk-installed drivers — loaded synchronously on first frame so
    // pickRecommended sees them on first render, refreshed after install.
    var diskInstalled by remember {
        mutableStateOf(
            runCatching { AdrenotoolsManager(ctx).enumarateInstalledDrivers() }
                .getOrDefault(emptyList())
        )
    }
    var showOlder by remember { mutableStateOf(false) }
    var showSourcePicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val result = handlePickedUri(ctx, uri)
                withContext(Dispatchers.Main) {
                    if (result.installed) {
                        diskInstalled = runCatching {
                            AdrenotoolsManager(ctx).enumarateInstalledDrivers()
                        }.getOrDefault(emptyList())
                    }
                    SnackbarManager.show(result.userMessage)
                }
            }
        }
    }

    // Fetch only the selected source. Bounded GitHub API use. Re-runs on retry.
    LaunchedEffect(selectedRepository.id, retryTrigger) {
        selectedDriverId = null
        remotePackages = emptyList()
        manifestError = null
        lastFailedPackage = null
        installedThisSession.clear()
        if (filteredRepositories.isEmpty()) {
            isLoadingManifest = false
            manifestError = ctx.getString(R.string.driver_repository_no_sources)
            return@LaunchedEffect
        }
        isLoadingManifest = true
        try {
            remotePackages = DriverRepositoryCatalog.fetchPackages(selectedRepository)
            if (remotePackages.isEmpty()) {
                manifestError = ctx.getString(R.string.driver_repository_empty)
            }
        } catch (e: Exception) {
            manifestError = ctx.getString(R.string.driver_error_loading, e.message ?: "")
            Timber.e(e, "GetDriverDialog: Error loading %s", selectedRepository.name)
        } finally {
            isLoadingManifest = false
        }
    }

    val downloadAndInstallDriver = { driverPackage: RemoteDriverPackage ->
        downloadJob?.cancel()
        downloadJob = scope.launch {
            isDownloading = true
            downloadProgress = 0f
            downloadBytes = 0L
            totalBytes = -1L
            try {
                val destFile = File(ctx.cacheDir, driverPackage.fileName)
                DriverRepositoryCatalog.downloadPackage(driverPackage, destFile) { downloaded, total ->
                    scope.launch(Dispatchers.Main) {
                        downloadBytes = downloaded
                        totalBytes = total
                        downloadProgress = if (total > 0) {
                            (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                        } else 0f
                    }
                }
                withContext(Dispatchers.Main) { isDownloading = false; downloadProgress = 1f; downloadBytes = destFile.length() }
                withContext(Dispatchers.Main) { isInstalling = true }
                val uri = Uri.fromFile(destFile)
                val res = withContext(Dispatchers.IO) { handlePickedUri(ctx, uri) }
                withContext(Dispatchers.Main) {
                    if (res.installed) {
                        installedThisSession.add(driverPackage.fileName)
                        // Refresh disk list so pickRecommended sees the new install.
                        diskInstalled = runCatching {
                            AdrenotoolsManager(ctx).enumarateInstalledDrivers()
                        }.getOrDefault(emptyList())
                    }
                    SnackbarManager.show(res.userMessage)
                }
                withContext(Dispatchers.IO) { destFile.delete() }
                withContext(Dispatchers.Main) { lastFailedPackage = null }
            } catch (e: CancellationException) {
                withContext(Dispatchers.IO) { File(ctx.cacheDir, driverPackage.fileName).delete() }
                throw e
            } catch (e: SocketTimeoutException) {
                SnackbarManager.show(ctx.getString(R.string.driver_timeout))
                lastFailedPackage = driverPackage
            } catch (e: IOException) {
                val errorMessage = if (e.message?.contains("timeout", ignoreCase = true) == true) {
                    ctx.getString(R.string.driver_timeout)
                } else {
                    ctx.getString(R.string.driver_network_error, e.message ?: "")
                }
                SnackbarManager.show(errorMessage)
                lastFailedPackage = driverPackage
            } catch (e: Exception) {
                SnackbarManager.show(ctx.getString(R.string.driver_snackbar_download_error, e.message ?: ""))
                lastFailedPackage = driverPackage
            } finally {
                isDownloading = false
                isInstalling = false
                downloadProgress = 0f
                downloadBytes = 0L
                totalBytes = -1L
                downloadJob = null
            }
        }
    }

    ConsoleSettingsPage(
        visible = true,
        title = stringResource(R.string.settings_get_driver_title),
        onDismissRequest = onDismiss,
        actions = {
            val installedUnion = remember(installedThisSession.toList(), diskInstalled) {
                (installedThisSession + diskInstalled).toSet()
            }
            val target = remotePackages.firstOrNull { it.id == selectedDriverId }
                ?: pickRecommended(remotePackages, installedUnion)
            val canDownload = !isLoadingManifest && target != null && !isDownloading && !isInstalling
            Button(
                onClick = {
                    val pkg = target ?: return@Button
                    downloadAndInstallDriver(pkg)
                },
                enabled = canDownload,
            ) {
                Text(stringResource(R.string.download))
            }
        },
    ) {
            BoxWithConstraints(
                modifier = Modifier
                    .heightIn(min = 1.dp, max = 900.dp)
                    .fillMaxWidth(),
            ) {
                val isWide = maxWidth >= 600.dp
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    // 1. GPU context — explains why source was pre-selected.
                    // On wide screens, GPU hint sits inline next to the change-source
                    // action so the header stays compact.
                    val detectedTargetLabel = stringResource(
                        when (detectedTarget) {
                            AdrenoTarget.A6XX -> R.string.adreno_target_a6xx
                            AdrenoTarget.A7XX -> R.string.adreno_target_a7xx
                            AdrenoTarget.A710_A720_A722 -> R.string.adreno_target_a710_a720_a722
                            AdrenoTarget.A8XX -> R.string.adreno_target_a8xx
                            AdrenoTarget.GENERIC -> R.string.adreno_target_generic
                        }
                    )
                    val showRenderer = detectedTarget == AdrenoTarget.GENERIC && detectedRenderer.isNotEmpty()
                    val gpuNotDetected = detectedTarget == AdrenoTarget.GENERIC && detectedRenderer.isEmpty()
                    val hintText = when {
                        gpuNotDetected -> stringResource(R.string.get_driver_no_gpu)
                        showRenderer -> stringResource(R.string.driver_detected_gpu_with_renderer, detectedTargetLabel, detectedRenderer)
                        else -> stringResource(R.string.driver_detected_gpu, detectedTargetLabel)
                    }
                    if (isWide) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = hintText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = { showSourcePicker = true },
                            ) {
                                Text(stringResource(R.string.get_driver_change_source))
                            }
                        }
                    } else {
                        Text(
                            text = hintText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }

                    // 2. Source row — single decision, not stacked dropdowns.
                    // On wide screens, source name and change-source button sit
                    // side-by-side to keep the header at one line.
                    if (isWide) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${categoryLabel(selectedCategory)} · ${selectedRepository.name}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = stringResource(R.string.driver_repository_available),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "${categoryLabel(selectedCategory)} · ${selectedRepository.name}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.driver_repository_available),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = { showSourcePicker = true },
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text(stringResource(R.string.get_driver_change_source))
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // 3. Recommended package — single tile, GPU-compatible first
                    when {
                        isLoadingManifest -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(vertical = 8.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(20.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    text = stringResource(R.string.driver_repository_loading),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }

                        manifestError != null -> {
                            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                Text(
                                    text = manifestError ?: stringResource(R.string.driver_unknown_error),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(
                                    onClick = {
                                        // Re-runs the manifest fetch LaunchedEffect.
                                        retryTrigger += 1
                                    },
                                    modifier = Modifier.padding(top = 4.dp),
                                ) {
                                    Text(stringResource(R.string.driver_action_retry))
                                }
                            }
                        }

                        remotePackages.isNotEmpty() -> {
                            // Union session-installed + disk-installed so we don't
                            // recommend a pkg that's already on disk from a previous session.
                            val installedUnion = remember(installedThisSession.toList(), diskInstalled) {
                                (installedThisSession + diskInstalled).toSet()
                            }
                            val recommended = remember(remotePackages, installedUnion) {
                                pickRecommended(remotePackages, installedUnion)
                            }
                            val isRecommendedInstalled = recommended?.fileName in installedUnion

                            Text(
                                text = stringResource(R.string.get_driver_recommended),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = recommended?.displayName ?: "",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    if ((recommended?.sizeBytes ?: 0) > 0) {
                                        Text(
                                            text = formatBytes(recommended?.sizeBytes ?: 0),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (isRecommendedInstalled) {
                                    Text(
                                        text = stringResource(R.string.driver_installed_badge),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }

                            // Download progress / install spinner
                            if (isDownloading || isInstalling) {
                                Spacer(modifier = Modifier.height(8.dp))
                                if (isDownloading) {
                                    LinearProgressIndicator(
                                        progress = { downloadProgress },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    ) {
                                        Text(
                                            text = if (totalBytes > 0) {
                                                "${formatBytes(downloadBytes)} / ${formatBytes(totalBytes)}"
                                            } else {
                                                stringResource(R.string.downloading)
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        TextButton(onClick = { downloadJob?.cancel() }) {
                                            Text(stringResource(R.string.driver_action_cancel))
                                        }
                                    }
                                }
                                if (isInstalling) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.padding(top = 4.dp),
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.height(16.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        Text(
                                            text = stringResource(R.string.installing),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }

                            // Retry
                            val pendingRetry = lastFailedPackage
                            if (pendingRetry != null && !isDownloading && !isInstalling) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { downloadAndInstallDriver(pendingRetry) },
                                ) {
                                    Text(stringResource(R.string.driver_action_retry))
                                }
                            }

                            // Show older versions
                            if (remotePackages.size > 1) {
                                TextButton(
                                    onClick = { showOlder = !showOlder },
                                    modifier = Modifier.padding(top = 4.dp),
                                ) {
                                    Text(stringResource(R.string.get_driver_show_older))
                                }
                                if (showOlder) {
                                    PackagePicker(
                                        packages = remotePackages,
                                        selectedId = selectedDriverId,
                                        onSelect = { pkg ->
                                            // Selecting a package highlights it.
                                            // Download button (confirmButton) then
                                            // fetches THIS package, not the recommended.
                                            // User must press Download to actually fetch.
                                            selectedDriverId = pkg.id
                                        },
                                        installedInSession = installedThisSession,
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                    Text(
                        text = stringResource(R.string.driver_import_local_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    TextButton(
                        onClick = {
                            importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileUpload,
                            contentDescription = null,
                        )
                        Text(
                            text = stringResource(R.string.import_zip_from_device),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
    }

    if (showSourcePicker) {
        AlertDialog(
            onDismissRequest = { showSourcePicker = false },
            title = { Text(text = stringResource(R.string.driver_repository_title)) },
            text = {
                SourcePicker(
                    current = selectedCategory,
                    detectedTarget = detectedTarget,
                    onPick = { newCategory, newRepo ->
                        selectedCategory = newCategory
                        selectedRepository = newRepo
                        showSourcePicker = false
                    },
                )
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSourcePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PackagePicker(
    packages: List<RemoteDriverPackage>,
    selectedId: String?,
    onSelect: (RemoteDriverPackage) -> Unit,
    installedInSession: List<String>,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        packages.forEach { pkg ->
            val isInstalled = pkg.fileName in installedInSession
            val isSelected = pkg.id == selectedId
            DropdownMenuItem(
                text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = pkg.displayName,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                )
                                if (pkg.sizeBytes > 0) {
                                    Text(
                                        text = formatBytes(pkg.sizeBytes),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            } else if (isInstalled) {
                                Text(
                                    text = stringResource(R.string.driver_installed_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    onClick = {
                        onSelect(pkg)
                    },
                )
            }
        }
    }

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SourcePicker(
    current: DriverCategory,
    detectedTarget: AdrenoTarget,
    onPick: (DriverCategory, app.gamenative.drivers.DriverRepository) -> Unit,
) {
    val visibleCategories = remember {
        DriverRepositoryCatalog.categories.filter { category ->
            DriverRepositoryCatalog.repositoriesByCategory(category).isNotEmpty()
        }
    }
    var selectedCategory by remember { mutableStateOf(current) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.driver_repository_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        visibleCategories.forEach { category ->
            val repos = DriverRepositoryCatalog.repositoriesByCategory(category)
            val totalCount = repos.size
            val supportedCount = repos.count { detectedTarget in it.targets || AdrenoTarget.GENERIC in it.targets }
            Column {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(categoryLabel(category))
                            // Description wired from strings.xml — was previously
                            // defined but never rendered, leaving Jordan without context.
                            Text(
                                text = categoryDescription(category),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = { selectedCategory = category },
                )
                if (totalCount > 0 && supportedCount < totalCount) {
                    Text(
                        text = stringResource(
                            R.string.driver_category_support_count,
                            supportedCount,
                            totalCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 4.dp),
                    )
                }
                if (selectedCategory == category) {
                    repos.forEach { repo ->
                        DropdownMenuItem(
                            text = { Text(repo.name) },
                            onClick = { onPick(category, repo) },
                        )
                    }
                }
            }
        }
    }
}

private data class InstallResult(val userMessage: String, val installed: Boolean)

private fun handlePickedUri(context: Context, uri: Uri): InstallResult {
    return try {
        val name = AdrenotoolsManager(context).installDriver(uri)
        if (name.isNotEmpty()) {
            InstallResult(
                userMessage = context.getString(R.string.driver_snackbar_installed, name),
                installed = true,
            )
        } else {
            InstallResult(
                userMessage = context.getString(R.string.driver_snackbar_install_failed),
                installed = false,
            )
        }
    } catch (e: Exception) {
        InstallResult(
            userMessage = context.getString(R.string.driver_snackbar_import_error, e.message ?: ""),
            installed = false,
        )
    }
}

@Composable
private fun categoryLabel(category: DriverCategory): String = stringResource(
    when (category) {
        DriverCategory.TURNIP_STABLE -> R.string.driver_category_turnip_stable
        DriverCategory.TURNIP_BLEEDING_EDGE -> R.string.driver_category_turnip_bleeding_edge
        DriverCategory.TURNIP_COMMUNITY -> R.string.driver_category_turnip_community
        DriverCategory.ADRENO_PACKAGED -> R.string.driver_category_adreno_packaged
        DriverCategory.WRAPPER -> R.string.driver_category_wrapper
    }
)

@Composable
private fun categoryDescription(category: DriverCategory): String = stringResource(
    when (category) {
        DriverCategory.TURNIP_STABLE -> R.string.driver_category_turnip_stable_desc
        DriverCategory.TURNIP_BLEEDING_EDGE -> R.string.driver_category_turnip_bleeding_edge_desc
        DriverCategory.TURNIP_COMMUNITY -> R.string.driver_category_turnip_community_desc
        DriverCategory.ADRENO_PACKAGED -> R.string.driver_category_adreno_packaged_desc
        DriverCategory.WRAPPER -> R.string.driver_category_wrapper_desc
    }
)

/**
 * Pick the recommended package for a GPU: first non-installed, or fallback to
 * the most recent. Single source of truth used by both the recommended tile
 * and the Download confirmButton so they never disagree.
 */
private fun pickRecommended(
    packages: List<RemoteDriverPackage>,
    installed: Collection<String>,
): RemoteDriverPackage? = packages.firstOrNull { pkg ->
    pkg.fileName !in installed
} ?: packages.firstOrNull()

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes} B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.ROOT, "%.2f GB", gb)
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview
@Composable
private fun Preview_GetDriverDialog() {
    PluviaTheme {
        GetDriverDialog(open = true, onDismiss = {})
    }
}
