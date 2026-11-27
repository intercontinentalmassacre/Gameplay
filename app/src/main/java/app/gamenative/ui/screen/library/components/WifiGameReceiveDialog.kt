package app.gamenative.ui.screen.library.components

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gamenative.R
import app.gamenative.localgames.CustomGameContainerCoordinator
import app.gamenative.localgames.InstallerType
import app.gamenative.localgames.LocalInstallerImporter
import app.gamenative.localgames.ExecutableKind
import app.gamenative.localgames.WindowsExecutableInspector
import app.gamenative.service.LanGameReceiver
import app.gamenative.ui.component.dialog.AlertDialog
import app.gamenative.ui.component.dialog.TextButton
import app.gamenative.utils.CustomGameScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.net.Inet4Address

internal data class WifiGameUpdateTarget(val appId: String, val title: String, val folder: File)

@Composable
internal fun WifiGameReceiveDialog(
    onDismiss: () -> Unit,
    onGameAdded: () -> Unit,
    onPlay: (String) -> Unit,
    onInstall: (String) -> Unit,
    updateTarget: WifiGameUpdateTarget? = null,
) {
    val context = LocalContext.current.applicationContext
    val view = LocalView.current
    val dismiss by rememberUpdatedState(onDismiss)
    val gameAdded by rememberUpdatedState(onGameAdded)
    var attempt by remember { mutableIntStateOf(0) }
    val bytesReceived = remember(attempt) { MutableStateFlow<Pair<Long, Long?>>(0L to null) }
    val (bytes, totalBytes) = bytesReceived.collectAsStateWithLifecycle().value
    val selection = remember(attempt) { Channel<String>() }
    val installConfirmed = remember(attempt) { Channel<Unit>() }
    val install by rememberUpdatedState(onInstall)
    var state by remember(attempt) { mutableStateOf<ReceiveState>(ReceiveState.Starting) }

    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    LifecycleStartEffect(Unit) {
        onStopOrDispose { dismiss() }
    }

    LaunchedEffect(attempt) {
        val received = CompletableDeferred<File>()
        var keepFolder = false
        try {
            if (updateTarget != null && app.gamenative.PluviaApp.xEnvironment != null) {
                state = ReceiveState.Error(R.string.wifi_game_update_close_game)
                return@LaunchedEffect
            }
            val address = withContext(Dispatchers.IO) { wifiAddress(context) }
            if (address == null) {
                state = ReceiveState.Error(R.string.wifi_game_no_network)
                return@LaunchedEffect
            }

            val folder = withContext(Dispatchers.IO) {
                val page = context.assets.open("lan_game_upload.html").bufferedReader().use { it.readText() }
                val uploadPage = if (updateTarget == null) page else {
                    page.replace("data-mode=\"import\"", "data-mode=\"update\"")
                }
                LanGameReceiver(
                    bindAddress = address,
                    libraryDirectory = File(CustomGameScanner.receivedGameRootPath),
                    uploadPage = uploadPage.toByteArray(),
                    onProgress = { receivedBytes, total -> bytesReceived.value = receivedBytes to total },
                    updateFolder = updateTarget?.folder,
                    onComplete = { completed ->
                        if (!received.complete(completed) && updateTarget == null) {
                            completed.parentFile?.deleteRecursively()
                        }
                    },
                ).use { receiver ->
                    withContext(Dispatchers.Main) {
                        state = ReceiveState.Waiting(receiver.url, receiver.pairingCode)
                    }
                    received.await()
                }
            }
            if (updateTarget != null) {
                gameAdded()
                state = ReceiveState.Ready(updateTarget.appId, updateTarget.title)
                return@LaunchedEffect
            }
            state = ReceiveState.Checking
            val executables = withContext(Dispatchers.IO) { findReceivedExecutables(folder) }
            if (executables.isEmpty()) {
                state = ReceiveState.Error(R.string.wifi_game_no_executable)
                return@LaunchedEffect
            }

            var target = executables.singleOrNull()
            var error: String? = null
            while (true) {
                if (target == null) {
                    state = ReceiveState.Choose(executables, error)
                    target = selection.receive()
                }
                val executable = File(folder, target)
                val installerType = receivedInstallerType(executable)
                if (installerType != null) {
                    state = ReceiveState.Installer(target)
                    installConfirmed.receive()
                    state = ReceiveState.Preparing
                    val result = withContext(NonCancellable) {
                        val staged = withContext(Dispatchers.IO) {
                            LocalInstallerImporter.stageInstallerSession(
                                context = context,
                                sourceUriString = executable.toURI().toString(),
                                sourceName = executable.name,
                                destinationFolder = folder,
                                installerFile = executable,
                                installerRelativePath = executable.relativeTo(folder).invariantSeparatorsPath,
                                installerType = installerType,
                                cleanupOnFailure = false,
                            )
                        }
                        if (staged is LocalInstallerImporter.ImportResult.Ready ||
                            (staged is LocalInstallerImporter.ImportResult.Failed && staged.session != null)
                        ) {
                            keepFolder = true
                            gameAdded()
                        }
                        staged
                    }
                    when (result) {
                        is LocalInstallerImporter.ImportResult.Ready -> {
                            install(result.session.id)
                            return@LaunchedEffect
                        }
                        is LocalInstallerImporter.ImportResult.Failed -> {
                            state = ReceiveState.Error(R.string.wifi_game_installer_failed)
                            Timber.w(result.cause, "Could not prepare received installer")
                            return@LaunchedEffect
                        }
                        is LocalInstallerImporter.ImportResult.Rejected -> error = result.reason
                        is LocalInstallerImporter.ImportResult.ReadyPortable -> error("Unexpected portable installer result")
                    }
                    target = null
                    continue
                }
                state = ReceiveState.Preparing
                val result = withContext(NonCancellable) {
                    val prepared = withContext(Dispatchers.IO) {
                        CustomGameContainerCoordinator.preparePortableGame(context, folder, executable)
                    }
                    if (prepared is CustomGameContainerCoordinator.PreparationResult.Ready) {
                        keepFolder = true
                        gameAdded()
                    }
                    prepared
                }
                when (result) {
                    is CustomGameContainerCoordinator.PreparationResult.Ready -> {
                        state = ReceiveState.Ready(result.appId, result.title)
                        return@LaunchedEffect
                    }
                    is CustomGameContainerCoordinator.PreparationResult.Rejected -> error = result.reason
                    is CustomGameContainerCoordinator.PreparationResult.Failed -> {
                        Timber.w(result.cause, "Could not prepare received game")
                        error = result.reason
                    }
                }
                target = null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.w(error, "Could not receive game over Wi-Fi")
            state = ReceiveState.Error(R.string.wifi_game_receive_failed)
        } finally {
            received.cancel()
            if (updateTarget == null && !keepFolder && !received.isCancelled) {
                withContext(NonCancellable + Dispatchers.IO) {
                    received.await().parentFile?.deleteRecursively()
                }
            }
        }
    }

    val current = state
    val closeDialog = { if (current != ReceiveState.Preparing) onDismiss() }
    AlertDialog(
        onDismissRequest = closeDialog,
        title = {
            Text(stringResource(if (updateTarget == null) R.string.wifi_game_receive else R.string.wifi_game_update))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (current) {
                    ReceiveState.Starting -> Text(stringResource(R.string.wifi_game_starting))
                    is ReceiveState.Waiting -> {
                        Text(
                            if (updateTarget == null) stringResource(R.string.wifi_game_instructions)
                            else stringResource(
                                R.string.wifi_game_update_instructions,
                                updateTarget.title,
                                updateTarget.folder.name,
                            ),
                        )
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(current.url, fontFamily = FontFamily.Monospace)
                                Text(
                                    stringResource(R.string.wifi_game_pairing_code, current.code),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                        if (totalBytes != null && totalBytes > 0) {
                            LinearProgressIndicator(
                                progress = { (bytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(stringResource(
                                R.string.wifi_game_progress_total,
                                Formatter.formatFileSize(context, bytes),
                                Formatter.formatFileSize(context, totalBytes),
                            ))
                        } else if (bytes > 0) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(
                                stringResource(
                                    R.string.wifi_game_progress,
                                    Formatter.formatFileSize(context, bytes),
                                ),
                            )
                        } else {
                            Text(stringResource(R.string.wifi_game_waiting))
                        }
                    }
                    ReceiveState.Checking -> Text(stringResource(R.string.wifi_game_checking))
                    is ReceiveState.Choose -> {
                        Text(stringResource(R.string.wifi_game_choose_executable))
                        current.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        current.paths.forEach { path ->
                            TextButton(
                                onClick = { selection.trySend(path) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(path)
                            }
                        }
                    }
                    is ReceiveState.Installer -> Text(current.path)
                    ReceiveState.Preparing -> Text(stringResource(R.string.wifi_game_preparing))
                    is ReceiveState.Ready -> Text(
                        stringResource(
                            if (updateTarget == null) R.string.wifi_game_ready else R.string.wifi_game_update_ready,
                            current.title,
                        ),
                    )
                    is ReceiveState.Error -> Text(stringResource(current.message))
                }
            }
        },
        confirmButton = {
            when (current) {
                is ReceiveState.Installer -> Button(onClick = { installConfirmed.trySend(Unit) }) {
                    Text(stringResource(R.string.install))
                }
                is ReceiveState.Ready -> Button(onClick = { onPlay(current.appId) }) {
                    Text(stringResource(R.string.wifi_game_play))
                }
                is ReceiveState.Error -> Button(onClick = { attempt++ }) {
                    Text(stringResource(R.string.connection_retry))
                }
                else -> Unit
            }
        },
        dismissButton = {
            TextButton(onClick = closeDialog, enabled = current != ReceiveState.Preparing) {
                Text(stringResource(if (current is ReceiveState.Ready) R.string.done else R.string.cancel))
            }
        },
    )
}

private sealed interface ReceiveState {
    data object Starting : ReceiveState
    data class Waiting(val url: String, val code: String) : ReceiveState
    data object Checking : ReceiveState
    data class Choose(val paths: List<String>, val error: String?) : ReceiveState
    data class Installer(val path: String) : ReceiveState
    data object Preparing : ReceiveState
    data class Ready(val appId: String, val title: String) : ReceiveState
    data class Error(val message: Int) : ReceiveState
}

private fun wifiAddress(context: Context): Inet4Address? {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    return connectivity.allNetworks.asSequence()
        .filter {
            val capabilities = connectivity.getNetworkCapabilities(it)
            capabilities != null &&
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        .flatMap { connectivity.getLinkProperties(it)?.linkAddresses.orEmpty() }
        .mapNotNull { it.address as? Inet4Address }
        .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
}

private fun findReceivedExecutables(folder: File): List<String> =
    folder.walkTopDown()
        .filter { it.isFile && (it.extension.equals("exe", true) || it.extension.equals("msi", true)) && !it.name.startsWith("unins", true) }
        .filter { exe ->
            val kind = exe.inputStream().buffered().use(WindowsExecutableInspector::inspect).kind
            if (exe.extension.equals("msi", true)) kind == ExecutableKind.WINDOWS_INSTALLER_MSI
            else kind == ExecutableKind.WINDOWS_32_PE || kind == ExecutableKind.WINDOWS_64_PE
        }
        .map { it.relativeTo(folder).invariantSeparatorsPath }
        .sortedWith(compareBy<String> { receivedInstallerType(File(it)) == null }.then(String.CASE_INSENSITIVE_ORDER))
        .toList()

internal fun receivedInstallerType(file: File): InstallerType? = when {
    file.extension.equals("msi", true) -> InstallerType.MSI
    file.extension.equals("exe", true) && installerName.matches(file.nameWithoutExtension) -> InstallerType.EXE
    else -> null
}

private val installerName = Regex("""(?:setup|install|installer|autorun)(?:[ ._-].*)?|.+[ ._-](?:setup|installer)""", RegexOption.IGNORE_CASE)
